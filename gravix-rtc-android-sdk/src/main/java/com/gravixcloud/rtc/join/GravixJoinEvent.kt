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

package com.gravixcloud.rtc.join

/** GRAVIX (0.1.2): what happened in a join ([GravixJoinEvent.kind]). */
enum class GravixJoinEventKind {
    /** [GravixJoin.warm]: a token prepared ahead of a tap (detail `cached` / `fetched`). */
    WARM,

    /** A join started (the tap). */
    PREJOIN_START,

    /** The token is in hand (detail `cached` / `fetched`). */
    TOKEN_READY,

    /** The url to dial is known (detail: `token`, or `measured` when the measured nearest region was picked). */
    URL_CHOSEN,

    /** The Room's connect started. */
    CONNECT_START,

    /** The Room is connected. */
    CONNECTED,

    /** The token or the connect failed (detail: the error type). */
    CONNECT_FAILED,

    /** The room screen took the Room ([GravixPrejoin.attach]). */
    ATTACHED,

    /** A frame was drawn with the app in the foreground after the screen called attach, or [GravixPrejoin.markShown]. */
    SHOWN,

    /** The join ended without the real exit (detail: the [GravixPrejoinEndReason], lower case). */
    ABANDONED,

    /** The real exit ([GravixJoin.leave] / [GravixPrejoin.leave]). */
    LEFT,

    /** A room switch started (the tap in the old room). */
    SWITCH_START,

    /** The old room's session is closed (and your beforeConnect done). */
    SWITCH_OLD_LEFT,

    /** The switched-to room is connected. */
    SWITCH_CONNECTED,

    /** A Room you connected yourself became the session ([GravixJoin.adopt]). */
    ADOPTED,
}

/**
 * GRAVIX (0.1.2): a timing event of a join, on the tap's clock: [sinceTapMs] is measured from the moment the join
 * started (or the switch was tapped). For a join trace or analytics; never carries a token.
 */
class GravixJoinEvent internal constructor(
    val kind: GravixJoinEventKind,
    /** The session (room, identity, publish right). */
    val request: GravixJoinRequest,
    /** Wall clock time of the event (ms since the epoch). */
    val atMs: Long,
    val sinceTapMs: Long,
    val detail: String?,
) {
    override fun toString(): String = "GravixJoinEvent($kind +${sinceTapMs}ms${detail?.let { " $it" } ?: ""})"
}

/** GRAVIX (0.1.2): receives [GravixJoinEvent]s (on the [GravixJoin]'s callback thread: the main thread). */
fun interface GravixJoinListener {
    fun onEvent(event: GravixJoinEvent)
}

/** GRAVIX (0.1.2): where a [GravixPrejoin] is. */
enum class GravixPrejoinState {
    /** Waiting for its token (and, in a switch, for the old session to close). */
    STARTING,

    /** The Room's connect is running. */
    CONNECTING,

    /** Connected, not yet taken by a screen. */
    CONNECTED,

    /** Taken by a screen ([GravixPrejoin.attach]) or adopted. */
    ATTACHED,

    /** Over ([GravixPrejoin.endReason] says why). */
    ENDED,
}

/** GRAVIX (0.1.2): why a [GravixPrejoin] ended. */
enum class GravixPrejoinEndReason {
    /** [GravixPrejoin.abandon] / [GravixJoin.abandon] (a gate refused the room, the user went elsewhere). */
    ABANDONED,

    /** The app went to the background before the room screen called attach (or the join started in the background). */
    BACKGROUND,

    /** The screen called attach, but the app went to the background before the screen was shown. */
    NEVER_SHOWN,

    /** Nobody called attach within the attach timeout (20 s by default). */
    ATTACH_TIMEOUT,

    /** The token or the connect failed ([GravixPrejoin.error]). */
    FAILED,

    /** The real exit ([GravixPrejoin.leave] / [GravixJoin.leave]). */
    LEFT,

    /** Another session replaced it: one session at a time. */
    SUPERSEDED,

    /** A room switch was cancelled by your beforeConnect. */
    CANCELLED,

    /** You connected the session again yourself and adopted the new Room ([GravixJoin.adopt]); not left here. */
    REPLACED,

    /**
     * Its Room is dead: disconnected after the connect (the server ended the session, a kick, the room closed, your own
     * disconnect), not a resume or a reconnect under way. Your leave hook does NOT run (your own disconnect handling
     * owns that cleanup); only a Room the join built with the default factory and the default leave is released.
     */
    ROOM_GONE,
}
