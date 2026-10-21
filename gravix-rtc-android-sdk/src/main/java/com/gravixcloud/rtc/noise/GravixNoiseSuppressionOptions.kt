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

// GRAVIX (2026-10-21): on-device noise suppression -- modes and options. Same shape and wire names as the Flutter
// SDK (lib/src/noise/gravix_noise_options.dart) and the JS SDK (src/core/noise/options.ts); the shared fixture
// ns_policy_cases.json pins the defaults of all of them.

/** What the app asks for. */
enum class GravixNoiseSuppressionMode(val wireName: String) {
    /** No Gravix NS. WebRTC's own NS stays as configured. */
    OFF("off"),

    /** RNNoise (bundled on every ABI with the native library). */
    STANDARD("standard"),

    /** DeepFilterNet3 when its model is available and its cached benchmark passes; RNNoise otherwise. */
    STUDIO("studio"),

    /**
     * RNNoise everywhere, DeepFilterNet3 on devices that pass the static check, the policy and the first-run
     * benchmark. The default of [GravixNoiseSuppressionOptions.mode].
     */
    AUTO("auto"),
    ;

    companion object {
        /** The mode named [name], else null. */
        @JvmStatic
        fun fromWireName(name: String?): GravixNoiseSuppressionMode? = entries.firstOrNull { it.wireName == name }
    }
}

/** The engine actually producing the audio. Wire name = the `eng=` log field. */
enum class GravixNsEngine(val wireName: String) {
    /** No Gravix processing. */
    NONE("off"),

    /** RNNoise. */
    RNNOISE("rnnoise"),

    /** DeepFilterNet3. */
    DFN3("dfn3"),

    /** Web only: the browser's built-in noiseSuppression. */
    BROWSER("browser"),
    ;

    /** One of ours (Gravix processing runs). */
    val isOurs: Boolean get() = this == RNNOISE || this == DFN3

    companion object {
        /** `off` / `none` -> [NONE]; else the engine named [name], or null. */
        @JvmStatic
        fun fromWireName(name: String?): GravixNsEngine? =
            if (name == "off" || name == "none") NONE else entries.firstOrNull { it.wireName == name }
    }
}

/** Where the small digital AGC sits relative to our NS. */
enum class GravixNsAgcOrder(val wireName: String) {
    /** AEC -> our NS -> our AGC + limiter; WebRTC software NS and AGC off. */
    OWN_AFTER_NS("ownAfterNs"),

    /** AEC -> WebRTC AGC -> our NS. */
    WEBRTC_BEFORE_NS("webrtcBeforeNs"),
}

/** Android platform (hardware) noise suppressor while ours runs. */
enum class GravixHwNs(val wireName: String) {
    /** Off while ours runs (default; avoids double processing). */
    OFF("off"),

    /** Leave the platform NS on as well. */
    ON("on"),
}

/** When a Bluetooth headset has its own NS/AEC. */
enum class GravixHeadsetNs(val wireName: String) {
    /** Keep ours on (default). */
    KEEP_OURS("keepOurs"),

    /** Trust the headset: our NS goes to `off` on a Bluetooth route. */
    PREFER_HEADSET("preferHeadset"),
}

/** When the low-latency studio pack may be downloaded (only with [GravixNoiseSuppressionOptions.studioLowLatency]). */
enum class GravixStudioDownload(val wireName: String) {
    NEVER("never"),
    UNMETERED_IDLE("unmeteredIdle"),
    ANY("any"),
}

/** Where the low-latency studio pack comes from. */
enum class GravixStudioSource(val wireName: String) {
    /** Play asset pack `gravix_ns_ll` on Play installs, [GravixNoiseSuppressionOptions.studioPackUrl] otherwise. */
    AUTO("auto"),
    PLAY_ASSET_PACK("playAssetPack"),
    CDN("cdn"),
    APP("app"),
}

/** Telemetry callback of [GravixNoiseSuppressionOptions.onReport]: engine changes (with their reason), benchmark, summaries. */
fun interface GravixNsReportListener {
    fun onReport(report: GravixNsReport)
}

