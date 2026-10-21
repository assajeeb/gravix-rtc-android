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

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gravixcloud.rtc.Version
import com.gravixcloud.rtc.audio.AudioProcessingController
import com.gravixcloud.rtc.room.track.LocalAudioTrack
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap
import kotlin.coroutines.resume

/**
 * GRAVIX (2026-10-21): on-device noise suppression of the microphone -- the Flutter SDK's
 * `GravixNoiseSuppression` (gravix_noise_controller.dart) for the native SDK. One per process: the processor sits on
 * the single capture stream of the audio device module.
 *
 * Usually driven by `RoomOptions(noiseSuppression = GravixNoiseSuppressionOptions(...))`: [Room.connect][com.gravixcloud.rtc.room.Room.connect]
 * configures it before the join microphone is created. Runtime changes: [setMode] (crossfaded natively). Null
 * options = today's behaviour (WebRTC's own NS, nothing on the capture path).
 *
 * While one of our engines runs, WebRTC's software NS is off ([GravixNoiseSuppressionOptions.disableWebRtcNs]) and, with
 * [GravixNsAgcOrder.OWN_AFTER_NS], WebRTC's AGC too (ours runs after our NS): decided before a microphone is created,
 * and re-applied to live microphones whenever the resolved engine or those options change.
 */
object GravixNoiseSuppression {
    private const val TAG = "GravixNS"
    private const val PREFS = "gravix_ns"

    // v2 (2026-10-21): the studio benchmark gained the quiet-mic check (pre-gain); older results never ran it.
    private const val CACHE_KEY = "gravix_ns_benchmark_v2"

    private val stateFlow = MutableStateFlow(GravixNsState())
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Context? = null

    @Volatile private var apc: AudioProcessingController? = null

    @Volatile private var opts: GravixNoiseSuppressionOptions? = null

    /** The argument of the last [configure] ([setMode] does not change it); see [joinShouldConfigure]. */
    @Volatile private var configuredWith: GravixNoiseSuppressionOptions? = null

    @Volatile private var resolvedEngine = GravixNsEngine.NONE

    /**
     * The processor passes frames through untouched (their shape: not direct, stereo, an unsupported rate ...):
     * no engine of ours runs, so WebRTC's NS / AGC must not stay off (review 2026-10-21, #10).
     */
    @Volatile private var shapeRejected = false

    /** The engine the microphones' WebRTC processing follows: [resolvedEngine] unless the processor passes through. */
    private val captureEngine: GravixNsEngine get() = GravixNsCapture.effectiveEngine(resolvedEngine, shapeRejected)
    private var facts: Map<String, Any?>? = null
    private var bench: JsonObject? = null
    private var benchLoaded = false

    @Volatile private var benchRun: Job? = null

    /** Microphones created by the SDK, with the options the app asked for (before our adjustment). Weak. */
    private val tracks = WeakHashMap<LocalAudioTrack, LocalAudioTrackOptions>()

    /** Live state: mode, engine, reason, route, rate, installed, music bypass, last frame-time window. */
    @JvmStatic
    val state: StateFlow<GravixNsState> = stateFlow.asStateFlow()

    /** The options of the last [configure] (null = not configured / legacy). */
    @JvmStatic
    val options: GravixNoiseSuppressionOptions? get() = opts

    /**
     * Applies [options] (null = legacy: no Gravix NS). Call before the microphone is created;
     * `RoomOptions.noiseSuppression` does it at connect. [audioProcessingController] = the room's
     * ([com.gravixcloud.rtc.room.Room.audioProcessingController]); null keeps the last one. Never throws.
     */
    @JvmOverloads
    suspend fun configure(
        context: Context,
        options: GravixNoiseSuppressionOptions?,
        audioProcessingController: AudioProcessingController? = null,
    ): GravixNsState = configureImpl(context, options, audioProcessingController, record = true)

