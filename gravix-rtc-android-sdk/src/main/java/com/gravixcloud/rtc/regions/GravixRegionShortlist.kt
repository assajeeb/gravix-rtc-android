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

import com.gravixcloud.rtc.room.retiredGravixServerHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// GRAVIX: which regions the start-up measurement probes, and when it may stop. The pure half of the
// measurement, rule for rule the Flutter SDK's gravix_region_shortlist.dart (= the React SDK's
// regionShortlist.ts); the shared fixture test/fixtures/region_shortlist_cases.json gives the same choice.
//
// Candidates:
// - shortlist present: the shortlist, plus the region joins use now when the shortlist leaves it out;
// - no shortlist, N <= PROBE_ALL_MAX: every region;
// - no shortlist, N > 6, `est_rtt_ms` known: the EST_PROBE_COUNT lowest;
// - else, a cache for this network: its EXPLORE_KNOWN last-known-best plus EXPLORE_COUNT others from a
//   ROTATING cursor (a rotation, not a random draw, so every SDK picks the same regions from the same state);
// - else every region (concurrency-capped; the budget decides how many finish).

/** Constants of the start-up region measurement (same values in every Gravix SDK). */
object GravixRegionPlanning {
    /** Without a shortlist, a list this short is measured whole. */
    const val PROBE_ALL_MAX = 6

    /** Without a shortlist, the regions with the lowest `est_rtt_ms` probed. */
    const val EST_PROBE_COUNT = 3

    /** Explore mode: last-known-best regions kept from the cache. */
    const val EXPLORE_KNOWN = 2

    /** Explore mode: other regions tried per run, from the rotating cursor. */
    const val EXPLORE_COUNT = 2

    /** Regions measured at the same time. */
    const val MEASURE_CONCURRENCY = 6

    /** Default total budget of one measurement on Wi-Fi / unknown networks. */
    const val MEASURE_BUDGET_MS = 1500L

    /** Default total budget on cellular (a floor under the gateway's budget there). */
    const val MEASURE_BUDGET_CELLULAR_MS = 3000L
}

/** Why a region was probed. `cache` = the held / last-known-best region. Wire names = [wireName]. */
enum class GravixRegionCandidateSource(val wireName: String) {
    SHORTLIST("shortlist"),
    EST("est"),
    EXPLORE("explore"),
    ALL("all"),
    CACHE("cache"),
}

/** How the candidates were chosen. Wire names = [wireName]. */
enum class GravixRegionPlanMode(val wireName: String) {
    SHORTLIST("shortlist"),
    EST("est"),
    EXPLORE("explore"),
    ALL("all"),
}

/**
 * The gateway's `GET /v1/regions` answer. Old gateways send only `regions`.
 *
 * @property shortlist region slugs to probe, best guess first.
 * @property probeBudgetMs total measurement budget the gateway asks for (`probe_budget_ms`).
 * @property shortlistTtlMs how long a measurement on this list is trusted (`shortlist_ttl_s`).
 */
