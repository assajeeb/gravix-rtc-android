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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// GRAVIX: which measured region joins use, with hysteresis -- rule for rule the Flutter SDK's
// gravix_region_selection.dart (= the React SDK's regionSelection.ts); the shared fixture
// test/fixtures/region_selection_cases.json gives the same choice.
//
// Why (field 2026-10-04, Bangladesh): sgp1 and blr1 measure within a few ms of each other; four sessions in 15
// minutes went blr1, sgp1, sgp1, blr1 and one audio room ended up cross-region. The last good region is an
// ANCHOR, kept per network for hours, and a rival must beat it by the keep-current margin (max(30 ms, 20 %)) in
// CONFIRM_RUNS consecutive measurements before joins move. Only a failed anchor moves at once.

/** One measured region as the selection sees it. [status] = `ok` | `failed` | `unmeasured`. */
data class GravixRegionSample @JvmOverloads constructor(val region: String, val status: String, val rttMs: Long? = null) {
    val ok: Boolean get() = status == "ok" && rttMs != null
}

/** A rival that beat the anchor by the margin in [wins] consecutive measurements. */
data class GravixRegionChallenger(val region: String, val wins: Int) {
    internal fun toJson(): JsonObject = buildJsonObject { // GRAVIX(Phase 4): internal, see GravixRegionDirectory
        put("region", region)
        put("wins", wins)
    }

    companion object {
        internal fun fromJson(o: JsonElement?): GravixRegionChallenger? {
            val m = o as? JsonObject ?: return null
            val region = (m["region"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val wins = (m["wins"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            return GravixRegionChallenger(region, wins)
        }
    }
}

/**
 * Why [GravixRegionSelection.region] was chosen. Wire names = [wireName].
 * - `fastest`: no anchor (or the anchor is the fastest);
 * - `kept`: the anchor, within the margin of the fastest;
 * - `confirming`: the anchor, although a rival beat it (not yet in enough consecutive runs);
 * - `switched`: the rival won its confirming run;
 * - `anchorFailed`: the anchor failed, the fastest at once;
 * - `anchorGone`: the anchor was not measured this run at all.
 */
enum class GravixRegionSelectReason(val wireName: String) {
    FASTEST("fastest"),
    KEPT("kept"),
    CONFIRMING("confirming"),
    SWITCHED("switched"),
    ANCHOR_FAILED("anchorFailed"),
    ANCHOR_GONE("anchorGone"),
}

/** @property challenger the challenger state to keep for the next measurement (null = none). */
data class GravixRegionSelection(
    val region: String,
    val reason: GravixRegionSelectReason,
    val challenger: GravixRegionChallenger? = null,
)

object GravixRegionSelector {
    /** How long the last good region is kept as the anchor for a network, measured or not. */
    const val ANCHOR_LIFE_MS = 12L * 60 * 60 * 1000

    /** Consecutive measurements a rival must win (by the keep-current margin) before joins move to it. */
    const val CONFIRM_RUNS = 2

    /** After a measurement in which a rival won once, measure again this soon. */
    const val CONFIRM_DELAY_MS = 20_000L

    /** The home-region margin: max([HOME_MARGIN_MIN_MS], [HOME_MARGIN_RATIO] x the fastest region's RTT). */
    const val HOME_MARGIN_MIN_MS = 25L
    const val HOME_MARGIN_RATIO = 0.3

    /** The keep-current margin against an anchor measured at [anchorMs]: max(30 ms, 20 %). */
    @JvmStatic
    fun anchorMargin(anchorMs: Long): Double =
        maxOf(GravixRegionDecision.SWITCH_MIN_GAIN_MS.toDouble(), anchorMs * GravixRegionDecision.SWITCH_MIN_GAIN_RATIO)

    /**
     * Picks the region joins use from one measurement, with hysteresis. Pure. Null when no region measured `ok`.
     *
     * 1. no `ok` region: null (challenger unchanged);
     * 2. no anchor, or the anchor is the fastest: the fastest, challenger cleared;
     * 3. the anchor not among the samples: the fastest (`anchorGone`);
     * 4. the anchor `failed`: the fastest at once (`anchorFailed`);
     * 5. the anchor `ok` and the fastest beats it by no more than the margin: the anchor (`kept`);
     * 6. otherwise the fastest gets a vote (one more if it is the same challenger as last time). At
     *    [CONFIRM_RUNS] votes: the fastest (`switched`); before that the anchor (`confirming`) -- when
     *    [anchorKnown] says the caller can still use it (an `unmeasured` anchor needs a last-known RTT).
     */
    @JvmStatic
    @JvmOverloads
    fun select(
        samples: List<GravixRegionSample>,
        anchor: String? = null,
        challenger: GravixRegionChallenger? = null,
        anchorKnown: Boolean = true,
    ): GravixRegionSelection? {
        var fastest: GravixRegionSample? = null
        for (s in samples) {
            if (s.ok && (fastest == null || s.rttMs!! < fastest.rttMs!!)) fastest = s
        }
        if (fastest == null) return null
        if (anchor == null || anchor == fastest.region) {
            return GravixRegionSelection(fastest.region, GravixRegionSelectReason.FASTEST)
        }
        val a = samples.firstOrNull { it.region == anchor }
            ?: return GravixRegionSelection(fastest.region, GravixRegionSelectReason.ANCHOR_GONE)
        if (a.status == "failed") return GravixRegionSelection(fastest.region, GravixRegionSelectReason.ANCHOR_FAILED)
        if (a.ok && a.rttMs!! - fastest.rttMs!! <= anchorMargin(a.rttMs)) {
            return GravixRegionSelection(anchor, GravixRegionSelectReason.KEPT)
        }
        val wins = if (challenger != null && challenger.region == fastest.region) challenger.wins + 1 else 1
        if (wins >= CONFIRM_RUNS) return GravixRegionSelection(fastest.region, GravixRegionSelectReason.SWITCHED)
        if (!a.ok && !anchorKnown) return GravixRegionSelection(fastest.region, GravixRegionSelectReason.ANCHOR_FAILED)
        return GravixRegionSelection(anchor, GravixRegionSelectReason.CONFIRMING, GravixRegionChallenger(fastest.region, wins))
    }

    /** The home-region margin against the fastest region at [fastestMs]: max(25 ms, 30 %). */
    @JvmStatic
    fun homeRegionMargin(fastestMs: Long): Double = maxOf(HOME_MARGIN_MIN_MS.toDouble(), fastestMs * HOME_MARGIN_RATIO)

    /** True when the room's home region ([homeMs]) is close enough to the fastest ([fastestMs]) to join it. */
    @JvmStatic
    fun homeRegionWithinMargin(homeMs: Long, fastestMs: Long): Boolean = homeMs - fastestMs <= homeRegionMargin(fastestMs)
}