    /** [record] = false: a join re-binding its room to the current options; [configuredWith] stays the app's. */
    private suspend fun configureImpl(
        context: Context,
        options: GravixNoiseSuppressionOptions?,
        audioProcessingController: AudioProcessingController?,
        record: Boolean,
    ): GravixNsState = lock.withLock {
        appContext = context.applicationContext
        if (audioProcessingController != null) apc = audioProcessingController
        val wasConfigured = opts != null
        opts = options
        if (record) configuredWith = options
        if (options == null && !wasConfigured) return@withLock stateFlow.value
        val o = options ?: GravixNoiseSuppressionOptions.LEGACY
        val s = apply(o, o.mode)
        reapplyTracks()
        s
    }

    @Volatile private var pendingConfigure: Job? = null

    /**
     * [configure] off the join path (Room.connect): the dial does not wait for it (device facts, benchmark cache);
     * the microphone does ([awaitConfigured]). A microphone created before it finished is re-applied by it anyway.
     */
    internal fun configureInBackground(
        context: Context,
        options: GravixNoiseSuppressionOptions?,
        audioProcessingController: AudioProcessingController?,
    ): Job {
        // the defaults never undo an earlier configure: the room is bound to the current options instead
        // (each Room has its own audio processing controller)
        val record = joinShouldConfigure(configuredWith, options)
        val use = if (record) options else opts ?: options
        if (record) configuredWith = options // at once: a second connect right behind sees it
        return scope.launch {
            try {
                configureImpl(context, use, audioProcessingController, record)
            } catch (t: Throwable) {
                Log.w(TAG, "[GravixNS] configure failed: $t")
            }
        }.also { pendingConfigure = it }
    }

    /**
     * Whether a connect with [options] (RoomOptions.noiseSuppression) re-configures, given the [configured]
     * options of the last [configure]. Not for null or the options already configured (a runtime [setMode]
     * survives the next join), and the defaults never override an earlier configure: a Room built with
     * default RoomOptions (connection check, a second Room) must not turn NS back on after the app chose off
     * (review 2026-10-21). Explicit options always apply.
     */
    internal fun joinShouldConfigure(configured: GravixNoiseSuppressionOptions?, options: GravixNoiseSuppressionOptions?): Boolean {
        if (options == null || options == configured) return false
        if (configured != null && options == GravixNoiseSuppressionOptions()) return false
        return true
    }

