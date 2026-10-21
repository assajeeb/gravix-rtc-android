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

// GRAVIX: region failover order and the connected region (port of the Flutter SDK's gravix_region_failover.dart).
//
// Field 2026-10-09 (Flutter): a session failed over from blr1 to doh1 (284 ms) because the core's failover dialled
// the first untried entry of the SFU's `/settings/regions` -- the other regions in configuration order, which
// knows nothing about latency -- while sgp1 (~61 ms) was up. [orderFailoverRegions] puts the measured regions
// first, nearest first, with the console's near-only cap.

object GravixRegionFailoverOrder {

    /** Normalised for comparison: scheme folded to ws/wss, host lower-cased, trailing slashes dropped. */
    @JvmStatic
    fun normalizeUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        val i = u.indexOf("://")
        if (i > 0) {
            var scheme = u.substring(0, i).lowercase()
            if (scheme == "https") scheme = "wss"
            if (scheme == "http") scheme = "ws"
            val rest = u.substring(i + 3)
            val slash = rest.indexOf('/')
            val host = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
            u = "$scheme://$host${if (slash < 0) "" else rest.substring(slash)}"
        }
        return u
    }

    /** The near-only cap of the console's shortlist: a region measured slower than max(2 x best, best + 100 ms). */
    @JvmStatic
    fun nearOnlyCapMs(bestMs: Long): Long = maxOf(bestMs * 2, bestMs + 100)

    /**
     * Orders the failover [candidates] (the server's `/settings/regions` entries, in the server's order) by the
     * fresh start-up [measurement]:
     *  1. measured `ok` and near -- RTT within [nearOnlyCapMs] of the best REMAINING measured candidate -- fastest
     *     first;
     *  2. the candidates the measurement has no RTT for (unmeasured, or not in it), in the server's order;
     *  3. measured `ok` but far, fastest first; then the ones whose measurement failed, in the server's order.
     * Nothing is dropped, only ordered. No measurement, or none of the candidates in it: [candidates] as given.
     *
     * [media] (2026-10-21, [GravixRegionMedia]): the candidates that do not take it (an audio-only region for a video
     * join, or one that refused it lately) go after all the others, in the same order -- last resort, never dropped.
     */
    @JvmStatic
    @JvmOverloads
    fun <T> order(
        candidates: List<T>,
        measurement: GravixRegionMeasurementResult?,
        regionOf: (T) -> String,
        urlOf: (T) -> String,
        media: GravixRoomMedia? = null,
    ): List<T> {
        val ordered = orderByMeasurement(candidates, measurement, regionOf, urlOf)
        return if (media == null) ordered else mediaLast(ordered, media, regionOf, urlOf)
    }

    /** [ordered] with the entries not taking [media] moved last (same relative order). Logs when it moved any. */
    internal fun <T> mediaLast(ordered: List<T>, media: GravixRoomMedia, regionOf: (T) -> String, urlOf: (T) -> String): List<T> {
        fun allows(c: T) = GravixRegionMedia.regionAllows(GravixRegionUrl(regionOf(c), urlOf(c)), media)
        val (ok, last) = ordered.partition(::allows)
        if (last.isNotEmpty() && ok.isNotEmpty()) {
            GravixRegionLog.step(
                "failoverOrder",
                "media=${media.wireName} last=${last.joinToString(",", transform = regionOf)} first=${ok.joinToString(",", transform = regionOf)}",
            )
        }
        return ok + last
    }

    private fun <T> orderByMeasurement(
        candidates: List<T>,
        measurement: GravixRegionMeasurementResult?,
        regionOf: (T) -> String,
        urlOf: (T) -> String,
    ): List<T> {
        val m = measurement ?: return candidates
        if (candidates.isEmpty()) return candidates
        fun measured(c: T): GravixRegionMeasurement? {
            val region = regionOf(c)
            if (region.isNotEmpty()) m.regions.firstOrNull { it.region == region }?.let { return it }
            val n = normalizeUrl(urlOf(c))
            return m.regions.firstOrNull { normalizeUrl(it.url) == n }
        }

        val ok = ArrayList<Pair<T, Long>>()
        val unknown = ArrayList<T>()
        val failed = ArrayList<T>()
        for (c in candidates) {
            val r = measured(c)
            when {
                r == null || r.status == GravixRegionMeasurementStatus.UNMEASURED -> unknown.add(c)
                r.ok -> ok.add(c to r.rttMs!!)
                else -> failed.add(c)
            }
        }
        if (ok.isEmpty()) return candidates
        ok.sortBy { it.second } // stable: equal RTTs keep the server's order
        val cap = nearOnlyCapMs(ok.first().second)
        val near = ok.filter { it.second <= cap }.map { it.first }
        val far = ok.filter { it.second > cap }.map { it.first }
        return near + unknown + far + failed
    }

    /**
     * The region slug of [url]: from [known] (a region list, the server's `/settings/regions`) first, else from the
     * [measurement]. Null when neither has it, or it is [GravixRegionUrl.UNKNOWN_REGION].
     */
    @JvmStatic
    @JvmOverloads
    fun regionOfUrl(
        url: String?,
        known: Iterable<GravixRegionUrl> = emptyList(),
        measurement: GravixRegionMeasurementResult? = GravixRegionMeasurements.current(),
    ): String? {
        if (url.isNullOrEmpty()) return null
        val n = normalizeUrl(url)
        fun valid(r: String?): String? = r?.takeIf { it.isNotEmpty() && it != GravixRegionUrl.UNKNOWN_REGION }
        for (e in known) {
            if (normalizeUrl(e.url) == n) valid(e.region)?.let { return it }
        }
        measurement?.regions?.forEach { r ->
            if (normalizeUrl(r.url) == n) valid(r.region)?.let { return it }
        }
        return null
    }
}
