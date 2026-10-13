/*
 * Copyright 2026 Gravity Compile
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

package com.gravixcloud.rtc.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.GravixRtcOverrides
import com.gravixcloud.rtc.RoomOptions
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer
import com.gravixcloud.rtc.renderer.TextureViewRenderer
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.participant.RemoteParticipant
import com.gravixcloud.rtc.room.track.LocalVideoTrack
import com.gravixcloud.rtc.room.track.RemoteTrackPublication
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.room.track.VideoTrack
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.flow
import com.gravixcloud.rtc.util.launchFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * GRAVIX: the Java-first API (Agora `RtcEngine` style): futures instead of `suspend`, a listener instead of event
 * Flows, plain String uids instead of value classes. Kotlin apps can use it too, or the full [Room] ([room]).
 *
 * ```java
 * GravixRoomService service = new GravixRoomService(context);
 * service.addListener(new GravixRoomServiceListener() {
 *     @Override public void onUserJoined(String uid) { ... }
 *     @Override public void onRemoteVideoTrack(String uid, VideoTrack track) { track.addRenderer(renderer); }
 *     @Override public void onReconnecting(boolean isResume) { showBanner(); }
 *     @Override public void onReconnected(boolean isResume) { hideBanner(); }
 * });
 * service.connect(url, token)
 *     .thenCompose(v -> service.setMicrophoneEnabled(true))
 *     .exceptionally(error -> { showError(error); return null; });
 * ...
 * service.release(); // in onDestroy
 * ```
 *
 * Threading: methods may be called from any thread and never block. Listener callbacks and future completions
 * (`thenAccept`, `exceptionally`...) run on the callback executor: the main thread by default. Never wait for a
 * future with `get()`/`join()` on the main thread: it completes there, so that would deadlock.
 *
 * Not in 0.1.0 (Flutter-service features planned for a later parity phase): data saver and host camera guard,
 * prewarm/standby, reports and analytics upload, uid mapping, audio routing helpers.
 */
