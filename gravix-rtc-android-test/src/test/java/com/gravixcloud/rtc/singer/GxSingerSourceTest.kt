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

import android.media.MediaRecorder
import com.gravixcloud.rtc.audio.GxSingerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GxSingerSourceTest {
    @Test
    fun sourcesMapToAudioSources() {
        assertEquals(GxSingerSource.MIC, GxSingerSource.resolve(GravixSingerCaptureSource.MIC, 34, false))
        assertEquals(GxSingerSource.VOICE_RECOGNITION, GxSingerSource.resolve(GravixSingerCaptureSource.VOICE_RECOGNITION, 34, false))
        assertEquals(GxSingerSource.UNPROCESSED, GxSingerSource.resolve(GravixSingerCaptureSource.UNPROCESSED, 34, true))
    }

    @Test
    fun unprocessedFallsBackToVoiceRecognition() {
        assertEquals(GxSingerSource.VOICE_RECOGNITION, GxSingerSource.resolve(GravixSingerCaptureSource.UNPROCESSED, 34, false))
        assertEquals(GxSingerSource.VOICE_RECOGNITION, GxSingerSource.resolve(GravixSingerCaptureSource.UNPROCESSED, 23, true))
    }

    @Test
    fun keepLeavesTheSource() {
        assertNull(GxSingerSource.resolve(GravixSingerCaptureSource.KEEP, 34, true))
        assertNull(GxSingerSource.resolve(null, 34, true))
    }

    @Test
    fun constantsMatchMediaRecorder() {
        assertEquals(MediaRecorder.AudioSource.MIC, GxSingerSource.MIC)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, GxSingerSource.VOICE_RECOGNITION)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, GxSingerSource.VOICE_COMMUNICATION)
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, GxSingerSource.UNPROCESSED)
    }

    @Test
    fun wireNamesMatchFlutter() {
        assertEquals(
            listOf("keep", "mic", "voiceRecognition", "unprocessed"),
            GravixSingerCaptureSource.entries.map { it.wireName },
        )
    }
}