data class GravixRegionDirectory @JvmOverloads constructor(
    val regions: List<GravixRegionUrl>,
    val shortlist: List<String>? = null,
    val probeBudgetMs: Long? = null,
    val shortlistTtlMs: Long? = null,
) {
    // GRAVIX(Phase 4): internal -- a public JsonObject/JsonElement signature needs kotlinx-serialization (an
    // `implementation` dependency) on the app's compile classpath; apps get the String form below instead.
    internal fun toJson(): JsonObject = buildJsonObject {
        put(
            "regions",
            buildJsonArray {
                for (r in regions) {
                    add(
                        buildJsonObject {
                            put("region", r.region)
                            put("url", r.url)
                            r.probeUrl?.let { put("probe_url", it) }
                            r.estRttMs?.let { put("est_rtt_ms", it) }
                            r.media?.let { m -> put("media", buildJsonArray { m.forEach { add(JsonPrimitive(it)) } }) }
                        },
                    )
                }
            },
        )
        shortlist?.let { sl -> put("shortlist", buildJsonArray { sl.forEach { add(JsonPrimitive(it)) } }) }
        probeBudgetMs?.let { put("probe_budget_ms", it) }
        shortlistTtlMs?.let { put("shortlist_ttl_s", it / 1000) }
    }

    /** The gateway's `GET /v1/regions` JSON for this directory (wire names). */
    fun toJsonString(): String = toJson().toString()

    companion object {
        /**
         * Parses a `GET /v1/regions` body, for a custom [GravixRegionDirectoryFetcher]. Malformed JSON gives an
         * empty directory (no regions), like a malformed field below.
         */
        @JvmStatic
        fun fromJson(json: String): GravixRegionDirectory =
            fromJson(runCatching { Json.parseToJsonElement(json) }.getOrNull())

        /**
         * Parses `{ regions:[...], shortlist?, probe_budget_ms?, shortlist_ttl_s? }`; anything malformed is left
         * out (an old gateway is just `regions`).
         */
        internal fun fromJson(body: JsonElement?): GravixRegionDirectory {
            val b = body as? JsonObject ?: JsonObject(emptyMap())
            val regions = (b["regions"] as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val region = r.string("region") ?: return@mapNotNull null
                val url = r.string("url") ?: return@mapNotNull null
                GravixRegionUrl(
                    region = region,
                    url = url,
                    probeUrl = r.string("probe_url"),
                    estRttMs = r.number("est_rtt_ms")?.takeIf { it >= 0 },
                    media = GravixRegionMedia.parse(r["media"]),
                )
            }
            val shortlist = (b["shortlist"] as? JsonArray).orEmpty().mapNotNull { s ->
                (s as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            }
            val budget = b.number("probe_budget_ms")?.takeIf { it > 0 }
            val ttl = b.number("shortlist_ttl_s")?.takeIf { it > 0 }
            return GravixRegionDirectory(
                regions = regions,
                shortlist = shortlist.ifEmpty { null },
                probeBudgetMs = budget?.let { Math.round(it) },
                shortlistTtlMs = ttl?.let { Math.round(it * 1000) },
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(key: String): Double? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    }
}

/** GRAVIX(retired servers): the directory without retired Gravix servers (by url or probe url). */
internal fun GravixRegionDirectory.withoutRetiredServers(): GravixRegionDirectory {
    val kept = regions.filter { r -> retiredGravixServerHost(r.url) == null && r.probeUrl?.let(::retiredGravixServerHost) == null }
    return if (kept.size == regions.size) this else copy(regions = kept)
}

/** What the per-network cache remembers between runs: last-known-best slugs (fastest first) and the cursor. */
data class GravixRegionPlanState @JvmOverloads constructor(
    val known: List<String> = emptyList(),
    val cursor: Int = 0,
)

data class GravixPlannedCandidate(val entry: GravixRegionUrl, val source: GravixRegionCandidateSource)

/** @property cursor the cursor the next run starts from (explore mode advances it). */
data class GravixRegionPlan(
    val mode: GravixRegionPlanMode,
    val candidates: List<GravixPlannedCandidate>,
    val cursor: Int,
)

/** One candidate as the early-exit rule sees it: its warm samples so far, and whether it is finished. */
data class GravixMeasureProgress(val warm: List<Long>, val done: Boolean)

/** The pure planning rules (shared with the Flutter and React SDKs). */
object GravixRegionShortlist {

    /** First entry per region slug, in list order. */
    private fun byRegion(regions: List<GravixRegionUrl>): LinkedHashMap<String, GravixRegionUrl> {
        val m = LinkedHashMap<String, GravixRegionUrl>()
        for (r in regions) m.putIfAbsent(r.region, r)
        return m
    }

    /** The shortlist entries the list actually has, deduplicated, in shortlist order. */
    private fun shortlistEntries(dir: GravixRegionDirectory): List<GravixRegionUrl> {
        val map = byRegion(dir.regions)
        val seen = HashSet<String>()
        return dir.shortlist.orEmpty().mapNotNull { slug -> map[slug]?.takeIf { seen.add(slug) } }
    }

    /** Regions with an `est_rtt_ms`, lowest first (list order breaks ties). */
    private fun byEst(regions: List<GravixRegionUrl>): List<GravixRegionUrl> =
        regions.withIndex()
            .filter { it.value.estRttMs != null }
            .sortedWith(compareBy<IndexedValue<GravixRegionUrl>> { it.value.estRttMs!! }.thenBy { it.index })
            .map { it.value }

    /**
     * The regions to probe this run. Pure: same inputs, same plan, in every SDK. [currentRegion] (the region
     * joins use now) is added with source `cache` when the plan leaves it out and the list has it.
     */
    @JvmStatic
    @JvmOverloads
    fun plan(
        dir: GravixRegionDirectory,
        state: GravixRegionPlanState? = null,
        currentRegion: String? = null,
    ): GravixRegionPlan {
        val map = byRegion(dir.regions)
        val regions = map.values.toList()
        var cursor = state?.cursor ?: 0
        val candidates = ArrayList<GravixPlannedCandidate>()
        fun add(e: GravixRegionUrl, s: GravixRegionCandidateSource) {
            if (candidates.none { it.entry.region == e.region }) candidates.add(GravixPlannedCandidate(e, s))
        }

        val mode: GravixRegionPlanMode
        val shortlist = shortlistEntries(dir)
        val known = state?.known.orEmpty().filter { map.containsKey(it) }.take(GravixRegionPlanning.EXPLORE_KNOWN)
        val est = byEst(regions)
        when {
            shortlist.isNotEmpty() -> {
                mode = GravixRegionPlanMode.SHORTLIST
                shortlist.forEach { add(it, GravixRegionCandidateSource.SHORTLIST) }
            }
            regions.size <= GravixRegionPlanning.PROBE_ALL_MAX -> {
                mode = GravixRegionPlanMode.ALL
                regions.forEach { add(it, GravixRegionCandidateSource.ALL) }
            }
            est.isNotEmpty() -> {
                mode = GravixRegionPlanMode.EST
                est.take(GravixRegionPlanning.EST_PROBE_COUNT).forEach { add(it, GravixRegionCandidateSource.EST) }
            }
            known.isNotEmpty() -> {
                mode = GravixRegionPlanMode.EXPLORE
                known.forEach { add(map.getValue(it), GravixRegionCandidateSource.CACHE) }
                val pool = regions.filter { it.region !in known }
                if (pool.isNotEmpty()) {
                    val start = ((cursor % pool.size) + pool.size) % pool.size
                    val n = minOf(GravixRegionPlanning.EXPLORE_COUNT, pool.size)
                    for (i in 0 until n) add(pool[(start + i) % pool.size], GravixRegionCandidateSource.EXPLORE)
                    cursor = (start + n) % pool.size
                }
            }
            else -> {
                mode = GravixRegionPlanMode.ALL
                regions.forEach { add(it, GravixRegionCandidateSource.ALL) }
            }
        }

        val cur = currentRegion?.let { map[it] }
        if (cur != null) add(cur, GravixRegionCandidateSource.CACHE)
        return GravixRegionPlan(mode, candidates.toList(), cursor)
    }

    /** The keep-current margin against a region measured at [slowerMs]: max(30 ms, 20 %). */
    @JvmStatic
    fun switchMargin(slowerMs: Long): Double =
        maxOf(GravixRegionDecision.SWITCH_MIN_GAIN_MS.toDouble(), slowerMs * GravixRegionDecision.SWITCH_MIN_GAIN_RATIO)

    /**
     * Early exit: every candidate has a warm sample (or is finished without one), at least one has, and the best
     * beats every other by the keep-rule margin -- the remaining samples cannot change the choice.
     */
    @JvmStatic
    fun shouldStopMeasuring(states: List<GravixMeasureProgress>): Boolean {
        var best: Long? = null
        for (s in states) {
            if (s.warm.isEmpty()) {
                if (!s.done) return false
                continue
            }
            val m = s.warm.min()
            if (best == null || m < best) best = m
        }
        if (best == null) return false
        var bestSeen = false
        for (s in states) {
            if (s.warm.isEmpty()) continue
            val m = s.warm.min()
            if (m == best && !bestSeen) {
                bestSeen = true
                continue
            }
            if (m - best <= switchMargin(m)) return false
        }
        return true
    }

    /**
     * When nothing was measured: `shortlist[0]`, else the lowest `est_rtt_ms`, else null.
     *
     * With [media] (a join's, 2026-10-21): the first shortlist region that allows it, else the lowest `est_rtt_ms`
     * that allows it, else null -- the caller's own last resort decides (a join never fails for want of a region).
     */
    @JvmStatic
    @JvmOverloads
    fun fallbackRegion(dir: GravixRegionDirectory, media: GravixRoomMedia? = null): GravixPlannedCandidate? {
        fun ok(e: GravixRegionUrl) = media == null || GravixRegionMedia.regionAllows(e, media)
        shortlistEntries(dir).firstOrNull(::ok)?.let { return GravixPlannedCandidate(it, GravixRegionCandidateSource.SHORTLIST) }
        byEst(byRegion(dir.regions).values.toList()).firstOrNull(::ok)?.let {
            return GravixPlannedCandidate(it, GravixRegionCandidateSource.EST)
        }
        return null
    }

    /**
     * The last-known-best list after a run: this run's answering regions fastest first, then earlier ones this
     * run did not probe (still unknown, not failed); a region that failed this run is dropped.
     */
    @JvmStatic
    fun nextKnown(rankedOk: List<String>, probed: List<String>, previous: List<String>): List<String> {
        val out = ArrayList<String>()
        for (r in rankedOk) if (r !in out) out.add(r)
        for (r in previous) if (r !in out && r !in probed) out.add(r)
        return out.take(GravixRegionPlanning.EXPLORE_KNOWN)
    }

    /**
     * FNV-1a 32-bit, hex: hashes an SSID or carrier name into the cache key, so the name itself is never stored.
     * Same function in the Flutter and React SDKs.
     */
    @JvmStatic
    fun hashNetworkId(id: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in id.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return h.toUInt().toString(16).padStart(8, '0')
    }

    /** The per-network cache key: the network type, plus the hashed SSID / carrier when known (`wifi:1a2b3c4d`). */
    @JvmStatic
    @JvmOverloads
    fun networkKey(type: String, id: String? = null): String =
        if (id.isNullOrEmpty()) type else "$type:${hashNetworkId(id)}"
}
