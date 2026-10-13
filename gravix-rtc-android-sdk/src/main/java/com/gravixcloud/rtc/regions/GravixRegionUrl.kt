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
 * GRAVIX: one region a client may connect to (port of the Flutter SDK's `GravixRegionUrl`).
 *
 * @property region region slug, e.g. `sgp1`, `blr1`.
 * @property url signalling url of that region, e.g. `wss://sgp1.example.com`.
 * @property probeUrl the region's dedicated probe endpoint (the SFU's `/v1/region-probe`, answers
 *   `{"region","node"}`). When set it is probed instead of the signalling origin and the region it names must
 *   match [region]. Null: the origin is probed (HEAD).
 * @property estRttMs the gateway's estimate of this client's RTT to the region (`est_rtt_ms`); only used to
 *   choose WHICH regions to measure, never as a measurement.
 */
data class GravixRegionUrl @JvmOverloads constructor(
    val region: String,
    val url: String,
    val probeUrl: String? = null,
    val estRttMs: Double? = null,
) {
    override fun toString(): String = "$region=$url"

    companion object {
        /** Slug used when a url cannot be matched to a region (shared with the other Gravix SDKs). */
        const val UNKNOWN_REGION = "unknown"
    }
}

/**
 * GRAVIX: the keep-current rule of the region decision (the Flutter/JS SDKs' decision cache): a rival must beat
 * the held region by MORE than the larger of [SWITCH_MIN_GAIN_MS] and [SWITCH_MIN_GAIN_RATIO] of the held
 * region's RTT. Two edges a few ms apart must not trade places on noise.
 */
object GravixRegionDecision {
    const val SWITCH_MIN_GAIN_MS = 30L
    const val SWITCH_MIN_GAIN_RATIO = 0.2
}
