/*
 * Copyright 2023-2026 LiveKit, Inc.
 * Modifications Copyright 2026 Gravity Compile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gravixcloud.rtc.room

import androidx.annotation.VisibleForTesting
import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.RoomOptions
import com.gravixcloud.rtc.dagger.InjectionNames
import com.gravixcloud.rtc.room.participant.ParticipantTrackPermission
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.stats.AndroidNetworkInfo
import com.gravixcloud.rtc.stats.NetworkInfo
import com.gravixcloud.rtc.stats.getClientInfo
import com.gravixcloud.rtc.util.CloseableCoroutineScope
import com.gravixcloud.rtc.util.Either
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.toHttpUrl
import com.gravixcloud.rtc.util.toWebsocketUrl
import com.gravixcloud.rtc.util.withDeadline
import com.gravixcloud.rtc.webrtc.toProtoSessionDescription
import com.vdurmont.semver4j.Semver
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudModels.AudioTrackFeature
import gravixcloud.GravixcloudRtc
import gravixcloud.GravixcloudRtc.JoinResponse
import gravixcloud.GravixcloudRtc.ReconnectResponse
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.Date
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * SignalClient to Gravix WS servers
 * @suppress
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SignalClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    private val networkInfo: NetworkInfo,
) : WebSocketListener() {
    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var currentWs: WebSocket? = null

    @Volatile
    private var isReconnecting: Boolean = false
    var listener: Listener? = null
    internal var serverVersion: Semver? = null
    internal var serverInfo: ServerInfo? = null
    private var lastUrl: String? = null
    internal var lastOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null

    @Volatile
    private var joinContinuation: CancellableContinuation<ConnectResult>? = null
    private lateinit var coroutineScope: CloseableCoroutineScope

    /**
     * @see [startRequestQueue]
     */
    private val requestFlow = MutableSharedFlow<GravixcloudRtc.SignalRequest>(Int.MAX_VALUE)
    private val requestFlowJobLock = Object()
    private var requestFlowJob: Job? = null

    /**
     * @see [onReadyForResponses]
     */
    private val responseFlow = MutableSharedFlow<Pair<WebSocket, GravixcloudRtc.SignalResponse>>(Int.MAX_VALUE)
    private val responseFlowJobLock = Object()
    private var responseFlowJob: Job? = null

    private var pingJob: Job? = null
    private var pongJob: Job? = null
    private var pingTimeoutDurationMillis: Long = 0
    private var pingIntervalDurationMillis: Long = 0
    private var rtt: Long = 0

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    /**
     * GRAVIX (region failover): the dial timeout of the next [connect] only -- the time until the WebSocket opens,
     * not the JoinResponse wait. Set by the region strategy for a same-url retry; null = the normal timeout.
     */
    @Volatile
    internal var gravixNextDialTimeoutMs: Long? = null

    @Volatile
    private var gravixWsOpened = false

    /** GRAVIX: the WebSocket the dial bound gave up on (a silent host: no validate request to it). */
    @Volatile
    private var gravixDialGaveUpWs: WebSocket? = null

    /**
     * GRAVIX (GravixRecoveryOptions.ignoreStaleSignalFailures): a failure of a WebSocket that is no longer the
     * current one (reported after the blocking validate request, possibly 10 s later) is ignored instead of failing
     * the newer connect or closing the newer session. Off: upstream.
     */
    @Volatile
    internal var gravixIgnoreStaleFailures: Boolean = true

    /**
     * GRAVIX (GravixRecoveryOptions.signalDialCapMs): the signal deadline (dial + JoinResponse) of a FRESH join (not a
     * resume, not a dial-bounded same-url retry); null = upstream's 10 s. A host with no TCP connection still fails at
     * the HTTP client's connect timeout.
     */
    @Volatile
    internal var gravixSignalDialCapMs: Long? = GravixRecoveryOptions.DEFAULT_SIGNAL_DIAL_CAP_MS

    /**
     * GRAVIX (GravixRecoveryOptions.silentHostFailFast): a dial that failed before any connection came up (refused,
     * unreachable) or timed out (connect, or the upgrade's answer) is not followed by a validate request to the same
     * host: it would fail or time out the same way before the join could fail. A refusal (401/403) answers at once.
     */
    @Volatile
    internal var gravixSilentHostFailFast: Boolean = true

    /**
     * GRAVIX (region failover): the HTTP status of the last failed connect (the validate answer, else the
     * upgrade response); 401/403 = refused (the token, not the region). Null = no answer.
     */
    @Volatile
    internal var gravixLastFailureStatus: Int? = null
        private set

    /**
     * GRAVIX: whether the device has a network at all (for the failover's evidence): an active network exists. Not
     * the transport mapping (its UNKNOWN includes connected networks on unmapped transports), and an unknown answer
     * (no ConnectivityManager, no permission, a replaced NetworkInfo) is true: it must not block a failover.
     */
    internal fun gravixNetworkAvailable(): Boolean = (networkInfo as? AndroidNetworkInfo)?.gravixHasActiveNetwork() ?: true

    /** GRAVIX: the client the reachability probes of the failover's evidence use. */
    internal val gravixHttpClient: OkHttpClient get() = okHttpClient

    /**
     * @throws Exception if fails to connect.
     */
    @Throws(Exception::class)
    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions = ConnectOptions(),
        roomOptions: RoomOptions = RoomOptions(),
    ): JoinResponse {
        return when (val result = connect(url, token, options, roomOptions)) {
            is ConnectResult.Join -> result.response
            else -> throw IllegalStateException("Unexpected response during join: $result")
        }
    }

    /**
     * @throws Exception if fails to connect.
     */
    @Throws(Exception::class)
    @VisibleForTesting
    suspend fun reconnect(url: String, token: String, participantSid: String?): Either<ReconnectResponse, Unit> {
        return when (
            val result = connect(
                url,
                token,
                (lastOptions ?: ConnectOptions()).copy()
                    .apply {
                        reconnect = true
                        this.participantSid = participantSid
                    },
                lastRoomOptions ?: RoomOptions(),
            )
        ) {
            is ConnectResult.Reconnect -> Either.Left(result.response)
            is ConnectResult.OtherResponse -> Either.Right(Unit)
            is ConnectResult.Join -> throw IllegalStateException("Unexpected join response during reconnect")
        }
    }

    private suspend fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): ConnectResult {
        // Clean up any pre-existing connection.
        close(reason = "Starting new connection", shouldClearQueuedRequests = false)

        val wsUrlString = "${url.toWebsocketUrl()}/rtc${createConnectionParams(getClientInfo(options.clientProtocol), options, roomOptions)}"
        isReconnecting = options.reconnect

        // GRAVIX (0.1.2): no query in the line (it carries the token and the client's details)
        GxLog.i { "connecting to ${url.toWebsocketUrl().substringBefore('?')}" }

        coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
        lastUrl = wsUrlString
        lastOptions = options
        lastRoomOptions = roomOptions

        val request = Request.Builder()
            .url(wsUrlString)
            .addHeader("Authorization", "Bearer $token")
            .build()

        // GRAVIX (region failover): a dial bounded for this connect only
        val dialTimeout = gravixNextDialTimeoutMs
        gravixNextDialTimeoutMs = null
        gravixWsOpened = false
        gravixLastFailureStatus = null

        // GRAVIX (signalDialCapMs): a fresh join on a slow path gets longer; a resume and a bounded retry do not
        val deadlineMs = gravixSignalDialCapMs?.takeIf { !options.reconnect && dialTimeout == null } ?: SIGNAL_CONNECT_TIMEOUT.toLong()
        return withDeadline(deadlineMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                // Wait for join response through WebSocketListener
                joinContinuation = cont
                cont.invokeOnCancellation {
                    // If the coroutine is cancelled, websocket needs to be cancelled.
                    // onFailure will handle cleanup.
                    GxLog.v { "connect cancelled, abort websocket" }
                    joinContinuation = null
                    currentWs?.cancel()
                }
                val ws = websocketFactory.newWebSocket(request, this@SignalClient)
                currentWs = ws
                if (dialTimeout != null) {
                    coroutineScope.launch {
                        delay(dialTimeout)
                        if (!gravixWsOpened && currentWs == ws && joinContinuation != null) {
                            GxLog.i { "signal dial: no WebSocket after $dialTimeout ms, giving up this attempt" }
                            gravixDialGaveUpWs = ws
                            ws.cancel()
                        }
                    }
                }
            }
        }
    }

    private fun createConnectionParams(
        clientInfo: GravixcloudModels.ClientInfo,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): String {
        val queryBuilder = StringBuilder()
        var first = true

        fun addParam(key: String, value: String) {
            queryBuilder.append(if (first) "?" else "&")
                .append(key).append("=").append(value)
            first = false
        }

        addParam(CONNECT_QUERY_PROTOCOL, options.protocolVersion.value.toString())

        if (options.reconnect) {
            addParam(CONNECT_QUERY_RECONNECT, "1")
            options.participantSid?.let { sid ->
                addParam(CONNECT_QUERY_PARTICIPANT_SID, sid)
            }
        }

        val autoSubscribe = if (options.autoSubscribe) 1 else 0
        addParam(CONNECT_QUERY_AUTOSUBSCRIBE, autoSubscribe.toString())

        val adaptiveStream = if (roomOptions.adaptiveStream) 1 else 0
        addParam(CONNECT_QUERY_ADAPTIVE_STREAM, adaptiveStream.toString())

        // Client info
        addParam(CONNECT_QUERY_SDK, "android")
        addParam(CONNECT_QUERY_VERSION, clientInfo.version)
        addParam(CONNECT_QUERY_DEVICE_MODEL, clientInfo.deviceModel)
        addParam(CONNECT_QUERY_OS, clientInfo.os)
        addParam(CONNECT_QUERY_OS_VERSION, clientInfo.osVersion)
        addParam(CONNECT_QUERY_NETWORK_TYPE, networkInfo.getNetworkType().protoName)
        addParam(CONNECT_QUERY_CLIENT_PROTOCOL, options.clientProtocol.value.toString())

        return queryBuilder.toString()
    }

    /**
     * Notifies that the downstream consumers of SignalClient are ready to consume messages.
     * Until this method is called, any messages received through the websocket are buffered.
     *
     * Should be called after resolving the join message.
     */
    fun onReadyForResponses() {
        if (responseFlowJob != null) {
            return
        }
        synchronized(responseFlowJobLock) {
            if (responseFlowJob == null) {
                responseFlowJob = coroutineScope.launch {
                    responseFlow.collect { (ws, response) ->
                        responseFlow.resetReplayCache()
                        handleSignalResponseImpl(ws, response)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startRequestQueue() {
        if (requestFlowJob != null) {
            return
        }
        synchronized(requestFlowJobLock) {
            if (requestFlowJob == null) {
                requestFlowJob = coroutineScope.launch {
                    requestFlow.collect {
                        requestFlow.resetReplayCache()
                        sendRequestImpl(it)
                    }
                }
            }
        }
    }

    /**
     * On reconnection, SignalClient waits until the peer connection is established to send messages.
     * Call this method when it is connected.
     */
    fun onPCConnected() {
        startRequestQueue()
    }

    /**
     * GRAVIX (silentHostFailFast): the dial failed before any connection to the host came up, or timed out (connect,
     * or the upgrade's answer): a validate request to that host would fail or time out the same way.
     */
    private fun Throwable.gravixNoAnswer(): Boolean = when (this) {
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        -> true
        else -> false
    }

    // --------------------------------- WebSocket Listener --------------------------------------//
    override fun onOpen(webSocket: WebSocket, response: Response) {
        // GRAVIX (region failover): the dial bound (gravixNextDialTimeoutMs) ends here
        if (webSocket == currentWs) gravixWsOpened = true
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket != currentWs) {
            // Possibly message from old websocket, discard.
            return
        }

        GxLog.w { "received JSON message, unsupported in this version." }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (webSocket != currentWs) {
            // Possibly message from old websocket, discard.
            return
        }
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = GravixcloudRtc.SignalResponse.newBuilder()
            .mergeFrom(byteArray)
        val response = signalResponseBuilder.build()

        handleSignalResponse(webSocket, response)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket != currentWs) {
            return
        }
        handleWebSocketClose(reason, code)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        GxLog.v { "websocket closing" }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (webSocket != currentWs) {
            return
        }
        var reason: String? = null
        try {
            val lastToken = webSocket.request().header("Authorization")
            // GRAVIX (region failover): our dial bound gave up on a silent host; validating there would block this
            // thread for the HTTP timeout before the attempt could fail
            lastUrl?.takeIf { gravixDialGaveUpWs !== webSocket && !(gravixSilentHostFailFast && t.gravixNoAnswer()) }?.let {
                val validationUrl = it.toHttpUrl().replaceFirst("/rtc?", "/rtc/validate?")
                val request = Request.Builder()
                    .url(validationUrl)
                    .apply {
                        if (lastToken != null) {
                            addHeader("Authorization", lastToken)
                        }
                    }
                    .build()

                val resp = okHttpClient.newCall(request).execute()
                val body = resp.body
                if (!resp.isSuccessful) {
                    reason = body?.string()
                    gravixLastFailureStatus = resp.code // GRAVIX (region failover)
                }
                body?.close()
            }
        } catch (e: Throwable) {
            GxLog.e(e) { "failed to validate connection" }
        }

        // GRAVIX (ignoreStaleSignalFailures): the validate above blocks; a newer connect may own this client now
        if (gravixIgnoreStaleFailures && webSocket != currentWs) {
            GxLog.i { "ignoring the failure of a WebSocket that is no longer current" }
            return
        }
        if (gravixLastFailureStatus == null) gravixLastFailureStatus = response?.code // GRAVIX (region failover)

        val wasConnected = isConnected
        val wasReconnectHandshake = joinContinuation != null && isReconnecting

        // Clear joinContinuation before cont.cancel() so a synchronous nested onFailure (e.g. from
        // WebSocket.cancel) does not treat this as an in-flight join/reconnect handshake.
        val joinCont = joinContinuation
        joinContinuation = null

        if (reason != null) {
            GxLog.e(t) { "websocket failure: $reason" }
            val error = if (joinCont != null) {
                RoomException.ConnectException(reason, t)
            } else {
                Exception(reason)
            }
            listener?.onError(error)
            joinCont?.resumeWithException(error)
        } else {
            // GRAVIX (0.1.2): the status only (the response's text names the url with its query)
            GxLog.e(t) { "websocket failure: ${response?.code ?: "no response"}" }
            val error = if (joinCont != null) {
                RoomException.ConnectException(t.localizedMessage, t)
            } else {
                t
            }
            listener?.onError(error)
            joinCont?.resumeWithException(error)
        }

        if (wasConnected || wasReconnectHandshake) {
            // onClosing/onClosed will not be called after onFailure.
            // Handle websocket closure here.
            // Also handle failure during a soft reconnect handshake (reconnect query): isConnected is
            // still false but the upper layer should be notified like a close. Initial join handshake
            // failures do not call onClose so RTCEngine can surface them via onError / onFailToConnect.
            handleWebSocketClose(
                reason = reason ?: response?.toString() ?: t.localizedMessage ?: "websocket failure",
                code = response?.code ?: CLOSE_REASON_WEBSOCKET_FAILURE,
            )
        }
    }

    private fun handleWebSocketClose(reason: String, code: Int) {
        GxLog.v { "websocket closed" }
        isConnected = false
        listener?.onClose(reason, code)
        requestFlow.resetReplayCache()
        responseFlow.resetReplayCache()
        pingJob?.cancel()
        pongJob?.cancel()
    }

    // ------------------------------- End WebSocket Listener ------------------------------------//

    private fun fromProtoSessionDescription(sd: GravixcloudRtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            SD_TYPE_ROLLBACK -> SessionDescription.Type.ROLLBACK
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sd.type}")
        }
        return SessionDescription(rtcSdpType, sd.sdp)
    }

    fun sendOffer(offer: SessionDescription, offerId: Int) {
        val sd = offer.toProtoSessionDescription(offerId)
        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setOffer(sd)
            .build()

        sendRequest(request)
    }

    fun sendAnswer(answer: SessionDescription, offerId: Int) {
        val sd = answer.toProtoSessionDescription(offerId)
        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setAnswer(sd)
            .build()

        sendRequest(request)
    }

    fun sendCandidate(candidate: IceCandidate, target: GravixcloudRtc.SignalTarget) {
        val iceCandidateJSON = IceCandidateJSON(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
        )

        val trickleRequest = GravixcloudRtc.TrickleRequest.newBuilder()
            .setCandidateInit(json.encodeToString(iceCandidateJSON))
            .setTarget(target)
            .build()

        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setTrickle(trickleRequest)
            .build()

        sendRequest(request)
    }

    fun sendMuteTrack(trackSid: String, muted: Boolean) {
        val muteRequest = GravixcloudRtc.MuteTrackRequest.newBuilder()
            .setSid(trackSid)
            .setMuted(muted)
            .build()

        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setMute(muteRequest)
            .build()

        sendRequest(request)
    }

    /**
     * @param builder an optional builder to include other parameters related to the track
     */
    fun sendAddTrack(
        cid: String,
        name: String,
        type: GravixcloudModels.TrackType,
        stream: String?,
        builder: GravixcloudRtc.AddTrackRequest.Builder = GravixcloudRtc.AddTrackRequest.newBuilder(),
    ) {
        val encryptionType = lastRoomOptions?.e2eeOptions?.encryptionType ?: GravixcloudModels.Encryption.Type.NONE
        val addTrackRequest = builder.apply {
            setCid(cid)
            setName(name)
            setType(type)
            if (stream != null) {
                setStream(stream)
            } else {
                clearStream()
            }
            encryption = encryptionType
        }
        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setAddTrack(addTrackRequest)
            .build()

        sendRequest(request)
    }

    fun sendUpdateTrackSettings(
        sid: String,
        disabled: Boolean,
        videoDimensions: Track.Dimensions?,
        videoQuality: GravixcloudModels.VideoQuality?,
        fps: Int?,
    ) {
        val trackSettings = GravixcloudRtc.UpdateTrackSettings.newBuilder()
            .addTrackSids(sid)
            .setDisabled(disabled)
            .apply {
                if (videoDimensions != null) {
                    width = videoDimensions.width
                    height = videoDimensions.height
                } else if (videoQuality != null) {
                    quality = videoQuality
                } else {
                    // default to HIGH
                    quality = GravixcloudModels.VideoQuality.HIGH
                }

                if (fps != null) {
                    setFps(fps)
                }
            }

        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setTrackSetting(trackSettings)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscription(subscribe: Boolean, vararg participantTracks: GravixcloudModels.ParticipantTracks) {
        val participantTracksList = participantTracks.toList()

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }.flatten()
        val subscription = GravixcloudRtc.UpdateSubscription.newBuilder()
            .addAllParticipantTracks(participantTracks.toList())
            .addAllTrackSids(trackSids)
            .setSubscribe(subscribe)

        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setSubscription(subscription)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscriptionPermissions(
        allParticipants: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission>,
    ) {
        val update = GravixcloudRtc.SubscriptionPermission.newBuilder()
            .setAllParticipants(allParticipants)
            .addAllTrackPermissions(participantTrackPermissions.map { it.toProto() })

        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setSubscriptionPermission(update)
            .build()

        sendRequest(request)
    }

    fun sendUpdateLocalMetadata(metadata: String?, name: String?, attributes: Map<String, String>? = emptyMap()) {
        val update = GravixcloudRtc.UpdateParticipantMetadata.newBuilder()
            .setMetadata(metadata ?: "")
            .setName(name ?: "")
            .putAllAttributes(attributes)

        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setUpdateMetadata(update)
            .build()

        sendRequest(request)
    }

    fun sendSyncState(syncState: GravixcloudRtc.SyncState) {
        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setSyncState(syncState)
            .build()

        sendRequest(request)
    }

    internal fun sendSimulateScenario(scenario: GravixcloudRtc.SimulateScenario) {
        val request = GravixcloudRtc.SignalRequest.newBuilder()
            .setSimulate(scenario)
            .build()

        sendRequest(request)
    }

    fun sendLeave() {
        val request = with(GravixcloudRtc.SignalRequest.newBuilder()) {
            leave = with(GravixcloudRtc.LeaveRequest.newBuilder()) {
                reason = GravixcloudModels.DisconnectReason.CLIENT_INITIATED
                // server doesn't process this field, keeping it here to indicate the intent of a full disconnect
                action = GravixcloudRtc.LeaveRequest.Action.DISCONNECT
                build()
            }
            build()
        }

        sendRequest(request)
    }

    fun sendPing(): Long {
        val time = Date().time
        sendRequest(
            with(GravixcloudRtc.SignalRequest.newBuilder()) {
                ping = time
                build()
            },
        )
        sendRequest(
            with(GravixcloudRtc.SignalRequest.newBuilder()) {
                pingReq = with(GravixcloudRtc.Ping.newBuilder()) {
                    rtt = this@SignalClient.rtt
                    timestamp = time
                    build()
                }
                build()
            },
        )

        return time
    }

    fun sendUpdateLocalAudioTrack(trackSid: String, features: Collection<AudioTrackFeature>) {
        val request = with(GravixcloudRtc.SignalRequest.newBuilder()) {
            updateAudioTrack = with(GravixcloudRtc.UpdateLocalAudioTrack.newBuilder()) {
                setTrackSid(trackSid)
                addAllFeatures(features)
                build()
            }
            build()
        }

        sendRequest(request)
    }

    private fun sendRequest(request: GravixcloudRtc.SignalRequest) {
        val skipQueue = skipQueueTypes.contains(request.messageCase)

        if (skipQueue) {
            sendRequestImpl(request)
        } else {
            requestFlow.tryEmit(request)
        }
    }

    private fun sendRequestImpl(request: GravixcloudRtc.SignalRequest) {
        // GRAVIX (0.1.2): the request's type, never its body (an offer's SDP, a candidate's ufrag, a token)
        GxLog.v { "sending request: ${request.messageCase}" }
        if (!isConnected || currentWs == null) {
            GxLog.w { "not connected, could not send request ${request.messageCase}" }
            return
        }
        val message = request.toByteArray().toByteString()
        val sent = currentWs?.send(message) ?: false

        if (!sent) {
            GxLog.e { "error sending request: ${request.messageCase}" }
        }
    }

    private fun handleSignalResponse(ws: WebSocket, response: GravixcloudRtc.SignalResponse) {
        if (ws != currentWs) {
            return
        }

        // GRAVIX (0.1.2): the response's type, never its body (SDP, TURN credentials, refresh token, identities)
        GxLog.v { "response: ${response.messageCase}" }

        if (!isConnected) {
            var shouldProcessMessage = false

            // Only handle certain messages if not connected.
            if (response.hasJoin()) {
                isConnected = true
                startRequestQueue()
                pingTimeoutDurationMillis = response.join.pingTimeout.toLong() * 1000
                pingIntervalDurationMillis = response.join.pingInterval.toLong() * 1000
                startPingJob()
                try {
                    serverVersion = Semver(response.join.serverVersion)
                } catch (t: Throwable) {
                    GxLog.w(t) { "Thrown while trying to parse server version." }
                }
                serverInfo = ServerInfo(
                    edition = ServerInfo.Edition.fromProto(response.join.serverInfo.edition),
                    version = serverVersion
                )
                joinContinuation?.resumeWith(Result.success(ConnectResult.Join(response.join)))
                joinContinuation = null
            } else if (response.hasLeave()) {
                // Some reconnects may immediately send leave back without a join response first.
                handleSignalResponseImpl(ws, response)
                val cont = joinContinuation
                joinContinuation = null
                cont?.resumeWithException(
                    RoomException.ConnectException("Received leave during reconnect: ${response.leave.reason}"),
                )
            } else if (isReconnecting) {
                // When reconnecting, any message received means signal reconnected.
                // Newer servers will send a reconnect response first
                isReconnecting = false
                isConnected = true

                // Restart ping job with old settings.
                startPingJob()

                if (response.hasReconnect()) {
                    if (response.reconnect.hasServerInfo()) {
                        try {
                            serverVersion = Semver(response.reconnect.serverInfo.version)
                        } catch (t: Throwable) {
                            GxLog.w(t) { "Thrown while trying to parse server version from reconnect." }
                        }
                        serverInfo = ServerInfo(
                            edition = ServerInfo.Edition.fromProto(response.reconnect.serverInfo.edition),
                            version = serverVersion,
                        )
                    }
                    joinContinuation?.resumeWith(Result.success(ConnectResult.Reconnect(response.reconnect)))
                    joinContinuation = null
                } else {
                    joinContinuation?.resumeWith(Result.success(ConnectResult.OtherResponse))
                    joinContinuation = null
                    // Non-reconnect response, handle normally
                    shouldProcessMessage = true
                }
            } else {
                GxLog.e { "Received response while not connected. ${response.messageCase}" }
            }
            if (!shouldProcessMessage) {
                return
            }
        }
        responseFlow.tryEmit(ws to response)
    }

    private fun handleSignalResponseImpl(ws: WebSocket, response: GravixcloudRtc.SignalResponse) {
        if (ws != currentWs) {
            GxLog.v { "received message from old websocket, discarding." }
            return
        }

        when (response.messageCase) {
            GravixcloudRtc.SignalResponse.MessageCase.ANSWER -> {
                val sd = fromProtoSessionDescription(response.answer)
                val offerId = response.answer.id
                listener?.onServerAnswer(sd, offerId)
            }

            GravixcloudRtc.SignalResponse.MessageCase.OFFER -> {
                val sd = fromProtoSessionDescription(response.offer)
                val offerId = response.offer.id
                listener?.onServerOffer(sd, offerId)
            }

            GravixcloudRtc.SignalResponse.MessageCase.TRICKLE -> {
                val iceCandidateJson =
                    json.decodeFromString<IceCandidateJSON>(response.trickle.candidateInit)
                val iceCandidate = IceCandidate(
                    iceCandidateJson.sdpMid,
                    iceCandidateJson.sdpMLineIndex,
                    iceCandidateJson.candidate,
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }

            GravixcloudRtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList)
            }

            GravixcloudRtc.SignalResponse.MessageCase.TRACK_SUBSCRIBED -> {
                listener?.onLocalTrackSubscribed(response.trackSubscribed)
            }

            GravixcloudRtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                listener?.onLocalTrackPublished(response.trackPublished)
            }

            GravixcloudRtc.SignalResponse.MessageCase.SPEAKERS_CHANGED -> {
                listener?.onSpeakersChanged(response.speakersChanged.speakersList)
            }

            GravixcloudRtc.SignalResponse.MessageCase.JOIN -> {
                GxLog.d { "received unexpected extra join message?" }
            }

            GravixcloudRtc.SignalResponse.MessageCase.LEAVE -> {
                listener?.onLeave(response.leave)
            }

            GravixcloudRtc.SignalResponse.MessageCase.MUTE -> {
                listener?.onRemoteMuteChanged(response.mute.sid, response.mute.muted)
            }

            GravixcloudRtc.SignalResponse.MessageCase.ROOM_UPDATE -> {
                listener?.onRoomUpdate(response.roomUpdate.room)
            }

            GravixcloudRtc.SignalResponse.MessageCase.CONNECTION_QUALITY -> {
                listener?.onConnectionQuality(response.connectionQuality.updatesList)
            }

            GravixcloudRtc.SignalResponse.MessageCase.STREAM_STATE_UPDATE -> {
                listener?.onStreamStateUpdate(response.streamStateUpdate.streamStatesList)
            }

            GravixcloudRtc.SignalResponse.MessageCase.SUBSCRIBED_QUALITY_UPDATE -> {
                val versionToIgnoreUpTo = Semver("0.15.1")
                if ((serverVersion?.compareTo(versionToIgnoreUpTo) ?: 1) <= 0) {
                    return
                }
                listener?.onSubscribedQualityUpdate(response.subscribedQualityUpdate)
            }

            GravixcloudRtc.SignalResponse.MessageCase.SUBSCRIPTION_PERMISSION_UPDATE -> {
                listener?.onSubscriptionPermissionUpdate(response.subscriptionPermissionUpdate)
            }

            GravixcloudRtc.SignalResponse.MessageCase.REFRESH_TOKEN -> {
                listener?.onRefreshToken(response.refreshToken)
            }

            GravixcloudRtc.SignalResponse.MessageCase.TRACK_UNPUBLISHED -> {
                listener?.onLocalTrackUnpublished(response.trackUnpublished)
            }

            GravixcloudRtc.SignalResponse.MessageCase.PONG -> {
                resetPingTimeout()
            }

            GravixcloudRtc.SignalResponse.MessageCase.PONG_RESP -> {
                rtt = Date().time - response.pongResp.lastPingTimestamp
                resetPingTimeout()
            }

            GravixcloudRtc.SignalResponse.MessageCase.RECONNECT -> {
                // Handshake-only message; handled in handleSignalResponse() before connection.
                GxLog.d { "ignoring reconnect response received after connected" }
            }

            GravixcloudRtc.SignalResponse.MessageCase.SUBSCRIPTION_RESPONSE -> {
                listener?.onSubscriptionError(response.subscriptionResponse)
            }

            GravixcloudRtc.SignalResponse.MessageCase.REQUEST_RESPONSE -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.ROOM_MOVED -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.MEDIA_SECTIONS_REQUIREMENT -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.SUBSCRIBED_AUDIO_CODEC_UPDATE -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.PUBLISH_DATA_TRACK_RESPONSE -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.UNPUBLISH_DATA_TRACK_RESPONSE -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.DATA_TRACK_SUBSCRIBER_HANDLES -> {
                // TODO
            }

            GravixcloudRtc.SignalResponse.MessageCase.MESSAGE_NOT_SET,
            null,
            -> {
                GxLog.v { "empty messageCase!" }
            }
        }
    }

    private fun startPingJob() {
        if (pingJob == null && pingIntervalDurationMillis != 0L) {
            pingJob = coroutineScope.launch {
                while (true) {
                    delay(pingIntervalDurationMillis)
                    val pingTimestamp = sendPing()
                    startPingTimeout(pingTimestamp)
                }
            }
        }
    }

    private fun startPingTimeout(timestamp: Long) {
        if (pongJob != null) {
            return
        }
        pongJob = coroutineScope.launch {
            delay(pingTimeoutDurationMillis)
            GxLog.d { "Ping timeout reached for ping sent at $timestamp." }
            currentWs?.close(CLOSE_REASON_PING_TIMEOUT, "Ping timeout")
        }
    }

    private fun resetPingTimeout() {
        pongJob?.cancel()
        pongJob = null
    }

    /**
     * Closes out any existing websocket connection, and cleans up used resources.
     *
     * Can be reused afterwards.
     */
    fun close(code: Int = CLOSE_REASON_NORMAL_CLOSURE, reason: String = "Normal Closure", shouldClearQueuedRequests: Boolean = true) {
        @Suppress("ThrowingExceptionsWithoutMessageOrCause")
        GxLog.v(Exception()) { "Closing SignalClient: code = $code, reason = $reason" }
        isConnected = false
        isReconnecting = false
        if (::coroutineScope.isInitialized) {
            coroutineScope.close()
        }
        requestFlowJob?.cancel()
        requestFlowJob = null
        responseFlowJob?.cancel()
        responseFlowJob = null
        pingJob?.cancel()
        pingJob = null
        pongJob?.cancel()
        pongJob = null
        currentWs?.close(code, reason)
        currentWs = null
        // Same ordering as [onFailure]: clear the field before cancel() for synchronous listener callbacks.
        val joinCont = joinContinuation
        joinContinuation = null
        joinCont?.cancel()
        if (shouldClearQueuedRequests) {
            requestFlow.resetReplayCache()
        }
        responseFlow.resetReplayCache()
        lastUrl = null
        lastOptions = null
        lastRoomOptions = null
        serverVersion = null
        serverInfo = null
    }

    interface Listener {
        fun onServerAnswer(sessionDescription: SessionDescription, offerId: Int)
        fun onServerOffer(sessionDescription: SessionDescription, offerId: Int)
        fun onTrickle(candidate: IceCandidate, target: GravixcloudRtc.SignalTarget)
        fun onLocalTrackPublished(response: GravixcloudRtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<GravixcloudModels.ParticipantInfo>)
        fun onSpeakersChanged(speakers: List<GravixcloudModels.SpeakerInfo>)
        fun onClose(reason: String, code: Int)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: GravixcloudModels.Room)
        fun onConnectionQuality(updates: List<GravixcloudRtc.ConnectionQualityInfo>)
        fun onLeave(leave: GravixcloudRtc.LeaveRequest)
        fun onError(error: Throwable)
        fun onStreamStateUpdate(streamStates: List<GravixcloudRtc.StreamStateInfo>)
        fun onSubscribedQualityUpdate(subscribedQualityUpdate: GravixcloudRtc.SubscribedQualityUpdate)
        fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: GravixcloudRtc.SubscriptionPermissionUpdate)
        fun onSubscriptionError(subscriptionResponse: GravixcloudRtc.SubscriptionResponse)
        fun onRefreshToken(token: String)
        fun onLocalTrackUnpublished(trackUnpublished: GravixcloudRtc.TrackUnpublishedResponse)
        fun onLocalTrackSubscribed(trackSubscribed: GravixcloudRtc.TrackSubscribed)
    }

    /**
     * Result of waiting for the initial signal response after opening the WebSocket.
     * Join always yields [Join]; reconnect yields [Reconnect] or [OtherResponse].
     */
    private sealed class ConnectResult {
        data class Join(val response: JoinResponse) : ConnectResult()
        data class Reconnect(val response: ReconnectResponse) : ConnectResult()
        data object OtherResponse : ConnectResult()
    }

    companion object {
        const val CONNECT_QUERY_TOKEN = "access_token"
        const val CONNECT_QUERY_RECONNECT = "reconnect"
        const val CONNECT_QUERY_AUTOSUBSCRIBE = "auto_subscribe"
        const val CONNECT_QUERY_ADAPTIVE_STREAM = "adaptive_stream"
        const val CONNECT_QUERY_SDK = "sdk"
        const val CONNECT_QUERY_VERSION = "version"
        const val CONNECT_QUERY_PROTOCOL = "protocol"
        const val CONNECT_QUERY_DEVICE_MODEL = "device_model"
        const val CONNECT_QUERY_OS = "os"
        const val CONNECT_QUERY_OS_VERSION = "os_version"
        const val CONNECT_QUERY_NETWORK_TYPE = "network"
        const val CONNECT_QUERY_PARTICIPANT_SID = "sid"
        const val CONNECT_QUERY_CLIENT_PROTOCOL = "client_protocol"

        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
        const val SD_TYPE_ROLLBACK = "rollback"
        const val SDK_TYPE = "android"

        private val skipQueueTypes = listOf(
            GravixcloudRtc.SignalRequest.MessageCase.SYNC_STATE,
            GravixcloudRtc.SignalRequest.MessageCase.TRICKLE,
            GravixcloudRtc.SignalRequest.MessageCase.OFFER,
            GravixcloudRtc.SignalRequest.MessageCase.ANSWER,
            GravixcloudRtc.SignalRequest.MessageCase.SIMULATE,
            GravixcloudRtc.SignalRequest.MessageCase.LEAVE,
        )

        private fun iceServer(url: String) =
            PeerConnection.IceServer.builder(url).createIceServer()

        // more stun servers might slow it down, WebRTC recommends 3 max
        val DEFAULT_ICE_SERVERS = listOf(
            iceServer("stun:stun.l.google.com:19302"),
            iceServer("stun:stun1.l.google.com:19302"),
//            iceServer("stun:stun2.l.google.com:19302"),
//            iceServer("stun:stun3.l.google.com:19302"),
//            iceServer("stun:stun4.l.google.com:19302"),
        )
        private const val SIGNAL_CONNECT_TIMEOUT = 10000
        const val CLOSE_REASON_NORMAL_CLOSURE = 1000
        const val CLOSE_REASON_PING_TIMEOUT = 3000
        const val CLOSE_REASON_WEBSOCKET_FAILURE = 3500
    }
}