    /** The microphone waits (bounded) for a [configureInBackground] still running, so it starts with the right processing. */
    internal suspend fun awaitConfigured(timeoutMs: Long = 1000) {
        val j = pendingConfigure
        if (j == null || j.isCompleted) {
            log("micWait waited=0ms ready=${j != null}")
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val done = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { j.join() } != null
        log("micWait waited=${SystemClock.elapsedRealtime() - t0}ms ready=$done")
    }

    /** One `[GravixNS]` line (never throws: JVM unit tests have no android.util.Log). */
    private fun log(line: String) {
        try {
            Log.i(TAG, "[GravixNS] $line")
        } catch (_: RuntimeException) {
        }
    }

    /** Changes the mode at runtime (crossfaded natively); also updates WebRTC's NS / AGC of live microphones. */
    suspend fun setMode(mode: GravixNoiseSuppressionMode): GravixNsState = lock.withLock {
        val o = (opts ?: GravixNoiseSuppressionOptions()).copy(mode = mode)
        opts = o
        val s = apply(o, mode)
        reapplyTracks()
        s
    }

    /**
     * Room music starts / stops: with [GravixNoiseSuppressionOptions.musicBypass] the whole chain crossfades to dry
     * while it plays (RNNoise treats music as noise). For apps that mix music into the microphone.
     */
    @JvmStatic
    fun setMusicActive(active: Boolean) = GxNoiseProcessor.onMusic(active)

    /** Frame-time stats and the native status (null before the processor exists). Also updates [state]. */
    @JvmStatic
    fun stats(): GravixNsStats? {
        val m = GxNoiseProcessor.stats() ?: return null
        onEvent(m + ("step" to GravixNsStep.STATS))
        return GravixNsStats(
            frames = m["frames"] as Int,
            p50Us = m["p50Us"] as Int,
            p95Us = m["p95Us"] as Int,
            p99Us = m["p99Us"] as Int,
            maxUs = m["maxUs"] as Int,
            overBudget = m["over"] as Int,
            underflows = m["underflows"] as Int,
            switches = m["switches"] as Int,
            engine = GravixNsEngine.fromWireName(m["eng"] as? String) ?: GravixNsEngine.NONE,
            requested = GravixNsEngine.fromWireName(m["requested"] as? String) ?: GravixNsEngine.NONE,
            reason = m["reason"] as? String ?: "",
            route = GravixNsRoute.fromWireName(m["route"] as? String),
            rate = m["rate"] as Int,
            latencySamples = m["latencySamples"] as Int,
            agcGainDb = m["agcGainDb"] as Double,
            preGainDb = m["preGainDb"] as? Double ?: 0.0,
            preGainAppliedDb = m["preGainAppliedDb"] as? Double ?: 0.0,
            agcAppliedDb = m["agcAppliedDb"] as? Double ?: 0.0,
            pauseAttDb = m["pauseAttDb"] as? Double ?: 0.0,
            musicBypass = m["musicBypass"] == true,
            installed = m["installed"] == true,
            shapeReject = m["shapeReject"] as? String,
            studioModel = m["studioModel"] as? String,
        )
    }

    // ============================== SDK hooks ==============================

    /** The options a microphone is created with: unchanged unless one of our engines runs (synchronous). */
    internal fun adjustCapture(base: LocalAudioTrackOptions): LocalAudioTrackOptions {
        val o = opts ?: return base
        return GravixNsCapture.options(base, o, captureEngine)
    }

    /** A microphone was created from [base] (before [adjustCapture]): remembered weakly; the processor goes on its controller. */
    internal fun trackCreated(track: LocalAudioTrack, base: LocalAudioTrackOptions, controller: AudioProcessingController?) {
        synchronized(tracks) { tracks[track] = base }
        if (controller != null && opts != null) {
            apc = controller
            GxNoiseProcessor.installOn(controller, "microphone")
        }
    }

    /** The room's selected audio device changed (audioswitch, main thread). */
    internal fun onRouteDevice(device: String?, audioMode: Int?) = GxNoiseProcessor.onRouteDevice(device, audioMode)

    // ============================== internals ==============================

    @androidx.annotation.VisibleForTesting
    internal fun testSet(options: GravixNoiseSuppressionOptions?, resolved: GravixNsEngine) {
        opts = options
        resolvedEngine = resolved
        shapeRejected = false
    }

    private suspend fun apply(o: GravixNoiseSuppressionOptions, mode: GravixNoiseSuppressionMode): GravixNsState {
        val t0 = SystemClock.elapsedRealtime()
        val ctx = appContext
        // re-read each time: the studio model changes when the LL pack arrives
        facts = try {
            withContext(Dispatchers.IO) { GxNoiseProcessor.deviceFacts(ctx) }
        } catch (t: Throwable) {
            Log.w(TAG, "[GravixNS] facts unavailable: $t")
            facts
        }
        val f = factsOf(facts)
        val studioAvailable = facts?.get("studioAvailable") == true
        val benchKey = benchmarkKey()
        loadBenchmark()
        val cached = bench
        val b = cached?.let { GravixNsBenchmark.fromJson(it) }
        val r = GravixNsResolver.resolve(
            mode = mode,
            facts = f,
            policy = o.policy,
            benchmark = b,
            benchmarkKey = benchKey,
            studioAvailable = studioAvailable,
            benchmarkP95Us = o.benchmarkP95Us,
        )
        if (r.needsBenchmark && benchKey != null) startBenchmark(o, benchKey)
        Log.i(
            TAG,
            GravixNsLog.line(
                GravixNsStep.POLICY,
                r.engine,
                elapsedMs = SystemClock.elapsedRealtime() - t0,
                detail = "mode=${mode.wireName} reason=${r.reason} studioEligible=${r.studioEligible} " +
                    "model=${f.model ?: "?"} soc=${f.soc ?: "?"} abi=${f.abi ?: "?"} ram=${f.ramMb ?: "?"}",
            ),
        )
        val cachedFresh = cached != null && (cached["key"] as? JsonPrimitive)?.content == benchKey
        val args = hashMapOf<String, Any?>(
            "mode" to mode.wireName,
            "engine" to r.engine.wireName,
            "reason" to r.reason,
            "agcOrder" to o.agcOrder.wireName,
            "musicBypass" to o.musicBypass,
            "hardwareNs" to o.hardwareNs.wireName,
            "headsetNs" to o.headsetNs.wireName,
            "watchdogFrameUs" to o.watchdogFrameUs,
            "watchdogConsecutive" to o.watchdogConsecutive,
            "thermalFallback" to o.thermalFallback,
            "batterySaverFallback" to o.batterySaverFallback,
            "studioSource" to o.studioSource.wireName,
            "studioDownload" to o.studioDownload.wireName,
            "studioPackUrl" to o.studioPackUrl,
            "studioAttenLimitDb" to o.studioAttenLimitDb,
            "studioP95BudgetUs" to o.studioP95BudgetUs,
            "studioP95WindowMs" to o.studioP95WindowMs,
            "studioLowLatency" to o.studioLowLatency,
        )
        if (cachedFresh) args["studioModel"] = if (o.studioLowLatency) (cached!!["model"] as? JsonPrimitive)?.content else "std"
        GxNoiseProcessor.eventSink = { e -> onEvent(e) }
        val reply: Map<String, Any?>? = try {
            GxNoiseProcessor.configure(ctx, apc, args)
        } catch (t: Throwable) {
            Log.w(TAG, "[GravixNS] configure failed: $t")
            null
        }
        var engine = r.engine
        var reason = r.reason
        if (engine.isOurs && (reply == null || reply["ok"] != true)) {
            // no native processor (library missing, unsupported ABI): leave WebRTC NS as is
            engine = GravixNsEngine.NONE
            reason = reply?.get("reason") as? String ?: GravixNsReason.NATIVE_UNAVAILABLE
        }
        resolvedEngine = engine
        val cur = stateFlow.value
        val s = cur.copy(
            mode = mode,
            engine = engine,
            reason = reason,
            installed = reply?.get("installed") == true,
            route = (reply?.get("route") as? String)?.let { GravixNsRoute.fromWireName(it) } ?: cur.route,
            rate = (reply?.get("rate") as? Int)?.takeIf { it > 0 } ?: cur.rate,
        )
        set(s, o, GravixNsStep.INIT, SystemClock.elapsedRealtime() - t0, "installed=${s.installed}")
        return s
    }

    private fun factsOf(m: Map<String, Any?>?): GravixNsDeviceFacts = GravixNsDeviceFacts(
        platform = "android",
        model = m?.get("model") as? String,
        manufacturer = m?.get("manufacturer") as? String,
        soc = m?.get("soc") as? String,
        osVersion = m?.get("osVersion") as? String,
        abi = m?.get("abi") as? String,
        ramMb = (m?.get("ramMb") as? Number)?.toInt(),
        isLowRamDevice = m?.get("isLowRamDevice") as? Boolean,
        mediaPerformanceClass = (m?.get("mediaPerformanceClass") as? Number)?.toInt(),
        cpuMaxFreqMhz = (m?.get("cpuMaxFreqMhz") as? Number)?.toInt(),
    )

    private fun benchmarkKey(): String? {
        val sha = facts?.get("studioSha") as? String ?: return null
        return GravixNsResolver.benchmarkKey(
            sdkVersion = Version.CLIENT_VERSION,
            modelSha256 = sha,
            runtimeVersion = facts?.get("studioRuntime") as? String ?: "?",
            osBuild = facts?.get("osBuild") as? String ?: "?",
        )
    }

    private fun loadBenchmark() {
        if (benchLoaded) return
        benchLoaded = true
        try {
            val raw = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(CACHE_KEY, null) ?: return
            bench = Json.parseToJsonElement(raw) as? JsonObject
        } catch (t: Throwable) {
            Log.w(TAG, "[GravixNS] benchmark cache unreadable: $t")
        }
    }

    /** The studio benchmark in the background (RNNoise meanwhile; never blocks a join); then the mode is re-applied. */
    private fun startBenchmark(o: GravixNoiseSuppressionOptions, key: String) {
        if (benchRun != null) return
        val ctx = appContext
        val t0 = SystemClock.elapsedRealtime()
        benchRun = scope.launch {
            try {
                val m = suspendCancellableCoroutine<Map<String, Any?>> { c ->
                    GxNoiseProcessor.benchmark(ctx, o.benchmarkP95Us) { r -> if (c.isActive) c.resume(r) }
                }
                val ok = m["ok"] == true
                val st = stateFlow.value
                Log.i(
                    TAG,
                    GravixNsLog.line(
                        GravixNsStep.BENCH, st.engine, st.route, st.rate, SystemClock.elapsedRealtime() - t0,
                        "p95=${m["p95Us"] ?: "?"} model=${m["model"] ?: "?"} outputOk=${m["outputOk"] ?: "?"} " +
                            "parity=${m["paritySnrDb"] ?: "-"} ok=$ok reason=${m["reason"] ?: "-"}",
                    ),
                )
                // a failed run counts as bad output (no re-run until the key changes)
                val entry = buildJsonObject {
                    put("key", key)
                    put("p95Us", if (ok) (m["p95Us"] as? Number)?.toInt() ?: 0 else 0)
                    put("outputOk", ok && m["outputOk"] == true)
                    put("model", if (ok) m["model"] as? String else null)
                }
                lock.withLock { bench = entry }
                ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(CACHE_KEY, entry.toString())?.apply()
                o.onReport?.onReport(
                    GravixNsReport(
                        step = GravixNsStep.BENCH, mode = st.mode, engine = st.engine, reason = st.reason,
                        platform = "android", benchmarkP95Us = (m["p95Us"] as? Number)?.toInt(), benchmarkCached = false,
                    ),
                )
                benchRun = null
                val cur = opts
                if (cur != null) {
                    lock.withLock {
                        apply(cur, stateFlow.value.mode)
                        reapplyTracks()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[GravixNS] benchmark failed: $t")
            } finally {
                benchRun = null
            }
        }
    }

    /**
     * Every live microphone gets the WebRTC NS / AGC the current engine implies (the Flutter fix f71d6f2: a runtime
     * agcOrder / mode change must reach a live microphone, or webrtcBeforeNs mid-call leaves both AGCs off). Also
     * covers a microphone created before the first configure (pre-join).
     */
    private fun reapplyTracks() {
        val o = opts ?: GravixNoiseSuppressionOptions.LEGACY
        val list = synchronized(tracks) { tracks.entries.map { it.key to it.value } }
        for ((t, base) in list) {
            val want = GravixNsCapture.options(base, o, captureEngine)
            if (t.options == want) continue
            val r = t.applyOptions(want)
            Log.i(
                TAG,
                "[GravixNS] webrtc processing ns=${want.noiseSuppression} agc=${want.autoGainControl} " +
                    "track=${t.sid ?: t.name} ok=${r.isSuccess}",
            )
        }
    }

    private fun set(s: GravixNsState, o: GravixNoiseSuppressionOptions, step: String, elapsedMs: Long?, detail: String?) {
        stateFlow.value = s
        Log.i(
            TAG,
            GravixNsLog.line(
                step,
                s.engine,
                s.route,
                s.rate,
                elapsedMs,
                listOfNotNull(
                    "mode=${s.mode.wireName} reason=${s.reason} agcOrder=${o.agcOrder.wireName} " +
                        "webrtcNs=${if (o.disableWebRtcNs) "off" else "kept"}",
                    detail,
                ).joinToString(" "),
            ),
        )
        report(o, step, s)
    }

    internal fun onEvent(e: Map<String, Any?>) {
        if (e["step"] == GravixNsStep.INIT && e.containsKey("shapeReject")) {
            val rejected = e["shapeReject"] != null
            if (rejected != shapeRejected) {
                shapeRejected = rejected
                scope.launch { lock.withLock { reapplyTracks() } }
            }
        }
        val cur = stateFlow.value
        val s = cur.copy(
            engine = GravixNsEngine.fromWireName(e["eng"] as? String) ?: cur.engine,
            reason = e["reason"] as? String ?: cur.reason,
            route = (e["route"] as? String)?.let { GravixNsRoute.fromWireName(it) } ?: cur.route,
            rate = (e["rate"] as? Int)?.takeIf { it > 0 } ?: cur.rate,
            musicBypass = e["musicBypass"] as? Boolean ?: cur.musicBypass,
            p50Us = e["p50Us"] as? Int ?: cur.p50Us,
            p95Us = e["p95Us"] as? Int ?: cur.p95Us,
            overBudget = e["over"] as? Int ?: cur.overBudget,
        )
        stateFlow.value = s
        val o = opts ?: return
        report(o, e["step"] as? String ?: "", s, e)
    }

    private fun report(o: GravixNoiseSuppressionOptions, step: String, s: GravixNsState, e: Map<String, Any?>? = null) {
        val l = o.onReport ?: return
        try {
            l.onReport(
                GravixNsReport(
                    step = step, mode = s.mode, engine = s.engine, reason = s.reason, platform = "android",
                    route = s.route, apmRate = s.rate, p50Us = s.p50Us, p95Us = s.p95Us,
                    p99Us = e?.get("p99Us") as? Int, maxUs = e?.get("maxUs") as? Int, framesOverBudget = s.overBudget,
                    musicBypass = s.musicBypass, cpuPercent = (e?.get("cpu") as? Number)?.toDouble(),
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "[GravixNS] onReport threw: $t")
        }
    }
}

/** [GravixNoiseSuppression.stats]: the processor's frame times (us) and status. */
data class GravixNsStats(
    val frames: Int,
    val p50Us: Int,
    val p95Us: Int,
    val p99Us: Int,
    val maxUs: Int,
    val overBudget: Int,
    val underflows: Int,
    val switches: Int,
    /** The engine running now (`NONE` while faded to dry). */
    val engine: GravixNsEngine,
    val requested: GravixNsEngine,
    val reason: String,
    val route: GravixNsRoute,
    val rate: Int,
    val latencySamples: Int,
    /** Our AGC's held gain (dB; what speech gets). */
    val agcGainDb: Double,
    val musicBypass: Boolean,
    val installed: Boolean,
    /** Why frames pass through untouched (null = processed). */
    val shapeReject: String?,
    val studioModel: String?,
    /** The pre-gain's held value (dB; what speech gets). */
    val preGainDb: Double = 0.0,
    /** The pre-gain actually applied to the last block (dB; lower in pauses: the pause gate). */
    val preGainAppliedDb: Double = 0.0,
    /** Our AGC actually applied to the last block (dB). */
    val agcAppliedDb: Double = 0.0,
    /** The pause expander's attenuation of the last block (dB; keeps DTX on noise between words). */
    val pauseAttDb: Double = 0.0,
)
