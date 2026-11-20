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

package com.gravixcloud.rtc.audio

import com.gravixcloud.rtc.singer.GravixSingerCaptureSource

/**
 * The capture source singer mode records from (the Flutter SDK's GxSingerSource). The SDK builds its recorder with
 * VOICE_COMMUNICATION, the call path: on most phones the HAL runs its own echo canceller, noise suppressor and AGC there
 * whatever the app asks. [GxSingerCapture] re-opens the recorder with the source below.
 *
 * - MIC (1), the default.
 * - VOICE_RECOGNITION (6): the CDD requires noise reduction and AGC off on it.
 * - UNPROCESSED (9) where the phone says it supports it (API 24+), else VOICE_RECOGNITION.
 * - KEEP: no change (null).
 *
 * The constants are MediaRecorder.AudioSource's, as plain ints so the JVM unit tests need no Android framework.
 */
internal object GxSingerSource {
    const val MIC = 1
    const val VOICE_RECOGNITION = 6
    const val VOICE_COMMUNICATION = 7
    const val UNPROCESSED = 9

    fun resolve(source: GravixSingerCaptureSource?, sdkInt: Int, unprocessedSupported: Boolean): Int? = when (source) {
        GravixSingerCaptureSource.MIC -> MIC
        GravixSingerCaptureSource.VOICE_RECOGNITION -> VOICE_RECOGNITION
        GravixSingerCaptureSource.UNPROCESSED -> if (sdkInt >= 24 && unprocessedSupported) UNPROCESSED else VOICE_RECOGNITION
        GravixSingerCaptureSource.KEEP, null -> null
    }
}
