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

import com.gravixcloud.rtc.regions.GravixRegionMedia
import com.gravixcloud.rtc.regions.GravixRoomMedia
import com.gravixcloud.rtc.token.TokenPayload

/**
 * GRAVIX (0.1.2): who joins which room, as the app's token call needs it. One session per [room] + [identity] +
 * [canPublish]: two requests with the same three values are the same session (one Room, one connect) and the same
 * token cache entry.
 */
class GravixJoinRequest @JvmOverloads constructor(
    /** The room name (as in the token's grant). */
    val room: String,
    /** The participant identity (as in the token). */
    val identity: String,
    /** Whether the token grants publishing (a viewer: false). */
    val canPublish: Boolean,
    /**
     * GRAVIX (2026-10-21, per-region media): what the room uses. [GravixRoomMedia.AUDIO] for a voice room nobody
     * publishes or watches video in, so the join may use an audio-only region (e.g. doh1); null /
     * [GravixRoomMedia.VIDEO] (the default, the safe one) keeps the join on regions that allow video. Chooses the
     * region only: not part of the session key, [equals] or the token cache (the token does not depend on it). A token
     * whose video grant may publish the camera / a screen share makes the join video whatever this says; a mic-only
     * grant does not make it audio. See [GravixRegionMedia].
     */
    val media: GravixRoomMedia? = null,
) {
    /** A viewer's request ([canPublish] false). */
    constructor(room: String, identity: String) : this(room, identity, false)

    /** room \u0000 identity \u0000 publish right (the Flutter SDK's session key shape). */
    internal val sessionKey: String
        get() = "$room\u0000$identity\u0000$canPublish"

    override fun equals(other: Any?): Boolean =
        other is GravixJoinRequest && other.room == room && other.identity == identity && other.canPublish == canPublish

    override fun hashCode(): Int = (room.hashCode() * 31 + identity.hashCode()) * 31 + canPublish.hashCode()

    /** Never the full identity (a short stable hash, as in the step lines). */
    override fun toString(): String =
        "GravixJoinRequest($room, ${GravixJoinLog.shortId(identity)}, pub=$canPublish${media?.let { ", media=${it.wireName}" } ?: ""})"
}

/**
 * The session key of a join token (room + identity + publish right, as [GravixJoinRequest]), read without verifying
 * it; null when it cannot be read. A grant without canPublish may publish (the server's default).
 */
@Suppress("SwallowedException") // an unreadable token has no key (the line says "-")
internal fun gravixSessionKeyOf(token: String): String? {
    return try {
        val p = TokenPayload(token)
        val room = p.video?.room
        val identity = p.identity
        if (room.isNullOrEmpty() || identity.isNullOrEmpty()) return null
        "$room\u0000$identity\u0000${p.video?.canPublish ?: true}"
    } catch (e: Exception) {
        null
    }
}
