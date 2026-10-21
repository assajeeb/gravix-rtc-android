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

/**
 * Pure decisions of the noise-suppression processor (no Android, no JNI), so
 * the JVM unit tests cover them: frame shape, route names, the watchdog and
 * the engine actually used.
 */
internal object GxNsFrameShape {
    private val RATES = intArrayOf(8000, 16000, 24000, 32000, 44100, 48000)

    /** APM frames are 10 ms: rate = numFrames * 100, if gx_ns supports it. */
    fun rateOf(numFrames: Int): Int? {
        val r = numFrames * 100
        return if (r in RATES) r else null
    }

    /**
     * Null when the buffer can be processed in place as mono full-band floats,
     * else the reason to stay passthrough. [channels] = initialize()'s count
     * (0 = not seen; the hook may be added after APM initialized).
     */
    fun reject(numFrames: Int, capacityBytes: Int, direct: Boolean, channels: Int): String? = when {
        !direct -> "buffer_not_direct"
        channels > 1 -> "channels_$channels"
        rateOf(numFrames) == null -> "rate_${numFrames * 100}"
        capacityBytes != numFrames * 4 -> "layout_cap${capacityBytes}_frames$numFrames"
        else -> null
    }
}

internal object GxNsRoute {
    /** android.media.AudioManager modes (kept as numbers: this file has no Android imports). */
    const val MODE_NORMAL = 0
    const val MODE_IN_CALL = 2
    const val MODE_IN_COMMUNICATION = 3

    /**
     * The plan's route names from the selected audioswitch device's class name (`BluetoothHeadset`, `WiredHeadset`,
     * `Earpiece`, `Speakerphone`; null = none) and the audio mode: Bluetooth in a communication mode is SCO (HFP),
     * in the media mode A2DP.
     */
    fun of(device: String?, audioMode: Int?): String = when (device) {
        "Speakerphone" -> "speaker"
        "Earpiece" -> "earpiece"
        "WiredHeadset" -> "wired"
        "Usb" -> "usb"
        "BluetoothHeadset" ->
            if (audioMode == MODE_IN_COMMUNICATION || audioMode == MODE_IN_CALL) "bt-sco" else "bt-a2dp"
        else -> "unknown"
    }

    fun isBluetooth(route: String): Boolean = route.startsWith("bt-")

    /** SCO / HFP microphones are 8-16 kHz content (auto keeps RNNoise there). */
    fun isNarrowband(route: String): Boolean = route == "bt-sco"
}

/**
 * 3 consecutive frames over the budget (owner spec §4). Audio thread only. The first [graceFrames] frames after
 * creation / [rearm] never count: the processor is configured (and the engine created, buffers first touched) right
 * before them, and a narrow budget would otherwise force RNNoise for that start-up cost alone (review 2026-10-21).
 */
internal class GxNsWatchdog(private val limitNs: Long, private val consecutive: Int, private val graceFrames: Int = 0) {
    private var run = 0
    private var seen = 0
    var tripped = false
        private set

    /** Returns true exactly once, on the frame that trips it. */
    fun onFrame(ns: Long): Boolean {
        if (seen < graceFrames) {
            seen++
            return false
        }
        if (ns > limitNs) run++ else run = 0
        if (!tripped && run >= consecutive) {
            tripped = true
            return true
        }
        return false
    }

    fun rearm() {
        tripped = false
        run = 0
        seen = 0
    }

    companion object {
        /** 1 s of 10 ms frames. */
        const val START_GRACE_FRAMES = 100
    }
}

/** Our AGC (review 2026-10-21, #1): only while one of our engines is requested, and not in the webrtcBeforeNs order. */
internal object GxNsAgc {
    fun enabled(requested: Int, agcOrder: String?): Boolean = requested != GxNsNative.ENGINE_OFF && agcOrder != "webrtcBeforeNs"
}

/**
 * Freeing a detached studio engine (review 2026-10-21, #14). gx_ns swaps the engine pointer atomically, so a block
 * that STARTS after the detach never sees the old engine: with no block in flight at that moment it is freed at
 * once; otherwise once the block counter has moved (that block finished), or never (leak rather than race).
 */
internal object GxNsRetire {
    fun freeNow(inFlight: Int): Boolean = inFlight == 0

    fun freeAfterWait(blocksAtDetach: Int, blocksNow: Int): Boolean = blocksNow != blocksAtDetach
}

/** The low-latency pack download's rules (review 2026-10-21, #13). */
internal object GxNsPackRules {
    /** Only https (also after redirects). */
    fun httpsOnly(url: String?): Boolean = url != null && url.trim().lowercase().startsWith("https://")

    /** Copies at most [limit] bytes; returns the count, or -1 as soon as the stream holds more (the rest is not read). */
    fun copyCapped(inp: java.io.InputStream, out: java.io.OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = inp.read(buf)
            if (n < 0) return total
            total += n
            if (total > limit) return -1
            out.write(buf, 0, n)
        }
    }
}

/** A studio benchmark / pack task that throws still answers (review 2026-10-21, #9): `ok=false` with the reason. */
internal inline fun gxNsGuarded(what: String, reply: (Map<String, Any?>) -> Unit, block: () -> Map<String, Any?>) {
    val r = try {
        block()
    } catch (t: Throwable) {
        mapOf("ok" to false, "reason" to "${what}_error: ${t.javaClass.simpleName}")
    }
    reply(r)
}

/** The engine actually run, from the requested one and the live conditions. */
internal object GxNsFallback {
    data class Decision(val engine: Int, val reason: String)

    fun decide(
        requested: Int,
        requestReason: String,
        watchdogTripped: Boolean,
        thermalHot: Boolean,
        batterySaver: Boolean,
        preferHeadset: Boolean,
        route: String,
        thermalFallback: Boolean = true,
        batterySaverFallback: Boolean = true,
        studioSlow: Boolean = false,
        studioSuppress: Boolean = false,
    ): Decision {
        if (requested == GxNsNative.ENGINE_OFF) return Decision(GxNsNative.ENGINE_OFF, requestReason)
        if (preferHeadset && GxNsRoute.isBluetooth(route)) return Decision(GxNsNative.ENGINE_OFF, "headset_ns")
        if (requested == GxNsNative.ENGINE_DFN3) {
            if (watchdogTripped) return Decision(GxNsNative.ENGINE_RNNOISE, "watchdog_frame")
            // gx_ns rolling p95 of the studio engine over ~2 s above budget (thermal drift mid-call)
            if (studioSlow) return Decision(GxNsNative.ENGINE_RNNOISE, "studio_slow")
            // gx_ns: studio output speech collapsed vs its input (live guard)
            if (studioSuppress) return Decision(GxNsNative.ENGINE_RNNOISE, "studio_suppress")
            if (thermalFallback && thermalHot) return Decision(GxNsNative.ENGINE_RNNOISE, "thermal")
            if (batterySaverFallback && batterySaver) return Decision(GxNsNative.ENGINE_RNNOISE, "battery_saver")
        }
        return Decision(requested, requestReason)
    }
}

/** `[GravixNS] <step> eng=<> route=<> rate=<> [+<ms>ms] [detail]` (plan §3, same as Dart/JS). */
internal fun gxNsLine(step: String, eng: String, route: String, rate: Int, elapsedMs: Long? = null, detail: String? = null): String =
    GravixNsLog.line(step, eng, route, rate, elapsedMs, detail)
