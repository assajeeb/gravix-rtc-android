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

package com.gravixcloud.rtc.regions

import androidx.annotation.VisibleForTesting
import com.gravixcloud.rtc.token.TokenPayload
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.GravixStepLogs
import com.gravixcloud.rtc.util.LoggingLevel
import gravixcloud.GravixcloudModels
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// GRAVIX (2026-10-21): per-region media -- which media a region's servers take. Rule for rule the Flutter SDK's
// gravix_region_media.dart (= the React SDK's regionMedia.ts); the `media` cases of the shared fixture
// region_shortlist_cases.json give the same choice in all three.
//
// doh1 (GCP Doha) egress is expensive, so doh1 is audio-only: the console's `GET /v1/regions` entries carry an
// optional `media` (`["audio"]`; absent = every medium). The start-up measurement is media-blind (one measurement
// serves every join); the JOIN filters: a join that uses video goes to the best measured region that allows video,
// an audio-only join may use any region.
//
// No network call on the join path: what a region allows is remembered from the start-up `/v1/regions` fetch (or
// its stored copy) in a slug -> media map, so candidates that carry no `media` are still checked.

/**
 * What a room uses. Declaring it is optional (2026-11-04): with nothing declared a join goes to the fastest region and
 * the session moves once if the room turns out to carry video ([GravixRegionMedia.autoDetect]). Wire names = [wireName].
 * - [AUDIO]: nobody in the room publishes or watches video (voice rooms): the join may use any region, whatever this
 *   process remembers of the room;
 * - [VIDEO]: the room uses video (published by this user or by others): the join never lands on an audio-only region.
 *
 * A declaration decides the join only: a session on a region without video still moves once if the room carries video.
 */
enum class GravixRoomMedia(val wireName: String) {
    AUDIO("audio"),
    VIDEO("video"),
    ;

    companion object {
        /** The medium named [name] (`audio` / `video`, any case), else null. */
        @JvmStatic
        fun fromWireName(name: String?): GravixRoomMedia? = entries.firstOrNull { it.wireName == name?.trim()?.lowercase() }
    }
}

/** The per-region media rules and the process-wide notes they read (see the file comment). */
@Suppress("TooManyFunctions")
object GravixRegionMedia {
    /** Media names a region's `media` list uses. */
    const val AUDIO = "audio"
    const val VIDEO = "video"

    /** The string a server refusal of a medium carries when this node does not take it. */
    const val MEDIA_NOT_ALLOWED = "media_not_allowed"

    /** How long a region that refused a medium is avoided for it. */
    const val REFUSAL_LIFE_MS = 10L * 60 * 1000

    private const val ROOMS_WITH_VIDEO_MAX = 500

    /** How long a room is remembered as carrying video. */
    const val ROOM_VIDEO_MEMORY_LIFE_MS = 10L * 60 * 1000

    /**
     * The rollback switch for auto-detect. True (the default): a join with nothing declared may land on an audio-only
     * region and moves once if the room carries video. False: the behaviour before 2026-11-04, where nothing declared
     * means video and nothing is detected. Set it before the first join.
     */
    @JvmStatic
    @Volatile
    var autoDetect: Boolean = true

    private val lock = Any()
    private val mediaBySlug = HashMap<String, List<String>?>()
    private var lastRegions: List<GravixRegionUrl> = emptyList()
    private var lastShortlist: List<String> = emptyList()

    /** region -> media -> refused until (wall ms). */
    private val refused = HashMap<String, MutableMap<GravixRoomMedia, Long>>()

    /**
     * Rooms that carried video when last seen (room -> wall ms), oldest first: a later join of one goes straight to a
     * region that carries video instead of landing and moving again. In memory only. Owner, 2026-11-04: a rejoin of a room
     * whose video is over goes back to the fastest region, so the session settles this at its leave and an entry ends
     * after [ROOM_VIDEO_MEMORY_LIFE_MS].
     */
    private val roomsWithVideo = LinkedHashMap<String, Long>()

