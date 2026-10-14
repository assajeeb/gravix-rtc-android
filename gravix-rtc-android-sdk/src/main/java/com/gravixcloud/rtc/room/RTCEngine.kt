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

import android.os.SystemClock
import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.RoomOptions
import com.gravixcloud.rtc.dagger.InjectionNames
import com.gravixcloud.rtc.e2ee.DataPacketCryptorManager
import com.gravixcloud.rtc.e2ee.E2EEManager
import com.gravixcloud.rtc.e2ee.EncryptedPacket
import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.events.convert
import com.gravixcloud.rtc.regions.GravixRegionProbeClient
import com.gravixcloud.rtc.room.network.DefaultReconnectPolicy
import com.gravixcloud.rtc.room.network.ReconnectContext
import com.gravixcloud.rtc.room.network.ReconnectPolicy
import com.gravixcloud.rtc.room.participant.Participant
import com.gravixcloud.rtc.room.participant.ParticipantTrackPermission
import com.gravixcloud.rtc.room.track.TrackException
import com.gravixcloud.rtc.room.util.MediaConstraintKeys
import com.gravixcloud.rtc.room.util.PeerConnectionStateObservable
import com.gravixcloud.rtc.room.util.createAnswer
import com.gravixcloud.rtc.room.util.setLocalDescription
import com.gravixcloud.rtc.room.util.waitUntilConnected
import com.gravixcloud.rtc.util.CloseableCoroutineScope
import com.gravixcloud.rtc.util.Either
import com.gravixcloud.rtc.util.FlowObservable
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.TTLMap
import com.gravixcloud.rtc.util.executeAsync
import com.gravixcloud.rtc.util.flow
import com.gravixcloud.rtc.util.flowDelegate
import com.gravixcloud.rtc.util.nullSafe
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import com.gravixcloud.rtc.util.withCheckLock
import com.gravixcloud.rtc.util.withDeadline
import com.gravixcloud.rtc.webrtc.DataChannelManager
import com.gravixcloud.rtc.webrtc.DataPacketBuffer
import com.gravixcloud.rtc.webrtc.DataPacketItem
import com.gravixcloud.rtc.webrtc.RTCStatsGetter
import com.gravixcloud.rtc.webrtc.copy
import com.gravixcloud.rtc.webrtc.isConnected
import com.gravixcloud.rtc.webrtc.isDisconnected
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadToken
import com.gravixcloud.rtc.webrtc.peerconnection.executeBlockingOnRTCThread
import com.gravixcloud.rtc.webrtc.peerconnection.executeOnRTCThread
import com.gravixcloud.rtc.webrtc.peerconnection.launchBlockingOnRTCThread
import com.gravixcloud.rtc.webrtc.toProtoSessionDescription
import com.vdurmont.semver4j.Semver
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudModels.AudioTrackFeature
import gravixcloud.GravixcloudRtc
import gravixcloud.GravixcloudRtc.JoinResponse
import gravixcloud.GravixcloudRtc.LeaveRequest
import gravixcloud.GravixcloudRtc.ReconnectResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.Request
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.PeerConnectionState
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * @suppress
 */
