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

@file:Suppress("unused")

package com.gravixcloud.rtc.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import androidx.annotation.VisibleForTesting
import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.GravixRtcOverrides
import com.gravixcloud.rtc.RoomOptions
import com.gravixcloud.rtc.Version
import com.gravixcloud.rtc.audio.AudioHandler
import com.gravixcloud.rtc.audio.AudioProcessingController
import com.gravixcloud.rtc.audio.AudioRecordPrewarmer
import com.gravixcloud.rtc.audio.AudioSwitchHandler
import com.gravixcloud.rtc.audio.AuthedAudioProcessingController
import com.gravixcloud.rtc.audio.CommunicationWorkaround
import com.gravixcloud.rtc.audio.startPreconnectAudioJob
import com.gravixcloud.rtc.dagger.InjectionNames
import com.gravixcloud.rtc.e2ee.E2EEManager
import com.gravixcloud.rtc.e2ee.E2EEOptions
import com.gravixcloud.rtc.events.BroadcastEventBus
import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.events.ParticipantEvent
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.join.GravixJoinLog
import com.gravixcloud.rtc.join.gravixSessionKeyOf
import com.gravixcloud.rtc.memory.CloseableManager
import com.gravixcloud.rtc.regions.GravixRegionFailoverOrder
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.renderer.TextureViewRenderer
import com.gravixcloud.rtc.room.datastream.incoming.IncomingDataStreamManager
import com.gravixcloud.rtc.room.metrics.collectMetrics
import com.gravixcloud.rtc.room.network.NetworkCallbackManagerFactory
import com.gravixcloud.rtc.room.network.ReconnectPolicy
import com.gravixcloud.rtc.room.participant.AudioTrackPublishDefaults
import com.gravixcloud.rtc.room.participant.ConnectionQuality
import com.gravixcloud.rtc.room.participant.LocalParticipant
import com.gravixcloud.rtc.room.participant.Participant
import com.gravixcloud.rtc.room.participant.ParticipantListener
import com.gravixcloud.rtc.room.participant.RemoteParticipant
import com.gravixcloud.rtc.room.participant.RpcHandler
import com.gravixcloud.rtc.room.participant.VideoTrackPublishDefaults
import com.gravixcloud.rtc.room.participant.publishTracksInfo
import com.gravixcloud.rtc.room.provisions.GxObjects
import com.gravixcloud.rtc.room.rpc.RPC_REQUEST_DATA_STREAM_TOPIC
import com.gravixcloud.rtc.room.rpc.RPC_RESPONSE_DATA_STREAM_TOPIC
import com.gravixcloud.rtc.room.rpc.RpcClientManager
import com.gravixcloud.rtc.room.rpc.RpcManager
import com.gravixcloud.rtc.room.rpc.RpcServerManager
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import com.gravixcloud.rtc.room.track.LocalTrackPublication
import com.gravixcloud.rtc.room.track.LocalVideoTrackOptions
import com.gravixcloud.rtc.room.track.RemoteTrackPublication
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.room.track.TrackPublication
import com.gravixcloud.rtc.room.types.toSDKType
import com.gravixcloud.rtc.room.util.ConnectionWarmer
import com.gravixcloud.rtc.util.FlowObservable
import com.gravixcloud.rtc.util.GravixStepLogs
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.flow
import com.gravixcloud.rtc.util.flowDelegate
import com.gravixcloud.rtc.util.invoke
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import com.gravixcloud.rtc.webrtc.getFilteredStats
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SurfaceViewRenderer
import org.webrtc.audio.AudioDeviceModule
import java.net.URI
import java.util.Collections
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Named
import kotlin.time.Duration

