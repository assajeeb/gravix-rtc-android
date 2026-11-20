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

package com.gravixcloud.rtc.voicefx

import com.gravixcloud.rtc.noise.GxVoiceFx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** An effect on the microphone. Same set and order as the Flutter SDK. */
enum class GravixVoiceEffect(val wireName: String) {
    NONE("none"),
    STUDIO("studio"),
    HALL("hall"),
    ROBOT("robot"),
    GIRL("girl"),
    TRANSFORMER("transformer"),
    BOY("boy"),
    CHILD("child"),
    WOMAN("woman"),
    MAN("man"),
    FOREIGNER("foreigner"),
    ETHEREAL("ethereal"),
    MAGNETIC("magnetic"),
    FRESH("fresh"),
    ENERGETIC("energetic"),
    RICH("rich"),
    MUFFLED("muffled"),
    ROUND("round"),
    FALSETTO("falsetto"),
    FULL("full"),
    CLEAR("clear"),
    RESONANT("resonant"),
    LOUD_CLEAR("loudClear"),
    SOFT_ROOM("softRoom"),
    LARGE_ROOM("largeRoom"),
    VALLEY("valley"),
    BASEMENT("basement"),
    KTV("ktv"),
    ENHANCED_KTV("enhancedKtv"),
    POPULAR("popular"),
    ROCK("rock"),
    VOCAL_CONCERT("vocalConcert"),
    GRAMOPHONE("gramophone"),
    AUTOTUNE_MAJOR_C("autotuneMajorC"),
    AUTOTUNE_MINOR_A("autotuneMinorA"),
    AUTOTUNE_HARMONIC_MINOR("autotuneHarmonicMinor"),
}

/** What the effect engine is doing. [active] differs from [effect] while the slow-device fallback is on. */
data class GravixVoiceEffectState(
    val effect: GravixVoiceEffect = GravixVoiceEffect.NONE,
    val active: GravixVoiceEffect = GravixVoiceEffect.NONE,
    val sampleRate: Int = 0,
    val musicBypass: Boolean = false,
    val watchdog: Boolean = false,
    val latencyMs: Int = 0,
    val frames: Long = 0,
    val overBudget: Long = 0,
    val maxBlockUs: Int = 0,
)

/** A timing run on a private chain, off the audio path. */
data class GravixVoiceEffectBenchmark(
    val effect: GravixVoiceEffect,
    val sampleRate: Int,
    val blocks: Int,
    val p50Us: Int,
    val p95Us: Int,
    val maxUs: Int,
    val latencyMs: Int,
)

/**
 * Voice effects on the local microphone, processed on the device after noise suppression.
 * Effects that change pitch add about 40 ms, the others none. The voice stays dry while
 * room music plays ([com.gravixcloud.rtc.noise.GravixNoiseSuppression.setMusicActive]).
 */
object GravixVoiceEffects {
    private val stateFlow = MutableStateFlow(GravixVoiceEffectState())

    /** Updated by [set], the music bypass and the slow-device fallback. */
    val state: StateFlow<GravixVoiceEffectState> = stateFlow.asStateFlow()

    init {
        GxVoiceFx.eventSink = { m -> stateFlow.value = parse(m) }
    }

    /** Selects [effect]; [GravixVoiceEffect.NONE] turns effects off. Not for the audio thread. False if the device cannot run it. */
    @JvmStatic
    fun set(effect: GravixVoiceEffect): Boolean {
        val m = GxVoiceFx.configure(mapOf("effect" to effect.wireName))
        if (m["ok"] != true) return false
        stateFlow.value = parse(m)
        return true
    }

    /** The engine's current counters, read now. */
    @JvmStatic
    fun currentState(): GravixVoiceEffectState = parse(GxVoiceFx.stats()).also { stateFlow.value = it }

    /** Times [effect] on this device for [seconds]; null if the library is unavailable. */
    suspend fun benchmark(effect: GravixVoiceEffect, seconds: Int = 5): GravixVoiceEffectBenchmark? =
        suspendCancellableCoroutine { c ->
            GxVoiceFx.benchmark(mapOf("effect" to effect.wireName, "seconds" to seconds)) { m ->
                c.resume(
                    if (m["ok"] != true) {
                        null
                    } else {
                        GravixVoiceEffectBenchmark(
                            effect,
                            int(m, "rate"),
                            int(m, "blocks"),
                            int(m, "p50Us"),
                            int(m, "p95Us"),
                            int(m, "maxUs"),
                            int(m, "latencyMs"),
                        )
                    },
                )
            }
        }

    private fun int(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toInt() ?: 0

    private fun effect(m: Map<String, Any?>, k: String) =
        GravixVoiceEffect.entries.firstOrNull { it.wireName == m[k] } ?: GravixVoiceEffect.NONE

    private fun parse(m: Map<String, Any?>) = GravixVoiceEffectState(
        effect = effect(m, "effect"),
        active = effect(m, "active"),
        sampleRate = int(m, "rate"),
        musicBypass = m["musicBypass"] == true,
        watchdog = m["watchdog"] == true,
        latencyMs = int(m, "latencyMs"),
        frames = (m["frames"] as? Number)?.toLong() ?: 0,
        overBudget = (m["over"] as? Number)?.toLong() ?: 0,
        maxBlockUs = int(m, "maxUs"),
    )
}