@Singleton
class RTCEngine
@Inject
internal constructor(
    val client: SignalClient,
    private val pctFactory: PeerConnectionTransport.Factory,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    private val rtcThreadToken: RTCThreadToken,
    private val dataPacketCryptorFactory: DataPacketCryptorManager.Factory,
) : SignalClient.Listener {
    internal var listener: Listener? = null

    /**
     * Reflects the combined connection state of SignalClient and primary PeerConnection.
     */
    @FlowObservable
    @get:FlowObservable
    var connectionState: ConnectionState by flowDelegate(ConnectionState.DISCONNECTED) { newVal, oldVal ->
        if (newVal == oldVal) {
            return@flowDelegate
        }
        when (newVal) {
            ConnectionState.CONNECTED -> {
                signalSessionState = SignalSessionState(ended = false)
                if (oldVal == ConnectionState.DISCONNECTED && gravixFullRejoinRunning && gravixRecovery.freshPeerFailedGraceMs != null) {
                    // GRAVIX (freshPeerFailedGraceMs): a rejoin's new primary that failed (longer than the grace) and
                    // then connected within the rejoin's wait: this is the rejoin's reconnect, not a new connect
                    GxLog.d { "primary ICE connected (full rejoin, after a FAILED)" }
                    listener?.onEngineReconnected()
                } else if (oldVal == ConnectionState.DISCONNECTED || oldVal == ConnectionState.CONNECTING) {
                    GxLog.d { "primary ICE connected" }
                    listener?.onEngineConnected()
                } else if (oldVal == ConnectionState.RECONNECTING) {
                    GxLog.d { "primary ICE reconnected" }
                    listener?.onEngineReconnected()
                } else if (oldVal == ConnectionState.RESUMING) {
                    listener?.onEngineResumed()
                }
            }

            ConnectionState.DISCONNECTED -> {
                GxLog.d { "primary ICE disconnected" }
                if (oldVal == ConnectionState.CONNECTED) {
                    gravixReconnect(gravixPrimaryDownCause) // GRAVIX: the cause is observation (RoomEvent.ReconnectAttempt)
                }
            }

            else -> {
            }
        }
    }

    @Volatile
    internal var reconnectType: ReconnectType = ReconnectType.DEFAULT

    @Volatile
    private var reconnectingJob: Job? = null

    /** GRAVIX (freshPeerFailedGraceMs): a reconnect's full rejoin is running (until that reconnect ends). */
    @Volatile
    private var gravixFullRejoinRunning = false

    /**
     * Replaced at each signal session boundary. Sender handles retain the state that owned their
     * transceiver, so cleanup cannot mutate a publisher after that session ends.
     */
    @Volatile
    private var signalSessionState = SignalSessionState(ended = true)

    @Volatile
    private var fullReconnectOnNext = false

    internal var reconnectPolicy: ReconnectPolicy = DefaultReconnectPolicy()

    private val pendingTrackResolvers: MutableMap<String, Continuation<GravixcloudModels.TrackInfo>> =
        mutableMapOf()

    internal var regionUrlProvider: RegionUrlProvider? = null

    /** GRAVIX: the region/failover switches (Room.gravixRecovery); all off = the upstream ordering. */
    @Volatile
    internal var gravixRecovery: GravixRecoveryOptions = GravixRecoveryOptions()
        set(value) {
            field = value
            client.gravixIgnoreStaleFailures = value.ignoreStaleSignalFailures
            client.gravixSignalDialCapMs = value.signalDialCapMs
            client.gravixSilentHostFailFast = value.silentHostFailFast
        }

    /**
     * GRAVIX: the url the engine is connected to now -- after a region failover, not the url passed to connect.
     * Observation only (Room.connectedUrl); null when closed.
     */
    @Volatile
    internal var connectedUrl: String? = null
        private set

    /** GRAVIX: when the device's network last changed (elapsedRealtime; 0 = never), for the failover's evidence. */
    @Volatile
    private var gravixLastNetworkChangeAt = 0L

    internal fun gravixNoteNetworkChange() {
        gravixLastNetworkChangeAt = SystemClock.elapsedRealtime()
    }

    /** GRAVIX: what the failover's evidence check asks (replaceable in tests). */
    internal var gravixFailoverEvidence: GravixFailoverEvidence = object : GravixFailoverEvidence {
        override fun networkChangedSince(sinceMs: Long): Boolean =
            gravixLastNetworkChangeAt != 0L && gravixLastNetworkChangeAt >= sinceMs

        override fun networkAvailable(): Boolean = client.gravixNetworkAvailable()

        @OptIn(ExperimentalCoroutinesApi::class)
        override suspend fun reachable(url: String, timeoutMs: Long): Boolean = try {
            withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder().url(GravixRegionProbeClient.toHttpUrl(url)).head().build()
                client.gravixHttpClient.newCall(request).executeAsync().use { true }
            } ?: false
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            false
        }
    }

    /**
     * GRAVIX (GravixRecoveryOptions.resumeOnPeerDisconnected): a connected session whose peer connection reports
     * DISCONNECTED starts its reconnect (a resume first) at once, as the Flutter SDK's engine does, instead of waiting
     * for FAILED (about 10 s later with libwebrtc's continual gathering) or the signal ping timeout. Off: upstream
     * (DISCONNECTED is left to recover by itself).
     */
    private fun gravixOnPeerDisconnected(newState: PeerConnection.PeerConnectionState, which: String) {
        if (newState != PeerConnection.PeerConnectionState.DISCONNECTED || !gravixRecovery.resumeOnPeerDisconnected) return
        if (connectionState != ConnectionState.CONNECTED) return
        GxLog.i { "$which peer connection DISCONNECTED: reconnecting now (resumeOnPeerDisconnected)" }
        gravixReconnect(CAUSE_PEER_DISCONNECTED)
    }

    /** GRAVIX: what the primary connection's last FAILED / CLOSED was (the cause of the reconnect it starts). */
    @Volatile
    private var gravixPrimaryDownCause = CAUSE_PEER_CLOSED

    private fun PeerConnection.PeerConnectionState.gravixDownCause() =
        if (this == PeerConnection.PeerConnectionState.FAILED) CAUSE_PEER_FAILED else CAUSE_PEER_CLOSED

    /**
     * GRAVIX (GravixRecoveryOptions.networkChangeWindowMs): the device's network changed (a network lost or available)
     * within the window before [reconnectStartMs] (now, when null), or since.
     */
    private fun gravixNetworkChangedRecently(reconnectStartMs: Long?): Boolean {
        val at = gravixLastNetworkChangeAt
        if (at == 0L) return false
        val ref = reconnectStartMs ?: SystemClock.elapsedRealtime()
        return at >= ref - gravixRecovery.networkChangeWindowMs
    }

    /**
     * GRAVIX (GravixRecoveryOptions.resumeAfterNetworkSocketError): the resume's dial failed with a socket error -- no
     * answer from the server (no HTTP status), an I/O failure, not the signal deadline -- while the device's network
     * changed within the window or reads offline: the interface went away under the dial, the session is still on the
     * server.
     */
    private fun gravixResumeDialLostToTheNetwork(e: Exception, reconnectStartMs: Long): Boolean {
        if (!gravixRecovery.resumeAfterNetworkSocketError || client.gravixLastFailureStatus != null) return false
        if (generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).none { it is java.io.IOException }) return false
        return gravixNetworkChangedRecently(reconnectStartMs) || !gravixNetworkCheck()
    }

    /**
     * GRAVIX (GravixRecoveryOptions.freshPeerFailedGraceMs): a FAILED of [transport], a connection that has never
     * connected, waits for it to connect for the grace; [action] (what the FAILED does upstream) runs on the RTC thread
     * only when it did not, and it is still this engine's connection. Returns false (act now) when the switch is off.
     */
    private fun gravixDeferFreshFailure(
        transport: PeerConnectionTransport?,
        observer: PeerConnectionStateObservable,
        which: String,
        action: () -> Unit,
    ): Boolean {
        val grace = gravixRecovery.freshPeerFailedGraceMs?.takeIf { it > 0 } ?: return false
        if (transport == null) return false
        GxLog.i { "$which connection FAILED before it ever connected: waiting $grace ms for it (freshPeerFailedGrace)" }
        coroutineScope.launch {
            val connected = withTimeoutOrNull(grace) { observer::connectionState.flow.first { it.isConnected() } } != null
            if (isClosed || (transport !== publisher && transport !== subscriber)) return@launch
            if (connected) {
                GxLog.i { "$which connection connected after its transient FAILED: nothing to recover" }
                return@launch
            }
            GxLog.i { "$which connection still not connected $grace ms after its FAILED" }
            executeOnRTCThread(rtcThreadToken) {
                // it may have connected between the timeout and this hop: then there is nothing to act on
                if ((transport === publisher || transport === subscriber) && !observer.connectionState.isConnected()) action()
            }
        }
        return true
    }

    /** GRAVIX (iceTransportWatchMs): the slow-TCP episode's clock (replaceable in tests). */
    internal var gravixIceClock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * The primary connection's state listener: the engine's [connectionState] follows it. GRAVIX: a FAILED of a
     * connection that never connected waits for it (freshPeerFailedGraceMs); DISCONNECTED resumes
     * (resumeOnPeerDisconnected).
     */
    private fun gravixPrimaryStateListener(
        transport: PeerConnectionTransport?,
        observer: PeerConnectionStateObservable,
    ): PeerConnectionStateListener {
        var everConnected = false
        return { newState ->
            GxLog.v { "onIceConnection new state: $newState" }
            if (newState.isConnected()) {
                everConnected = true
                connectionState = ConnectionState.CONNECTED
            } else if (newState.isDisconnected()) {
                val down = {
                    gravixPrimaryDownCause = newState.gravixDownCause()
                    connectionState = ConnectionState.DISCONNECTED
                }
                val deferred = newState == PeerConnection.PeerConnectionState.FAILED && !everConnected &&
                    gravixDeferFreshFailure(transport, observer, "primary", down)
                if (!deferred) down()
            } else {
                gravixOnPeerDisconnected(newState, "primary")
            }
        }
    }

    /**
     * Subscriber-primary mode: the publisher's FAILED / CLOSED reconnects (upstream). GRAVIX: a FAILED of a publisher
     * that never connected waits for it (freshPeerFailedGraceMs); DISCONNECTED resumes (resumeOnPeerDisconnected).
     */
    private fun gravixSecondaryPublisherListener(transport: PeerConnectionTransport?): PeerConnectionStateListener {
        var everConnected = false
        return { newState ->
            if (newState.isConnected()) everConnected = true
            if (newState.isDisconnected()) {
                val cause = newState.gravixDownCause()
                val deferred = newState == PeerConnection.PeerConnectionState.FAILED && !everConnected &&
                    gravixDeferFreshFailure(transport, publisherObserver, "publisher") { gravixReconnect(cause) }
                if (!deferred) gravixReconnect(cause)
            } else {
                gravixOnPeerDisconnected(newState, "publisher")
            }
        }
    }

    /**
     * GRAVIX (GravixRecoveryOptions.iceTransportWatchMs): the session's ICE transport (Room.selectedTransport), from
     * each connection's selected candidate pair; the round trip is read only while on ICE/TCP.
     */
    internal val gravixIceWatch = GravixIceTransportWatch(
        settings = { gravixRecovery },
        steady = { connectionState == ConnectionState.CONNECTED && reconnectingJob?.isActive != true },
        readStats = { isPublisher -> gravixConnectedStats(if (isPublisher) publisher else subscriber) },
        now = { gravixIceClock() },
        onChanged = { transport, previous, pub, sub -> listener?.onEngineIceTransportChanged(transport, previous, pub, sub) },
        onSlowTcp = { rttMs, overMs -> listener?.onEngineSlowTcpTransport(rttMs, overMs) },
    )

    /** One RTC-thread hop: the connection's stats when it is connected, else null. */
    private suspend fun gravixConnectedStats(transport: PeerConnectionTransport?): RTCStatsReport? {
        if (transport == null) return null
        return transport.withPeerConnection {
            if (!isConnected()) {
                null
            } else {
                suspendCancellableCoroutine { cont -> getStats { report -> if (cont.isActive) cont.resume(report) } }
            }
        }
    }

    /** GRAVIX: libwebrtc selected a candidate pair on the publisher / subscriber connection. */
    internal fun gravixOnSelectedCandidatePair(isPublisher: Boolean, local: IceCandidate?) {
        gravixIceWatch.onSelectedPair(isPublisher, GravixIceTransport.ofCandidateSdp(local?.sdp))
    }

    /**
     * GRAVIX: ends the reconnect loop's current wait for its peer connections; set while a wait runs and its switch
     * (leaveAbortsResumeWait for a resume, restartAbortsOnSignalLoss for a full reconnect) is on.
     */
    @Volatile
    private var gravixEndReconnectWait: ((String) -> Unit)? = null

    /**
     * GRAVIX: arms the early ends of one reconnect attempt's peer-connection [wait]: a Leave or the signal socket
     * closing ([GravixRecoveryOptions.leaveAbortsResumeWait] for a resume, [GravixRecoveryOptions.restartAbortsOnSignalLoss]
     * for a full reconnect: nothing can be negotiated without the socket), and for a resume the primary-connection
     * deadline ([GravixRecoveryOptions.resumePrimaryDeadlineMs]). Returns the deadline's job (to cancel), if any.
     */
    private fun CoroutineScope.gravixArmReconnectWait(wait: Job, full: Boolean): Job? {
        val recovery = gravixRecovery
        val endsOnSignalLoss = if (full) recovery.restartAbortsOnSignalLoss else recovery.leaveAbortsResumeWait
        gravixEndReconnectWait = if (endsOnSignalLoss) {
            { why ->
                if (wait.isActive) {
                    GxLog.i { "${if (full) "full reconnect" else "resume"}: $why while waiting for the peer connections; next attempt now" }
                    wait.cancel()
                }
            }
        } else {
            null
        }
        val deadline = recovery.resumePrimaryDeadlineMs
        if (full || deadline == null) return null
        return launch {
            delay(deadline)
            val primary = if (isSubscriberPrimary) subscriber else publisher
            if (primary?.isConnected() != true && wait.isActive) {
                GxLog.i { "resume: the primary connection is not up $deadline ms after the ReconnectResponse; full rejoin now" }
                wait.cancel()
            }
        }
    }

    /** GRAVIX (waitForNetwork): whether the device has a network now (replaceable in tests). */
    internal var gravixNetworkCheck: () -> Boolean = { client.gravixNetworkAvailable() }

    /**
     * GRAVIX (GravixRecoveryOptions.waitForNetwork): when the device has no network, waits for it before the next
     * reconnect attempt instead of dialling (each dial fails at once, spends the resume and grows the back-off). Checks
     * every [NETWORK_CHECK_MS]; with [GravixRecoveryOptions.networkWaitProbeIntervalMs] also probes [url] (one HTTP
     * HEAD; any answer ends the wait: a stale reading, a per-app block lifted). Waits at most until the reconnect's
     * time budget is spent (the policy then ends it, as upstream). Returns true when it waited.
     */
    private suspend fun gravixAwaitNetwork(reconnectStartMs: Long, url: String?): Boolean {
        if (gravixNetworkCheck()) return false
        GxLog.i { "reconnect: the device has no network; waiting for it before the next attempt" }
        val budgetMs = MAX_RECONNECT_TIMEOUT - (SystemClock.elapsedRealtime() - reconnectStartMs)
        val probeEvery = gravixRecovery.networkWaitProbeIntervalMs
        var waitedMs = 0L
        var sinceProbeMs = 0L
        while (waitedMs < budgetMs) {
            delay(NETWORK_CHECK_MS)
            waitedMs += NETWORK_CHECK_MS
            sinceProbeMs += NETWORK_CHECK_MS
            if (gravixNetworkCheck()) {
                GxLog.i { "reconnect: the network is back after ~$waitedMs ms; attempting now" }
                return true
            }
            if (probeEvery != null && url != null && sinceProbeMs >= probeEvery) {
                sinceProbeMs = 0
                if (gravixFailoverEvidence.reachable(url, GravixRegionFailover.PROBE_TIMEOUT_MS)) {
                    GxLog.i { "reconnect: no network reading, but $url answers; attempting now" }
                    return true
                }
            }
        }
        return true
    }

    /** GRAVIX: the region strategy for one reconnect / connect; null (every region switch off) = upstream. */
    internal fun gravixRegionStrategy(): GravixRegionStrategy? =
        if (gravixRecovery.regionStrategyEnabled) GravixRegionFailover(gravixRecovery, regionUrlProvider, gravixFailoverEvidence) else null

    private var sessionUrl: String? = null
    private var sessionToken: String? = null
    private var connectOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null
    private var participantSid: String? = null

    internal val serverVersion: Semver?
        get() = client.serverVersion

    internal val serverInfo: ServerInfo?
        get() = client.serverInfo

    private val publisherObserver = PublisherTransportObserver(this, client, rtcThreadToken)
    private val subscriberObserver = SubscriberTransportObserver(this, client, rtcThreadToken)

    internal var publisher: PeerConnectionTransport? = null
    private var subscriber: PeerConnectionTransport? = null

    private var reliableDataChannel: DataChannel? = null
    private var reliableDataChannelSub: DataChannel? = null
    private var lossyDataChannel: DataChannel? = null
    private var lossyDataChannelSub: DataChannel? = null
    private var reliableDataChannelManager: DataChannelManager? = null
    private var reliableBufferedAmountJob: Job? = null
    private var reliableDataChannelSubManager: DataChannelManager? = null
    private var lossyDataChannelManager: DataChannelManager? = null
    private var lossyDataChannelSubManager: DataChannelManager? = null

    private val reliableStateLock = Object()
    private var reliableDataSequence: Int = 1
    private val reliableMessageBuffer = DataPacketBuffer(RELIABLE_RETRY_AMOUNT)
    private val reliableReceivedState = TTLMap<String, Int>(RELIABLE_RECEIVE_STATE_TTL_MS)

    private var isSubscriberPrimary = false

    @Volatile
    private var isClosed = true

    @Volatile
    private var hasPublished = false

    private var coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)

    internal var e2EEManager: E2EEManager? = null
    private val dataPacketCryptorManager: DataPacketCryptorManager?
        get() = e2EEManager?.dataPacketCryptorManager

    /**
     * Note: If this lock is ever used in conjunction with the RTC thread,
     * this must be grabbed on the RTC thread to prevent deadlocks.
     */
    private var configurationLock = Mutex()

    /**
     * Prevents concurrent publisher negotiations which can cause ICE gathering
     * race conditions and connection failures.
     */
    private val negotiatePublisherMutex = Mutex()

    init {
        client.listener = this
    }

    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): JoinResponse {
        coroutineScope.close()
        coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
        sessionUrl = url
        sessionToken = token
        connectOptions = options
        lastRoomOptions = roomOptions
        // GRAVIX: a fresh session never inherits a same-url dial bound armed by an earlier reconnect
        client.gravixNextDialTimeoutMs = null
        return joinImpl(url, token, options, roomOptions)
            .also { connectedUrl = url } // GRAVIX: where the session is (observation)
    }

    suspend fun joinImpl(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): JoinResponse = coroutineScope {
        if (connectionState == ConnectionState.DISCONNECTED) {
            connectionState = ConnectionState.CONNECTING
        }
        val joinResponse = client.join(url, token, options, roomOptions)
        ensureActive()

        listener?.onJoinResponse(joinResponse)
        isClosed = false
        listener?.onSignalConnected(false)

        isSubscriberPrimary = joinResponse.subscriberPrimary

        configure(joinResponse, options)
        gravixIceWatch.start(this@RTCEngine.coroutineScope) // GRAVIX (iceTransportWatchMs): no-op when off or running

        // create offer
        if (!isSubscriberPrimary || joinResponse.fastPublish) {
            negotiatePublisher()
        }
        client.onReadyForResponses()

        return@coroutineScope joinResponse
    }

    private suspend fun configure(joinResponse: JoinResponse, connectOptions: ConnectOptions) {
        launchBlockingOnRTCThread(rtcThreadToken) {
            configurationLock.withCheckLock(
                {
                    ensureActive()
                    if (publisher != null && subscriber != null) {
                        // already configured
                        return@launchBlockingOnRTCThread
                    }
                },
            ) {
                participantSid = if (joinResponse.hasParticipant()) {
                    joinResponse.participant.sid
                } else {
                    null
                }

                // Setup peer connections
                val rtcConfig = makeRTCConfig(Either.Left(joinResponse), connectOptions)

                publisher?.close()
                publisher = pctFactory.create(
                    rtcConfig,
                    publisherObserver,
                    publisherObserver,
                )
                subscriber?.close()
                subscriber = pctFactory.create(
                    rtcConfig,
                    subscriberObserver,
                    null,
                )

                // GRAVIX (freshPeerFailedGraceMs): the listeners track whether their connection ever connected
                val connectionStateListener = if (joinResponse.subscriberPrimary) {
                    gravixPrimaryStateListener(subscriber, subscriberObserver)
                } else {
                    gravixPrimaryStateListener(publisher, publisherObserver)
                }

                if (joinResponse.subscriberPrimary) {
                    // in subscriber primary mode, server side opens sub data channels.
                    subscriberObserver.dataChannelListener = onDataChannel@{ dataChannel: DataChannel ->
                        when (dataChannel.label()) {
                            RELIABLE_DATA_CHANNEL_LABEL -> reliableDataChannelSub = dataChannel
                            LOSSY_DATA_CHANNEL_LABEL -> lossyDataChannelSub = dataChannel
                            else -> return@onDataChannel
                        }
                        dataChannel.registerObserver(DataChannelObserver(dataChannel))
                    }

                    subscriberObserver.connectionChangeListener = connectionStateListener
                    // Also reconnect on publisher disconnect
                    publisherObserver.connectionChangeListener = gravixSecondaryPublisherListener(publisher)
                } else {
                    publisherObserver.connectionChangeListener = connectionStateListener
                }

                ensureActive()
                // data channels
                val reliableInit = DataChannel.Init()
                reliableInit.ordered = true
                reliableDataChannel = publisher?.withPeerConnection {
                    createDataChannel(
                        RELIABLE_DATA_CHANNEL_LABEL,
                        reliableInit,
                    ).also { dataChannel ->

                        val dataChannelManager = DataChannelManager(dataChannel, DataChannelObserver(dataChannel), rtcThreadToken)
                        reliableDataChannelManager = dataChannelManager
                        dataChannel.registerObserver(dataChannelManager)
                        reliableBufferedAmountJob?.cancel()
                        reliableBufferedAmountJob = coroutineScope.launch {
                            dataChannelManager::bufferedAmount.flow.collect { bufferedAmount ->
                                synchronized(reliableStateLock) {
                                    reliableMessageBuffer.trim(bufferedAmount)
                                }
                            }
                        }
                    }
                }

                ensureActive()
                val lossyInit = DataChannel.Init()
                lossyInit.ordered = false
                lossyInit.maxRetransmits = 0
                lossyDataChannel = publisher?.withPeerConnection {
                    createDataChannel(
                        LOSSY_DATA_CHANNEL_LABEL,
                        lossyInit,
                    ).also { dataChannel ->
                        lossyDataChannelManager = DataChannelManager(dataChannel, DataChannelObserver(dataChannel), rtcThreadToken)
                        dataChannel.registerObserver(lossyDataChannelManager)
                    }
                }
            }
        }
    }

    /**
     * @param builder an optional builder to include other parameters related to the track
     */
    suspend fun addTrack(
        cid: String,
        name: String,
        kind: GravixcloudModels.TrackType,
        stream: String?,
        builder: GravixcloudRtc.AddTrackRequest.Builder = GravixcloudRtc.AddTrackRequest.newBuilder(),
    ): GravixcloudModels.TrackInfo {
        synchronized(pendingTrackResolvers) {
            if (pendingTrackResolvers[cid] != null) {
                throw TrackException.DuplicateTrackException("Track with same ID $cid has already been published!")
            }
        }

        // Suspend until signal client receives message confirming track publication.
        return withDeadline(20.seconds) {
            suspendCancellableCoroutine { cont ->
                synchronized(pendingTrackResolvers) {
                    pendingTrackResolvers[cid] = cont
                }
                cont.invokeOnCancellation {
                    synchronized(pendingTrackResolvers) {
                        if (pendingTrackResolvers[cid] === cont) {
                            pendingTrackResolvers.remove(cid)
                        }
                    }
                }
                client.sendAddTrack(
                    cid = cid,
                    name = name,
                    type = kind,
                    stream = stream,
                    builder = builder,
                )
            }
        }
    }

    internal suspend fun createSenderTransceiver(
        rtcTrack: MediaStreamTrack,
        transInit: RtpTransceiverInit,
    ): SenderTransceiverHandle? {
        val sessionState = signalSessionState
        val publisher = publisher ?: return null
        val transceiver = publisher.withPeerConnection {
            addTransceiver(rtcTrack, transInit)
        } ?: return null
        return SenderTransceiverHandle(publisher, transceiver, sessionState)
    }

    fun updateSubscriptionPermissions(
        allParticipants: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission>,
    ) {
        client.sendUpdateSubscriptionPermissions(allParticipants, participantTrackPermissions)
    }

    fun updateMuteStatus(sid: String, muted: Boolean) {
        client.sendMuteTrack(sid, muted)
    }

    fun updateLocalAudioTrack(sid: String, features: Collection<AudioTrackFeature>) {
        client.sendUpdateLocalAudioTrack(sid, features)
    }

    fun close(reason: String = "Normal Closure") {
        if (isClosed) {
            return
        }
        GxLog.v { "Close - $reason" }
        isClosed = true
        reconnectingJob?.cancel()
        reconnectingJob = null
        coroutineScope.close()
        hasPublished = false
        sessionUrl = null
        sessionToken = null
        connectOptions = null
        lastRoomOptions = null
        participantSid = null
        regionUrlProvider = null
        connectedUrl = null // GRAVIX
        endSignalSession()
        abortPendingPublishTracks()
        closeResources(reason)
        gravixIceWatch.stop() // GRAVIX (iceTransportWatchMs): after the PCs are gone; late callbacks are ignored
        connectionState = ConnectionState.DISCONNECTED

        synchronized(reliableStateLock) {
            reliableDataSequence = 1
            reliableMessageBuffer.clear()
            reliableReceivedState.clear()
        }
    }

    private fun closeResources(reason: String) {
        executeBlockingOnRTCThread(rtcThreadToken) {
            runBlocking {
                configurationLock.withLock {
                    publisherObserver.connectionChangeListener = null
                    subscriberObserver.connectionChangeListener = null
                    publisher?.closeBlocking()
                    publisher = null
                    subscriber?.closeBlocking()
                    subscriber = null

                    reliableBufferedAmountJob?.cancel()
                    reliableBufferedAmountJob = null
                    reliableDataChannelManager?.dispose()
                    reliableDataChannelManager = null
                    reliableDataChannel = null
                    reliableDataChannelSubManager?.dispose()
                    reliableDataChannelSubManager = null
                    reliableDataChannelSub = null
                    lossyDataChannelManager?.dispose()
                    lossyDataChannelManager = null
                    lossyDataChannel = null
                    lossyDataChannelSubManager?.dispose()
                    lossyDataChannelSubManager = null
                    lossyDataChannelSub = null
                    isSubscriberPrimary = false
                }
            }
        }
        client.close(reason = reason)
    }

    /**
     * Marks the current signal session as finished, so publish cleanup that resumes afterwards
     * leaves the publisher alone.
     *
     * Precedes [abortPendingPublishTracks]: each abort unwinds a publish into that cleanup, and
     * on some paths the reconnect which would otherwise end the session only runs later, when
     * the socket closes.
     */
    private fun endSignalSession() {
        signalSessionState = SignalSessionState(ended = true)
    }

    private fun abortPendingPublishTracks() {
        synchronized(pendingTrackResolvers) {
            pendingTrackResolvers.values.forEach {
                it.resumeWithException(TrackException.PublishException("pending track aborted"))
            }
            pendingTrackResolvers.clear()
        }
    }

    /**
     * reconnect Signal and PeerConnections
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun reconnect() = gravixReconnect(CAUSE_REQUESTED)

    /**
     * GRAVIX: [reconnect] with what started it ([cause]: observation only,
     * [RoomEvent.ReconnectAttempt][com.gravixcloud.rtc.events.RoomEvent.ReconnectAttempt]).
     */
    @Synchronized
    internal fun gravixReconnect(cause: String) {
        if (reconnectingJob?.isActive == true) {
            GxLog.d { "Reconnection is already in progress" }
            return
        }
        if (this.isClosed) {
            GxLog.d { "Skip reconnection - engine is closed" }
            return
        }
        var url = sessionUrl
        val token = sessionToken
        if (url == null || token == null) {
            GxLog.w { "couldn't reconnect, no url or no token" }
            return
        }
        val forceFullReconnect = fullReconnectOnNext
        fullReconnectOnNext = false
        endSignalSession()
        val job = coroutineScope.launch {
            var hasResumedOnce = false
            var hasReconnectedOnce = false

            // GRAVIX (resume event): announce a resume as soon as this reconnect starts with one -- also while it
            // waits for the network below -- so apps can show "reconnecting" from the drop (RoomEvent.Resuming).
            // Upstream called onEngineResuming at the first resume dial and Room ignored it; observation only.
            val firstAttemptResumes = when (reconnectType) {
                ReconnectType.DEFAULT -> !forceFullReconnect
                ReconnectType.FORCE_SOFT_RECONNECT -> true
                ReconnectType.FORCE_FULL_RECONNECT -> false
            }
            if (firstAttemptResumes) {
                hasResumedOnce = true
                listener?.onEngineResuming()
            }

            val reconnectStartTime = SystemClock.elapsedRealtime()
            val reconnectPolicy = this@RTCEngine.reconnectPolicy

            // GRAVIX (region failover): this reconnect's region strategy; null = the upstream ordering below
            val regionStrategy = gravixRegionStrategy()
            val reconnectSessionUrl = url!!
            var lastRefused = false
            var gravixLastWasResume = false
            // GRAVIX (resumeAfterNetworkSocketError): the last resume's dial failed with a socket error during a
            // network change, and how many resumes in a row did so
            var gravixResumeAfterSocketError = false
            var gravixSocketErrorResumes = 0

            for (retries in 0 until MAX_RECONNECT_RETRIES) {
                // GRAVIX (waitForNetwork): no attempt (and no back-off) while the device has no network; a resume that
                // failed because the network went away is tried again when it is back (not spent offline)
                val gravixWaitedForNetwork = gravixRecovery.waitForNetwork && gravixAwaitNetwork(reconnectStartTime, url)
                val gravixAfterSocketError = gravixResumeAfterSocketError && reconnectType == ReconnectType.DEFAULT
                gravixResumeAfterSocketError = false
                val gravixResumeAgain =
                    (gravixWaitedForNetwork && gravixLastWasResume && reconnectType == ReconnectType.DEFAULT) || gravixAfterSocketError
                if (gravixResumeAgain) GxLog.i { "reconnect: the resume failed as the network went away; resuming again" }
                // First try use previously valid url.
                if (retries != 0) {
                    if (regionStrategy != null) {
                        // GRAVIX: same url first, measured order, last good list, evidence before leaving
                        val full = when (reconnectType) {
                            ReconnectType.DEFAULT -> !gravixResumeAgain
                            ReconnectType.FORCE_SOFT_RECONNECT -> false
                            ReconnectType.FORCE_FULL_RECONNECT -> true
                        }
                        url = regionStrategy.reconnectUrl(
                            GravixRegionAttempt(
                                retry = retries,
                                full = full,
                                sessionUrl = reconnectSessionUrl,
                                lastUrl = url!!,
                                lastRefused = lastRefused,
                                startedAtMs = reconnectStartTime,
                            ),
                        )
                        client.gravixNextDialTimeoutMs = regionStrategy.nextDialTimeoutMs
                    } else {
                        try {
                            url = regionUrlProvider?.getNextBestRegionUrl() ?: url
                        } catch (e: Exception) {
                            e.rethrowIfCancellationSignal()
                            GxLog.d(e) { "Exception while getting next best region url while reconnecting." }
                        }
                    }
                }
                lastRefused = false

                ensureActive()
                if (retries != 0) {
                    yield()
                }

                if (isClosed) {
                    GxLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }

                val reconnectContext = ReconnectContext(
                    retryCount = retries,
                    elapsedTime = (SystemClock.elapsedRealtime() - reconnectStartTime).milliseconds,
                )
                val startDelay = reconnectPolicy.getNextRetryDelay(reconnectContext)
                if (startDelay == null) {
                    GxLog.i { "cancelling reconnection due to policy." }
                    break
                }

                GxLog.i { "Reconnecting to signal, attempt ${retries + 1}" }
                delay(if (gravixWaitedForNetwork) 0 else startDelay.inWholeMilliseconds) // GRAVIX: back now: no back-off

                val isFullReconnect = when (reconnectType) {
                    // full reconnect after first try.
                    ReconnectType.DEFAULT -> (retries != 0 || forceFullReconnect) && !gravixResumeAgain
                    ReconnectType.FORCE_SOFT_RECONNECT -> false
                    ReconnectType.FORCE_FULL_RECONNECT -> true
                }
                gravixLastWasResume = !isFullReconnect // GRAVIX (waitForNetwork)
                if (isFullReconnect) gravixSocketErrorResumes = 0
                // GRAVIX: every attempt is reported (RoomEvent.ReconnectAttempt); observation only
                listener?.onEngineReconnectAttempt(
                    full = isFullReconnect,
                    cause = if (retries == 0) cause else CAUSE_RETRY,
                    attempt = retries + 1,
                    afterNetworkChange = gravixNetworkChangedRecently(reconnectStartTime),
                )

                var lastMessageSeq: Int? = null
                val connectOptions = connectOptions ?: ConnectOptions()
                if (isFullReconnect) {
                    GxLog.v { "Attempting full reconnect." }
                    gravixIceWatch.resetSession() // GRAVIX (iceTransportWatchMs): a new session, new transports
                    gravixFullRejoinRunning = true // GRAVIX (freshPeerFailedGraceMs)

                    if (!hasReconnectedOnce) {
                        hasReconnectedOnce = true
                        listener?.onEngineReconnecting()
                    }
                    connectionState = ConnectionState.RECONNECTING
                    try {
                        closeResources("Full Reconnecting")
                        listener?.onFullReconnecting()
                        joinImpl(url!!, token, connectOptions, lastRoomOptions ?: RoomOptions())
                    } catch (e: Exception) {
                        e.rethrowIfCancellationSignal()
                        GxLog.w(e) { "Error during reconnection." }
                        lastRefused = client.gravixLastFailureStatus.let { it == 401 || it == 403 } // GRAVIX
                        // reconnect failed, retry.
                        continue
                    }
                } else {
                    if (!hasResumedOnce) {
                        hasResumedOnce = true
                        listener?.onEngineResuming()
                    }
                    connectionState = ConnectionState.RESUMING
                    GxLog.v { "Attempting soft reconnect." }
                    subscriber?.prepareForIceRestart()
                    try {
                        val response = client.reconnect(url!!, token, participantSid)
                        if (response is Either.Left) {
                            val reconnectResponse = response.value
                            val rtcConfig = makeRTCConfig(Either.Right(reconnectResponse), connectOptions)
                            subscriber?.updateRTCConfig(rtcConfig)
                            publisher?.updateRTCConfig(rtcConfig)
                            lastMessageSeq = reconnectResponse.lastMessageSeq
                        } else {
                            GxLog.w { "Did not receive reconnect response" }
                        }
                        client.onReadyForResponses()
                    } catch (e: Exception) {
                        e.rethrowIfCancellationSignal()
                        GxLog.w(e) { "Error during reconnection." }
                        lastRefused = client.gravixLastFailureStatus.let { it == 401 || it == 403 } // GRAVIX
                        // GRAVIX (resumeAfterNetworkSocketError): a dial lost to the device's network changing keeps
                        // the next attempt a resume; two in a row still become a full rejoin
                        if (gravixResumeDialLostToTheNetwork(e, reconnectStartTime)) {
                            gravixSocketErrorResumes++
                            if (gravixSocketErrorResumes >= SOCKET_ERROR_RESUMES_BEFORE_FULL) {
                                GxLog.i { "resume dial failed $gravixSocketErrorResumes times during a network change; full rejoin next" }
                                gravixSocketErrorResumes = 0
                            } else {
                                GxLog.i { "resume dial failed during a network change (${e.message}); resuming again" }
                                gravixResumeAfterSocketError = true
                            }
                        } else {
                            gravixSocketErrorResumes = 0
                        }
                        // ws reconnect failed, retry.
                        continue
                    }

                    GxLog.v { "ws reconnected, restarting ICE" }
                    listener?.onSignalConnected(true)

                    // trigger publisher reconnect
                    // only restart publisher if it's needed
                    if (hasPublished) {
                        negotiatePublisher()
                    }
                }

                ensureActive()
                if (isClosed) {
                    GxLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }

                // GRAVIX: the wait can end early (resumePrimaryDeadlineMs, leaveAbortsResumeWait,
                // restartAbortsOnSignalLoss); with all three off it is exactly the upstream wait
                val gravixWait = launch {
                    withTimeoutOrNull(MAX_ICE_CONNECT_TIMEOUT_MS.toLong()) {
                        // wait until publisher ICE connected
                        var publisherWaitJob: Job? = null
                        if (hasPublished) {
                            publisherWaitJob = launch {
                                publisherObserver.waitUntilConnected()
                            }
                        }

                        // wait until subscriber ICE connected
                        val subscriberWaitJob = launch {
                            subscriberObserver.waitUntilConnected()
                        }

                        listOfNotNull(publisherWaitJob, subscriberWaitJob)
                            .joinAll()
                        // GRAVIX (resumeWaitsForLiveState): an observer can still say CONNECTED from before the ICE
                        // restart its live connection has just started; wait for the live state too
                        if (gravixRecovery.resumeWaitsForLiveState) {
                            while (!(subscriber?.isConnected() == true && (!hasPublished || publisher?.isConnected() == true))) {
                                delay(LIVE_STATE_POLL_MS)
                            }
                        }
                    }
                }
                val gravixDeadline = gravixArmReconnectWait(gravixWait, isFullReconnect)
                gravixWait.join()
                gravixDeadline?.cancel()
                gravixEndReconnectWait = null

                ensureActive()
                if (isClosed) {
                    GxLog.v { "RTCEngine closed, aborting reconnection" }
                    break
                }

                val subscriberConnected = subscriber?.isConnected() == true
                val publisherConnected = !hasPublished || publisher?.isConnected() == true
                if ((connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.RESUMING) &&
                    subscriberConnected &&
                    publisherConnected
                ) {
                    if (connectionState == ConnectionState.RESUMING) {
                        connectionState = ConnectionState.CONNECTED
                    }
                    if (lastMessageSeq != null) {
                        resendReliableMessagesForResume(lastMessageSeq).onFailure { e ->
                            GxLog.w(e) {
                                "Reliable data replay did not complete on resume; " +
                                    "buffered items remain queued for the next resume."
                            }
                        }
                    }
                    // Is connected, notify and return.
                    GxLog.i { // GRAVIX: observation (field timelines)
                        "reconnected (${if (isFullReconnect) "full" else "resume"}) at $url, attempt ${retries + 1}, " +
                            "${SystemClock.elapsedRealtime() - reconnectStartTime} ms after the reconnect began"
                    }
                    regionUrlProvider?.clearAttemptedRegions()
                    // GRAVIX (rejoinClearsFullRequest): a full-reconnect request made during this reconnect (a server
                    // Leave{RECONNECT}) is satisfied by this full rejoin; it must not force the NEXT reconnect full
                    if (isFullReconnect && gravixRecovery.rejoinClearsFullRequest) fullReconnectOnNext = false
                    // GRAVIX: where the session is now. connectedUrl is observation only; the session url follows
                    // the session (so the next resume dials where it is) only with the region strategy on.
                    connectedUrl = url
                    if (regionStrategy != null) sessionUrl = url
                    client.onPCConnected()
                    listener?.onPostReconnect(isFullReconnect)
                    return@launch
                }

                // Didn't manage to reconnect, check if should continue to next attempt.
                val curReconnectTime = SystemClock.elapsedRealtime() - reconnectStartTime
                if (curReconnectTime > MAX_RECONNECT_TIMEOUT) {
                    break
                }
            }

            client.gravixNextDialTimeoutMs = null // GRAVIX: an armed dial bound ends with this reconnect
            close("Failed reconnecting")
            listener?.onEngineDisconnected(DisconnectReason.UNKNOWN_REASON)
        }

        reconnectingJob = job
        job.invokeOnCompletion {
            gravixFullRejoinRunning = false // GRAVIX
            if (reconnectingJob == job) {
                reconnectingJob = null
            }
        }
    }

    internal fun negotiatePublisher() {
        // Mark intent to publish before checking Signal state so reconnect() can
        // re-trigger negotiation even if the first attempt occurs before Signal connects.
        hasPublished = true

        if (!client.isConnected) {
            return
        }

        coroutineScope.launch {
            negotiatePublisherMutex.withLock {
                publisher?.negotiate?.invoke(getPublisherOfferConstraints())
            }
        }
    }

    @CheckResult
    internal suspend fun sendData(dataPacket: GravixcloudModels.DataPacket): Result<Unit> {
        try {
            ensurePublisherConnected(dataPacket.kind)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            return Result.failure(e)
        }

        fun sendDataImpl(dataPacket: GravixcloudModels.DataPacket): Result<Unit> {
            try {
                // Redeclare to make variable
                var dataPacket = dataPacket

                val e2EEManager = e2EEManager
                val dataEncryptionEnabled = e2EEManager?.isDataChannelEncryptionEnabled() ?: false
                if (dataEncryptionEnabled && e2EEManager != null) {
                    val encryptedPacketPayload = dataPacket.asEncryptedPacketPayload()
                    if (encryptedPacketPayload != null) {
                        val encryptedData = e2EEManager.encrypt(encryptedPacketPayload.toByteArray())
                        if (encryptedData != null) {
                            dataPacket = with(dataPacket.toBuilder()) {
                                encryptedPacket = with(GravixcloudModels.EncryptedPacket.newBuilder()) {
                                    encryptedValue = ByteString.copyFrom(encryptedData.payload)
                                    iv = ByteString.copyFrom(encryptedData.iv)
                                    keyIndex = encryptedData.keyIndex
                                    encryptionType = GravixcloudModels.Encryption.Type.GCM
                                    build()
                                }
                                build()
                            }
                        }
                    }
                }

                val isReliable = dataPacket.kind == GravixcloudModels.DataPacket.Kind.RELIABLE
                if (isReliable) {
                    dataPacket = dataPacket.toBuilder()
                        .setSequence(reliableDataSequence)
                        .build()
                }

                val packetBytes = dataPacket.toByteArray()

                if (packetBytes.size > MAX_DATA_PACKET_SIZE) {
                    return Result.failure(IllegalArgumentException("packet size (${packetBytes.size}) exceeds the max size (${MAX_DATA_PACKET_SIZE})"))
                }

                if (isReliable && this.connectionState == ConnectionState.RECONNECTING) {
                    reliableMessageBuffer.queue(DataPacketItem(ByteBuffer.wrap(packetBytes), dataPacket.sequence))
                    reliableDataSequence++
                    return Result.success(Unit)
                }
                val buf = DataChannel.Buffer(
                    ByteBuffer.wrap(packetBytes),
                    true,
                )
                val channel = dataChannelForKind(dataPacket.kind)
                    ?: throw RoomException.ConnectException("channel not established for ${dataPacket.kind.name}")

                if (!channel.send(buf)) {
                    return Result.failure(
                        RoomException.ConnectException("failed to send data packet for ${dataPacket.kind.name}"),
                    )
                }

                if (isReliable) {
                    // Wrap a fresh ByteBuffer so the queued item's position stays at 0; the
                    // ByteBuffer just sent has been drained by DataChannel.send.
                    reliableMessageBuffer.queue(DataPacketItem(ByteBuffer.wrap(packetBytes), dataPacket.sequence))
                    reliableDataSequence++
                }
            } catch (e: Exception) {
                e.rethrowIfCancellationSignal()
                return Result.failure(e)
            }
            return Result.success(Unit)
        }

        if (dataPacket.kind == GravixcloudModels.DataPacket.Kind.RELIABLE) {
            synchronized(reliableStateLock) {
                return sendDataImpl(dataPacket)
            }
        } else {
            return sendDataImpl(dataPacket)
        }
    }

    internal suspend fun resendReliableMessagesForResume(lastMessageSeq: Int): Result<Unit> {
        try {
            ensurePublisherConnected(GravixcloudModels.DataPacket.Kind.RELIABLE)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            return Result.failure(e)
        }
        val channel = dataChannelForKind(GravixcloudModels.DataPacket.Kind.RELIABLE)
            ?: return Result.failure(NullPointerException("reliable channel not established!"))

        synchronized(reliableStateLock) {
            reliableMessageBuffer.popToSequence(lastMessageSeq)
            for (item in reliableMessageBuffer.getAll()) {
                // Send a duplicate so the underlying buffer keeps position=0 and survives
                // multiple resume attempts. DataChannel.send drains the buffer's position to
                // its limit; without duplicate(), the second replay would send empty bytes.
                if (!channel.send(DataChannel.Buffer(item.data.duplicate(), true))) {
                    return Result.failure(
                        RoomException.ConnectException(
                            "failed to replay reliable data packet at sequence ${item.sequence}",
                        ),
                    )
                }
            }
        }

        return Result.success(Unit)
    }

    internal suspend fun waitForBufferStatusLow(kind: GravixcloudModels.DataPacket.Kind) {
        try {
            ensurePublisherConnected(kind)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            return
        }
        val manager = dataChannelManagerForKind(kind) ?: return
        manager.waitForBufferedAmountLow(DATA_CHANNEL_LOW_THRESHOLD.toLong())
    }

    @Throws(exceptionClasses = [RoomException.ConnectException::class])
    private suspend fun ensurePublisherConnected(kind: GravixcloudModels.DataPacket.Kind) {
        if (!isSubscriberPrimary) {
            return
        }

        if (publisher == null) {
            throw RoomException.ConnectException("Publisher isn't setup yet! Is the room connected?")
        }

        if (publisher?.isConnected() != true &&
            publisher?.iceConnectionState() != PeerConnection.IceConnectionState.CHECKING
        ) {
            // start negotiation
            this.negotiatePublisher()
        }

        // Ensure data channel exists
        dataChannelForKind(kind) ?: throw RoomException.ConnectException("Publisher data channel not established for ${kind.name}; is the room connected?")

        val channelManager = dataChannelManagerForKind(kind)
            ?: throw RoomException.ConnectException("Publisher data channel manager not established for ${kind.name}; is the room connected?")
        if (channelManager.state == DataChannel.State.OPEN) {
            return
        }

        // wait until publisher ICE connected
        val endTime = SystemClock.elapsedRealtime() + MAX_ICE_CONNECT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < endTime) {
            if (publisher?.isConnected() == true &&
                channelManager.state == DataChannel.State.OPEN
            ) {
                return
            }
            delay(50)
        }

        throw RoomException.ConnectException(
            "could not establish publisher connection: publisher state: ${publisherObserver.connectionState}, channel state: ${channelManager.state}",
        )
    }

    private fun dataChannelManagerForKind(kind: GravixcloudModels.DataPacket.Kind): DataChannelManager? =
        when (kind) {
            GravixcloudModels.DataPacket.Kind.RELIABLE -> reliableDataChannelManager
            GravixcloudModels.DataPacket.Kind.LOSSY -> lossyDataChannelManager
            GravixcloudModels.DataPacket.Kind.UNRECOGNIZED -> throw IllegalArgumentException("Unknown data packet kind!")
        }

    private fun dataChannelForKind(kind: GravixcloudModels.DataPacket.Kind) =
        when (kind) {
            GravixcloudModels.DataPacket.Kind.RELIABLE -> reliableDataChannel
            GravixcloudModels.DataPacket.Kind.LOSSY -> lossyDataChannel
            GravixcloudModels.DataPacket.Kind.UNRECOGNIZED -> throw IllegalArgumentException("Unknown data packet kind!")
        }

    private fun getPublisherOfferConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            with(mandatory) {
                add(
                    MediaConstraints.KeyValuePair(
                        MediaConstraintKeys.OFFER_TO_RECV_AUDIO,
                        MediaConstraintKeys.FALSE,
                    ),
                )
                add(
                    MediaConstraints.KeyValuePair(
                        MediaConstraintKeys.OFFER_TO_RECV_VIDEO,
                        MediaConstraintKeys.FALSE,
                    ),
                )
                if (connectionState == ConnectionState.RECONNECTING || connectionState == ConnectionState.RESUMING) {
                    add(
                        MediaConstraints.KeyValuePair(
                            MediaConstraintKeys.ICE_RESTART,
                            MediaConstraintKeys.TRUE,
                        ),
                    )
                }
            }
        }
    }

    private fun makeRTCConfig(
        serverResponse: Either<JoinResponse, ReconnectResponse>,
        connectOptions: ConnectOptions,
    ): RTCConfiguration {
        // Convert protobuf ice servers
        val serverIceServers = run {
            val servers = mutableListOf<PeerConnection.IceServer>()
            val responseServers = when (serverResponse) {
                is Either.Left -> serverResponse.value.iceServersList
                is Either.Right -> serverResponse.value.iceServersList
            }
            for (serverInfo in responseServers) {
                servers.add(serverInfo.toWebrtc())
            }

            if (servers.isEmpty()) {
                servers.addAll(SignalClient.DEFAULT_ICE_SERVERS)
            }
            servers
        }

        val rtcConfig = connectOptions.rtcConfig?.copy()?.apply {
            val mergedServers = iceServers.toMutableList()
            connectOptions.iceServers?.forEach { server ->
                if (!mergedServers.contains(server)) {
                    mergedServers.add(server)
                }
            }

            // Only use server-provided servers if user doesn't provide any.
            if (mergedServers.isEmpty()) {
                mergedServers.addAll(serverIceServers)
            }

            iceServers = mergedServers
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
            ?: RTCConfiguration(serverIceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

        val clientConfig = when (serverResponse) {
            is Either.Left -> {
                if (serverResponse.value.hasClientConfiguration()) {
                    serverResponse.value.clientConfiguration
                } else {
                    null
                }
            }

            is Either.Right -> {
                if (serverResponse.value.hasClientConfiguration()) {
                    serverResponse.value.clientConfiguration
                } else {
                    null
                }
            }
        }
        if (clientConfig != null) {
            if (clientConfig.forceRelay == GravixcloudModels.ClientConfigSetting.ENABLED) {
                rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }

        return rtcConfig
    }

    internal interface Listener {
        fun onEngineConnected()
        fun onEngineReconnected()
        fun onEngineReconnecting()
        fun onEngineResuming() {}
        fun onEngineResumed() {}

        /** GRAVIX: a reconnect attempt starts (before its dial). */
        fun onEngineReconnectAttempt(full: Boolean, cause: String, attempt: Int, afterNetworkChange: Boolean) {}

        /** GRAVIX (iceTransportWatchMs): the session's ICE transport changed. */
        fun onEngineIceTransportChanged(
            transport: GravixIceTransport?,
            previous: GravixIceTransport?,
            publisher: GravixIceTransport?,
            subscriber: GravixIceTransport?,
        ) {}

        /** GRAVIX (iceTransportWatchMs): a TCP session's round trip stayed above slowTcpRttMs for slowTcpForMs. */
        fun onEngineSlowTcpTransport(rttMs: Long, overMs: Long) {}
        fun onEngineDisconnected(reason: DisconnectReason)
        fun onFailToConnect(error: Throwable)
        fun onJoinResponse(response: JoinResponse)
        fun onAddTrack(receiver: RtpReceiver, track: MediaStreamTrack, streams: Array<out MediaStream>)
        fun onUpdateParticipants(updates: List<GravixcloudModels.ParticipantInfo>)
        fun onActiveSpeakersUpdate(speakers: List<GravixcloudModels.SpeakerInfo>)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: GravixcloudModels.Room)
        fun onConnectionQuality(updates: List<GravixcloudRtc.ConnectionQualityInfo>)
        fun onSpeakersChanged(speakers: List<GravixcloudModels.SpeakerInfo>)
        fun onUserPacket(packet: GravixcloudModels.UserPacket, kind: GravixcloudModels.DataPacket.Kind, encryptionType: GravixcloudModels.Encryption.Type)
        fun onStreamStateUpdate(streamStates: List<GravixcloudRtc.StreamStateInfo>)
        fun onSubscribedQualityUpdate(subscribedQualityUpdate: GravixcloudRtc.SubscribedQualityUpdate)
        fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: GravixcloudRtc.SubscriptionPermissionUpdate)
        fun onSubscriptionError(subscriptionResponse: GravixcloudRtc.SubscriptionResponse)
        fun onSignalConnected(isResume: Boolean)
        fun onFullReconnecting()
        suspend fun onPostReconnect(isFullReconnect: Boolean)
        fun onLocalTrackUnpublished(trackUnpublished: GravixcloudRtc.TrackUnpublishedResponse)
        fun onTranscriptionReceived(transcription: GravixcloudModels.Transcription)
        fun onLocalTrackSubscribed(trackSubscribed: GravixcloudRtc.TrackSubscribed)
        fun onRpcPacketReceived(dp: GravixcloudModels.DataPacket)
        fun onDataStreamPacket(dp: GravixcloudModels.DataPacket, encryptionType: GravixcloudModels.Encryption.Type)
    }

    companion object {

        /**
         * @suppress
         */
        @VisibleForTesting
        const val RELIABLE_DATA_CHANNEL_LABEL = "_reliable"

        /**
         * @suppress
         */
        @VisibleForTesting
        const val LOSSY_DATA_CHANNEL_LABEL = "_lossy"
        internal const val TARGET_DATA_PACKET_SIZE = 15 * 1024 // 15 KB

        /**
         * Corresponds to the max-message-size in SDP. Attempting to send packets
         * over this size will cause the data channel to close, so this must be enforced
         * within the SDK. Note that [DataChannel.send] will still report true
         * even if a huge packet is sent, so it's not a usable signal.
         *
         * TODO: get max-message-size from SDP or equivalent from libwebrtc.
         */
        internal const val MAX_DATA_PACKET_SIZE = 64 * 1024 - 1 // 64 KB
        private const val MAX_RECONNECT_RETRIES = 30
        private const val MAX_RECONNECT_TIMEOUT = 60 * 1000
        private const val MAX_ICE_CONNECT_TIMEOUT_MS = 20000
        private const val NETWORK_CHECK_MS = 250L // GRAVIX (waitForNetwork)
        private const val LIVE_STATE_POLL_MS = 50L // GRAVIX (resumeWaitsForLiveState)
        private const val SOCKET_ERROR_RESUMES_BEFORE_FULL = 2 // GRAVIX (resumeAfterNetworkSocketError)
        private const val MAX_CAUSE_DEPTH = 8 // GRAVIX

        // GRAVIX: RoomEvent.ReconnectAttempt causes (the Flutter SDK's ClientDisconnectReason names where one exists)
        internal const val CAUSE_PEER_FAILED = "peerConnectionFailed"
        internal const val CAUSE_PEER_CLOSED = "peerConnectionClosed"
        internal const val CAUSE_PEER_DISCONNECTED = "peerConnectionDisconnected"
        internal const val CAUSE_SIGNAL = "signal"
        internal const val CAUSE_NETWORK = "networkAvailable"
        internal const val CAUSE_RETRY = "reconnectRetry"
        internal const val CAUSE_REQUESTED = "requested"

        private const val DATA_CHANNEL_LOW_THRESHOLD = 2 * 1024 * 1024 // 2 MB

        private val RELIABLE_RECEIVE_STATE_TTL_MS = 30.seconds
        private val RELIABLE_RETRY_AMOUNT = (DATA_CHANNEL_LOW_THRESHOLD * 1.25).toLong()

        internal val CONN_CONSTRAINTS = MediaConstraints().apply {
            with(optional) {
                add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
            }
        }
    }

    // ---------------------------------- SignalClient.Listener --------------------------------------//

    override fun onServerAnswer(sessionDescription: SessionDescription, offerId: Int) {
        GxLog.v { "received server answer: ${sessionDescription.type}, ${runBlocking { publisher?.signalingState() }}" }
        coroutineScope.launch {
            when (val outcome = publisher?.setRemoteDescription(sessionDescription, offerId).nullSafe()) {
                is Either.Left -> {
                    // do nothing.
                }

                is Either.Right -> {
                    GxLog.e { "error setting remote description for answer: ${outcome.value} " }
                }
            }
        }
    }

    override fun onServerOffer(sessionDescription: SessionDescription, offerId: Int) {
        GxLog.v { "received server offer: ${sessionDescription.type}, ${runBlocking { publisher?.signalingState() }}" }
        coroutineScope.launch {
            run {
                when (val outcome = subscriber?.setRemoteDescription(sessionDescription, offerId).nullSafe()) {
                    is Either.Right -> {
                        GxLog.e { "error setting remote description for offer: ${outcome.value} " }
                        return@launch
                    }

                    else -> {}
                }
            }

            if (isClosed) {
                return@launch
            }

            val answer = run {
                when (val outcome = subscriber?.withPeerConnection { createAnswer(MediaConstraints()) }.nullSafe()) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        GxLog.e { "error creating answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            if (isClosed) {
                return@launch
            }

            run<Unit> {
                when (val outcome = subscriber?.withPeerConnection { setLocalDescription(answer) }.nullSafe()) {
                    is Either.Left -> Unit
                    is Either.Right -> {
                        GxLog.e { "error setting local description for answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            if (isClosed) {
                return@launch
            }
            client.sendAnswer(answer, offerId)
        }
    }

    override fun onTrickle(candidate: IceCandidate, target: GravixcloudRtc.SignalTarget) {
        GxLog.v { "received ice candidate from peer: $candidate, $target" }
        when (target) {
            GravixcloudRtc.SignalTarget.PUBLISHER -> {
                publisher?.addIceCandidate(candidate)
                    ?: GxLog.w { "received candidate for publisher when we don't have one. ignoring." }
            }

            GravixcloudRtc.SignalTarget.SUBSCRIBER -> {
                subscriber?.addIceCandidate(candidate)
                    ?: GxLog.w { "received candidate for subscriber when we don't have one. ignoring." }
            }

            else -> GxLog.i { "unknown ice candidate target?" }
        }
    }

    override fun onLocalTrackPublished(response: GravixcloudRtc.TrackPublishedResponse) {
        val cid = response.cid ?: run {
            GxLog.e { "local track published with null cid?" }
            return
        }

        val track = response.track
        if (track == null) {
            GxLog.d { "local track published with null track info?" }
        }

        GxLog.v { "local track published $cid" }
        val cont = synchronized(pendingTrackResolvers) {
            pendingTrackResolvers.remove(cid)
        }
        if (cont == null) {
            GxLog.d { "missing track resolver for: $cid" }
            return
        }
        cont.resume(response.track)
    }

    override fun onLocalTrackSubscribed(trackSubscribed: GravixcloudRtc.TrackSubscribed) {
        listener?.onLocalTrackSubscribed(trackSubscribed)
    }

    override fun onParticipantUpdate(updates: List<GravixcloudModels.ParticipantInfo>) {
        listener?.onUpdateParticipants(updates)
    }

    override fun onSpeakersChanged(speakers: List<GravixcloudModels.SpeakerInfo>) {
        listener?.onSpeakersChanged(speakers)
    }

    override fun onClose(reason: String, code: Int) {
        GxLog.i { "received close event: $reason, code: $code" }
        gravixEndReconnectWait?.invoke("the signal socket closed ($code)") // GRAVIX (leaveAbortsResumeWait / restartAbortsOnSignalLoss)
        endSignalSession()
        abortPendingPublishTracks()
        gravixReconnect(CAUSE_SIGNAL)
    }

    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        listener?.onRemoteMuteChanged(trackSid, muted)
    }

    override fun onRoomUpdate(update: GravixcloudModels.Room) {
        listener?.onRoomUpdate(update)
    }

    override fun onConnectionQuality(updates: List<GravixcloudRtc.ConnectionQualityInfo>) {
        listener?.onConnectionQuality(updates)
    }

    override fun onLeave(leave: LeaveRequest) {
        GxLog.d { "leave request received: reason = ${leave.reason.name}" }

        endSignalSession()
        abortPendingPublishTracks()

        if (leave.hasRegions()) {
            regionUrlProvider?.setServerReportedRegions(RegionSettings.fromProto(leave.regions))
        }
        // GRAVIX (leaveAbortsResumeWait): a reconnect attempt waiting for its peer connections acts on the Leave now
        if (leave.action == LeaveRequest.Action.RESUME || leave.action == LeaveRequest.Action.RECONNECT || leave.canReconnect) {
            gravixEndReconnectWait?.invoke("the server sent Leave{${leave.action}}")
        }

        when {
            leave.action == LeaveRequest.Action.RESUME -> {
                // resume will be triggered on close.
                // TODO: trigger immediately.
                fullReconnectOnNext = false
            }

            leave.action == LeaveRequest.Action.RECONNECT ||
                // canReconnect is deprecated protocol version >= 13
                leave.canReconnect -> {
                // resume will be triggered on close.
                // TODO: trigger immediately.
                fullReconnectOnNext = true
            }

            else -> {
                close()
                val disconnectReason = leave.reason.convert()
                listener?.onEngineDisconnected(disconnectReason)
            }
        }
    }

    // Signal error
    override fun onError(error: Throwable) {
        if (connectionState == ConnectionState.CONNECTING) {
            listener?.onFailToConnect(error)
        }
    }

    override fun onStreamStateUpdate(streamStates: List<GravixcloudRtc.StreamStateInfo>) {
        listener?.onStreamStateUpdate(streamStates)
    }

    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: GravixcloudRtc.SubscribedQualityUpdate) {
        listener?.onSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: GravixcloudRtc.SubscriptionPermissionUpdate) {
        listener?.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    override fun onSubscriptionError(subscriptionResponse: GravixcloudRtc.SubscriptionResponse) {
        listener?.onSubscriptionError(subscriptionResponse)
    }

    override fun onRefreshToken(token: String) {
        sessionToken = token
        regionUrlProvider?.token = token
    }

    override fun onLocalTrackUnpublished(trackUnpublished: GravixcloudRtc.TrackUnpublishedResponse) {
        listener?.onLocalTrackUnpublished(trackUnpublished)
    }

    // --------------------------------- DataChannel.Observer ------------------------------------//

    fun onBufferedAmountChange(dataChannel: DataChannel, previousAmount: Long) {
    }

    fun onStateChange(dataChannel: DataChannel) {
    }

    fun onMessage(dataChannel: DataChannel, buffer: DataChannel.Buffer?) {
        if (buffer == null) {
            return
        }
        var dp = GravixcloudModels.DataPacket.parseFrom(ByteString.copyFrom(buffer.data))

        if (dp.sequence > 0 && dp.participantSid.isNotEmpty()) {
            synchronized(reliableStateLock) {
                val lastSeq = reliableReceivedState[dp.participantSid]
                if (lastSeq != null && dp.sequence <= lastSeq) {
                    // ignore duplicate or out-of-order packets in reliable channel
                    return
                }
                this.reliableReceivedState[dp.participantSid] = dp.sequence
            }
        }

        // Always decrypt if able, to allow for backward compatibility.
        val dataPacketCryptor = dataPacketCryptorManager
        var encryptionType = GravixcloudModels.Encryption.Type.NONE
        if (dp.hasEncryptedPacket() && dataPacketCryptor != null) {
            val encryptedPacket = EncryptedPacket(
                dp.encryptedPacket.encryptedValue.toByteArray(),
                dp.encryptedPacket.iv.toByteArray(),
                dp.encryptedPacket.keyIndex,
            )
            encryptionType = dp.encryptedPacket.encryptionType

            val decryptedData = dataPacketCryptor.decrypt(Participant.Identity(dp.participantIdentity), encryptedPacket)
            if (decryptedData == null) {
                GxLog.i { "Failed to decrypt data packet." }
                return
            }
            val payload = GravixcloudModels.EncryptedPacketPayload.parseFrom(decryptedData)

            dp = with(dp.toBuilder()) {
                setFromEncryptedPayload(payload)
                build()
            }
        }

        when (dp.valueCase) {
            GravixcloudModels.DataPacket.ValueCase.SPEAKER -> {
                listener?.onActiveSpeakersUpdate(dp.speaker.speakersList)
            }

            GravixcloudModels.DataPacket.ValueCase.USER -> {
                listener?.onUserPacket(dp.user, dp.kind, encryptionType)
            }

            GravixcloudModels.DataPacket.ValueCase.SIP_DTMF -> {
                // TODO
            }

            GravixcloudModels.DataPacket.ValueCase.TRANSCRIPTION -> {
                listener?.onTranscriptionReceived(dp.transcription)
            }

            GravixcloudModels.DataPacket.ValueCase.METRICS -> {
                // TODO
            }

            GravixcloudModels.DataPacket.ValueCase.CHAT_MESSAGE -> {
                // TODO
            }

            GravixcloudModels.DataPacket.ValueCase.RPC_REQUEST,
            GravixcloudModels.DataPacket.ValueCase.RPC_ACK,
            GravixcloudModels.DataPacket.ValueCase.RPC_RESPONSE,
            -> {
                listener?.onRpcPacketReceived(dp)
            }

            GravixcloudModels.DataPacket.ValueCase.STREAM_HEADER,
            GravixcloudModels.DataPacket.ValueCase.STREAM_CHUNK,
            GravixcloudModels.DataPacket.ValueCase.STREAM_TRAILER,
            -> {
                listener?.onDataStreamPacket(dp, encryptionType)
            }

            GravixcloudModels.DataPacket.ValueCase.ENCRYPTED_PACKET -> {
                // should be handled above.
            }

            GravixcloudModels.DataPacket.ValueCase.VALUE_NOT_SET,
            null,
            -> {
                GxLog.v { "invalid value for data packet" }
            }
        }
    }

    private inner class DataChannelObserver(val dataChannel: DataChannel) : DataChannel.Observer {
        override fun onBufferedAmountChange(p0: Long) {
            this@RTCEngine.onBufferedAmountChange(dataChannel, p0)
        }

        override fun onStateChange() {
            this@RTCEngine.onStateChange(dataChannel)
        }

        override fun onMessage(p0: DataChannel.Buffer) {
            this@RTCEngine.onMessage(dataChannel, p0)
        }
    }

    fun sendSyncState(
        subscription: GravixcloudRtc.UpdateSubscription,
        publishedTracks: List<GravixcloudRtc.TrackPublishedResponse>,
    ) {
        var answer: GravixcloudRtc.SessionDescription? = null
        var offer: GravixcloudRtc.SessionDescription? = null
        runBlocking {
            subscriber?.withPeerConnection {
                answer = localDescription?.toProtoSessionDescription()
                offer = remoteDescription?.toProtoSessionDescription()
            }
        }

        val dataChannelInfos = GravixcloudModels.DataPacket.Kind.entries
            .filterNot { it == GravixcloudModels.DataPacket.Kind.UNRECOGNIZED }
            .mapNotNull { kind -> dataChannelForKind(kind) }
            .map { dataChannel ->
                GravixcloudRtc.DataChannelInfo.newBuilder()
                    .setId(dataChannel.id())
                    .setLabel(dataChannel.label())
                    .build()
            }

        val dataChannelReceiveStates = synchronized(reliableStateLock) {
            this.reliableReceivedState.map { (participantSid, sequence) ->
                with(GravixcloudRtc.DataChannelReceiveState.newBuilder()) {
                    publisherSid = participantSid
                    lastSeq = sequence
                    build()
                }
            }
        }

        val syncState = with(GravixcloudRtc.SyncState.newBuilder()) {
            if (answer != null) {
                setAnswer(answer)
            }
            if (offer != null) {
                setOffer(offer)
            }
            setSubscription(subscription)
            addAllPublishTracks(publishedTracks)
            addAllDataChannels(dataChannelInfos)
            addAllDatachannelReceiveStates(dataChannelReceiveStates)
            build()
        }

        client.sendSyncState(syncState)
    }

    fun getPublisherRTCStats(callback: RTCStatsCollectorCallback) {
        runBlocking {
            publisher?.withPeerConnection { getStats(callback) }
                ?: callback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
        }
    }

    fun getSubscriberRTCStats(callback: RTCStatsCollectorCallback) {
        runBlocking {
            subscriber?.withPeerConnection { getStats(callback) }
                ?: callback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
        }
    }

    fun createStatsGetter(sender: RtpSender): RTCStatsGetter {
        val p = publisher
        return { statsCallback: RTCStatsCollectorCallback ->
            runBlocking {
                p?.withPeerConnection {
                    getStats(sender, statsCallback)
                } ?: statsCallback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
            }
        }
    }

    fun createStatsGetter(receiver: RtpReceiver): RTCStatsGetter {
        val p = subscriber
        return { statsCallback: RTCStatsCollectorCallback ->
            runBlocking {
                p?.withPeerConnection {
                    getStats(receiver, statsCallback)
                } ?: statsCallback.onStatsDelivered(RTCStatsReport(0, emptyMap()))
            }
        }
    }

    internal fun registerTrackBitrateInfo(cid: String, trackBitrateInfo: TrackBitrateInfo) {
        publisher?.registerTrackBitrateInfo(cid, trackBitrateInfo)
    }

    internal fun removeTrack(rtcTrack: MediaStreamTrack) {
        runBlocking {
            publisher?.withPeerConnection {
                val senders = this.senders
                for (sender in senders) {
                    val t = sender.track() ?: continue
                    if (t.id() == rtcTrack.id()) {
                        this@withPeerConnection.removeTrack(sender)
                    }
                }
            }
        }
    }

    internal fun stopTransceivers(transceivers: List<RtpTransceiver>) {
        if (transceivers.isEmpty()) {
            return
        }
        runBlocking {
            publisher?.withPeerConnection {
                for (transceiver in transceivers) {
                    if (!transceiver.isStopped) {
                        transceiver.stopInternal()
                    }
                }
            }
        }
    }

    /**
     * Detaches a sender transceiver whose publish attempt produced no publication.
     *
     * A publish fails most often because the signal connection died, and that same failure
     * starts a reconnect which renegotiates this peer connection. Mutating its senders and
     * transceivers alongside that negotiation races it, so the rollback is skipped unless the
     * publisher is connected and idle. The sender retains its signal session state so an aborted
     * publish cannot mutate the same publisher after a soft reconnect.
     *
     * @param stopTransceiver releases the transceiver and its SDP m-section for reuse. Reserved
     * for video, matching the teardown an unpublish performs.
     * @return whether the sender was detached, so the track can drop its reference to it.
     */
    internal fun rollbackSenderTransceiver(
        senderTransceiver: SenderTransceiverHandle,
        stopTransceiver: Boolean,
    ): Boolean {
        val publisher = senderTransceiver.publisher
        return runBlocking {
            publisher.withPeerConnection {
                val sessionState = signalSessionState
                val publisherIsIdle = this@RTCEngine.publisher === publisher &&
                    connectionState == ConnectionState.CONNECTED &&
                    reconnectingJob?.isActive != true &&
                    senderTransceiver.signalSessionState === sessionState &&
                    !sessionState.ended
                if (!publisherIsIdle) {
                    return@withPeerConnection false
                }
                val transceiver = senderTransceiver.transceiver
                removeTrack(transceiver.sender)
                if (stopTransceiver && !transceiver.isStopped) {
                    transceiver.stopInternal()
                }
                true
            } ?: false
        }
    }

    @VisibleForTesting
    fun getPublisherPeerConnection() =
        publisher!!.peerConnection

    @VisibleForTesting
    fun getSubscriberPeerConnection() =
        subscriber!!.peerConnection
}

/**
 * Identity of one signal session, replaced at every session boundary.
 *
 * [ended] distinguishes a session that is finishing from one that is serving, for work that
 * starts after the boundary and would otherwise match the current identity.
 */
internal class SignalSessionState(
    val ended: Boolean,
)

internal class SenderTransceiverHandle(
    internal val publisher: PeerConnectionTransport,
    internal val transceiver: RtpTransceiver,
    internal val signalSessionState: SignalSessionState,
)

/**
 * @suppress
 */
enum class ReconnectType {
    DEFAULT,
    FORCE_SOFT_RECONNECT,
    FORCE_FULL_RECONNECT,
}

/**
 * @suppress
 */
fun GravixcloudRtc.ICEServer.toWebrtc(): PeerConnection.IceServer = PeerConnection.IceServer.builder(urlsList)
    .setUsername(username ?: "")
    .setPassword(credential ?: "")
    .setTlsAlpnProtocols(emptyList())
    .setTlsEllipticCurves(emptyList())
    .createIceServer()

typealias PeerConnectionStateListener = (PeerConnectionState) -> Unit

internal fun GravixcloudModels.DataPacket.asEncryptedPacketPayload(): GravixcloudModels.EncryptedPacketPayload? {
    return when (valueCase) {
        GravixcloudModels.DataPacket.ValueCase.USER -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setUser(this.user)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.RPC_REQUEST -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setRpcRequest(this.rpcRequest)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.RPC_ACK -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setRpcAck(this.rpcAck)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.RPC_RESPONSE -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setRpcResponse(this.rpcResponse)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.STREAM_HEADER -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setStreamHeader(this.streamHeader)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.STREAM_CHUNK -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setStreamChunk(this.streamChunk)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.STREAM_TRAILER -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setStreamTrailer(this.streamTrailer)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.CHAT_MESSAGE -> {
            GravixcloudModels.EncryptedPacketPayload.newBuilder()
                .setChatMessage(this.chatMessage)
                .build()
        }

        GravixcloudModels.DataPacket.ValueCase.METRICS,
        GravixcloudModels.DataPacket.ValueCase.SIP_DTMF,
        GravixcloudModels.DataPacket.ValueCase.SPEAKER,
        GravixcloudModels.DataPacket.ValueCase.ENCRYPTED_PACKET,
        GravixcloudModels.DataPacket.ValueCase.TRANSCRIPTION,
        GravixcloudModels.DataPacket.ValueCase.VALUE_NOT_SET,
        -> {
            null
        }
    }
}

internal fun GravixcloudModels.DataPacket.Builder.setFromEncryptedPayload(payload: GravixcloudModels.EncryptedPacketPayload) {
    when (payload.valueCase) {
        GravixcloudModels.EncryptedPacketPayload.ValueCase.USER -> {
            this.user = payload.user
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.CHAT_MESSAGE -> {
            this.chatMessage = payload.chatMessage
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.RPC_REQUEST -> {
            this.rpcRequest = payload.rpcRequest
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.RPC_ACK -> {
            this.rpcAck = payload.rpcAck
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.RPC_RESPONSE -> {
            this.rpcResponse = payload.rpcResponse
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.STREAM_HEADER -> {
            this.streamHeader = payload.streamHeader
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.STREAM_CHUNK -> {
            this.streamChunk = payload.streamChunk
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.STREAM_TRAILER -> {
            this.streamTrailer = payload.streamTrailer
        }

        GravixcloudModels.EncryptedPacketPayload.ValueCase.VALUE_NOT_SET -> {
            // decryption likely failed
            GxLog.w { "Attempting to set from non-valid payload" }
        }
    }
}
