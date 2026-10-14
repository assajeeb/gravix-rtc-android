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

package com.gravixcloud.rtc.events

import com.gravixcloud.rtc.annotations.Beta
import com.gravixcloud.rtc.e2ee.E2EEState
import com.gravixcloud.rtc.room.GravixIceTransport
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.participant.ConnectionQuality
import com.gravixcloud.rtc.room.participant.LocalParticipant
import com.gravixcloud.rtc.room.participant.Participant
import com.gravixcloud.rtc.room.participant.ParticipantPermission
import com.gravixcloud.rtc.room.participant.RemoteParticipant
import com.gravixcloud.rtc.room.track.LocalTrackPublication
import com.gravixcloud.rtc.room.track.RemoteTrackPublication
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.room.track.TrackException
import com.gravixcloud.rtc.room.track.TrackPublication
import com.gravixcloud.rtc.room.types.TranscriptionSegment
import gravixcloud.GravixcloudModels

sealed class RoomEvent(val room: Room) : Event() {

    /**
     * Connected to Room
     */
    class Connected(room: Room) : RoomEvent(room)

    /**
     * A network change has been detected and Gravix attempts to reconnect to the room
     * When reconnect attempts succeed, the room state will be kept, including tracks that are subscribed/published
     */
    class Reconnecting(room: Room) : RoomEvent(room)

    /**
     * The reconnect attempt had been successful
     */
    class Reconnected(room: Room) : RoomEvent(room)

    /**
     * GRAVIX: the connection dropped and the SDK is RESUMING the session: the signal connection is re-established
     * and the media connections are ICE-restarted; participants, subscriptions and published tracks are kept and
     * [Room.state] stays [Room.State.CONNECTED]. Emitted as soon as such a reconnect starts (also while it waits
     * for the device's network to come back). Show "reconnecting" here.
     *
     * What follows: [Resumed] (the session is back), or [Reconnecting] then [Reconnected] (the resume failed and
     * the SDK rejoined in full: tracks are republished), or [Disconnected] (recovery gave up).
     *
     * Upstream emits nothing for a resume ([Reconnecting]/[Reconnected] are full rejoins only), so an app had no
     * event during the most common recovery. Flutter SDK equivalent: `RoomResumingEvent`.
     */
    class Resuming(room: Room) : RoomEvent(room)

    /**
     * GRAVIX: the resume announced by [Resuming] succeeded; the session continues where it was (nothing was
     * republished). Flutter SDK equivalent: the `RoomReconnectedEvent` that follows a `RoomResumingEvent`.
     */
    class Resumed(room: Room) : RoomEvent(room)

    /**
     * GRAVIX: a reconnect attempt starts (one per attempt, before its dial). Until now only a full rejoin was visible
     * ([Reconnecting]); [Resuming] says a recovery started, this says what each attempt does. The Flutter SDK's
     * `RoomReconnectAttemptEvent` (0.4.18).
     *
     * @property mode [ReconnectMode.RESUME] (the same session: a new signal connection and an ICE restart) or
     *   [ReconnectMode.FULL] (a new session: the others may see this participant leave and join again).
     * @property cause why the session is being recovered: `peerConnectionFailed`, `peerConnectionClosed`,
     *   `peerConnectionDisconnected` (GravixRecoveryOptions.resumeOnPeerDisconnected), `signal` (the signal connection
     *   closed or failed), `networkAvailable` (the device's network came back), `requested` (`reconnect()` called), and
     *   `reconnectRetry` for every attempt after the first.
     * @property attempt 1 for the first attempt of this outage.
     * @property afterNetworkChange the device's network changed (lost or available) within
     *   GravixRecoveryOptions.networkChangeWindowMs before the reconnect began, or since.
     *
     * Unlike the Flutter event there is no `reconnectReason`: this SDK sends none (the server reads RR_UNKNOWN).
     */
    class ReconnectAttempt(
        room: Room,
        val mode: ReconnectMode,
        val cause: String,
        val attempt: Int,
        val afterNetworkChange: Boolean,
    ) : RoomEvent(room) {
        override fun toString(): String =
            "ReconnectAttempt($mode, cause: $cause, attempt: $attempt, afterNetworkChange: $afterNetworkChange)"
    }

