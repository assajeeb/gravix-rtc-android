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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// GRAVIX (2026-10-21): engine resolver -- static device facts + policy + benchmark cache -> engine + reason. Pure;
// rule for rule the Flutter SDK's gravix_noise_resolver.dart (= the JS SDK's resolver.ts): the shared fixture
// ns_policy_cases.json resolves identically in all three.

/** Static facts about the device. Unknown = null. */
data class GravixNsDeviceFacts(
    /** android | ios | web | other */
    val platform: String,
    val model: String? = null,
    val manufacturer: String? = null,
    /** Build.SOC_MODEL (API 31+). */
    val soc: String? = null,
    val osVersion: String? = null,
    /** First supported ABI (`arm64-v8a`). */
    val abi: String? = null,
    val ramMb: Int? = null,
    val isLowRamDevice: Boolean? = null,
    /** Build.VERSION.MEDIA_PERFORMANCE_CLASS (0 = none). */
    val mediaPerformanceClass: Int? = null,
    /** Fastest core's cpuinfo_max_freq. */
    val cpuMaxFreqMhz: Int? = null,
    val hardwareConcurrency: Int? = null,
    val deviceMemoryGb: Double? = null,
    val wasmSimd: Boolean? = null,
    val audioWorklet: Boolean? = null,
    val userAgent: String? = null,
    /** Current route is narrowband (Bluetooth SCO / HFP): auto stays on RNNoise. */
    val narrowbandRoute: Boolean = false,
) {
    internal fun matches(r: GravixNsRule): Boolean =
        r.matches(platform = platform, model = model, manufacturer = manufacturer, soc = soc, userAgent = userAgent, osVersion = osVersion)

    companion object {
        /** The fixture / Flutter `fromJson` shape. */
        internal fun fromJson(j: JsonObject): GravixNsDeviceFacts = GravixNsDeviceFacts(
            platform = nsStr(j["platform"]) ?: "other",
            model = nsStr(j["model"]),
            manufacturer = nsStr(j["manufacturer"]),
            soc = nsStr(j["soc"]),
            osVersion = nsStr(j["osVersion"]),
            abi = nsStr(j["abi"]),
            ramMb = nsNum(j["ramMb"])?.toInt(),
            isLowRamDevice = bool(j["isLowRamDevice"]),
            mediaPerformanceClass = nsNum(j["mediaPerformanceClass"])?.toInt(),
            cpuMaxFreqMhz = nsNum(j["cpuMaxFreqMhz"])?.toInt(),
            hardwareConcurrency = nsNum(j["hardwareConcurrency"])?.toInt(),
            deviceMemoryGb = nsNum(j["deviceMemoryGb"]),
            wasmSimd = bool(j["wasmSimd"]),
            audioWorklet = bool(j["audioWorklet"]),
            userAgent = nsStr(j["userAgent"]),
            narrowbandRoute = nsTrue(j["narrowbandRoute"]),
        )

        private fun bool(o: JsonElement?): Boolean? = (o as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
    }
}

/** A cached first-run benchmark of the studio engine on this device. */
data class GravixNsBenchmark @JvmOverloads constructor(
    /** [GravixNsResolver.benchmarkKey] at the time of the run; a different key = stale. */
    val key: String,
    val p95Us: Int,
    /** The output checks passed (a corrupt model, or a model erasing quiet speech, fails them). */
    val outputOk: Boolean = true,
) {
    companion object {
        internal fun fromJson(j: JsonObject): GravixNsBenchmark = GravixNsBenchmark(
            key = nsStr(j["key"]) ?: "",
            p95Us = nsNum(j["p95Us"])?.toInt() ?: 0,
            outputOk = (j["outputOk"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
        )
    }
}

/** Why the resolver (or the live processor) picked the engine. Wire names are shared with Flutter, JS and the logs. */
object GravixNsReason {
    const val MODE_OFF = "mode_off"
    const val KILL_SWITCH = "kill_switch"
    const val KILL_SWITCH_STUDIO = "kill_switch_studio"
    const val POLICY_DENY_STANDARD = "policy_deny_standard"
    const val POLICY_DENY_STUDIO = "policy_deny_studio"
    const val NO_AUDIO_WORKLET = "no_audio_worklet"
    const val MODE_STANDARD = "mode_standard"
    const val MODE_STUDIO = "mode_studio"
    const val STUDIO_UNAVAILABLE = "studio_unavailable"
    const val NARROWBAND_ROUTE = "narrowband_route"
    const val STATIC_ABI = "static_abi"
    const val STATIC_LOW_RAM = "static_low_ram"
    const val STATIC_LOW_RAM_DEVICE = "static_low_ram_device"
    const val STATIC_SLOW_CPU = "static_slow_cpu"
    const val STATIC_OLD_CHIP = "static_old_chip"
    const val STATIC_FEW_CORES = "static_few_cores"
    const val STATIC_LOW_MEMORY = "static_low_memory"
    const val STATIC_NO_SIMD = "static_no_simd"
    const val STATIC_PLATFORM = "static_platform"
    const val BENCHMARK_PENDING = "benchmark_pending"
    const val BENCHMARK_SLOW = "benchmark_slow"
    const val BENCHMARK_BAD_OUTPUT = "benchmark_bad_output"

    /** Explicit studio, but the (cached) benchmark p95 is over the budget. */
    const val STUDIO_TOO_SLOW = "studio_too_slow"

    /** Mid-call: the studio engine's rolling p95 frame time went over the budget -> RNNoise. */
    const val STUDIO_SLOW = "studio_slow"

    /** Mid-call: the studio output speech collapsed vs its input for 2 s of speech -> RNNoise. */
    const val STUDIO_SUPPRESS = "studio_suppress"

    /** Mid-call: [GravixNoiseSuppressionOptions.watchdogConsecutive] frames over the watchdog budget -> RNNoise. */
    const val WATCHDOG_FRAME = "watchdog_frame"
    const val THERMAL = "thermal"
    const val BATTERY_SAVER = "battery_saver"
    const val HEADSET_NS = "headset_ns"

    /** No native processor on this platform / ABI. */
    const val PLATFORM_UNSUPPORTED = "platform_unsupported"

    /** The native processor could not be installed (library missing). */
    const val NATIVE_UNAVAILABLE = "native_unavailable"
    const val AUTO_CAPABLE = "auto_capable"
}

/** The resolver's answer. */
data class GravixNsResolution(
    val engine: GravixNsEngine,
    val reason: String,
    /** auto/studio: run (or re-run) the studio benchmark in the background; RNNoise meanwhile. */
    val needsBenchmark: Boolean = false,
    /** Static check + policy passed (benchmark aside). */
    val studioEligible: Boolean = false,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("engine", engine.wireName)
        put("reason", reason)
        put("needsBenchmark", needsBenchmark)
        put("studioEligible", studioEligible)
    }
}

/** The pure decisions (see the file comment). */
object GravixNsResolver {
    /**
     * Benchmark cache key: re-run after an SDK, model, runtime or OS/build change. Field order and separators are
     * part of the contract with the Flutter and JS SDKs.
     */
    @JvmStatic
    fun benchmarkKey(sdkVersion: String, modelSha256: String, runtimeVersion: String, osBuild: String): String {
        fun esc(s: String) = s.replace("%", "%25").replace("|", "%7C")
        val sha = modelSha256.lowercase()
        return "ns-bench/v1|sdk=${esc(sdkVersion)}|model=${esc(if (sha.length > 16) sha.substring(0, 16) else sha)}" +
            "|rt=${esc(runtimeVersion)}|os=${esc(osBuild)}"
    }

    /** iOS machine id -> A-chip generation (`iPhone11,2` -> 12). Null when unknown. */
    @JvmStatic
    fun iosChipFromModel(model: String?): Int? {
        if (model == null) return null
        val m = Regex("^(iPhone|iPad|iPod)(\\d+),\\d+$").find(model) ?: return null
        val n = m.groupValues[2].toInt()
        return when (m.groupValues[1]) {
            "iPhone" -> n + 1
            "iPad" -> if (n >= 8) 12 + (if (n >= 11) n - 11 else 0) else n + 3
            else -> if (n >= 9) 10 else 8
        }
    }

    /** Static capability for studio; null = capable, else a [GravixNsReason]. */
    @JvmStatic
    fun staticCheck(f: GravixNsDeviceFacts, t: GravixNsThresholds): String? = when (f.platform) {
        "android" -> when {
            f.abi != null && f.abi != "arm64-v8a" -> GravixNsReason.STATIC_ABI
            (f.mediaPerformanceClass ?: 0) >= t.minPerformanceClass!! -> null
            f.isLowRamDevice == true -> GravixNsReason.STATIC_LOW_RAM_DEVICE
            f.ramMb != null && f.ramMb < t.minRamMb!! -> GravixNsReason.STATIC_LOW_RAM
            f.cpuMaxFreqMhz != null && f.cpuMaxFreqMhz < t.minCpuMaxFreqMhz!! -> GravixNsReason.STATIC_SLOW_CPU
            else -> null
        }
        "ios" -> {
            val chip = iosChipFromModel(f.model)
            if (chip != null && chip < t.minIosChip!!) GravixNsReason.STATIC_OLD_CHIP else null
        }
        "web" -> when {
            f.wasmSimd == false -> GravixNsReason.STATIC_NO_SIMD
            f.hardwareConcurrency != null && f.hardwareConcurrency < t.minCores!! -> GravixNsReason.STATIC_FEW_CORES
            f.deviceMemoryGb != null && f.deviceMemoryGb < t.minDeviceMemoryGb!! -> GravixNsReason.STATIC_LOW_MEMORY
            else -> null
        }
        else -> GravixNsReason.STATIC_PLATFORM
    }

    /**
     * Resolves the engine. Order (first hit wins): mode off, kill switch, web without AudioWorklet, standard deny,
     * mode standard; then for studio/auto: studio kill switch, studio deny, (studio) model unavailable, benchmark
     * missing/stale, bad output, slow (studio_too_slow) -> mode_studio; (auto) static check unless allow-listed,
     * narrowband route, model unavailable, benchmark missing/stale, bad output, slow -> auto_capable.
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("ReturnCount")
    fun resolve(
        mode: GravixNoiseSuppressionMode,
        facts: GravixNsDeviceFacts,
        policy: GravixNsPolicy? = null,
        benchmark: GravixNsBenchmark? = null,
        benchmarkKey: String? = null,
        studioAvailable: Boolean = false,
        benchmarkP95Us: Int = 3000,
    ): GravixNsResolution {
        val p = policy ?: GravixNsPolicy.EMPTY
        val t = GravixNsThresholds.DEFAULTS.merge(GravixNsThresholds(benchmarkP95Us = benchmarkP95Us)).merge(p.thresholds)
        val web = facts.platform == "web"
        val fallback = if (web) GravixNsEngine.BROWSER else GravixNsEngine.NONE
        if (mode == GravixNoiseSuppressionMode.OFF) return GravixNsResolution(GravixNsEngine.NONE, GravixNsReason.MODE_OFF)
        if (p.killAll) return GravixNsResolution(fallback, GravixNsReason.KILL_SWITCH)
        if (web && facts.audioWorklet == false) return GravixNsResolution(GravixNsEngine.BROWSER, GravixNsReason.NO_AUDIO_WORKLET)
        if (p.standardDeny.any(facts::matches)) return GravixNsResolution(fallback, GravixNsReason.POLICY_DENY_STANDARD)
        if (mode == GravixNoiseSuppressionMode.STANDARD) return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.MODE_STANDARD)
        if (p.killStudio) return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.KILL_SWITCH_STUDIO)
        if (p.studioDeny.any(facts::matches)) return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.POLICY_DENY_STUDIO)
        if (mode == GravixNoiseSuppressionMode.STUDIO) {
            // explicit studio skips the static check (and the narrowband rule) but still needs the cached benchmark
            if (!studioAvailable) return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.STUDIO_UNAVAILABLE)
            return byBenchmark(benchmark, benchmarkKey, t, GravixNsReason.MODE_STUDIO, GravixNsReason.STUDIO_TOO_SLOW)
        }
        val allowListed = p.studioAllow.any(facts::matches)
        val staticFail = if (allowListed) null else staticCheck(facts, t)
        if (staticFail != null) return GravixNsResolution(GravixNsEngine.RNNOISE, staticFail)
        if (facts.narrowbandRoute) {
            return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.NARROWBAND_ROUTE, studioEligible = true)
        }
        if (!studioAvailable) {
            return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.STUDIO_UNAVAILABLE, studioEligible = true)
        }
        return byBenchmark(benchmark, benchmarkKey, t, GravixNsReason.AUTO_CAPABLE, GravixNsReason.BENCHMARK_SLOW)
    }

    private fun byBenchmark(
        benchmark: GravixNsBenchmark?,
        benchmarkKey: String?,
        t: GravixNsThresholds,
        okReason: String,
        slowReason: String,
    ): GravixNsResolution {
        if (benchmark == null || (benchmarkKey != null && benchmark.key != benchmarkKey)) {
            return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.BENCHMARK_PENDING, needsBenchmark = true, studioEligible = true)
        }
        if (!benchmark.outputOk) {
            return GravixNsResolution(GravixNsEngine.RNNOISE, GravixNsReason.BENCHMARK_BAD_OUTPUT, studioEligible = true)
        }
        if (benchmark.p95Us >= t.benchmarkP95Us!!) return GravixNsResolution(GravixNsEngine.RNNOISE, slowReason, studioEligible = true)
        return GravixNsResolution(GravixNsEngine.DFN3, okReason, studioEligible = true)
    }
}