@Suppress("EnumEntryName", "unused")
enum class ProtocolVersion(val value: Int) {
    v1(1),
    v2(2),
    v3(3),
    v4(4),
    v5(5),
    v6(6),
    v7(7),
    v8(8),
    v9(9),
    v10(10),
    v11(11),
    v12(12),

    // new leave request handling
    v13(13),
}

/**
 * The protocol version this SDK advertises to **peers** (other participants) for
 * client-to-client feature negotiation (RPC v2, etc.). Distinct from [ProtocolVersion],
 * which tracks the signaling protocol between client and server.
 *
 * Sent to the server during the join handshake via the `client_protocol` connection
 * query parameter and `ClientInfo.client_protocol`; the server then populates
 * `ParticipantInfo.client_protocol` for other peers in the room to read.
 */
@Suppress("unused")
enum class ClientProtocolVersion(val value: Int) {
    /** Initial client protocol. RPC v1 only (15 KB packet payload limit). */
    DEFAULT(0),

    /**
     * RPC v2: request and success-response payloads are carried over text data streams
     * instead of inline packets, lifting the 15 KB payload limit.
     */
    DATA_STREAM_RPC(1),
}

class ServerInfo(
    val edition: Edition,
    val version: Semver?,
) {
    enum class Edition {
        STANDARD,
        CLOUD,
        UNKNOWN
        ;

        companion object {
            fun fromProto(proto: GravixcloudModels.ServerInfo.Edition): Edition {
                return when (proto) {
                    GravixcloudModels.ServerInfo.Edition.Standard -> STANDARD
                    GravixcloudModels.ServerInfo.Edition.Cloud -> CLOUD
                    GravixcloudModels.ServerInfo.Edition.UNRECOGNIZED -> UNKNOWN
                }
            }
        }
    }
}