    /** GRAVIX: how a reconnect attempt recovers the session ([ReconnectAttempt.mode]). */
    enum class ReconnectMode {
        /** The same session: participants, subscriptions and published tracks stay on the server. */
        RESUME,

        /** A new session: a new join and new peer connections ([Reconnecting]); tracks are republished. */
        FULL,
    }

    /**
     * GRAVIX: the session's ICE transport changed ([Room.selectedTransport]): [transport] is TCP when either peer
     * connection runs over ICE/TCP, RELAY when either is relayed, else UDP. A session that lands on TCP after a
     * reconnect is often slow (see [SlowTcpTransport]). From libwebrtc's selected-candidate-pair callback
     * (GravixRecoveryOptions.iceTransportWatchMs). The Flutter SDK's `RoomIceTransportChangedEvent` (0.4.18).
     *
     * @property publisher each connection's own transport (null: not known yet).
     */
    class IceTransportChanged(
        room: Room,
        val transport: GravixIceTransport?,
        val previous: GravixIceTransport?,
        val publisher: GravixIceTransport?,
        val subscriber: GravixIceTransport?,
    ) : RoomEvent(room)

    /**
     * GRAVIX: the session runs over ICE/TCP and its round trip ([rttMs], the selected pair's) stayed above
     * GravixRecoveryOptions.slowTcpRttMs (1 s) for [overMs] (at least slowTcpForMs, 10 s). Once per episode (again
     * after the round trip came back below the threshold). Media is likely frozen or very low quality; an app may show
     * it or report it. The Flutter SDK's `RoomSlowTcpTransportEvent` (0.4.18).
     */
    class SlowTcpTransport(room: Room, val rttMs: Long, val overMs: Long) : RoomEvent(room)

    /**
     * Disconnected from room
     */
    class Disconnected(room: Room, val error: Exception?, val reason: DisconnectReason) : RoomEvent(room)

    /**
     * When a [RemoteParticipant] joins after the local participant. It will not emit events
     * for participants that are already in the room
     */
    class ParticipantConnected(room: Room, val participant: RemoteParticipant) : RoomEvent(room)

    /**
     * When a [RemoteParticipant] leaves after the local participant has joined.
     */
    class ParticipantDisconnected(room: Room, val participant: RemoteParticipant) : RoomEvent(room)

    /**
     * Active speakers changed. List of speakers are ordered by their audio level. loudest
     * speakers first. This will include the [LocalParticipant] too.
     */
    class ActiveSpeakersChanged(room: Room, val speakers: List<Participant>) : RoomEvent(room)

    class RoomMetadataChanged(
        room: Room,
        val newMetadata: String?,
        val prevMetadata: String?,
    ) : RoomEvent(room)

    // Participant callbacks
    /**
     * Participant metadata is a simple way for app-specific state to be pushed to all users.
     * When RoomService.UpdateParticipantMetadata is called to change a participant's state,
     * this event will be fired for all clients in the room.
     */
    class ParticipantMetadataChanged(
        room: Room,
        val participant: Participant,
        val prevMetadata: String?,
    ) : RoomEvent(room)

    /**
     * When a participant's attributes are changed, fired for all participants
     */
    class ParticipantAttributesChanged(
        room: Room,
        val participant: Participant,
        /**
         * The attributes that have changed and their new associated values.
         */
        val changedAttributes: Map<String, String>,
        /**
         * The old attributes prior to change.
         */
        val oldAttributes: Map<String, String>,
    ) : RoomEvent(room)

    class ParticipantNameChanged(
        room: Room,
        val participant: Participant,
        val name: String?,
    ) : RoomEvent(room)

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackMuted(room: Room, val publication: TrackPublication, val participant: Participant) : RoomEvent(room)

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackUnmuted(room: Room, val publication: TrackPublication, val participant: Participant) : RoomEvent(room)

    /**
     * Fired when the first remote participant has subscribed to the localParticipant's track
     */
    class LocalTrackSubscribed(room: Room, val publication: LocalTrackPublication, val participant: LocalParticipant) : RoomEvent(room)