    private fun nowMs(): Long = GravixRegionMeasurements.clock.wallMs()

    /**
     * Parses a region entry's `media`: the names (lower-cased; unknown names kept, they just never match), or null
     * when absent / not a list (= every medium). An empty list allows nothing.
     */
    internal fun parse(v: JsonElement?): List<String>? {
        val a = v as? JsonArray ?: return null
        return a.mapNotNull { e ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        }
    }

    /** True when a region whose `media` is [media] takes a [want] join. Null media = every medium. */
    @JvmStatic
    fun allows(media: List<String>?, want: GravixRoomMedia): Boolean = media == null || want.wireName in media

    /** Remembers what each region of a fresh (or stored) `/v1/regions` answer allows. Called by the measurement. */
    @JvmStatic
    @JvmOverloads
    fun noteDirectory(regions: List<GravixRegionUrl>, shortlist: List<String>? = null) {
        if (regions.isEmpty()) return
        synchronized(lock) {
            mediaBySlug.clear()
            for (r in regions) if (!mediaBySlug.containsKey(r.region)) mediaBySlug[r.region] = r.media
            lastRegions = regions.toList()
            lastShortlist = shortlist.orEmpty().toList()
        }
    }

    /** The last region directory's entries (see [noteDirectory]). */
    @JvmStatic
    fun lastDirectory(): List<GravixRegionUrl> = synchronized(lock) { lastRegions }

    /** The last region directory's shortlist. */
    @JvmStatic
    fun lastShortlist(): List<String> = synchronized(lock) { lastShortlist }

    /** What [region] allows, as the last directory said (null = every medium, or not known). */
    @JvmStatic
    fun mediaOf(region: String): List<String>? = synchronized(lock) { mediaBySlug[region] }

    /**
     * The session's media after a refusal of [refused] (review 2026-10-21, #7): promoted, never demoted -- video if
     * either is video.
     */
    @JvmStatic
    fun promote(current: GravixRoomMedia?, refused: GravixRoomMedia): GravixRoomMedia =
        if (current == GravixRoomMedia.VIDEO || refused == GravixRoomMedia.VIDEO) GravixRoomMedia.VIDEO else GravixRoomMedia.AUDIO

    /** Notes that [region] refused [media] ([isMediaNotAllowed]): joins and failovers avoid it for [REFUSAL_LIFE_MS]. */
    @JvmStatic
    fun noteRefused(region: String, media: GravixRoomMedia) {
        if (region.isEmpty()) return
        synchronized(lock) { refused.getOrPut(region) { HashMap() }[media] = nowMs() + REFUSAL_LIFE_MS }
        GravixRegionLog.step("mediaRefused", "region=$region media=${media.wireName} avoid=${REFUSAL_LIFE_MS / 60_000}min")
    }

    private fun refusedNow(region: String, media: GravixRoomMedia): Boolean {
        val until = synchronized(lock) { refused[region]?.get(media) } ?: return false
        return nowMs() < until
    }

    /**
     * True when [entry] takes a [media] join: its own `media` when it carries one, else what the last directory said
     * of its region, else yes (old servers). A region that refused [media] in the last [REFUSAL_LIFE_MS] does not.
     */
    @JvmStatic
    fun regionAllows(entry: GravixRegionUrl, media: GravixRoomMedia): Boolean {
        if (refusedNow(entry.region, media)) return false
        return allows(entry.media ?: mediaOf(entry.region), media)
    }

    /**
     * True when [url] takes a [media] join: its region (from [known], the last directory, the measurement) allows
     * it, or its region is not known (a url outside the fleet is never second-guessed).
     */
    @JvmStatic
    @JvmOverloads
    fun urlAllows(url: String, media: GravixRoomMedia, known: List<GravixRegionUrl> = emptyList()): Boolean {
        val region = GravixRegionFailoverOrder.regionOfUrl(url, known + lastDirectory()) ?: return true
        val entry = known.firstOrNull { it.region == region } ?: GravixRegionUrl(region, url)
        return regionAllows(entry, media)
    }