class Room
@AssistedInject
constructor(
    @Assisted private val context: Context,
    internal val engine: RTCEngine,
    private val eglBase: EglBase,
    localParticipantFactory: LocalParticipant.Factory,
    private val defaultsManager: DefaultsManager,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val defaultDispatcher: CoroutineDispatcher,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * The [AudioHandler] for setting up the audio as need.
     *
     * By default, this is an instance of [AudioSwitchHandler].
     *
     * This can be substituted for your own custom implementation through
     * [GravixRtcOverrides.audioOptions] when creating the room with [GravixRtc.create].
     *
     * @see [audioSwitchHandler]
     * @see [AudioSwitchHandler]
     */
    val audioHandler: AudioHandler,
    private val closeableManager: CloseableManager,
    private val e2EEManagerFactory: E2EEManager.Factory,
    private val communicationWorkaround: CommunicationWorkaround,
    val audioProcessingController: AudioProcessingController,
    /**
     * A holder for objects that are used internally within Gravix.
     */
    val gxObjects: GxObjects,
    networkCallbackManagerFactory: NetworkCallbackManagerFactory,
    private val audioDeviceModule: AudioDeviceModule,
    private val regionUrlProviderFactory: RegionUrlProvider.Factory,
    private val connectionWarmer: ConnectionWarmer,
    private val audioRecordPrewarmer: AudioRecordPrewarmer,
    private val incomingDataStreamManager: IncomingDataStreamManager,
    private val rpcClientManager: RpcClientManager,
    private val rpcServerManager: RpcServerManager,
    private val remoteParticipantFactory: RemoteParticipant.Factory,
) : RTCEngine.Listener, ParticipantListener, RpcManager, IncomingDataStreamManager by incomingDataStreamManager {

    private lateinit var coroutineScope: CoroutineScope
    private val eventBus = BroadcastEventBus<RoomEvent>()
    val events = eventBus.readOnly()

    init {
        engine.listener = this

        // Register SDK-internal text-stream handlers for the RPC v2 transport. These reserve
        // the topics `lk.rpc_request` and `lk.rpc_response` from user-level handler registration.
        incomingDataStreamManager.registerTextStreamHandler(RPC_REQUEST_DATA_STREAM_TOPIC) { receiver, fromIdentity ->
            coroutineScope.launch {
                rpcServerManager.handleIncomingDataStream(receiver, fromIdentity)
            }
        }
        incomingDataStreamManager.registerTextStreamHandler(RPC_RESPONSE_DATA_STREAM_TOPIC) { receiver, fromIdentity ->
            coroutineScope.launch {
                rpcClientManager.handleIncomingDataStreamResponse(receiver, fromIdentity)
            }
        }

        // Wire each manager's clientProtocol lookup via the remote-participants store.
        val getRemoteClientProtocol: (Participant.Identity) -> Int = { id ->
            remoteParticipants[id]?.clientProtocol
                ?: ClientProtocolVersion.DEFAULT.value
        }
        rpcClientManager.getRemoteClientProtocol = getRemoteClientProtocol
        rpcServerManager.getRemoteClientProtocol = getRemoteClientProtocol
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
    }

    /**
     * @suppress
     */
    enum class SimulateScenario {
        SPEAKER_UPDATE,
        NODE_FAILURE,
        MIGRATION,
        SERVER_LEAVE,
        SERVER_LEAVE_FULL_RECONNECT,
    }

    @Serializable
    @JvmInline
    value class Sid(val sid: String)

    /**
     * The session id of the room.
     *
     * Note: the sid may not be populated immediately upon [connect],
     * so using the suspend function [getSid] or listening to the flow
     * `room::sid.flow` is highly advised.
     */
    @FlowObservable
    @get:FlowObservable
    var sid: Sid? by flowDelegate(null)
        private set

    /**
     * Gets the sid of the room.
     *
     * If the sid is not yet available, will suspend until received.
     */
    suspend fun getSid(): Sid {
        return this@Room::sid.flow
            .filterNotNull()
            .first()
    }

    @FlowObservable
    @get:FlowObservable
    var name: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var state: State by flowDelegate(State.DISCONNECTED) { new, old ->
        if (new != old) {
            when (new) {
                State.CONNECTING -> {
                    audioHandler.start()
                    communicationWorkaround.start()
                }

                State.DISCONNECTED -> {
                    audioHandler.stop()
                    communicationWorkaround.stop()
                    audioRecordPrewarmer.stop()
                }

                else -> {}
            }
        }
    }
        private set

    @FlowObservable
    @get:FlowObservable
    var metadata: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var isRecording: Boolean by flowDelegate(false)
        private set

    /**
     * @suppress
     */
    @VisibleForTesting
    var enableMetrics: Boolean = true

    /**
     *  end-to-end encryption manager
     */
    var e2eeManager: E2EEManager? = null

    /**
     * Automatically manage quality of subscribed video tracks, subscribe to the
     * an appropriate resolution based on the size of the video elements that tracks
     * are attached to.
     *
     * Also observes the visibility of attached tracks and pauses receiving data
     * if they are not visible.
     *
     * Defaults to false.
     */
    var adaptiveStream: Boolean = false

    /**
     *  audio processing is enabled
     */
    var audioProcessorIsEnabled: Boolean = false

    /**
     * Dynamically pauses video layers that are not being consumed by any subscribers,
     * significantly reducing publishing CPU and bandwidth usage.
     *
     * Defaults to false.
     *
     * Will be enabled if SVC codecs (i.e. VP9/AV1) are used. Multi-codec simulcast
     * requires dynacast.
     */
    var dynacast: Boolean
        get() = localParticipant.dynacast
        set(value) {
            localParticipant.dynacast = value
        }

    /**
     * Options for end-to-end encryption. Must be setup prior to [connect].
     *
     * If null, e2ee will be disabled.
     */
    var e2eeOptions: E2EEOptions? = null

    /**
     * Default options to use when creating an audio track.
     */
    var audioTrackCaptureDefaults: LocalAudioTrackOptions by defaultsManager::audioTrackCaptureDefaults

    /**
     * Default options to use when publishing an audio track.
     */
    var audioTrackPublishDefaults: AudioTrackPublishDefaults by defaultsManager::audioTrackPublishDefaults

    /**
     * Default options to use when creating a video track.
     */
    var videoTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::videoTrackCaptureDefaults

    /**
     * Default options to use when publishing a video track.
     */
    var videoTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::videoTrackPublishDefaults

    /**
     * Default options to use when creating a screen share track.
     */
    var screenShareTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::screenShareTrackCaptureDefaults

    /**
     * Default options to use when publishing a screen share track.
     */
    var screenShareTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::screenShareTrackPublishDefaults

    /**
     * [ReconnectPolicy] to use when reconnecting to the server.
     */
    var reconnectPolicy: ReconnectPolicy by engine::reconnectPolicy

    /**
     * GRAVIX: how this room recovers from network and region failures (region failover switches; see
     * [GravixRecoveryOptions]). [GravixRecoveryOptions.UPSTREAM] = the upstream engine's behaviour.
     */
    var gravixRecovery: GravixRecoveryOptions by engine::gravixRecovery

    /**
     * GRAVIX: the url the session is connected to -- after a region failover or a full rejoin to another region,
     * NOT the url passed to [connect]. Kept through a resume and while reconnecting; null when disconnected. An
     * app's server indicator should read this (or follow [RoomEvent.ConnectedUrlChanged]).
     */
    @FlowObservable
    @get:FlowObservable
    var connectedUrl: String? by flowDelegate(null)
        private set

    /**
     * GRAVIX: the region of [connectedUrl] when known: the region the server named in its JoinResponse, else the
     * entry for the url in [gravixRegionHints] or the server's region list (`/settings/regions`), else the start-up
     * measurement ([com.gravixcloud.rtc.regions.GravixRegions]). Null when unknown or not connected.
     */
    @FlowObservable
    @get:FlowObservable
    var connectedRegion: String? by flowDelegate(null)
        private set

    /** GRAVIX: region / url pairs this room may connect to (e.g. the token's region list), to name [connectedRegion]. */
    var gravixRegionHints: List<GravixRegionUrl> = emptyList()

    private val connectedUrlListeners = CopyOnWriteArrayList<GravixConnectedUrlListener>()

    /** GRAVIX: the region the server named in the last JoinResponse (ServerInfo.region), if any. */
    @Volatile
    private var gravixServerRegion: String? = null

    /**
     * GRAVIX: [listener] is called (on the thread that made the change) whenever [connectedUrl] changes: the first
     * connect, a region failover, a full rejoin to another url. The Java-friendly form of
     * [RoomEvent.ConnectedUrlChanged]; the Kotlin form is `room::connectedUrl.flow` or the event.
     */
    fun addConnectedUrlListener(listener: GravixConnectedUrlListener) {
        connectedUrlListeners.addIfAbsent(listener)
    }

    fun removeConnectedUrlListener(listener: GravixConnectedUrlListener) {
        connectedUrlListeners.remove(listener)
    }

    /**
     * GRAVIX: how this session's media travels, from each peer connection's selected ICE candidate pair:
     * [GravixIceTransport.TCP] when either connection runs over ICE/TCP, [GravixIceTransport.RELAY] when either is
     * relayed, else [GravixIceTransport.UDP]. Null until the first reading, after the session ended, during a full
     * rejoin until the new connections selected their pairs, or with [GravixRecoveryOptions.iceTransportWatchMs] off
     * (turned off during a session, the last value stays). This getter is the source of truth: two quick changes'
     * events may arrive in either order. Changes are [RoomEvent.IceTransportChanged]; a slow TCP
     * session is [RoomEvent.SlowTcpTransport]. The Flutter SDK's `Room.selectedTransport` (0.4.18).
     */
    val selectedTransport: GravixIceTransport?
        get() = engine.gravixIceWatch.transport

    /** GRAVIX: the publisher connection's transport (see [selectedTransport]). */
    val publisherTransport: GravixIceTransport?
        get() = engine.gravixIceWatch.publisher

    /** GRAVIX: the subscriber connection's transport (see [selectedTransport]). */
    val subscriberTransport: GravixIceTransport?
        get() = engine.gravixIceWatch.subscriber

    private fun gravixRegionFor(url: String): String? {
        gravixServerRegion?.let { return it }
        val known = gravixRegionHints +
            regionUrlProvider?.gravixRegionSettings?.regions.orEmpty().map { GravixRegionUrl(it.region, it.url) }
        return GravixRegionFailoverOrder.regionOfUrl(url, known)
    }

    /**
     * GRAVIX: records the url the engine is connected to now. A rejoin to another url emits
     * [RoomEvent.ConnectedUrlChanged]; a connect's event is emitted with [RoomEvent.Connected]
     * ([gravixEmitConnectedUrl]).
     */
    private fun gravixNoteConnectedUrl(reason: RoomEvent.ConnectedUrlChangeReason) {
        val url = engine.connectedUrl ?: return
        val region = gravixRegionFor(url)
        val previousUrl = connectedUrl
        val previousRegion = connectedRegion
        connectedUrl = url
        connectedRegion = region
        if (reason == RoomEvent.ConnectedUrlChangeReason.CONNECT) return
        // same server (a resume, or the region named late): no event
        if (previousUrl == url) return
        gravixEmit(RoomEvent.ConnectedUrlChanged(this, url, region, previousUrl, previousRegion, reason))
    }

    /** GRAVIX: the first connect's [RoomEvent.ConnectedUrlChanged], right after [RoomEvent.Connected]. */
    private fun gravixEmitConnectedUrl() {
        val url = engine.connectedUrl ?: return
        val region = gravixRegionFor(url)
        connectedUrl = url
        connectedRegion = region
        gravixEmit(RoomEvent.ConnectedUrlChanged(this, url, region, null, null, RoomEvent.ConnectedUrlChangeReason.CONNECT))
    }

    private fun gravixEmit(event: RoomEvent.ConnectedUrlChanged) {
        GxLog.i { "connected url: ${GravixStepLogs.host(event.url)} (region ${event.region ?: "unknown"}; was ${GravixStepLogs.host(event.previousUrl)})" }
        for (l in connectedUrlListeners) {
            try {
                l.onConnectedUrlChanged(event)
            } catch (e: Exception) {
                GxLog.w(e) { "connected url listener threw" }
            }
        }
        eventBus.postEvent(event, coroutineScope)
    }

    val localParticipant: LocalParticipant = localParticipantFactory.create(dynacast = false).apply {
        internalListener = this@Room
    }

    private var mutableRemoteParticipants by flowDelegate(emptyMap<Participant.Identity, RemoteParticipant>())

    @FlowObservable
    @get:FlowObservable
    val remoteParticipants: Map<Participant.Identity, RemoteParticipant>
        get() = mutableRemoteParticipants

    /**
     * A convenience getter for the audio handler as a [AudioSwitchHandler].
     *
     * Will return null if [audioHandler] is not a [AudioSwitchHandler].
     */
    val audioSwitchHandler: AudioSwitchHandler?
        get() = audioHandler as? AudioSwitchHandler

    val serverInfo: ServerInfo?
        get() = engine.serverInfo

    private val sidToIdentity = mutableMapOf<Participant.Sid, Participant.Identity>()

    private var mutableActiveSpeakers by flowDelegate(emptyList<Participant>())

    @FlowObservable
    @get:FlowObservable
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    @Volatile
    private var hasLostConnectivity: Boolean = false
    private var connectOptions: ConnectOptions = ConnectOptions()

    private var stateLock = Mutex()

    private var regionUrlProvider: RegionUrlProvider? = null
    private var regionUrl: String? = null

    private val transcriptionReceivedTimes = mutableMapOf<String, Long>()

    internal var isPrerecording by defaultsManager::isPrerecording

    private fun getCurrentRoomOptions(): RoomOptions =
        RoomOptions(
            adaptiveStream = adaptiveStream,
            dynacast = dynacast,
            e2eeOptions = e2eeOptions,
            audioTrackCaptureDefaults = audioTrackCaptureDefaults,
            videoTrackCaptureDefaults = videoTrackCaptureDefaults,
            audioTrackPublishDefaults = audioTrackPublishDefaults,
            videoTrackPublishDefaults = videoTrackPublishDefaults,
            screenShareTrackCaptureDefaults = screenShareTrackCaptureDefaults,
            screenShareTrackPublishDefaults = screenShareTrackPublishDefaults,
        )

    /**
     * prepareConnection should be called as soon as the page is loaded, in order
     * to speed up the connection attempt. This function will
     * - perform DNS resolution and pre-warm the DNS cache
     * - establish TLS connection and cache TLS keys
     *
     * With Gravix Cloud, it will also determine the best edge data center for
     * the current client to connect to if a token is provided.
     *
     * @throws RetiredServerException when [url] names a retired Gravix server (nothing is contacted).
     */
    suspend fun prepareConnection(url: String, token: String? = null) {
        // GRAVIX(retired servers): first, so a retired server is not even warmed up (see RetiredServerException)
        requireNotRetiredGravixServer(url)
        if (state != State.DISCONNECTED) {
            GxLog.i { "Room is not in disconnected state, ignoring prepareConnection call." }
            return
        }
        GxLog.d { "preparing connection to ${GravixStepLogs.host(url)}" }

        try {
            val urlActual = URI(url)
            if (urlActual.isGravixCloud() && token != null) {
                val regionUrlProvider = regionUrlProviderFactory.create(urlActual, token)
                this.regionUrlProvider = regionUrlProvider

                val regionUrl = regionUrlProvider.getNextBestRegionUrl()
                // we will not replace the regionUrl if an attempt had already started
                // to avoid overriding regionUrl after a new connection attempt had started
                if (regionUrl != null && state == State.DISCONNECTED) {
                    this.regionUrl = regionUrl
                    connectionWarmer.fetch(regionUrl)
                    GxLog.d { "prepared connection to ${GravixStepLogs.host(regionUrl)}" }
                }
            } else {
                connectionWarmer.fetch(url)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            GxLog.e(e) { "Error while preparing connection:" }
        }
    }

    /**
     * Connect to a Gravix Room.
     *
     * @param url
     * @param token
     * @param options
     *
     * @throws RetiredServerException when [url] names a retired Gravix server: thrown first, before any state change.
     * @throws IllegalStateException when connect is attempted while the room is not disconnected.
     * @throws Exception when connection fails
     */
    @Throws(Exception::class)
    suspend fun connect(url: String, token: String, options: ConnectOptions = ConnectOptions()) = coroutineScope {
        // GRAVIX(retired servers, 2026-10-12): refuse the retired deployment before anything else (no state change, no
        // region lookup, no dial). Joining it "worked" but put the call on a server outside the fleet.
        requireNotRetiredGravixServer(url)
        if (state != State.DISCONNECTED) {
            throw IllegalStateException("Room.connect attempted while room is not disconnected!")
        }
        // GRAVIX (0.1.2): [GravixJoin] step lines for every connect (keyed from the token's grant; no secret)
        val gxKey = gravixSessionKeyOf(token)
        val gxStartNs = System.nanoTime()
        GravixJoinLog.step("roomConnect", gxKey, detail = "host=${GravixJoinLog.host(url)}")
        val roomOptions: RoomOptions
        stateLock.withLock {
            if (state != State.DISCONNECTED) {
                throw IllegalStateException("Room.connect attempted while room is not disconnected!")
            }
            if (::coroutineScope.isInitialized) {
                val job = coroutineScope.coroutineContext.job
                coroutineScope.cancel()
                job.join()
            }

            state = State.CONNECTING
            connectOptions = options

            coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())

            roomOptions = getCurrentRoomOptions()

            // Setup local participant.
            localParticipant.reinitialize(options)
            setupLocalParticipantEventHandling()

            if (roomOptions.e2eeOptions != null) {
                e2eeManager = e2EEManagerFactory.create(roomOptions.e2eeOptions.keyProvider).apply {
                    setup(this@Room) { event ->
                        coroutineScope.launch {
                            emitWhenConnected(event)
                        }
                    }
                }
                engine.e2EEManager = e2eeManager
            }
        }

        // Use an empty coroutineExceptionHandler since we want to
        // rethrow all throwables from the connect job.
        val emptyCoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> }
        val connectJob = coroutineScope.launch(
            ioDispatcher + emptyCoroutineExceptionHandler,
        ) {
            if (audioProcessingController is AuthedAudioProcessingController) {
                audioProcessingController.authenticate(url, token)
            }

            // Don't use URL equals.
            if (regionUrlProvider?.serverUrl.toString() != url) {
                regionUrl = null
                regionUrlProvider = null
            }

            val urlObj = URI(url)
            if (urlObj.isGravixCloud()) {
                if (regionUrlProvider == null) {
                    regionUrlProvider = regionUrlProviderFactory.create(urlObj, token)
                } else {
                    regionUrlProvider?.token = token
                }

                // trigger the first fetch without waiting for a response
                // if initial connection fails, this will speed up picking regional url
                // on subsequent runs
                launch {
                    try {
                        regionUrlProvider?.fetchRegionSettings()
                    } catch (e: Exception) {
                        e.rethrowIfCancellationSignal()
                        GxLog.w(e) { "could not fetch region settings" }
                    }
                }
            }

            var nextUrl: String? = regionUrl ?: url
            regionUrl = null

            while (nextUrl != null) {
                val connectUrl = nextUrl
                nextUrl = null
                try {
                    engine.regionUrlProvider = regionUrlProvider
                    engine.join(connectUrl, token, options, roomOptions)
                } catch (e: Exception) {
                    e.rethrowIfCancellationSignal()

                    // GRAVIX (region failover): measured order, last good list; no strategy = upstream ordering
                    val regionStrategy = engine.gravixRegionStrategy()
                    val status = engine.client.gravixLastFailureStatus
                    val refused = status == 401 || status == 403
                    // GRAVIX (refusalEndsConnect): the token was refused; another region would refuse it too
                    if (refused && regionUrlProvider != null && engine.gravixRecovery.refusalEndsConnect) {
                        GxLog.i { "connect to ${GravixStepLogs.host(connectUrl)} refused ($status): the token, not the region; no failover" }
                        throw e
                    }
                    nextUrl = if (regionStrategy != null && regionUrlProvider != null) {
                        regionStrategy.connectFailoverUrl(connectUrl, refused = refused)
                    } else {
                        regionUrlProvider?.getNextBestRegionUrl()
                    }
                    if (nextUrl != null) {
                        GravixJoinLog.step(
                            "regionFailover",
                            gxKey,
                            detail = "from=${GravixJoinLog.host(connectUrl)} to=${GravixJoinLog.host(nextUrl)} after=${GravixJoinLog.errorOf(e)}",
                        )
                        GxLog.d(e) { "Connection to ${GravixStepLogs.host(connectUrl)} failed, retrying with another region: ${GravixStepLogs.host(nextUrl)}" }
                    } else {
                        throw e // rethrow since no more regions to try.
                    }
                }
            }

            ensureActive()
            // GRAVIX: a new connect: its first url is a change
            connectedUrl = null
            connectedRegion = null
            gravixNoteConnectedUrl(RoomEvent.ConnectedUrlChangeReason.CONNECT)
            gravixRegisterNetworkCallback()
            if (options.audio) {
                var cancelPreconnect: (() -> Unit)? = null

                if (audioTrackPublishDefaults.preconnect) {
                    cancelPreconnect = startPreconnectAudioJob(roomScope = coroutineScope)
                }
                // Enable through setMicrophoneEnabled rather than publishing directly,
                // so that this serializes with any concurrent enable calls from the app
                // once the room state flips to CONNECTED.
                if (!localParticipant.setMicrophoneEnabled(true)) {
                    cancelPreconnect?.invoke()
                }
            }
            ensureActive()
            if (options.video) {
                localParticipant.setCameraEnabled(true)
            }

            coroutineScope.launch {
                if (enableMetrics) {
                    collectMetrics(room = this@Room, rtcEngine = engine)
                }
            }
        }

        val outerHandler = coroutineContext.job.invokeOnCompletion { cause ->
            // Cancel connect job if invoking coroutine is cancelled.
            if (cause is CancellationException) {
                connectJob.cancel(cause)
            }
        }

        var error: Throwable? = null
        connectJob.invokeOnCompletion { cause ->
            outerHandler.dispose()
            error = cause
        }
        connectJob.join()

        val gxInMs = (System.nanoTime() - gxStartNs) / 1_000_000
        if (error == null) {
            GravixJoinLog.step(
                "roomConnectOk",
                gxKey,
                detail = "in=${gxInMs}ms host=${GravixJoinLog.host(connectedUrl)} region=${connectedRegion ?: "-"}",
            )
        }
        error?.let {
            if (it !is CancellationException) {
                GravixJoinLog.step("roomConnectFail", gxKey, detail = "reason=${GravixJoinLog.errorOf(it)} in=${gxInMs}ms")
                handleDisconnect(DisconnectReason.JOIN_FAILURE)
            } else {
                GravixJoinLog.step("roomConnectCancelled", gxKey, detail = "in=${gxInMs}ms")
            }
            throw it
        }
    }

    /**
     * Disconnect from the room.
     */
    fun disconnect() {
        if (state == State.DISCONNECTED) {
            return
        }
        engine.client.sendLeave()
        handleDisconnect(DisconnectReason.CLIENT_INITIATED)
    }

    /**
     * Copies all the options to the Room object.
     *
     * Any null values in [options] will not overwrite existing values.
     * To clear existing values on the Room object, explicitly set the value
     * directly instead of using this method.
     */
    fun setRoomOptions(options: RoomOptions) {
        options.audioTrackCaptureDefaults?.let {
            audioTrackCaptureDefaults = it
        }
        options.videoTrackCaptureDefaults?.let {
            videoTrackCaptureDefaults = it
        }

        options.audioTrackPublishDefaults?.let {
            audioTrackPublishDefaults = it
        }
        options.videoTrackPublishDefaults?.let {
            videoTrackPublishDefaults = it
        }
        options.screenShareTrackCaptureDefaults?.let {
            screenShareTrackCaptureDefaults = it
        }
        options.screenShareTrackPublishDefaults?.let {
            screenShareTrackPublishDefaults = it
        }
        options.reconnectPolicy?.let {
            reconnectPolicy = it
        }
        options.gravixRecovery?.let {
            gravixRecovery = it
        }
        adaptiveStream = options.adaptiveStream
        dynacast = options.dynacast
        e2eeOptions = options.e2eeOptions
    }

    /**
     * Release all resources held by this object.
     *
     * Once called, this room object must not be used to connect to a server and a new one
     * must be created.
     */
    fun release() {
        disconnect()
        closeableManager.close()
    }

    /**
     * @suppress
     */
    override fun onJoinResponse(response: GravixcloudRtc.JoinResponse) {
        GxLog.i { "Connected to server, server version: ${response.serverVersion}, client version: ${Version.CLIENT_VERSION}" }
        // GRAVIX: names connectedRegion
        gravixServerRegion = if (response.hasServerInfo()) response.serverInfo.region.takeIf { it.isNotEmpty() } else null

        if (response.room.sid != null) {
            sid = Sid(response.room.sid)
        } else {
            sid = null
        }
        name = response.room.name
        metadata = response.room.metadata

        if (e2eeManager != null && !response.sifTrailer.isEmpty) {
            e2eeManager!!.keyProvider().setSifTrailer(response.sifTrailer.toByteArray())
        }

        if (response.room.activeRecording != isRecording) {
            isRecording = response.room.activeRecording
            eventBus.postEvent(RoomEvent.RecordingStatusChanged(this, isRecording), coroutineScope)
        }

        if (!response.hasParticipant()) {
            throw RoomException.ConnectException("server didn't return a local participant")
        }

        localParticipant.updateFromInfo(response.participant)
        localParticipant.setEnabledPublishCodecs(response.enabledPublishCodecsList)

        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach { info ->
                getOrCreateRemoteParticipant(Participant.Identity(info.identity), info)
            }
        }
    }

    private fun setupLocalParticipantEventHandling() {
        coroutineScope.launch {
            localParticipant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> emitWhenConnected(
                        RoomEvent.TrackPublished(
                            room = this@Room,
                            publication = it.publication,
                            participant = it.participant,
                        ),
                    )

                    is ParticipantEvent.LocalTrackPublicationFailed -> emitWhenConnected(
                        RoomEvent.TrackPublicationFailed(
                            room = this@Room,
                            track = it.track,
                            participant = it.participant,
                            e = it.e,
                        ),
                    )

                    is ParticipantEvent.TrackUnpublished -> emitWhenConnected(
                        RoomEvent.TrackUnpublished(
                            room = this@Room,
                            publication = it.publication,
                            participant = it.participant,
                        ),
                    )

                    is ParticipantEvent.ParticipantPermissionsChanged -> emitWhenConnected(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        ),
                    )

                    is ParticipantEvent.MetadataChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata,
                            ),
                        )
                    }

                    is ParticipantEvent.AttributesChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantAttributesChanged(
                                this@Room,
                                it.participant,
                                it.changedAttributes,
                                it.oldAttributes,
                            ),
                        )
                    }

                    is ParticipantEvent.NameChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantNameChanged(
                                this@Room,
                                it.participant,
                                it.name,
                            ),
                        )
                    }

                    is ParticipantEvent.StateChanged -> {
                        RoomEvent.ParticipantStateChanged(
                            this@Room,
                            it.participant,
                            it.newState,
                            it.oldState,
                        )
                    }

                    else -> {
                        // do nothing
                    }
                }
            }
        }
    }

    private fun handleParticipantDisconnect(identity: Participant.Identity) {
        val newParticipants = mutableRemoteParticipants.toMutableMap()
        val removedParticipant = newParticipants.remove(identity) ?: return
        removedParticipant.trackPublications.values.toList().forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid, true)
        }

        mutableRemoteParticipants = newParticipants
        eventBus.postEvent(RoomEvent.ParticipantDisconnected(this, removedParticipant), coroutineScope)

        localParticipant.handleParticipantDisconnect(identity)
    }

    fun getParticipantBySid(sid: String): Participant? {
        return getParticipantBySid(Participant.Sid(sid))
    }

    fun getParticipantBySid(sid: Participant.Sid): Participant? {
        if (sid == localParticipant.sid) {
            return localParticipant
        } else {
            return remoteParticipants[sidToIdentity[sid]]
        }
    }

    fun getParticipantByIdentity(identity: String): Participant? {
        return getParticipantByIdentity(Participant.Identity(identity))
    }

    fun getParticipantByIdentity(identity: Participant.Identity): Participant? {
        if (identity == localParticipant.identity) {
            return localParticipant
        } else {
            return remoteParticipants[identity]
        }
    }

    @Synchronized
    private fun getOrCreateRemoteParticipant(
        identity: Participant.Identity,
        info: GravixcloudModels.ParticipantInfo,
    ): RemoteParticipant {
        var participant = remoteParticipants[identity]
        if (participant != null) {
            return participant
        }

        participant = remoteParticipantFactory.create(info)
        participant.internalListener = this

        coroutineScope.launch {
            participant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> {
                        if (state == State.CONNECTED) {
                            eventBus.postEvent(
                                RoomEvent.TrackPublished(
                                    room = this@Room,
                                    publication = it.publication,
                                    participant = it.participant,
                                ),
                            )
                        }
                    }

                    is ParticipantEvent.TrackStreamStateChanged -> eventBus.postEvent(
                        RoomEvent.TrackStreamStateChanged(
                            this@Room,
                            it.trackPublication,
                            it.streamState,
                        ),
                    )

                    is ParticipantEvent.TrackSubscriptionPermissionChanged -> eventBus.postEvent(
                        RoomEvent.TrackSubscriptionPermissionChanged(
                            this@Room,
                            it.participant,
                            it.trackPublication,
                            it.subscriptionAllowed,
                        ),
                    )

                    is ParticipantEvent.MetadataChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata,
                            ),
                        )
                    }

                    is ParticipantEvent.AttributesChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantAttributesChanged(
                                this@Room,
                                it.participant,
                                it.changedAttributes,
                                it.oldAttributes,
                            ),
                        )
                    }

                    is ParticipantEvent.NameChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantNameChanged(
                                this@Room,
                                it.participant,
                                it.name,
                            ),
                        )
                    }

                    is ParticipantEvent.ParticipantPermissionsChanged -> eventBus.postEvent(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        ),
                    )

                    is ParticipantEvent.StateChanged -> {
                        eventBus.postEvent(
                            RoomEvent.ParticipantStateChanged(
                                room = this@Room,
                                participant = it.participant,
                                newState = it.newState,
                                oldState = it.oldState,
                            ),
                        )
                    }

                    else -> {
                        // do nothing
                    }
                }
            }
        }

        participant.updateFromInfo(info)

        val newRemoteParticipants = mutableRemoteParticipants.toMutableMap()
        newRemoteParticipants[identity] = participant
        mutableRemoteParticipants = newRemoteParticipants
        sidToIdentity[participant.sid] = identity

        return participant
    }

    private fun handleActiveSpeakersUpdate(speakerInfos: List<GravixcloudModels.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<Participant.Sid>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = Participant.Sid(speakerInfo.sid)
            seenSids.add(speakerSid)

            val participant = getParticipantBySid(speakerSid) ?: return@forEach
            participant.audioLevel = speakerInfo.level
            participant.isSpeaking = true
            speakers.add(participant)
        }

        if (!seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
            localParticipant.isSpeaking = false
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach {
                it.audioLevel = 0.0f
                it.isSpeaking = false
            }

        mutableActiveSpeakers = speakers.toList()
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun handleSpeakersChanged(speakerInfos: List<GravixcloudModels.SpeakerInfo>) {
        val updatedSpeakers = mutableMapOf<Participant.Sid, Participant>()
        activeSpeakers.forEach { participant ->
            updatedSpeakers[participant.sid] = participant
        }

        speakerInfos.forEach { speaker ->
            val speakerSid = Participant.Sid(speaker.sid)
            val participant = getParticipantBySid(speakerSid) ?: return@forEach

            participant.audioLevel = speaker.level
            participant.isSpeaking = speaker.active

            if (speaker.active) {
                updatedSpeakers[speakerSid] = participant
            } else {
                updatedSpeakers.remove(speakerSid)
            }
        }

        val updatedSpeakersList = updatedSpeakers.values.toList()
            .sortedBy { it.audioLevel }

        mutableActiveSpeakers = updatedSpeakersList.toList()
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun reconnect() {
        if (state == State.RECONNECTING) {
            return
        }
        engine.gravixReconnect(RTCEngine.CAUSE_NETWORK) // GRAVIX: the cause is observation (RoomEvent.ReconnectAttempt)
    }

    private fun handleDisconnect(reason: DisconnectReason) {
        if (state == State.DISCONNECTED) {
            return
        }
        runBlocking {
            stateLock.withLock {
                if (state == State.DISCONNECTED) {
                    return@runBlocking
                }
                networkCallbackManager.unregisterCallback()
                hasLostConnectivity = false

                state = State.DISCONNECTED
                // GRAVIX
                connectedUrl = null
                connectedRegion = null
                gravixServerRegion = null
                cleanupRoom()
                engine.close()

                localParticipant.dispose()

                // Ensure all observers see the disconnected before closing scope.
                eventBus.postEvent(RoomEvent.Disconnected(this@Room, null, reason), coroutineScope).join()
                coroutineScope.cancel()
            }
        }
    }

    /**
     * Removes all participants and tracks from the room.
     */
    private fun cleanupRoom() {
        e2eeManager?.dispose()
        e2eeManager = null
        localParticipant.cleanup()
        remoteParticipants.keys.toMutableSet() // copy keys to avoid concurrent modifications.
            .forEach { sid -> handleParticipantDisconnect(sid) }

        sid = null
        metadata = null
        name = null
        isRecording = false
        sidToIdentity.clear()
        incomingDataStreamManager.clearOpenStreams()
    }

    private fun sendSyncState() {
        // Whether we're sending subscribed tracks or tracks to unsubscribe.
        val sendUnsub = connectOptions.autoSubscribe
        val participantTracksList = mutableListOf<GravixcloudModels.ParticipantTracks>()
        for (participant in remoteParticipants.values) {
            val builder = GravixcloudModels.ParticipantTracks.newBuilder()
            builder.participantSid = participant.sid.value
            for (trackPub in participant.trackPublications.values) {
                val remoteTrackPub = (trackPub as? RemoteTrackPublication) ?: continue

                // Use isDesired (subscription intent) instead of isSubscribed (actual state)
                // to avoid race condition during quick reconnect where tracks aren't attached yet.
                if (remoteTrackPub.isDesired != sendUnsub) {
                    builder.addTrackSids(remoteTrackPub.sid)
                }
            }

            if (builder.trackSidsCount > 0) {
                participantTracksList.add(builder.build())
            }
        }

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }
            .flatten()

        val subscription = GravixcloudRtc.UpdateSubscription.newBuilder()
            .setSubscribe(!sendUnsub)
            .addAllParticipantTracks(participantTracksList)
            .addAllTrackSids(trackSids)
            .build()
        val publishedTracks = localParticipant.publishTracksInfo()
        engine.sendSyncState(subscription, publishedTracks)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: GravixcloudRtc.SimulateScenario) {
        engine.client.sendSimulateScenario(scenario)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: SimulateScenario) {
        val builder = GravixcloudRtc.SimulateScenario.newBuilder()
        when (scenario) {
            SimulateScenario.SPEAKER_UPDATE -> builder.speakerUpdate = 5
            SimulateScenario.NODE_FAILURE -> builder.nodeFailure = true
            SimulateScenario.MIGRATION -> builder.migration = true
            SimulateScenario.SERVER_LEAVE -> builder.serverLeave = true
            SimulateScenario.SERVER_LEAVE_FULL_RECONNECT -> builder.leaveRequestFullReconnect = true
        }
        sendSimulateScenario(builder.build())
    }

    /**
     * @suppress
     */
    @AssistedFactory
    interface Factory {
        fun create(context: Context): Room
    }

    // ------------------------------------- General Utility functions -------------------------------------//

    /**
     * Control muting/unmuting all audio output.
     *
     * Note: using this mute will only mute local audio output, and will still receive the data from
     * remote audio tracks. Consider turning off [ConnectOptions.autoSubscribe] and manually unsubscribing
     * if you need more fine-grained control over the data usage.
     **/
    fun setSpeakerMute(muted: Boolean) {
        audioDeviceModule.setSpeakerMute(muted)
    }

    /**
     * Control muting/unmuting the audio input.
     *
     * Note: using this mute will only zero the microphone audio data, and will still send data to
     * remote participants (although they will not hear anything). Consider using [TrackPublication.muted]
     * for more fine-grained control over the data usage.
     */
    fun setMicrophoneMute(muted: Boolean) {
        audioDeviceModule.setMicrophoneMute(muted)
    }

    // ------------------------------------- NetworkCallback -------------------------------------//

    /**
     * GRAVIX: the networks that existed when the callback was registered. registerNetworkCallback answers at once with
     * onAvailable for each of them: that is no change of the device's network (it would have counted every connect as
     * one: resumeAfterNetworkSocketError, RoomEvent.ReconnectAttempt.afterNetworkChange).
     */
    private val gravixNetworksAtRegistration: MutableSet<Network> = Collections.synchronizedSet(HashSet())

    private fun gravixRegisterNetworkCallback() {
        gravixNetworksAtRegistration.clear()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            @Suppress("DEPRECATION") // allNetworks: the same set registerNetworkCallback announces
            cm?.allNetworks?.let { gravixNetworksAtRegistration.addAll(it) }
        } catch (e: Exception) {
            GxLog.d(e) { "could not list the networks" }
        }
        networkCallbackManager.registerCallback()
    }

    private val networkCallbackManager = networkCallbackManagerFactory.invoke(
        object : NetworkCallback() {
            override fun onLost(network: Network) {
                // lost connection, flip to reconnecting
                hasLostConnectivity = true
                gravixNetworksAtRegistration.remove(network)
                engine.gravixNoteNetworkChange() // GRAVIX: evidence for region failover
            }

            override fun onAvailable(network: Network) {
                // GRAVIX: evidence for region failover / a network change; not the registration's own announcement of a
                // network that was already there
                if (!gravixNetworksAtRegistration.remove(network)) engine.gravixNoteNetworkChange()
                // only actually reconnect after connection is re-established
                if (!hasLostConnectivity) {
                    return
                }
                GxLog.i { "network connection available, reconnecting" }
                reconnect()
                hasLostConnectivity = false
            }
        },
    )

    // ----------------------------------- RpcManager ------------------------------------//
    override fun registerRpcMethod(method: String, handler: RpcHandler) {
        localParticipant.registerRpcMethod(method, handler)
    }

    override fun unregisterRpcMethod(method: String) {
        localParticipant.unregisterRpcMethod(method)
    }

    override suspend fun performRpc(destinationIdentity: Participant.Identity, method: String, payload: String, responseTimeout: Duration, maxRoundTripLatency: Duration): String {
        return localParticipant.performRpc(destinationIdentity, method, payload, responseTimeout, maxRoundTripLatency)
    }

    // ----------------------------------- RTCEngine.Listener ------------------------------------//

    /**
     * @suppress
     */
    override fun onEngineConnected() {
        state = State.CONNECTED
        eventBus.postEvent(RoomEvent.Connected(this), coroutineScope)
        gravixEmitConnectedUrl() // GRAVIX
    }

    /**
     * @suppress
     */
    override fun onEngineReconnected() {
        state = State.CONNECTED
        eventBus.postEvent(RoomEvent.Reconnected(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onEngineReconnecting() {
        state = State.RECONNECTING
        eventBus.postEvent(RoomEvent.Reconnecting(this), coroutineScope)
    }

    /**
     * GRAVIX: a reconnect started with a resume ([RoomEvent.Resuming]); [state] stays CONNECTED as upstream.
     * @suppress
     */
    override fun onEngineResuming() {
        eventBus.postEvent(RoomEvent.Resuming(this), coroutineScope)
    }

    /**
     * GRAVIX: the resume succeeded ([RoomEvent.Resumed]).
     * @suppress
     */
    override fun onEngineResumed() {
        eventBus.postEvent(RoomEvent.Resumed(this), coroutineScope)
    }

    /**
     * GRAVIX: a reconnect attempt starts ([RoomEvent.ReconnectAttempt]).
     * @suppress
     */
    override fun onEngineReconnectAttempt(full: Boolean, cause: String, attempt: Int, afterNetworkChange: Boolean) {
        val mode = if (full) RoomEvent.ReconnectMode.FULL else RoomEvent.ReconnectMode.RESUME
        eventBus.postEvent(RoomEvent.ReconnectAttempt(this, mode, cause, attempt, afterNetworkChange), coroutineScope)
    }

    /**
     * GRAVIX: the session's ICE transport changed ([RoomEvent.IceTransportChanged]).
     * @suppress
     */
    override fun onEngineIceTransportChanged(
        transport: GravixIceTransport?,
        previous: GravixIceTransport?,
        publisher: GravixIceTransport?,
        subscriber: GravixIceTransport?,
    ) {
        eventBus.postEvent(RoomEvent.IceTransportChanged(this, transport, previous, publisher, subscriber), coroutineScope)
    }

    /**
     * GRAVIX: a slow ICE/TCP session ([RoomEvent.SlowTcpTransport]).
     * @suppress
     */
    override fun onEngineSlowTcpTransport(rttMs: Long, overMs: Long) {
        eventBus.postEvent(RoomEvent.SlowTcpTransport(this, rttMs, overMs), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onAddTrack(receiver: RtpReceiver, track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.isEmpty()) {
            GxLog.i { "add track with empty streams?" }
            return
        }

        var (participantSid, streamId) = unpackStreamId(streams.first().id)
        var trackSid = track.id()

        if (streamId != null && streamId.startsWith("TR")) {
            trackSid = streamId
        }
        val participant = getParticipantBySid(participantSid) as? RemoteParticipant

        if (participant == null) {
            GxLog.e { "Tried to add a track for a participant that is not present. sid: $participantSid" }
            return
        }

        val statsGetter = engine.createStatsGetter(receiver)
        participant.addSubscribedMediaTrack(
            track,
            trackSid!!,
            autoManageVideo = adaptiveStream,
            statsGetter = statsGetter,
            receiver = receiver,
        )
    }

    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<GravixcloudModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = Participant.Sid(info.sid)
            // Gravix server doesn't send identity info prior to version 1.5.2 in disconnect updates
            // so we try to map an empty identity to an already known sID manually

            @Suppress("NAME_SHADOWING") var info = info
            if (info.identity.isNullOrBlank()) {
                info = with(info.toBuilder()) {
                    identity = sidToIdentity[participantSid]?.value ?: ""
                    build()
                }
            }

            val participantIdentity = Participant.Identity(info.identity)

            if (localParticipant.identity == participantIdentity) {
                localParticipant.updateFromInfo(info)
                continue
            }

            val isNewParticipant = !remoteParticipants.contains(participantIdentity)

            if (info.state == GravixcloudModels.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantIdentity)
            } else {
                val participant = getOrCreateRemoteParticipant(participantIdentity, info)
                if (isNewParticipant) {
                    eventBus.postEvent(RoomEvent.ParticipantConnected(this, participant), coroutineScope)
                } else {
                    participant.updateFromInfo(info)
                    sidToIdentity[participantSid] = participantIdentity
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onActiveSpeakersUpdate(speakers: List<GravixcloudModels.SpeakerInfo>) {
        handleActiveSpeakersUpdate(speakers)
    }

    /**
     * @suppress
     */
    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        localParticipant.onRemoteMuteChanged(trackSid, muted)
    }

    /**
     * @suppress
     */
    override fun onRoomUpdate(update: GravixcloudModels.Room) {
        if (update.sid != null) {
            sid = Sid(update.sid)
        }
        val oldMetadata = metadata
        metadata = update.metadata

        val oldIsRecording = isRecording
        isRecording = update.activeRecording

        if (oldMetadata != metadata) {
            eventBus.postEvent(RoomEvent.RoomMetadataChanged(this, metadata, oldMetadata), coroutineScope)
        }

        if (oldIsRecording != isRecording) {
            eventBus.postEvent(RoomEvent.RecordingStatusChanged(this, isRecording), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onConnectionQuality(updates: List<GravixcloudRtc.ConnectionQualityInfo>) {
        updates.forEach { info ->
            val quality = ConnectionQuality.fromProto(info.quality)
            val participant = getParticipantBySid(info.participantSid) ?: return@forEach
            participant.connectionQuality = quality
            eventBus.postEvent(RoomEvent.ConnectionQualityChanged(this, participant, quality), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onSpeakersChanged(speakers: List<GravixcloudModels.SpeakerInfo>) {
        handleSpeakersChanged(speakers)
    }

    /**
     * @suppress
     */
    override fun onUserPacket(packet: GravixcloudModels.UserPacket, kind: GravixcloudModels.DataPacket.Kind, encryptionType: GravixcloudModels.Encryption.Type) {
        val participant = getParticipantBySid(packet.participantSid) as? RemoteParticipant
        val data = packet.payload.toByteArray()
        val topic = if (packet.hasTopic()) {
            packet.topic
        } else {
            null
        }

        val event = RoomEvent.DataReceived(this, data, participant, topic, encryptionType)
        eventBus.postEvent(event, coroutineScope)
        participant?.onDataReceived(event)
    }

    /**
     * @suppress
     */
    override fun onDataStreamPacket(dp: GravixcloudModels.DataPacket, encryptionType: GravixcloudModels.Encryption.Type) {
        when (dp.valueCase) {
            GravixcloudModels.DataPacket.ValueCase.STREAM_HEADER -> {
                incomingDataStreamManager.handleStreamHeader(dp.streamHeader, Participant.Identity(dp.participantIdentity), encryptionType)
            }

            GravixcloudModels.DataPacket.ValueCase.STREAM_CHUNK -> {
                incomingDataStreamManager.handleDataChunk(dp.streamChunk, encryptionType)
            }

            GravixcloudModels.DataPacket.ValueCase.STREAM_TRAILER -> {
                incomingDataStreamManager.handleStreamTrailer(dp.streamTrailer, encryptionType)
            }

            // Ignore other cases.
            else -> {}
        }
    }

    /**
     * @suppress
     */
    override fun onTranscriptionReceived(transcription: GravixcloudModels.Transcription) {
        if (transcription.segmentsList.isEmpty()) {
            GxLog.d { "Received transcription segments are empty." }
            return
        }

        val participant = getParticipantByIdentity(transcription.transcribedParticipantIdentity)
        val publication = participant?.trackPublications?.get(transcription.trackId)
        val segments = transcription.segmentsList
            .map { it.toSDKType(firstReceivedTime = transcriptionReceivedTimes[it.id] ?: Date().time) }

        // Update receive times
        for (segment in segments) {
            if (segment.final) {
                transcriptionReceivedTimes.remove(segment.id)
            } else {
                transcriptionReceivedTimes[segment.id] = segment.firstReceivedTime
            }
        }

        val event = RoomEvent.TranscriptionReceived(
            room = this,
            transcriptionSegments = segments,
            participant = participant,
            publication = publication,
        )
        eventBus.tryPostEvent(event)
        participant?.onTranscriptionReceived(event)
        publication?.onTranscriptionReceived(event)
    }

    override fun onRpcPacketReceived(dp: GravixcloudModels.DataPacket) {
        localParticipant.handleDataPacket(dp)
    }

    /**
     * @suppress
     */
    override fun onStreamStateUpdate(streamStates: List<GravixcloudRtc.StreamStateInfo>) {
        for (streamState in streamStates) {
            val participant = getParticipantBySid(streamState.participantSid) ?: continue
            val track = participant.trackPublications[streamState.trackSid] ?: continue

            track.track?.streamState = Track.StreamState.fromProto(streamState.state)
        }
    }

    /**
     * @suppress
     */
    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: GravixcloudRtc.SubscribedQualityUpdate) {
        localParticipant.handleSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    /**
     * @suppress
     */
    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: GravixcloudRtc.SubscriptionPermissionUpdate) {
        val participant = getParticipantBySid(subscriptionPermissionUpdate.participantSid) as? RemoteParticipant ?: return
        participant.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    /**
     * @suppress
     */
    override fun onSubscriptionError(subscriptionResponse: GravixcloudRtc.SubscriptionResponse) {
        val participant = remoteParticipants.values
            .firstOrNull { it.trackPublications.containsKey(subscriptionResponse.trackSid) } as? RemoteParticipant
            ?: return
        participant.onSubscriptionError(subscriptionResponse)
    }

    /**
     * @suppress
     */
    override fun onEngineDisconnected(reason: DisconnectReason) {
        GxLog.v { "engine did disconnect: $reason" }
        handleDisconnect(reason)
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Throwable) {
        // scope will likely be closed already here, so force it out of scope.
        eventBus.tryPostEvent(RoomEvent.FailedToConnect(this, error))
    }

    /**
     * @suppress
     */
    override fun onSignalConnected(isResume: Boolean) {
        if (isResume) {
            // during resume reconnection, need to send sync state upon signal connection.
            sendSyncState()
        }
    }

    /**
     * @suppress
     */
    override fun onFullReconnecting() {
        localParticipant.prepareForFullReconnect()
        remoteParticipants.keys.toMutableSet() // copy keys to avoid concurrent modifications.
            .forEach { identity -> handleParticipantDisconnect(identity) }
    }

    /**
     * @suppress
     */
    override suspend fun onPostReconnect(isFullReconnect: Boolean) {
        // GRAVIX: a rejoin may have moved the session to another region
        gravixNoteConnectedUrl(RoomEvent.ConnectedUrlChangeReason.REJOIN)
        if (isFullReconnect) {
            localParticipant.republishTracks()
        } else {
            val remoteParticipants = remoteParticipants.values.toList()
            for (participant in remoteParticipants) {
                val pubs = participant.trackPublications.values.toList()
                for (pub in pubs) {
                    val remotePub = pub as? RemoteTrackPublication ?: continue
                    if (remotePub.subscribed) {
                        remotePub.sendUpdateTrackSettings.invoke()
                    }
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onLocalTrackSubscribed(response: GravixcloudRtc.TrackSubscribed) {
        val publication = localParticipant.trackPublications[response.trackSid] as? LocalTrackPublication

        if (publication == null) {
            GxLog.w { "Could not find local track publication for subscribed event " }
            return
        }

        coroutineScope.launch {
            emitWhenConnected(RoomEvent.LocalTrackSubscribed(this@Room, publication, this@Room.localParticipant))
        }
        this.localParticipant.onLocalTrackSubscribed(publication)
    }

    /**
     * @suppress
     */
    override fun onLocalTrackUnpublished(trackUnpublished: GravixcloudRtc.TrackUnpublishedResponse) {
        localParticipant.handleLocalTrackUnpublished(trackUnpublished)
    }

    // ------------------------------- ParticipantListener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
    }

    /** @suppress */
    override fun onTrackMuted(publication: TrackPublication, participant: Participant) {
        eventBus.postEvent(RoomEvent.TrackMuted(this, publication, participant), coroutineScope)
    }

    /** @suppress */
    override fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {
        eventBus.postEvent(RoomEvent.TrackUnmuted(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        if (e2eeManager != null) {
            e2eeManager!!.addPublishedTrack(publication.track!!, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackPublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        e2eeManager?.removePublishedTrack(publication.track!!, publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {
        if (e2eeManager != null) {
            e2eeManager!!.addSubscribedTrack(track, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackSubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant,
    ) {
        eventBus.postEvent(RoomEvent.TrackSubscriptionFailed(this, sid, exception, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant,
    ) {
        e2eeManager?.removeSubscribedTrack(track, publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackUnsubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * Initialize a [SurfaceViewRenderer] for rendering a video from this room.
     */
    // TODO(@dl): can this be moved out of Room/SDK?
    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false)
    }

    /**
     * Initialize a [TextureViewRenderer] for rendering a video from this room.
     */
    // TODO(@dl): can this be moved out of Room/SDK?
    fun initVideoRenderer(viewRenderer: TextureViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false)
    }

    private suspend fun emitWhenConnected(event: RoomEvent) {
        if (state == State.CONNECTED) {
            eventBus.postEvent(event)
        }
    }

    /**
     * Get stats for the publisher peer connection.
     *
     * @see getSubscriberRTCStats
     * @see getFilteredStats
     */
    fun getPublisherRTCStats(callback: RTCStatsCollectorCallback) = engine.getPublisherRTCStats(callback)

    /**
     * Get stats for the subscriber peer connection.
     *
     * @see getPublisherRTCStats
     * @see getFilteredStats
     */
    fun getSubscriberRTCStats(callback: RTCStatsCollectorCallback) = engine.getSubscriberRTCStats(callback)

    // Debug options

    /**
     * @suppress
     */
    @VisibleForTesting
    fun setReconnectionType(reconnectType: ReconnectType) {
        engine.reconnectType = reconnectType
    }
}

sealed class RoomException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class ConnectException(message: String? = null, cause: Throwable? = null) :
        RoomException(message, cause)
}

internal fun unpackStreamId(packed: String): Pair<String, String?> {
    val parts = packed.split('|')
    if (parts.size != 2) {
        return Pair(packed, null)
    }
    return Pair(parts[0], parts[1])
}
