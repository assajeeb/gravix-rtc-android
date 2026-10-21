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

import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions

/**
 * GRAVIX (2026-10-21): WebRTC processing while OUR noise suppression runs (the Flutter SDK's
 * gravix_noise_capture.dart). Decided before the capture starts: the Android platform NS/AEC effects are bound when
 * the AudioRecord is created.
 *
 * - not ours (off / unsupported): [LocalAudioTrackOptions] unchanged.
 * - [GravixNoiseSuppressionOptions.disableWebRtcNs]: WebRTC NS off, unless [GravixHwNs.ON] (the NS flag stays on).
 * - [GravixNsAgcOrder.OWN_AFTER_NS]: WebRTC AGC off (ours runs after our NS); [GravixNsAgcOrder.WEBRTC_BEFORE_NS]: as asked.
 * - an app that already turned NS / AGC off keeps them off.
 */
internal object GravixNsCapture {
    /** The processor passes frames through ([shapeRejected]): none of ours runs, whatever was resolved. */
    fun effectiveEngine(resolved: GravixNsEngine, shapeRejected: Boolean): GravixNsEngine =
        if (shapeRejected) GravixNsEngine.NONE else resolved

    fun options(base: LocalAudioTrackOptions, ns: GravixNoiseSuppressionOptions, engine: GravixNsEngine): LocalAudioTrackOptions {
        if (!engine.isOurs) return base
        val nsOn = base.noiseSuppression && !(ns.disableWebRtcNs && ns.hardwareNs != GravixHwNs.ON)
        val agcOn = if (ns.agcOrder == GravixNsAgcOrder.OWN_AFTER_NS) false else base.autoGainControl
        return base.copy(noiseSuppression = nsOn, autoGainControl = agcOn)
    }
}
