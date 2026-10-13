/*
 * Copyright 2023-2026 LiveKit, Inc.
 * Modifications Copyright 2026 Gravity Compile
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

package com.gravixcloud.rtc.test.mock

import org.webrtc.AudioTrack
import org.webrtc.audio.AudioProcessingOptions
import org.webrtc.audio.AudioProcessingOptionsResult

class MockAudioStreamTrack(
    val id: String = "id",
    val kind: String = AUDIO_TRACK_KIND,
    var enabled: Boolean = true,
    var state: State = State.LIVE,
) : AudioTrack(1L) {

    var disposed = false
    var lastAudioProcessingOptions: AudioProcessingOptions? = null
    var audioProcessingOptionsResult: AudioProcessingOptionsResult = AudioProcessingOptionsResult.stored()

    override fun setAudioProcessingOptions(options: AudioProcessingOptions): AudioProcessingOptionsResult {
        lastAudioProcessingOptions = options
        return audioProcessingOptionsResult
    }

    override fun id(): String = id

    override fun kind(): String = kind

    override fun enabled(): Boolean = enabled

    override fun setEnabled(enable: Boolean): Boolean {
        enabled = enable
        return true
    }

    override fun state(): State {
        return state
    }

    override fun dispose() {
        if (disposed) {
            throw IllegalStateException("already disposed")
        }
        disposed = true
    }

    override fun isDisposed(): Boolean = disposed

    override fun setVolume(volume: Double) {
    }
}
