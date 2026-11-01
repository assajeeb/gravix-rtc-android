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

import com.gravixcloud.rtc.util.GxLog
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stops a refused microphone start from aborting the process (SIGABRT at
 * jvm.cc:81 under AudioRecordJni::StartRecording; same fix as the Flutter SDK 0.7.5).
 *
 * WebRtcAudioRecord.startRecording() sets nativeCalledStartRecording before it
 * opens the microphone and leaves it set when AudioRecord refuses to start. The
 * native side then has recording_ false, so its StopRecording does not reach
 * Java and nothing clears the flag. The engine's next StartRecording makes Java
 * throw "startRecording called twice without stopRecording", and native code
 * aborts on the pending exception. That next start can come from a mic toggle or
 * from the same negotiation, within milliseconds.
 *
 * WebRTC reports the refusal through its error callback before it returns false,
 * so clearing the flag there turns the next start into a normal retry. The guard
 * wraps the module's callback after the module is built, so a callback set through
 * the module customizer keeps working. The fields are private in a package-private
 * class: reflection, kept by name in consumer-rules.pro.
 */
internal object GxRecordStartGuard {

    /** A failure leaves WebRTC as it was and is only logged. */
    fun installOn(module: AudioDeviceModule) {
        if (module !is JavaAudioDeviceModule) return
        try {
            val input = JavaAudioDeviceModule::class.java.getField("audioInput").get(module)
                ?: throw IllegalStateException("no audio input")
            val callbackField = input.javaClass.getDeclaredField("errorCallback").apply { isAccessible = true }
            val previous = callbackField.get(input) as AudioRecordErrorCallback?
            val flagField = input.javaClass.getDeclaredField("nativeCalledStartRecording").apply { isAccessible = true }
            callbackField.set(input, Callback(flagField.get(input) as AtomicBoolean, previous))
            GxLog.i { "GxRecordStartGuard installed" }
        } catch (t: Throwable) {
            GxLog.w(t) { "GxRecordStartGuard not installed" }
        }
    }

    /**
     * Runs inside startRecordingImpl with WebRTC's recorder locks held, on the
     * native call's stack: it must not block, and nothing may escape it, because
     * an exception here would reach native code and cause the same abort.
     */
    private class Callback(
        private val nativeCalledStart: AtomicBoolean,
        private val previous: AudioRecordErrorCallback?,
    ) : AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordStartError(code: AudioRecordStartErrorCode?, message: String?) {
            nativeCalledStart.set(false)
            chain { it?.onWebRtcAudioRecordStartError(code, message) }
        }

        override fun onWebRtcAudioRecordInitError(message: String?) {
            chain { it?.onWebRtcAudioRecordInitError(message) }
        }

        override fun onWebRtcAudioRecordError(message: String?) {
            chain { it?.onWebRtcAudioRecordError(message) }
        }

        private inline fun chain(call: (AudioRecordErrorCallback?) -> Unit) {
            try {
                call(previous)
            } catch (t: Throwable) {
                GxLog.w(t) { "microphone error callback failed" }
            }
        }
    }
}
