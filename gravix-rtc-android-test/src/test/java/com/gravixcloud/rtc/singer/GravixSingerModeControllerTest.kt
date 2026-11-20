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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Singer mode's state machine with a fake microphone (the Flutter SDK's singer_mode_test without the room-music cases:
 * this SDK has no room music engine).
 */
class GravixSingerModeControllerTest {

    private val talk = LocalAudioTrackOptions()

    private class Wire(var processing: LocalAudioTrackOptions) {
        var dtx = true
        var bitrate: Int? = 64_000
        var republishes = 0
        var failRepublish = false
    }

    private class FakeMic(val w: Wire) : GravixSingerMic {
        override val processing get() = w.processing
        override fun setProcessing(o: LocalAudioTrackOptions): Boolean {
            w.processing = o
            return true
        }
        override val dtx get() = w.dtx
        override val maxBitrate get() = w.bitrate
        override fun setMaxBitrate(bps: Int?): Boolean {
            w.bitrate = bps
            return true
        }
        override suspend fun republish(dtx: Boolean, maxBitrate: Int?): GravixSingerMic? {
            if (w.failRepublish) return null
            w.republishes++
            w.dtx = dtx
            w.bitrate = maxBitrate
            return FakeMic(w)
        }
    }

    private lateinit var w: Wire
    private var micUp = true
    private val nativeCalls = mutableListOf<Pair<Boolean, GravixSingerCaptureSource>>()
    private var nativeOk = true
    private val profiles = mutableListOf<GravixSingerOptions?>()
    private lateinit var c: GravixSingerModeController

    @Before
    fun setUp() {
        w = Wire(talk)
        micUp = true
        nativeCalls.clear()
        nativeOk = true
        profiles.clear()
        c = GravixSingerModeController(
            mic = { if (micUp) FakeMic(w) else null },
            native = { on, source ->
                nativeCalls.add(on to source)
                nativeOk
            },
            defaults = { GravixSingerProfile(talk, dtx = true, bitrate = 64_000) },
            publishProfile = { profiles.add(it) },
        )
    }

    @Test
    fun onTurnsProcessingOffRepublishesWithoutDtxAndOffRestores() = runTest {
        val s = c.set(true)
        assertTrue(s.on)
        assertTrue(s.republished)
        assertFalse(w.processing.noiseSuppression)
        assertFalse(w.processing.autoGainControl)
        assertFalse(w.processing.highPassFilter)
        assertFalse(w.processing.echoCancellation)
        assertFalse(w.dtx)
        assertEquals(96_000, w.bitrate)
        assertEquals(1, w.republishes)
        assertEquals(listOf(true to GravixSingerCaptureSource.MIC), nativeCalls)
        assertEquals(listOf<GravixSingerOptions?>(GravixSingerOptions()), profiles)

        val off = c.set(false)
        assertFalse(off.on)
        assertEquals(talk, w.processing)
        assertTrue(w.dtx)
        assertEquals(64_000, w.bitrate)
        assertEquals(2, w.republishes)
        assertEquals(false to GravixSingerCaptureSource.KEEP, nativeCalls.last())
        assertEquals(null, profiles.last())
        assertFalse(c.active)
    }

    @Test
    fun keepDtxSetsTheBitrateOnTheSenderAndKeepsEchoCancellationWhenAsked() = runTest {
        val s = c.set(true, GravixSingerOptions(disableDtx = false, echoCancellation = true, maxBitrate = 128_000))
        assertFalse(s.republished)
        assertTrue(s.bitrateApplied)
        assertTrue(w.dtx)
        assertEquals(128_000, w.bitrate)
        assertTrue(w.processing.echoCancellation)
        assertFalse(w.processing.noiseSuppression)
        c.set(false)
        assertEquals(64_000, w.bitrate)
        assertEquals(0, w.republishes)
    }

    @Test
    fun noMicrophoneYetAppliesAtItsPublish() = runTest {
        micUp = false
        val s = c.set(true)
        assertTrue(s.on)
        assertFalse(s.micPublished)
        assertTrue(s.notes.isNotEmpty())
        assertEquals(listOf(true to GravixSingerCaptureSource.MIC), nativeCalls)
        micUp = true
        c.onMicPublished()
        assertFalse(w.processing.noiseSuppression)
        assertFalse(w.dtx)
        c.set(false)
        assertEquals(talk, w.processing)
        assertTrue(w.dtx)
    }

    @Test
    fun aNewMicrophoneWhileOnGetsTheProfileWithoutAnotherRepublish() = runTest {
        c.set(true)
        w.processing = talk // a rejoin created the track with the room defaults
        w.bitrate = 96_000 // published with singer mode's publish profile
        w.dtx = false
        val before = w.republishes
        c.onMicPublished()
        assertFalse(w.processing.noiseSuppression)
        assertEquals(before, w.republishes)
    }

    @Test
    fun failuresAreReportedNotThrown() = runTest {
        nativeOk = false
        w.failRepublish = true
        val s = c.set(true)
        assertTrue(s.on)
        assertFalse(s.nativeApplied)
        assertFalse(s.republished)
        assertTrue(s.notes.joinToString(" ").contains("DTX"))
        assertTrue(s.notes.joinToString(" ").contains("native"))
        assertEquals(96_000, w.bitrate) // the sender still takes the rate
    }

    @Test
    fun nativeThrowingIsANote() = runTest {
        val throwing = GravixSingerModeController(
            mic = { FakeMic(w) },
            native = { _, _ -> error("boom") },
            defaults = { GravixSingerProfile(talk, true, 64_000) },
        )
        val s = throwing.set(true)
        assertTrue(s.on)
        assertFalse(s.nativeApplied)
        assertTrue(s.notes.any { it.contains("boom") })
    }

    @Test
    fun callsAreSerialisedTheLastOneWins() = runTest {
        val calls = listOf(
            async { c.set(true) },
            async { c.set(false) },
            async { c.set(true, GravixSingerOptions(captureSource = GravixSingerCaptureSource.UNPROCESSED)) },
        )
        calls.awaitAll()
        assertTrue(c.active)
        assertEquals(GravixSingerCaptureSource.UNPROCESSED, c.options!!.captureSource)
        assertEquals(true to GravixSingerCaptureSource.UNPROCESSED, nativeCalls.last())
    }

    @Test
    fun processingKeepsTheOtherOptions() {
        val base = LocalAudioTrackOptions(typingNoiseDetection = false)
        val p = GravixSingerOptions(echoCancellation = true).processing(base)
        val want = LocalAudioTrackOptions(
            noiseSuppression = false,
            echoCancellation = true,
            autoGainControl = false,
            highPassFilter = false,
            typingNoiseDetection = false,
        )
        assertEquals(want, p)
        assertFalse(GravixSingerOptions().processing().echoCancellation)
    }
}