    /** Why a region was passed over, for the log: `audio-only`, `video-only`, `refused`, or the region's list. */
    @JvmStatic
    fun skipWhy(entry: GravixRegionUrl, media: GravixRoomMedia): String {
        if (refusedNow(entry.region, media)) return "refused"
        val m = entry.media ?: mediaOf(entry.region) ?: emptyList()
        return if (m.size == 1) "${m[0]}-only" else if (m.isEmpty()) "no-media" else m.joinToString("+")
    }

    /**
     * The token's hint (never a demotion): [GravixRoomMedia.VIDEO] when its video grant lets this participant publish
     * the camera or a screen share (`video.canPublishSources` names `camera` / `screen_share`). Null otherwise --
     * including a mic-only publisher, who may still WATCH video. Never throws.
     */
    @JvmStatic
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun hintFromToken(token: String?): GravixRoomMedia? {
        if (token.isNullOrEmpty()) return null
        return try {
            val sources = TokenPayload(token).video?.canPublishSources ?: return null
            if (sources.any { s -> s.lowercase().let { it == "camera" || it == "screen_share" || it == "screenshare" } }) {
                GravixRoomMedia.VIDEO
            } else {
                null
            }
        } catch (e: Exception) {
            null // a token this SDK cannot read gives no hint
        }
    }

    /** The room a token is for (`video.room`), or null. Never throws. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    internal fun roomOfToken(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        return try {
            TokenPayload(token).video?.room?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Remembers that [room] carries video. Keeps the newest [ROOMS_WITH_VIDEO_MAX]. */
    internal fun noteRoomHasVideo(room: String) {
        if (room.isEmpty()) return
        synchronized(lock) {
            roomsWithVideo.remove(room)
            roomsWithVideo[room] = nowMs()
            val over = roomsWithVideo.keys.iterator()
            while (roomsWithVideo.size > ROOMS_WITH_VIDEO_MAX) {
                over.next()
                over.remove()
            }
        }
    }

    /** Forgets [room]: it had no video when it was left. */
    internal fun forgetRoomVideo(room: String) {
        synchronized(lock) { roomsWithVideo.remove(room) }
    }

    /** True when [room] was remembered as carrying video in the last [ROOM_VIDEO_MEMORY_LIFE_MS]. */
    internal fun roomHadVideo(room: String?): Boolean {
        if (room == null) return false
        return synchronized(lock) {
            val at = roomsWithVideo[room] ?: return@synchronized false
            if (nowMs() - at > ROOM_VIDEO_MEMORY_LIFE_MS) {
                roomsWithVideo.remove(room)
                false
            } else {
                true
            }
        }
    }

    /** How many rooms are remembered (tests). */
    @VisibleForTesting
    internal fun roomsWithVideoCount(): Int = synchronized(lock) { roomsWithVideo.size }

    /**
     * True when a participant other than [localIdentity] who has not left has a live video track. Camera and screen share
     * both are; a muted one carries nothing, and its unmute is an update that says so. Allocates nothing: the server re-sends the same update about every 500 ms.
     */
    internal fun infosCarryVideo(participants: List<GravixcloudModels.ParticipantInfo>, localIdentity: String?): Boolean {
        for (p in participants) {
            if (p.state == GravixcloudModels.ParticipantInfo.State.DISCONNECTED || p.identity == localIdentity) continue
            for (i in 0 until p.tracksCount) {
                val t = p.getTracks(i)
                if (t.type == GravixcloudModels.TrackType.VIDEO && !t.muted) return true
            }
        }
        return false
    }

