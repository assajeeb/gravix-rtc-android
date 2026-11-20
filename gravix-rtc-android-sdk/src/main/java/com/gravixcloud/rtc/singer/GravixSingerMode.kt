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

package com.gravixcloud.rtc.singer

import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions

// Singer mode (Flutter gravix_rtc, 2026-11-20): an explicit, runtime opt-in for singers and live-stream sound cards
// (V8-type cards, a mixer, a line input). Pro singers heard loud / high notes lowered and notes dropped: the voice chain
// is built for talk. Our NS (RNNoise) erases sung fundamentals over ~523 Hz, our AGC pulls loud singing down, WebRTC's
// APM and the phone's VOICE_COMMUNICATION path (its own AEC/NS/AGC in the HAL) do the same again, and DTX plus a voice
// bitrate trim the rest.
//
// On (at runtime, mid-room; the audio device module is not rebuilt):
//  - our chain bypassed: no NS, no AGC, no pause expander; only the limiter near clip stays;
//  - WebRTC voice processing off: NS, AGC, high-pass, and echo cancellation unless [GravixSingerOptions.echoCancellation]
//    (software, and the phone's built-in AEC / NS effects on the recorder);
//  - the recorder re-opened with [GravixSingerOptions.captureSource] (MIC by default, not the call path): a capture
//    restart of tens to a few hundred ms;
//  - the sender's bitrate raised to [GravixSingerOptions.maxBitrate] (96 kbps mono by default, no republish);
//  - DTX off ([GravixSingerOptions.disableDtx]): the only step that republishes the microphone (an audible gap of
//    ~100-300 ms; listeners see the track change).
// Off restores what the microphone had before.
//
// Not changed: the capture rate (fixed when WebRTC initialises) and the audio mode (the audio session's). Never switched
// on by the SDK. Echo risk: with echo cancellation off, what the loudspeaker plays goes back into the microphone. Singer
// mode is for headphones / a sound card monitor; on the loudspeaker keep [GravixSingerOptions.echoCancellation] true.

/** The Android capture source singer mode records from. */
enum class GravixSingerCaptureSource(val wireName: String) {
    /** Leave the source as it is (VOICE_COMMUNICATION under the SDK's defaults). */
    KEEP("keep"),

    /** MIC (default): no call-path processing on most phones. */
    MIC("mic"),

    /** VOICE_RECOGNITION: the CDD requires noise reduction and AGC off on it. */
    VOICE_RECOGNITION("voiceRecognition"),

    /** UNPROCESSED where the phone supports it (API 24+), else VOICE_RECOGNITION. Flat and raw; quiet on some phones. */
    UNPROCESSED("unprocessed"),
}

/**
 * What [com.gravixcloud.rtc.room.Room.setSingerMode] changes.
 *
 * @property echoCancellation keep echo cancellation (WebRTC's and the phone's) on. Default false: for headphones or a
 * sound card. True on the loudspeaker.
 * @property captureSource the capture source while singer mode is on.
 * @property disableDtx Opus DTX off while singer mode is on (a microphone republish; false keeps the track as published).
 * @property maxBitrate the microphone sender's maximum bitrate (bps, mono Opus).
 */
data class GravixSingerOptions @JvmOverloads constructor(
    val echoCancellation: Boolean = false,
    val captureSource: GravixSingerCaptureSource = GravixSingerCaptureSource.MIC,
    val disableDtx: Boolean = true,
    val maxBitrate: Int = 96_000,
) {
    /** The WebRTC voice processing singer mode asks for, on top of the microphone's [base] options. */
    fun processing(base: LocalAudioTrackOptions = LocalAudioTrackOptions()): LocalAudioTrackOptions = base.copy(
        echoCancellation = echoCancellation,
        noiseSuppression = false,
        autoGainControl = false,
        highPassFilter = false,
    )
}

/**
 * What [com.gravixcloud.rtc.room.Room.setSingerMode] did.
 *
 * @property on singer mode is on.
 * @property options its options (null when off).
 * @property micPublished a microphone was published (false: everything applies at its publish).
 * @property processingApplied WebRTC's voice processing took the singer (or the restored) options.
 * @property nativeApplied the native side took it (our chain bypass; the capture source is requested: the recorder
 * restarts asynchronously and falls back to its original source if the phone refuses the new one).
 * @property bitrateApplied the sender's bitrate was set.
 * @property republished the microphone was republished (the DTX change).
 * @property notes what did not apply, and why.
 */
data class GravixSingerModeState(
    val on: Boolean,
    val options: GravixSingerOptions? = null,
    val micPublished: Boolean = false,
    val processingApplied: Boolean = false,
    val nativeApplied: Boolean = false,
    val bitrateApplied: Boolean = false,
    val republished: Boolean = false,
    val notes: List<String> = emptyList(),
)