/**
 * Options for on-device noise suppression (`RoomOptions(noiseSuppression = ...)`, or
 * [GravixNoiseSuppression.configure]). Every field has a safe default. On by default: `RoomOptions()` carries
 * `GravixNoiseSuppressionOptions()` (mode auto, 2026-10-21); off = mode [GravixNoiseSuppressionMode.OFF] ([LEGACY]).
 *
 * @property mode requested mode; [GravixNoiseSuppressionMode.AUTO] by default.
 * @property disableWebRtcNs turn WebRTC's software NS off while ours runs (avoids double NS).
 * @property hardwareNs Android platform NS while ours runs. [GravixHwNs.ON] keeps the microphone's NS flag on (the
 *   platform effect when the device has one).
 * @property headsetNs Bluetooth headset with its own NS.
 * @property agcOrder AGC position; [DEFAULT_AGC_ORDER].
 * @property musicBypass crossfade the whole chain to dry while room music plays ([GravixNoiseSuppression.setMusicActive]).
 * @property studioDownload / [studioSource] / [studioPackUrl]: the low-latency studio pack (https only, SHA-256
 *   verified against the compiled-in hash). Used only with [studioLowLatency].
 * @property policyUrl reserved (the remote policy is not fetched by this SDK yet); [policy] wins.
 * @property policy an app-injected policy ([GravixNsPolicy.parse]).
 * @property benchmarkP95Us studio only if the first-run benchmark p95 frame time is below this (us).
 * @property watchdogFrameUs a frame longer than this counts as over budget ...
 * @property watchdogConsecutive ... and this many in a row fall back to RNNoise mid-call.
 * @property thermalFallback RNNoise while the device is thermally throttled (>= MODERATE).
 * @property batterySaverFallback RNNoise in battery saver.
 * @property studioAttenLimitDb studio attenuation limit (dB); 0 = no limit.
 * @property studioP95BudgetUs mid-call studio fallback: rolling p95 of the DFN3 frame time over [studioP95WindowMs]
 *   above this -> RNNoise (reason `studio_slow`). 0 disables it.
 * @property studioP95WindowMs window of that rolling p95.
 * @property studioLowLatency allow the downloaded low-latency (LL) studio model. Off by default (LL over-suppresses
 *   speech in babble); studio then uses the bundled standard model.
 * @property onReport telemetry callback (engine changes and their reasons, benchmark, summary).
 */
data class GravixNoiseSuppressionOptions @JvmOverloads constructor(
    val mode: GravixNoiseSuppressionMode = GravixNoiseSuppressionMode.AUTO,
    val disableWebRtcNs: Boolean = true,
    val hardwareNs: GravixHwNs = GravixHwNs.OFF,
    val headsetNs: GravixHeadsetNs = GravixHeadsetNs.KEEP_OURS,
    val agcOrder: GravixNsAgcOrder = DEFAULT_AGC_ORDER,
    val musicBypass: Boolean = true,
    val studioDownload: GravixStudioDownload = GravixStudioDownload.UNMETERED_IDLE,
    val studioSource: GravixStudioSource = GravixStudioSource.AUTO,
    val studioPackUrl: String? = null,
    val policyUrl: String? = null,
    val policy: GravixNsPolicy? = null,
    val benchmarkP95Us: Int = 3000,
    val watchdogFrameUs: Int = 8000,
    val watchdogConsecutive: Int = 3,
    val thermalFallback: Boolean = true,
    val batterySaverFallback: Boolean = true,
    val studioAttenLimitDb: Double = DEFAULT_STUDIO_ATTEN_LIMIT_DB,
    val studioP95BudgetUs: Int = DEFAULT_STUDIO_P95_BUDGET_US,
    val studioP95WindowMs: Int = DEFAULT_STUDIO_P95_WINDOW_MS,
    val studioLowLatency: Boolean = false,
    val onReport: GravixNsReportListener? = null,
) {
    companion object {
        /**
         * THE default processing order (owner 2026-10-21). Mirrors Flutter `kGravixNsDefaultAgcOrder`, JS
         * `GRAVIX_NS_DEFAULT_AGC_ORDER` and the C core's `GX_NS_DEFAULT_AGC_ORDER`; the shared fixture pins them.
         */
        @JvmField
        val DEFAULT_AGC_ORDER: GravixNsAgcOrder = GravixNsAgcOrder.OWN_AFTER_NS

        /** Studio (DFN3) attenuation limit, dB (`GX_NS_DEFAULT_ATTEN_LIM_DB`). */
        const val DEFAULT_STUDIO_ATTEN_LIMIT_DB: Double = 40.0

        /** Mid-call studio fallback budget (`GX_NS_DEFAULT_EXT_P95_BUDGET_US`). */
        const val DEFAULT_STUDIO_P95_BUDGET_US: Int = 3000

        /** Its window (`GX_NS_DEFAULT_EXT_P95_WINDOW`). */
        const val DEFAULT_STUDIO_P95_WINDOW_MS: Int = 2000

        /** Today's behaviour: no Gravix NS. */
        @JvmField
        val LEGACY = GravixNoiseSuppressionOptions(mode = GravixNoiseSuppressionMode.OFF)
    }
}
