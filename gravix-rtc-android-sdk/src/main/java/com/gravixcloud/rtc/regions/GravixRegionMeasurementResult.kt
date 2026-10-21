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

/**
 * GRAVIX: `ok` = measured (has a warm sample); `failed` = answered with an error, or not at all within its request
 * timeout; `unmeasured` = cut off by the budget, the early exit or a stop before it had a warm sample -- no
 * evidence either way. Wire names = [wireName]. Declaration order is the result order (ok, failed, unmeasured).
 */
enum class GravixRegionMeasurementStatus(val wireName: String) {
    OK("ok"),
    FAILED("failed"),
    UNMEASURED("unmeasured"),
}

/**
 * Where [GravixRegionMeasurementResult.best] came from. `kept` = the held region, within the switch margin of the
 * fastest; `cache` = this network's cached answer (no new measurement yet); `shortlist`/`est` = nothing measured,
 * the gateway's guess.
 */
enum class GravixRegionBestSource(val wireName: String) {
    MEASURED("measured"),
    KEPT("kept"),
    CACHE("cache"),
    SHORTLIST("shortlist"),
    EST("est"),
}

/**
 * One region of a measurement.
 *
 * @property rttMs minimum of the warm samples; null when the region has none.
 * @property samplesMs every sample taken, the dropped first (cold) one included.
 * @property connections sockets the region's probe client opened (1 = every warm sample reused the first one's
 *   connection). Null when a custom sampler without a probe client measured.
 * @property budgetHit still being measured when the total budget ran out.
 * @property retried the first request got no answer and the region was asked again.
 */
data class GravixRegionMeasurement @JvmOverloads constructor(
    val entry: GravixRegionUrl,
    val rttMs: Long?,
    val samplesMs: List<Long>,
    val connections: Int? = null,
    val status: GravixRegionMeasurementStatus = if (rttMs != null) GravixRegionMeasurementStatus.OK else GravixRegionMeasurementStatus.FAILED,
    val source: GravixRegionCandidateSource = GravixRegionCandidateSource.ALL,
    val budgetHit: Boolean = false,
    val retried: Boolean = false,
) {
    val region: String get() = entry.region
    val url: String get() = entry.url
    val ok: Boolean get() = rttMs != null
}

/**
 * The answer of one start-up region measurement.
 *
 * @property measuredAtMs wall-clock time (ms since the epoch) of the answer.
 * @property regions the probed regions: `ok` fastest first, then `failed`, then `unmeasured`.
 * @property best the region joins should use (the fastest, unless the held one is within the switch margin); with
 *   nothing measured, the gateway's guess (not [GravixRegionMeasurement.ok], see [bestSource]). Null when none.
 * @property error why nothing was measured (the list could not be had), when that is the case.
 * @property ttlMs how long this answer is trusted (`shortlist_ttl_s`, default 10 min).
 */
data class GravixRegionMeasurementResult @JvmOverloads constructor(
    val measuredAtMs: Long,
    val regions: List<GravixRegionMeasurement>,
    val best: GravixRegionMeasurement? = null,
    val error: String? = null,
    val elapsedMs: Long? = null,
    val bestSource: GravixRegionBestSource? = null,
    val mode: GravixRegionPlanMode? = null,
    val budgetMs: Long? = null,
    val budgetHit: Boolean = false,
    val earlyExit: Boolean = false,
    val networkKey: String? = null,
    val ttlMs: Long = GravixRegionMeasurements.TTL_MS,
    val selectReason: GravixRegionSelectReason? = null,
    val challenger: GravixRegionChallenger? = null,
    /**
     * GRAVIX (2026-10-21, per-region media): for a medium some measured region does not take, the region joins of
     * that medium use -- the same hysteresis as [best] (an anchor per medium, a rival must win twice by the keep
     * margin), over the regions that take it. Absent: [best] serves. See [bestFor].
     */
    val bestByMedia: Map<GravixRoomMedia, GravixRegionMeasurement> = emptyMap(),
    /** How [bestByMedia] was chosen, per medium. */
    val mediaSelectReason: Map<GravixRoomMedia, GravixRegionSelectReason> = emptyMap(),
    /** A rival not yet confirmed, per medium. */
    val mediaChallenger: Map<GravixRoomMedia, GravixRegionChallenger> = emptyMap(),
) {
    /** The region a [media] join should prefer: [bestByMedia], else [best]. */
    fun bestFor(media: GravixRoomMedia?): GravixRegionMeasurement? = media?.let { bestByMedia[it] } ?: best

    /** True while [nowMs] (wall clock) is within the answer's TTL. */
    fun isFreshAt(nowMs: Long): Boolean = nowMs - measuredAtMs <= ttlMs
}
