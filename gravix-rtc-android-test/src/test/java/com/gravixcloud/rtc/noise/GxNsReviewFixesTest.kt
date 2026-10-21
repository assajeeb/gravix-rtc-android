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

import com.gravixcloud.rtc.audio.AudioProcessorInterface
import com.gravixcloud.rtc.audio.AuthedAudioProcessorInterface
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** GRAVIX (review 2026-10-21): one test per finding fixed on feat/ns (#1, #9, #10, #13, #14, watchdog grace, authenticate). */
class GxNsReviewFixesTest {
    @After
    fun tearDown() {
        GravixNoiseSuppression.testSet(null, GravixNsEngine.NONE)
        GxNoiseProcessor.chained = null
    }

    @Test
    fun agcOnlyWhileOursIsRequested() { // #1
        assertFalse(GxNsAgc.enabled(GxNsNative.ENGINE_OFF, "ownAfterNs"))
        assertFalse(GxNsAgc.enabled(GxNsNative.ENGINE_OFF, null))
        assertTrue(GxNsAgc.enabled(GxNsNative.ENGINE_RNNOISE, "ownAfterNs"))
        assertTrue(GxNsAgc.enabled(GxNsNative.ENGINE_DFN3, null))
        assertFalse(GxNsAgc.enabled(GxNsNative.ENGINE_RNNOISE, "webrtcBeforeNs"))
        assertFalse(GxNsAgc.enabled(GxNsNative.ENGINE_DFN3, "webrtcBeforeNs"))
    }

    @Test
    fun throwingStudioTaskStillReplies() { // #9
        var reply: Map<String, Any?>? = null
        gxNsGuarded("bench", { reply = it }) { throw IllegalStateException("boom") }
        assertEquals(false, reply!!["ok"])
        assertEquals("bench_error: IllegalStateException", reply!!["reason"])
        gxNsGuarded("bench", { reply = it }) { mapOf("ok" to true) }
        assertEquals(true, reply!!["ok"])
    }

    @Test
    fun passThroughGivesWebRtcProcessingBack() { // #10
        val base = LocalAudioTrackOptions()
        GravixNoiseSuppression.testSet(GravixNoiseSuppressionOptions(), GravixNsEngine.RNNOISE)
        val ours = GravixNoiseSuppression.adjustCapture(base)
        assertFalse(ours.noiseSuppression)
        assertFalse(ours.autoGainControl)
        GravixNoiseSuppression.onEvent(mapOf("step" to "init", "shapeReject" to "buffer_not_direct", "eng" to "off"))
        assertEquals(base, GravixNoiseSuppression.adjustCapture(base))
        assertEquals(GravixNsEngine.NONE, GravixNoiseSuppression.state.value.engine)
        // the shape is processable again: back to ours
        GravixNoiseSuppression.onEvent(mapOf("step" to "init", "shapeReject" to null))
        assertFalse(GravixNoiseSuppression.adjustCapture(base).noiseSuppression)
        // other events never touch it
        GravixNoiseSuppression.onEvent(mapOf("step" to "summary", "p95Us" to 900))
        assertFalse(GravixNoiseSuppression.adjustCapture(base).noiseSuppression)
        assertEquals(GravixNsEngine.NONE, GravixNsCapture.effectiveEngine(GravixNsEngine.DFN3, shapeRejected = true))
        assertEquals(GravixNsEngine.DFN3, GravixNsCapture.effectiveEngine(GravixNsEngine.DFN3, shapeRejected = false))
    }

    @Test
    fun packDownloadRules() { // #13
        assertTrue(GxNsPackRules.httpsOnly("https://cdn.example/ns"))
        assertTrue(GxNsPackRules.httpsOnly(" HTTPS://cdn.example/ns"))
        assertFalse(GxNsPackRules.httpsOnly("http://192.168.0.158:8099"))
        assertFalse(GxNsPackRules.httpsOnly("file:///sdcard/x.ort"))
        assertFalse(GxNsPackRules.httpsOnly(null))
        val out = ByteArrayOutputStream()
        assertEquals(10L, GxNsPackRules.copyCapped(ByteArrayInputStream(ByteArray(10)), out, 10))
        assertEquals(10, out.size())
        val big = object : java.io.InputStream() { // endless: must stop at the cap, not read on
            var reads = 0L
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads += len
                check(reads < 10_000_000) { "read past the cap" }
                return len
            }
        }
        assertEquals(-1L, GxNsPackRules.copyCapped(big, ByteArrayOutputStream(), 100_000))
    }

    @Test
    fun retiredEngineFreedAtOnceWithoutABlockInFlight() { // #14
        assertTrue(GxNsRetire.freeNow(0))
        assertFalse(GxNsRetire.freeNow(1))
        assertFalse(GxNsRetire.freeAfterWait(41, 41))
        assertTrue(GxNsRetire.freeAfterWait(41, 42))
    }

    @Test
    fun watchdogIgnoresTheStartGrace() { // minor: narrow window right after configure
        val w = GxNsWatchdog(1_000_000L, 3, graceFrames = 100)
        repeat(100) { assertFalse(w.onFrame(50_000_000L)) }
        assertFalse(w.tripped)
        assertFalse(w.onFrame(5_000_000L))
        assertFalse(w.onFrame(5_000_000L))
        assertTrue(w.onFrame(5_000_000L))
        w.rearm()
        repeat(100) { assertFalse(w.onFrame(50_000_000L)) } // grace again after a rearm
        assertEquals(100, GxNsWatchdog.START_GRACE_FRAMES)
    }

    @Test
    fun chainedAppProcessorIsAuthenticatedAndRuns() { // minor: single-slot chaining
        var auth: Pair<String, String>? = null
        var frames = 0
        val app = object : AuthedAudioProcessorInterface {
            override fun authenticate(url: String, token: String) {
                auth = url to token
            }
            override fun isEnabled() = true
            override fun getName() = "krisp_noise_cancellation"
            override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {}
            override fun resetAudioProcessing(newRate: Int) {}
            override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
                frames++
            }
        }
        GxNoiseProcessor.chained = app
        // the room authenticates whatever sits in the slot: ours, which forwards
        (GxNoiseProcessor as AuthedAudioProcessorInterface).authenticate("wss://x", "t")
        assertEquals("wss://x" to "t", auth)
        assertEquals("krisp_noise_cancellation", GxNoiseProcessor.getName())
        GxNoiseProcessor.processAudio(1, 480, ByteBuffer.allocateDirect(1920))
        assertEquals(1, frames)
        GxNoiseProcessor.chained = object : AudioProcessorInterface by app {}
        (GxNoiseProcessor as AuthedAudioProcessorInterface).authenticate("wss://y", "u") // not authed: nothing
        assertEquals("wss://x" to "t", auth)
        GxNoiseProcessor.chained = null
        assertEquals(GxNoiseProcessor.NAME, GxNoiseProcessor.getName())
        assertNull(GxNoiseProcessor.chained)
    }
}
