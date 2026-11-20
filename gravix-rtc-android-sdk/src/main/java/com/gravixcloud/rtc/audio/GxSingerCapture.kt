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

import android.media.AudioRecord
import com.gravixcloud.rtc.util.GxLog
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singer mode's capture side on WebRTC's recorder (the Flutter SDK's GxCaptureSwitcher, singer part only):
 *
 * - capture source: libwebrtc fixes it when the audio device module is built (`WebRtcAudioRecord.audioSource` is private
 *   final, read each time it opens an AudioRecord), so it is rewritten by reflection and a running recorder restarted
 *   under WebRTC's own lock, the same sequence its stop/init/start paths run. A refused open falls back to the original
 *   source. Not recording: the next start opens with the new source.
 * - built-in effects: this SDK builds the module with the platform AEC / NS on (API 29+). They are attached to every
 *   AudioRecord session WebRTC opens, so singer mode turns them off on WebRtcAudioEffects (live, and for the next open)
 *   and keeps the AEC only when echo cancellation is asked for. Off: back to what the module had.
 *
 * A custom (non-Java) audio device module: nothing to do, [apply] returns false. All recorder work runs on one worker
 * thread, never on the caller's. The field / method names are kept in consumer-rules.pro.
 */
internal class GxSingerCapture(private val adm: () -> AudioDeviceModule?) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "gravix-singer-capture") }
    private var originalSource: Int? = null
    private var originalAec: Boolean? = null
    private var originalNs: Boolean? = null

    /**
     * Singer mode on ([source]: null = keep; [keepAec]) or off ([on] false). False: no Java audio device module.
     * The recorder work is queued: a refused source falls back to the original one there.
     */
    fun apply(on: Boolean, source: Int?, keepAec: Boolean): Boolean {
        if (adm() !is JavaAudioDeviceModule) return false
        try {
            worker.execute {
                try {
                    applyOnWorker(on, source, keepAec)
                } catch (t: Throwable) {
                    GxLog.w(t) { "singer capture failed" }
                }
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return true
    }

    fun release() {
        worker.shutdown()
    }

    private fun applyOnWorker(on: Boolean, source: Int?, keepAec: Boolean) {
        val input = webRtcAudioRecord() ?: return
        val effects = try {
            field(input, "effects").get(input)
        } catch (_: Throwable) {
            null
        }
        if (effects != null) setEffects(effects, on, keepAec)
        val original = originalSource ?: readSource(input)?.also { originalSource = it } ?: return
        val target = if (on) source ?: original else original
        if (readSource(input) != target) switchSource(input, target, original)
        GxLog.i { "singer capture on=$on source=${readSource(input)} (original $original)" }
    }

    private fun webRtcAudioRecord(): Any? = try {
        val m = adm()
        JavaAudioDeviceModule::class.java.getField("audioInput").get(m)
    } catch (_: Throwable) {
        null
    }

    // ── built-in effects ────────────────────────────────────────────────────────────────────────────

    private fun setEffects(effects: Any, on: Boolean, keepAec: Boolean) {
        val cls = effects.javaClass
        try {
            if (originalAec == null) originalAec = cls.getMethod("shouldEnableAEC").invoke(effects) as Boolean
            if (originalNs == null) originalNs = cls.getMethod("shouldEnableNS").invoke(effects) as Boolean
            val aec = if (on) keepAec && originalAec == true else originalAec == true
            val ns = if (on) false else originalNs == true
            // live on an attached effect, else the flag the next open reads
            cls.getMethod("setAEC", Boolean::class.javaPrimitiveType).invoke(effects, aec)
            cls.getMethod("setNS", Boolean::class.javaPrimitiveType).invoke(effects, ns)
            cls.getDeclaredField("shouldEnableAec").apply { isAccessible = true }.setBoolean(effects, aec)
            cls.getDeclaredField("shouldEnableNs").apply { isAccessible = true }.setBoolean(effects, ns)
            GxLog.i { "singer capture platform effects aec=$aec ns=$ns" }
        } catch (t: Throwable) {
            GxLog.w(t) { "singer capture: platform effects unchanged" }
        }
    }

    // ── capture source ──────────────────────────────────────────────────────────────────────────────

    private fun field(input: Any, name: String) = input.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun recorder(input: Any): AudioRecord? = try {
        field(input, "audioRecord").get(input) as? AudioRecord
    } catch (_: Throwable) {
        null
    }

    private fun readSource(input: Any): Int? = try {
        field(input, "audioSource").getInt(input)
    } catch (t: Throwable) {
        GxLog.w(t) { "singer capture: source unreadable" }
        null
    }

    private fun switchSource(input: Any, target: Int, original: Int) {
        val cls = input.javaClass
        val lock = field(input, "audioRecordStateLock").get(input) ?: return
        synchronized(lock) {
            val sourceField = field(input, "audioSource")
            val running = recorder(input) != null && field(input, "audioThread").get(input) != null
            sourceField.setInt(input, target)
            if (!running) {
                GxLog.i { "singer capture source -> $target (applies at the next start)" }
                return
            }
            val sampleRate = field(input, "sampleRate").getInt(input)
            val channels = field(input, "channelCount").getInt(input)
            val client = field(input, "clientCalledStartRecording").get(input) as AtomicBoolean
            val native = field(input, "nativeCalledStartRecording").get(input) as AtomicBoolean
            val c0 = client.get()
            val n0 = native.get()
            val stop = cls.getDeclaredMethod("stopRecordingIfNeededImpl").apply { isAccessible = true }
            val init = cls.getDeclaredMethod(
                "initRecordingImpl",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val start = cls.getDeclaredMethod("startRecordingImpl").apply { isAccessible = true }

            // stopRecordingIfNeededImpl only stops when nobody asked for recording: clear both flags for the call and
            // restore them right after (the same lock WebRTC's own start/stop paths take is held)
            fun stopNow() {
                client.set(false)
                native.set(false)
                try {
                    stop.invoke(input)
                } finally {
                    client.set(c0)
                    native.set(n0)
                }
            }
            val t0 = System.nanoTime()
            stopNow()
            // true = re-cache the new direct buffer's address in the native recorder
            val open = { init.invoke(input, sampleRate, channels, true) as Int >= 0 && start.invoke(input) as Boolean }
            var started = open()
            if (!started && target != original) {
                GxLog.w { "singer capture source $target refused: back to $original" }
                started = reopenWith(input, original, sourceField, ::stopNow, open)
            }
            // a refused start went through GxRecordStartGuard (it clears the native flag); a good one must keep it set
            if (started) native.set(n0)
            val ms = (System.nanoTime() - t0) / 1_000_000
            GxLog.i {
                "singer capture source -> ${sourceField.getInt(input)} restarted=$started in $ms ms " +
                    "(rate=$sampleRate ch=$channels session=${recorder(input)?.audioSessionId})"
            }
        }
    }

    private fun reopenWith(
        input: Any,
        source: Int,
        sourceField: java.lang.reflect.Field,
        stopNow: () -> Unit,
        open: () -> Boolean,
    ): Boolean {
        try {
            if (recorder(input) != null) stopNow()
        } catch (t: Throwable) {
            GxLog.w(t) { "singer capture: stop before the fallback failed" }
        }
        sourceField.setInt(input, source)
        return open()
    }
}
