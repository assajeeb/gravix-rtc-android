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

import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.room.GravixIceTransport
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.track.VideoTrack

/**
 * GRAVIX: callbacks of a [GravixRoomService] (the Agora `IRtcEngineEventHandler` pattern): every method is a no-op;
 * override only what the app needs. Java: `new GravixRoomServiceListener() { @Override public void onUserJoined(String
 * uid) { ... } }`; Kotlin: `object : GravixRoomServiceListener() { ... }`.
 *
 * Callbacks run on the service's callback executor: the main thread unless the service was created with another
 * executor. A `uid` is the participant's identity (the token's `identity` claim).
 *
 * An abstract class rather than an interface with default methods: Kotlin 1.9 compiles interface defaults for Java only
 * with `-Xjvm-default`, a module-wide flag that would change the bytecode of every upstream interface.
 */
abstract class GravixRoomServiceListener {

    /** [Room.state] changed (CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED). A resume keeps CONNECTED. */
    open fun onConnectionStateChanged(state: Room.State) {}

    /** The first connect completed (the media connection is up). */
    open fun onConnected() {}

    /**
     * The connection is being recovered. [isResume] = true: the session is resumed (participants and tracks kept);
     * false: a full rejoin (remote tracks are subscribed again, local tracks republished). A resume that fails
     * becomes a full rejoin: `onReconnecting(true)` then `onReconnecting(false)`. Show "reconnecting" here.
     */
    open fun onReconnecting(isResume: Boolean) {}

    /** The recovery announced by [onReconnecting] succeeded ([isResume] as there). */
    open fun onReconnected(isResume: Boolean) {}

    /**
     * A reconnect attempt starts (one per attempt, before its dial; [RoomEvent.ReconnectAttempt][com.gravixcloud.rtc.events.RoomEvent.ReconnectAttempt]).
     * [isResume]: the attempt resumes the session (else a full rejoin). [cause]: why (`peerConnectionFailed`, `signal`,
     * `networkAvailable`, `reconnectRetry` for every attempt after the first, ...). [attempt]: 1 for the first of an
     * outage. [afterNetworkChange]: the device's network changed shortly before or during the outage. For field logs.
     */
    open fun onReconnectAttempt(isResume: Boolean, cause: String, attempt: Int, afterNetworkChange: Boolean) {}

    /**
     * How the session's media travels changed ([GravixRoomService.selectedTransport]): [transport] is TCP when either
     * media connection runs over ICE/TCP, RELAY when either is relayed, else UDP; [previous] null on the first reading.
     */
    open fun onIceTransportChanged(transport: GravixIceTransport?, previous: GravixIceTransport?) {}

    /**
     * The session runs over ICE/TCP with a round trip of [rttMs] for [overMs] (above 1 s for 10 s by default): media
     * is likely frozen or very low quality. Once per episode.
     */
    open fun onSlowTcpTransport(rttMs: Long, overMs: Long) {}

    /** The session ended: the app disconnected, the server removed the participant, or recovery gave up. */
    open fun onDisconnected(reason: DisconnectReason, error: Throwable?) {}

    /**
     * A remote participant is in the room: joined after us, or already there when we joined. Not repeated by a full
     * rejoin for someone who stayed (only real changes are reported, as in the Flutter SDK's service).
     */
    open fun onUserJoined(uid: String) {}

    /** A remote participant left (or left while we were rejoining). */
    open fun onUserOffline(uid: String) {}

    /** A remote participant muted or unmuted their microphone. */
    open fun onUserMuteAudio(uid: String, muted: Boolean) {}

    /** A remote participant muted or unmuted their camera. */
    open fun onUserMuteVideo(uid: String, muted: Boolean) {}

    /**
     * A remote video track is ready to render: `track.addRenderer(renderer)` after
     * [GravixRoomService.initVideoRenderer]. After a full rejoin a NEW track object arrives here again.
     */
    open fun onRemoteVideoTrack(uid: String, track: VideoTrack) {}

    /** The remote video track of [uid] went away (unpublished, unsubscribed or the participant left). */
    open fun onRemoteVideoTrackRemoved(uid: String) {}

    /** The participants speaking now, loudest first (may include the local participant). */
    open fun onActiveSpeakers(uids: List<String>) {}

    /**
     * The session is on another server url: first connect, or a failover/rejoin to another region. [region] is the
     * Gravix region name when known.
     */
    open fun onConnectedUrlChanged(url: String, region: String?) {}

    /** Data from another participant ([uid] null when sent by the server) on [topic]. */
    open fun onDataReceived(uid: String?, topic: String?, data: ByteArray) {}
}