    /**
     * The media a join uses, in this order: a join camera ([enableVideo]) or a token's video grant ([hintFromToken]) is
     * video; then [declared]; then a room this process has seen with video (the token's room); then audio,
     * so the fastest region wins and the session moves if the room turns out to carry video. With [autoDetect] off,
     * nothing declared is video. Never demotes a declared video. Logs the decision and its cause.
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("ReturnCount")
    fun joinMedia(declared: GravixRoomMedia? = null, enableVideo: Boolean = false, token: String? = null): GravixRoomMedia {
        if (declared == GravixRoomMedia.VIDEO) {
            GravixRegionLog.step("media", "media=video cause=declared")
            return GravixRoomMedia.VIDEO
        }
        if (declared == null && !autoDetect) {
            GravixRegionLog.step("media", "media=video cause=default")
            return GravixRoomMedia.VIDEO
        }
        val said = if (declared == null) "auto" else "declared audio"
        if (enableVideo) {
            GravixRegionLog.step("media", "media=video cause=enableVideo ($said)")
            return GravixRoomMedia.VIDEO
        }
        if (hintFromToken(token) == GravixRoomMedia.VIDEO) {
            GravixRegionLog.step("media", "media=video cause=token-grant-camera ($said)")
            return GravixRoomMedia.VIDEO
        }
        if (declared == GravixRoomMedia.AUDIO) {
            GravixRegionLog.step("media", "media=audio cause=declared")
            return GravixRoomMedia.AUDIO
        }
        if (roomHadVideo(roomOfToken(token))) {
            GravixRegionLog.step("media", "media=video cause=room-had-video")
            return GravixRoomMedia.VIDEO
        }
        GravixRegionLog.step("media", "media=audio cause=auto")
        return GravixRoomMedia.AUDIO
    }

    /** True when [errorOrReason] (an error, a publish failure, a leave reason) is a server refusal of a medium. */
    @JvmStatic
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun isMediaNotAllowed(errorOrReason: Any?): Boolean {
        if (errorOrReason == null) return false
        return try {
            errorOrReason.toString().lowercase().contains(MEDIA_NOT_ALLOWED)
        } catch (e: Exception) {
            false
        }
    }

    /** The medium a refusal text names: audio when it says audio and not video, else video. */
    internal fun refusedMediaOf(reason: Any?): GravixRoomMedia {
        val text = reason?.toString()?.lowercase().orEmpty()
        return if (text.contains("audio") && !text.contains("video")) GravixRoomMedia.AUDIO else GravixRoomMedia.VIDEO
    }

    /** Forgets every media note (tests; [GravixRegionMeasurements.clear] calls it). */
    @JvmStatic
    @VisibleForTesting
    fun clear() {
        synchronized(lock) {
            mediaBySlug.clear()
            lastRegions = emptyList()
            lastShortlist = emptyList()
            refused.clear()
            roomsWithVideo.clear()
        }
    }
}

/**
 * GRAVIX (2026-10-21): `[GravixRegion] <step> <detail>`, one line per region decision (the reason and the cause of
 * every step). Never a token or a url path; region slugs and numbers only.
 *
 * On by default at [LoggingLevel.INFO], independent of `GravixRtc.loggingLevel`. The lines go to [sink], else
 * `GravixRtc.logger` (logcat by default).
 */
object GravixRegionLog {
    /** The stable prefix of every line (grep for it). */
    const val PREFIX = "[GravixRegion]"

    /** The lowest level written: [LoggingLevel.INFO] (default), [LoggingLevel.VERBOSE] (more), or [LoggingLevel.OFF]. */
    @JvmStatic
    @Volatile
    var level: LoggingLevel = LoggingLevel.INFO

    /** Where the lines go (null: `GravixRtc.logger`). */
    @JvmStatic
    @Volatile
    var sink: GravixLogSink? = null

    internal fun step(step: String, detail: String, at: LoggingLevel = LoggingLevel.INFO) {
        if (!GravixStepLogs.writes(level, at)) return
        GravixStepLogs.emit(sink, at, "$PREFIX $step ${GravixStepLogs.scrub(detail)}")
    }
}