class GravixRoomService internal constructor(
    /** The underlying [Room]: the full Kotlin API (events Flow, participants, tracks, options). */
    val room: Room,
    private val callbackExecutor: Executor,
    dispatcher: CoroutineDispatcher,
    private val ownsRoom: Boolean,
) {
    /**
     * A service with its own [Room] ([GravixRtc.create]). Callbacks and future completions run on the main thread.
     * Call [release] when done (e.g. in `onDestroy`).
     */
    @JvmOverloads
    constructor(
        context: Context,
        options: RoomOptions = RoomOptions(),
        overrides: GravixRtcOverrides = GravixRtcOverrides(),
    ) : this(
        room = GravixRtc.create(context.applicationContext, options, overrides),
        callbackExecutor = mainThreadExecutor(),
        dispatcher = Dispatchers.Main.immediate,
        ownsRoom = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val listeners = CopyOnWriteArrayList<GravixRoomServiceListener>()

    // Identity bookkeeping, touched only by the event collector (one coroutine): the uids the app was told about.
    // During a full rejoin upstream re-announces everyone (ParticipantDisconnected for all, then the JoinResponse);
    // the app is told only the real changes when the rejoin completes (the Flutter service does the same since 0.4.6).
    private val announced = LinkedHashSet<String>()
    private var rejoining = false

    @Volatile
    private var released = false

    init {
        scope.launch { room.events.collect { onRoomEvent(it) } }
        scope.launch {
            var first = true
            room::state.flow.collect { state ->
                // the StateFlow replays the current state to a new collector: not a change
                if (first) {
                    first = false
                } else {
                    dispatch { it.onConnectionStateChanged(state) }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- listeners

    /** Adds [listener] (no duplicates). Its callbacks run on the callback executor. */
    fun addListener(listener: GravixRoomServiceListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: GravixRoomServiceListener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------------------------------- connection

    /**
     * Joins the room at [url] (e.g. `wss://<region>.gravixcloud.com`) with [token]. The future completes when the
     * session is up, or exceptionally with the reason (a refused token, no server reachable...). Cancelling it
     * cancels the join.
     */
    @JvmOverloads
    fun connect(url: String, token: String, options: ConnectOptions = ConnectOptions()): CompletableFuture<Void?> =
        launchFuture(scope, callbackExecutor) {
            room.connect(url, token, options)
            null
        }

    /** Leaves the room. Completes once [Room.state] is DISCONNECTED. The service can connect again afterwards. */
    fun disconnect(): CompletableFuture<Void?> = launchFuture(scope, callbackExecutor) {
        room.disconnect()
        room::state.flow.first { it == Room.State.DISCONNECTED }
        null
    }

    /**
     * Disconnects, stops the callbacks and (for a service created with a [Context]) releases the [Room]. The service
     * cannot be used afterwards. Futures still running are cancelled.
     */
    fun release() {
        if (released) return
        released = true
        listeners.clear()
        scope.cancel()
        if (ownsRoom) room.release() else room.disconnect()
    }

    /** The connection state ([Room.state]); a resume keeps CONNECTED (see [GravixRoomServiceListener.onReconnecting]). */
    val connectionState: Room.State
        get() = room.state

    /** The server url the session is on now (changes on a failover), or null when not connected. */
    val connectedUrl: String?
        get() = room.connectedUrl

    /** The Gravix region the session is on, when known. */
    val connectedRegion: String?
        get() = room.connectedRegion

    /** The local participant's uid (identity), once connected. */
    val localUid: String?
        get() = room.localParticipant.identity?.value

    /** The uids of the remote participants in the room now. */
    val remoteUids: List<String>
        get() = room.remoteParticipants.keys.map { it.value }

    // ------------------------------------------------------------------------------------------- local media

    /**
     * Publishes (or unmutes) the microphone, or mutes it. Needs the RECORD_AUDIO permission. Completes with true when
     * the microphone is in the requested state.
     */
    fun setMicrophoneEnabled(enabled: Boolean): CompletableFuture<Boolean> = launchFuture(scope, callbackExecutor) {
        room.localParticipant.setMicrophoneEnabled(enabled)
    }

    /** Agora name for `setMicrophoneEnabled(!mute)`. */
    fun muteLocalAudio(mute: Boolean): CompletableFuture<Boolean> = setMicrophoneEnabled(!mute)

    /**
     * Publishes (or unmutes) the camera, or mutes it. Needs the CAMERA permission. Completes with true when the camera
     * is in the requested state.
     */
    fun setCameraEnabled(enabled: Boolean): CompletableFuture<Boolean> = launchFuture(scope, callbackExecutor) {
        room.localParticipant.setCameraEnabled(enabled)
    }

    /** Agora name for `setCameraEnabled(!mute)`. */
    fun muteLocalVideo(mute: Boolean): CompletableFuture<Boolean> = setCameraEnabled(!mute)

    /** Switches to the next camera (front/back). Completes false when no camera track is published. */
    fun switchCamera(): CompletableFuture<Boolean> = launchFuture(scope, callbackExecutor) {
        val track = localVideoTrack() ?: return@launchFuture false
        track.switchCamera()
        true
    }

    /** The published camera track (render it locally with `addRenderer`), or null. */
    fun localVideoTrack(): LocalVideoTrack? =
        room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack

    /** Prepares [renderer] for this room's video (once per renderer, before `track.addRenderer(renderer)`). */
    fun initVideoRenderer(renderer: SurfaceViewRenderer) = room.initVideoRenderer(renderer)

    /** Prepares [renderer] for this room's video (once per renderer, before `track.addRenderer(renderer)`). */
    fun initVideoRenderer(renderer: TextureViewRenderer) = room.initVideoRenderer(renderer)

    // ------------------------------------------------------------------------------------------- remote media

    /** The subscribed video track of [uid] (its camera, else any video), or null. */
    fun remoteVideoTrack(uid: String): VideoTrack? {
        val p = remote(uid) ?: return null
        val pubs = p.trackPublications.values.filter { it.kind == Track.Kind.VIDEO }
        val pub = pubs.firstOrNull { it.source == Track.Source.CAMERA } ?: pubs.firstOrNull()
        return pub?.track as? VideoTrack
    }

    /**
     * Stops (mute = true) or resumes receiving [uid]'s audio. The server stops forwarding it, so it also saves
     * bandwidth. Completes false when [uid] is not in the room.
     */
    fun muteRemoteAudio(uid: String, mute: Boolean): CompletableFuture<Boolean> = launchFuture(scope, callbackExecutor) {
        val p = remote(uid) ?: return@launchFuture false
        setAudioEnabled(p, !mute)
        true
    }

    /** [muteRemoteAudio] for everyone in the room now. */
    fun muteAllRemoteAudio(mute: Boolean): CompletableFuture<Void?> = launchFuture(scope, callbackExecutor) {
        room.remoteParticipants.values.forEach { setAudioEnabled(it, !mute) }
        null
    }

    private fun setAudioEnabled(p: RemoteParticipant, enabled: Boolean) {
        p.trackPublications.values
            .filter { it.kind == Track.Kind.AUDIO }
            .forEach { (it as? RemoteTrackPublication)?.setEnabled(enabled) }
    }

    private fun remote(uid: String): RemoteParticipant? =
        room.remoteParticipants.entries.firstOrNull { it.key.value == uid }?.value

    // ------------------------------------------------------------------------------------------- events

    private fun dispatch(call: (GravixRoomServiceListener) -> Unit) {
        if (released || listeners.isEmpty()) return
        callbackExecutor.execute {
            for (l in listeners) {
                try {
                    call(l)
                } catch (e: Exception) {
                    // an app callback that throws must not stop the others (nor the event collector)
                    GxLog.e(e) { "GravixRoomServiceListener callback threw" }
                }
            }
        }
    }

    private fun uidOf(p: com.gravixcloud.rtc.room.participant.Participant?): String? = p?.identity?.value

    private fun announceJoined(uid: String) {
        if (announced.add(uid)) dispatch { it.onUserJoined(uid) }
    }

    private fun announceLeft(uid: String) {
        if (announced.remove(uid)) dispatch { it.onUserOffline(uid) }
    }

    /** After a connect or a full rejoin: tell the app the difference between what it knows and who is here. */
    private fun reconcileParticipants() {
        val here = room.remoteParticipants.keys.map { it.value }.toSet()
        announced.filter { it !in here }.forEach { announceLeft(it) }
        here.filter { it !in announced }.forEach { announceJoined(it) }
    }

    internal fun onRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> {
                rejoining = false
                reconcileParticipants() // participants already in the room get no ParticipantConnected upstream
                dispatch { it.onConnected() }
            }
            is RoomEvent.Resuming -> dispatch { it.onReconnecting(true) }
            is RoomEvent.Resumed -> dispatch { it.onReconnected(true) }
            is RoomEvent.Reconnecting -> {
                rejoining = true
                dispatch { it.onReconnecting(false) }
            }
            is RoomEvent.Reconnected -> {
                rejoining = false
                reconcileParticipants()
                dispatch { it.onReconnected(false) }
            }
            is RoomEvent.Disconnected -> {
                rejoining = false
                announced.clear() // the session is over: one onDisconnected, not an onUserOffline per participant
                val reason = event.reason
                val error = event.error
                dispatch { it.onDisconnected(reason, error) }
            }
            is RoomEvent.ParticipantConnected -> if (!rejoining) uidOf(event.participant)?.let { announceJoined(it) }
            // a session that is ending (our leave, a server removal, recovery giving up) tears every participant down
            // before RoomEvent.Disconnected: that is one onDisconnected for the app, not an onUserOffline per participant
            is RoomEvent.ParticipantDisconnected ->
                if (!rejoining && room.state != Room.State.DISCONNECTED) uidOf(event.participant)?.let { announceLeft(it) }
            is RoomEvent.TrackMuted -> onMute(event.participant, event.publication.kind, true)
            is RoomEvent.TrackUnmuted -> onMute(event.participant, event.publication.kind, false)
            is RoomEvent.TrackSubscribed -> {
                val uid = uidOf(event.participant)
                val track = event.track
                if (uid != null && track is VideoTrack) dispatch { it.onRemoteVideoTrack(uid, track) }
            }
            is RoomEvent.TrackUnsubscribed -> {
                val uid = uidOf(event.participant)
                if (uid != null && event.track is VideoTrack) dispatch { it.onRemoteVideoTrackRemoved(uid) }
            }
            is RoomEvent.ActiveSpeakersChanged -> {
                val uids = event.speakers.mapNotNull { uidOf(it) }
                dispatch { it.onActiveSpeakers(uids) }
            }
            is RoomEvent.ConnectedUrlChanged -> {
                val url = event.url
                val region = event.region
                dispatch { it.onConnectedUrlChanged(url, region) }
            }
            is RoomEvent.DataReceived -> {
                val uid = uidOf(event.participant)
                val topic = event.topic
                val data = event.data
                dispatch { it.onDataReceived(uid, topic, data) }
            }
            else -> Unit
        }
    }

    private fun onMute(participant: com.gravixcloud.rtc.room.participant.Participant, kind: Track.Kind, muted: Boolean) {
        if (participant !is RemoteParticipant) return
        val uid = uidOf(participant) ?: return
        when (kind) {
            Track.Kind.AUDIO -> dispatch { it.onUserMuteAudio(uid, muted) }
            Track.Kind.VIDEO -> dispatch { it.onUserMuteVideo(uid, muted) }
            else -> Unit
        }
    }

    private companion object {
        fun mainThreadExecutor(): Executor {
            val main = Handler(Looper.getMainLooper())
            return Executor { r -> if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r) }
        }
    }
}
