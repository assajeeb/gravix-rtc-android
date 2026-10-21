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

package com.gravixcloud.rtc.noise

import java.util.Locale

// GRAVIX (2026-10-21): `[GravixNS]` log lines, telemetry report and live state. Format shared with the Flutter SDK
// (gravix_noise_report.dart) and the JS SDK (log.ts):
//   [GravixNS] <step> eng=<off|rnnoise|dfn3|browser> route=<route> rate=<apmHz|?> [+<ms>ms] [detail]

/** Log / report steps. */
object GravixNsStep {
    const val INSTALL = "install"
    const val INIT = "init"
    const val POLICY = "policy"
    const val STATIC = "static"
    const val BENCH = "bench"
    const val PACK = "pack"
    const val SWITCH = "switch"
    const val ROUTE_RESET = "routeReset"
    const val MUSIC_BYPASS = "musicBypass"
    const val SUMMARY = "summary"
    const val STATS = "stats"
}

/** Audio route as it appears in logs / telemetry. */
enum class GravixNsRoute(val wireName: String) {
    SPEAKER("speaker"),
    EARPIECE("earpiece"),
    WIRED("wired"),
    BT_SCO("bt-sco"),
    BT_A2DP("bt-a2dp"),
    BT_LE("bt-le"),
    USB("usb"),
    UNKNOWN("unknown"),
    ;

    companion object {
        @JvmStatic
        fun fromWireName(name: String?): GravixNsRoute = entries.firstOrNull { it.wireName == name } ?: UNKNOWN
    }
}

/** The `[GravixNS]` line builders (pure). */
object GravixNsLog {
    /** One `[GravixNS]` line. [elapsedMs] is printed as `+<ms>ms`; [detail] is appended as-is (key=value pairs, no secrets). */
    @JvmStatic
    @JvmOverloads
    fun line(step: String, engine: GravixNsEngine, route: GravixNsRoute = GravixNsRoute.UNKNOWN, rate: Int? = null, elapsedMs: Long? = null, detail: String? = null): String =
        line(step, engine.wireName, route.wireName, rate ?: 0, elapsedMs, detail)

    internal fun line(step: String, eng: String, route: String, rate: Int, elapsedMs: Long? = null, detail: String? = null): String {
        val b = StringBuilder("[GravixNS] ").append(step).append(" eng=").append(eng).append(" route=").append(route)
            .append(" rate=").append(if (rate > 0) rate.toString() else "?")
        if (elapsedMs != null) b.append(" +").append(elapsedMs).append("ms")
        if (!detail.isNullOrEmpty()) b.append(' ').append(detail)
        return b.toString()
    }

    /** `switch` detail: `from=<eng> to=<eng> reason=<reason>`. */
    @JvmStatic
    fun switchDetail(from: GravixNsEngine, to: GravixNsEngine, reason: String): String =
        "from=${from.wireName} to=${to.wireName} reason=$reason"

    /** `summary` detail: `p50=.. p95=.. p99=.. max=.. over=.. [cpu=..]` (frame times in us). */
    @JvmStatic
    @JvmOverloads
    fun summaryDetail(p50Us: Int, p95Us: Int, p99Us: Int, maxUs: Int, overBudget: Int, cpuPercent: Double? = null): String {
        val s = "p50=$p50Us p95=$p95Us p99=$p99Us max=$maxUs over=$overBudget"
        return if (cpuPercent == null) s else "$s cpu=${String.format(Locale.US, "%.1f", cpuPercent)}"
    }
}

/**
 * Telemetry handed to [GravixNoiseSuppressionOptions.onReport]. Each report carries what its step knows; [reason] is
 * always set (a [GravixNsReason] or a native one such as `watchdog_frame`).
 */
data class GravixNsReport(
    val step: String,
    val mode: GravixNoiseSuppressionMode,
    val engine: GravixNsEngine,
    val reason: String,
    val platform: String? = null,
    val route: GravixNsRoute = GravixNsRoute.UNKNOWN,
    val apmRate: Int? = null,
    val p50Us: Int? = null,
    val p95Us: Int? = null,
    val p99Us: Int? = null,
    val maxUs: Int? = null,
    val framesOverBudget: Int? = null,
    val benchmarkP95Us: Int? = null,
    val benchmarkCached: Boolean? = null,
    val musicBypass: Boolean? = null,
    val cpuPercent: Double? = null,
)

/** Live state ([GravixNoiseSuppression.state]). */
data class GravixNsState(
    /** What the app asked for. */
    val mode: GravixNoiseSuppressionMode = GravixNoiseSuppressionMode.OFF,
    /** The engine producing the audio now (`NONE` during a music bypass or a route-reset fade). */
    val engine: GravixNsEngine = GravixNsEngine.NONE,
    /** Why ([GravixNsReason] or a native reason). */
    val reason: String = GravixNsReason.MODE_OFF,
    val route: GravixNsRoute = GravixNsRoute.UNKNOWN,
    /** APM capture rate seen by the processor (null before the first frame). */
    val rate: Int? = null,
    /** The native processor is on the capture path. */
    val installed: Boolean = false,
    /** Room music is playing: the chain is bypassed (dry). */
    val musicBypass: Boolean = false,
    /** Frame time of the processor, last stats window (us). */
    val p50Us: Int? = null,
    val p95Us: Int? = null,
    val overBudget: Int? = null,
) {
    override fun toString(): String =
        "GravixNsState(mode=${mode.wireName} eng=${engine.wireName} reason=$reason route=${route.wireName} " +
            "rate=${rate ?: "?"} installed=$installed music=$musicBypass p95=${p95Us ?: "-"})"
}
