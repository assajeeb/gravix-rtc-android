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
import com.gravixcloud.rtc.util.GxLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The published microphone as singer mode drives it (a seam for tests). */
internal interface GravixSingerMic {
    val processing: LocalAudioTrackOptions
    fun setProcessing(o: LocalAudioTrackOptions): Boolean

    /** DTX as published. */
    val dtx: Boolean

    /** The sender's max bitrate (null: no cap / unknown). */
    val maxBitrate: Int?
    fun setMaxBitrate(bps: Int?): Boolean

    /** Republishes with [dtx] / [maxBitrate]; null when it failed (the old publication stays). */
    suspend fun republish(dtx: Boolean, maxBitrate: Int?): GravixSingerMic?
}

/** The microphone's profile without singer mode. */
internal data class GravixSingerProfile(val processing: LocalAudioTrackOptions, val dtx: Boolean, val bitrate: Int?)

/**
 * Singer mode's state machine (one per Room), the Flutter SDK's GravixSingerModeController without the room-music
 * hand-over (this SDK has no room music engine). Calls are serialised (a fair mutex): the last one wins.
 */
internal class GravixSingerModeController(
    /** The published microphone (null: none). */
    private val mic: () -> GravixSingerMic?,
    /** The native side: (on, source) -> applied. */
    private val native: (Boolean, GravixSingerCaptureSource) -> Boolean,
    /** The microphone's profile without singer mode: what it is restored to when it was published while on. */
    private val defaults: () -> GravixSingerProfile,
    /** Singer mode's publish profile for every later microphone publish (null: back to the app's). */
    private val publishProfile: (GravixSingerOptions?) -> Unit = {},
) {
    private val lock = Mutex()

    @Volatile
    var options: GravixSingerOptions? = null
        private set
    private var before: GravixSingerProfile? = null

    // one DTX republish per enable (a failed one is not retried on every publish)
    private var dtxTried = false

    val active: Boolean get() = options != null

    suspend fun set(on: Boolean, o: GravixSingerOptions = GravixSingerOptions()): GravixSingerModeState =
        lock.withLock { if (on) enable(o) else disable() }

    private suspend fun enable(o: GravixSingerOptions): GravixSingerModeState {
        val notes = mutableListOf<String>()
        val m = mic()
        if (m != null && before == null) before = GravixSingerProfile(m.processing, m.dtx, m.maxBitrate)
        options = o
        dtxTried = false
        publishProfile(o)
        val nativeOk = native(true, o.captureSource, notes)
        if (m == null) {
            notes.add("no microphone published: applies when it is")
            return GravixSingerModeState(on = true, options = o, nativeApplied = nativeOk, notes = notes)
        }
        val r = applyTo(m, o, notes)
        return GravixSingerModeState(
            on = true,
            options = o,
            micPublished = true,
            processingApplied = r.processing,
            nativeApplied = nativeOk,
            bitrateApplied = r.bitrate,
            republished = r.republished,
            notes = notes,
        )
    }

    private data class Applied(val processing: Boolean, val bitrate: Boolean, val republished: Boolean)

    private suspend fun applyTo(m: GravixSingerMic, o: GravixSingerOptions, notes: MutableList<String>): Applied {
        var processing = true
        val want = o.processing(m.processing)
        if (!sameProcessing(m.processing, want)) {
            processing = m.setProcessing(want)
            if (!processing) notes.add("voice processing not changed")
        }
        if (o.disableDtx && m.dtx && !dtxTried) {
            dtxTried = true
            val fresh = m.republish(dtx = false, maxBitrate = o.maxBitrate)
            if (fresh != null) return Applied(processing, bitrate = true, republished = true)
            notes.add("DTX unchanged (republish failed)")
        }
        val bitrate = setBitrate(m, o.maxBitrate, notes)
        return Applied(processing, bitrate, republished = false)
    }

    private suspend fun disable(): GravixSingerModeState {
        val notes = mutableListOf<String>()
        if (options == null) return GravixSingerModeState(on = false)
        options = null
        val b = before ?: defaults()
        before = null
        publishProfile(null)
        val nativeOk = native(false, GravixSingerCaptureSource.KEEP, notes)
        val m = mic() ?: return GravixSingerModeState(on = false, nativeApplied = nativeOk, notes = notes)
        val processing = sameProcessing(m.processing, b.processing) || m.setProcessing(b.processing)
        if (!processing) notes.add("voice processing not restored")
        var republished = false
        if (b.dtx != m.dtx) {
            republished = m.republish(dtx = b.dtx, maxBitrate = b.bitrate) != null
            if (!republished) notes.add("DTX not restored (republish failed)")
        }
        val bitrate = republished || setBitrate(m, b.bitrate, notes)
        return GravixSingerModeState(
            on = false,
            micPublished = true,
            processingApplied = processing,
            nativeApplied = nativeOk,
            bitrateApplied = bitrate,
            republished = republished,
            notes = notes,
        )
    }

    /** A microphone was (re)published while singer mode is on (a first publish, a reconnect): the profile again. */
    suspend fun onMicPublished() = lock.withLock {
        val o = options ?: return@withLock
        val m = mic() ?: return@withLock
        if (before == null) before = defaults()
        val notes = mutableListOf<String>()
        // the native side again (idempotent): a capture source asked for before the recorder existed applies now
        native(true, o.captureSource, notes)
        applyTo(m, o, notes)
        if (notes.isNotEmpty()) GxLog.i { "singer mode on the new microphone: $notes" }
    }

    private fun native(on: Boolean, source: GravixSingerCaptureSource, notes: MutableList<String>): Boolean = try {
        native(on, source).also { if (!it) notes.add("native singer mode unavailable (no Java audio device module)") }
    } catch (t: Throwable) {
        notes.add("native singer mode failed: $t")
        false
    }

    private fun setBitrate(m: GravixSingerMic, bps: Int?, notes: MutableList<String>): Boolean {
        if (m.maxBitrate == bps) return true
        val ok = m.setMaxBitrate(bps)
        if (!ok) notes.add("bitrate not set (no sender)")
        return ok
    }

    private fun sameProcessing(a: LocalAudioTrackOptions, b: LocalAudioTrackOptions) =
        a.echoCancellation == b.echoCancellation &&
            a.noiseSuppression == b.noiseSuppression &&
            a.autoGainControl == b.autoGainControl &&
            a.highPassFilter == b.highPassFilter
}