    /**
     * When a new track is published to room after the local participant has joined. It will
     * not fire for tracks that are already published
     */
    class TrackPublished(room: Room, val publication: TrackPublication, val participant: Participant) : RoomEvent(room)

    /**
     * Error had occurred while publishing a track, for LocalParticipant only
     * not fire for tracks that are already published
     */
    class TrackPublicationFailed(
        room: Room,
        val track: Track,
        val participant: LocalParticipant,
        e: TrackException.PublishException,
    ) : RoomEvent(room)

    /**
     * A [Participant] has unpublished a track
     */
    class TrackUnpublished(room: Room, val publication: TrackPublication, val participant: Participant) :
        RoomEvent(room)

    /**
     * The [LocalParticipant] has subscribed to a new track. This event will always fire as
     * long as new tracks are ready for use.
     */
    class TrackSubscribed(
        room: Room,
        val track: Track,
        val publication: TrackPublication,
        val participant: RemoteParticipant,
    ) : RoomEvent(room)

    /**
     * Could not subscribe to a track
     */
    class TrackSubscriptionFailed(
        room: Room,
        val sid: String,
        val exception: Exception,
        val participant: RemoteParticipant,
    ) : RoomEvent(room)

    /**
     * A subscribed track is no longer available. Clients should listen to this event and ensure
     * the track removes all renderers
     */
    class TrackUnsubscribed(
        room: Room,
        val track: Track,
        val publications: TrackPublication,
        val participant: RemoteParticipant,
    ) : RoomEvent(room)

    /**
     * A track's stream state has changed.
     */
    class TrackStreamStateChanged(
        room: Room,
        val trackPublication: TrackPublication,
        val streamState: Track.StreamState,
    ) : RoomEvent(room)

    /**
     * A remote track's subscription permissions have changed.
     */
    class TrackSubscriptionPermissionChanged(
        room: Room,
        val participant: RemoteParticipant,
        val trackPublication: RemoteTrackPublication,
        val subscriptionAllowed: Boolean,
    ) : RoomEvent(room)

    /**
     * Received data published by another participant
     *
     * @param data the published data
     * @param participant the participant if available
     */
    class DataReceived(
        room: Room,
        val data: ByteArray,
        val participant: RemoteParticipant?,
        val topic: String?,
        val encryptionType: GravixcloudModels.Encryption.Type,
    ) :
        RoomEvent(room)

    /**
     * The connection quality for a participant has changed.
     *
     * @param participant Either a remote participant or [Room.localParticipant]
     * @param quality the new connection quality
     */
    class ConnectionQualityChanged(room: Room, val participant: Participant, val quality: ConnectionQuality) :
        RoomEvent(room)

    class FailedToConnect(room: Room, val error: Throwable) : RoomEvent(room)

    /**
     * GRAVIX: the server the session is connected to changed: the first connect, a region failover, or a full
     * rejoin to another region. An app's server indicator should follow this (or [Room.connectedUrl]) rather than
     * the url it passed to [Room.connect]. A resume keeps the url and emits nothing.
     *
     * @property region the region slug when known (see [Room.connectedRegion]).
     * @property previousUrl the url before; null on the first connect.
     */
    class ConnectedUrlChanged(
        room: Room,
        val url: String,
        val region: String?,
        val previousUrl: String?,
        val previousRegion: String?,
        val reason: ConnectedUrlChangeReason,
    ) : RoomEvent(room) {
        override fun toString(): String =
            "ConnectedUrlChanged(url=$url, region=$region, previousUrl=$previousUrl, previousRegion=$previousRegion, reason=$reason)"
    }

    /** GRAVIX: why [ConnectedUrlChanged] was emitted. */
    enum class ConnectedUrlChangeReason {
        /** A [Room.connect] completed (also when it landed on another region than the url it was given). */
        CONNECT,

        /** A full reconnect (a new session) joined another url than the previous one. */
        REJOIN,
    }

    /**
     * A participant's permissions have changed.
     *
     * Currently only fires for the local participant.
     */
    class ParticipantPermissionsChanged(
        room: Room,
        val participant: Participant,
        val newPermissions: ParticipantPermission?,
        val oldPermissions: ParticipantPermission?,
    ) : RoomEvent(room)

    /**
     * The recording of a room has started/stopped.
     */
    class RecordingStatusChanged(room: Room, isRecording: Boolean) : RoomEvent(room)

    /**
     * The E2EE state of a track has changed.
     */
    class TrackE2EEStateEvent(
        room: Room,
        val track: Track,
        val publication: TrackPublication,
        val participant: Participant,
        var state: E2EEState,
    ) : RoomEvent(room)

    @Beta
    class TranscriptionReceived(
        room: Room,
        /**
         * The transcription segments.
         */
        val transcriptionSegments: List<TranscriptionSegment>,
        /**
         * The applicable participant these transcriptions apply to.
         */
        val participant: Participant?,
        /**
         * The applicable track publication these transcriptions apply to.
         */
        val publication: TrackPublication?,
    ) : RoomEvent(room)

    /**
     * The state for a participant has changed.
     */
    class ParticipantStateChanged(
        room: Room,
        val participant: Participant,
        val newState: Participant.State,
        val oldState: Participant.State,
    ) : RoomEvent(room)
}

enum class DisconnectReason {
    UNKNOWN_REASON,
    CLIENT_INITIATED,
    DUPLICATE_IDENTITY,
    SERVER_SHUTDOWN,
    PARTICIPANT_REMOVED,
    ROOM_DELETED,
    STATE_MISMATCH,
    JOIN_FAILURE,
    MIGRATION,
    SIGNAL_CLOSE,
    ROOM_CLOSED,
    USER_UNAVAILABLE,
    USER_REJECTED,
    SIP_TRUNK_FAILURE,
    CONNECTION_TIMEOUT,
    MEDIA_FAILURE,
}

/**
 * @suppress
 */
fun GravixcloudModels.DisconnectReason?.convert(): DisconnectReason {
    return when (this) {
        GravixcloudModels.DisconnectReason.CLIENT_INITIATED -> DisconnectReason.CLIENT_INITIATED
        GravixcloudModels.DisconnectReason.DUPLICATE_IDENTITY -> DisconnectReason.DUPLICATE_IDENTITY
        GravixcloudModels.DisconnectReason.SERVER_SHUTDOWN -> DisconnectReason.SERVER_SHUTDOWN
        GravixcloudModels.DisconnectReason.PARTICIPANT_REMOVED -> DisconnectReason.PARTICIPANT_REMOVED
        GravixcloudModels.DisconnectReason.ROOM_DELETED -> DisconnectReason.ROOM_DELETED
        GravixcloudModels.DisconnectReason.STATE_MISMATCH -> DisconnectReason.STATE_MISMATCH
        GravixcloudModels.DisconnectReason.JOIN_FAILURE -> DisconnectReason.JOIN_FAILURE
        GravixcloudModels.DisconnectReason.MIGRATION -> DisconnectReason.MIGRATION
        GravixcloudModels.DisconnectReason.SIGNAL_CLOSE -> DisconnectReason.SIGNAL_CLOSE
        GravixcloudModels.DisconnectReason.ROOM_CLOSED -> DisconnectReason.ROOM_CLOSED
        GravixcloudModels.DisconnectReason.USER_UNAVAILABLE -> DisconnectReason.USER_UNAVAILABLE
        GravixcloudModels.DisconnectReason.USER_REJECTED -> DisconnectReason.USER_REJECTED
        GravixcloudModels.DisconnectReason.SIP_TRUNK_FAILURE -> DisconnectReason.SIP_TRUNK_FAILURE
        GravixcloudModels.DisconnectReason.CONNECTION_TIMEOUT -> DisconnectReason.CONNECTION_TIMEOUT
        GravixcloudModels.DisconnectReason.MEDIA_FAILURE -> DisconnectReason.MEDIA_FAILURE
        GravixcloudModels.DisconnectReason.AGENT_ERROR,
        GravixcloudModels.DisconnectReason.UNKNOWN_REASON,
        GravixcloudModels.DisconnectReason.UNRECOGNIZED,
        null,
        -> DisconnectReason.UNKNOWN_REASON
    }
}
