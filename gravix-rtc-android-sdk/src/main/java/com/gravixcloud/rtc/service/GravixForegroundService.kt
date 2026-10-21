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

package com.gravixcloud.rtc.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GRAVIX (2026-10-21): the Android call foreground service ([GravixCallService]): keeps the microphone, the room
 * playback (and with [GravixForegroundServiceOptions.includeCamera] the camera) working while the app is in the
 * background. The Flutter SDK's `GravixForegroundService`, same model.
 *
 * **Off by default.** Turn it on for every room before the first connect, or per room:
 * ```
 * GravixForegroundService.defaults = GravixForegroundServiceOptions(enabled = true, notificationTitle = "Live room")
 * // or: GravixRtc.create(context, RoomOptions(foregroundService = GravixForegroundServiceOptions(enabled = true)))
 * ```
 * Then [com.gravixcloud.rtc.room.Room.connect] starts it (`mediaPlayback` + `microphone` when RECORD_AUDIO is granted),
 * a camera publish adds `camera` ([GravixForegroundServiceOptions.includeCamera]), and only the room's FINAL disconnect
 * or release lets go of it, after [STOP_GRACE_MS]. The room's own reconnects keep it, a
 * [com.gravixcloud.rtc.join.GravixRejoinPolicy] holds it through its whole backoff (so a rejoin never has to start it
 * again from the background, which Android 14 refuses), and muting never stops it.
 *
 * Android rules: the service must start while the app is visible (the SDK starts it at connect); a start or a type
 * upgrade Android refused from the background is logged and retried on the next return to the foreground. The app
 * requests POST_NOTIFICATIONS (Android 13+) itself; the SDK asks for nothing at runtime.
 */
object GravixForegroundService {
    private const val TAG = "GravixCallService"

    /** Grace before an automatic stop once the last holder let go (a room being replaced keeps the service). */
    const val STOP_GRACE_MS = 3_000L

    /** Process-wide defaults (used when a room's [com.gravixcloud.rtc.RoomOptions.foregroundService] is null): off. */
    @JvmStatic
    @Volatile
    var defaults: GravixForegroundServiceOptions = GravixForegroundServiceOptions()

    /** The platform side, a seam for tests. */
    internal interface Driver {
        val isVisible: Boolean
        fun start(context: Context, config: GravixCallService.Config, onResult: (GravixCallService.Outcome) -> Unit)
        fun update(micWanted: Boolean?, cameraWanted: Boolean?): GravixCallService.Outcome
        fun stop(context: Context)
        fun onForeground(callback: () -> Unit)
        fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit
    }

    private val lock = Any()
    private var driver: Driver = AndroidDriver
    private var appContext: Context? = null
    private var running = false
    private var starting = false
    private var typeNames: List<String> = emptyList()
    private var activeOptions: GravixForegroundServiceOptions? = null
    private var mic = false
    private var camera = false
    private var updatePending = false
    private var manual = false
    private val holds = IdentityHashMap<Any, GravixForegroundServiceOptions>()
    private var cancelStop: (() -> Unit)? = null
    private var foregroundWatch = false
    internal var stopGraceMs = STOP_GRACE_MS

    private val leave = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val leaveListeners = CopyOnWriteArrayList<Runnable>()

    /** Whether the service is in the foreground. */
    @JvmStatic
    val isRunning: Boolean get() = synchronized(lock) { running }

    /** The types Android applied, e.g. `[mediaPlayback]` or `[microphone, mediaPlayback]`. */
    @JvmStatic
    val activeTypes: List<String> get() = synchronized(lock) { typeNames }

    /** The notification's Leave action was tapped (the app decides what leaving means). */
    @JvmStatic
    val leaveRequests: SharedFlow<Unit> = leave.asSharedFlow()

    /** Java: a Leave tap. */
    @JvmStatic
    fun addLeaveListener(listener: Runnable) {
        leaveListeners.add(listener)
    }

    @JvmStatic
    fun removeLeaveListener(listener: Runnable) {
        leaveListeners.remove(listener)
    }

    // ── public, for apps that drive it themselves ──

    /**
     * Starts the service now (or re-applies the flags when it runs), whether or not [defaults] is enabled. Call it while
     * the app is visible. It stays until [stop].
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, options: GravixForegroundServiceOptions? = null, micPublished: Boolean = false, cameraPublished: Boolean = false) {
        synchronized(lock) {
            appContext = context.applicationContext
            manual = true
            cancelStopLocked()
            mic = mic || micPublished
            camera = camera || cameraPublished
            ensureStartedLocked(options ?: activeOptions ?: defaults)
        }
    }

    /** Changes the requested types (null = unchanged); an upgrade refused in the background is retried in the foreground. */
    @JvmStatic
    fun update(micPublished: Boolean?, cameraPublished: Boolean?) {
        synchronized(lock) {
            if (micPublished != null) mic = micPublished
            if (cameraPublished != null) camera = cameraPublished
            applyUpdateLocked()
        }
    }

    /**
     * Keeps the running service for an app's own rejoin logic (not [com.gravixcloud.rtc.join.GravixRejoinPolicy]):
     * without it the service stops [STOP_GRACE_MS] after the room's final disconnect. Never starts the service: null
     * when it is not in use (not enabled, never started, or already stopped). Take it while the call is up (or at the
     * latest in the Disconnected handler, within the grace) and [Hold.release] it when the rejoin worked (the room
     * holds again) or the app gave up.
     * ```
     * val hold = GravixForegroundService.hold() // e.g. right after connect
     * // ... the room ends, the app rejoins as it likes ...
     * hold?.release()
     * ```
     */
    @JvmStatic
    fun hold(): Hold? {
        val h = Hold()
        return if (holdIfActive(h)) h else null
    }

    /** One [hold]; releasing it never touches any room's own hold. */
    class Hold internal constructor() {
        /** Lets go (idempotent); the service stops after [STOP_GRACE_MS] when nothing else holds it. */
        fun release() = GravixForegroundService.release(this)
    }

    /** Stops the service now (also every room's hold). */
    @JvmStatic
    fun stop() {
        synchronized(lock) {
            manual = false
            holds.clear()
            stopNowLocked("stop()")
        }
    }

    // ── SDK hooks ──

    /** Room.connect: a hold for [room] when its options (or [defaults]) enable the service. Never blocks the join. */
    internal fun roomConnecting(room: Any, context: Context, override: GravixForegroundServiceOptions?) {
        val opts = override ?: defaults
        if (!opts.enabled) return
        synchronized(lock) {
            appContext = context.applicationContext
            cancelStopLocked()
            holds[room] = opts
            ensureStartedLocked(activeOptions ?: opts)
        }
    }

    /** A local microphone / camera publication of [room]. */
    internal fun roomPublished(room: Any, microphone: Boolean, camera: Boolean) {
        synchronized(lock) {
            val opts = holds[room] ?: return
            val wantCamera = camera && opts.includeCamera
            val micMissing = microphone && running && "microphone" !in typeNames
            if ((!microphone || (mic && !micMissing)) && (!wantCamera || this.camera)) return
            if (microphone) mic = true
            if (wantCamera) this.camera = true
            applyUpdateLocked()
        }
    }

    /**
     * [holder] lets go (a room's final disconnect / release, a rejoin policy done); the service stops after
     * [STOP_GRACE_MS] when nothing else holds it.
     */
    internal fun release(holder: Any) {
        synchronized(lock) {
            if (holds.remove(holder) == null) return
            if (holds.isNotEmpty() || manual) return
            cancelStopLocked()
            log("release: last holder gone, stopping in ${stopGraceMs}ms")
            cancelStop = driver.postDelayed(stopGraceMs) {
                synchronized(lock) {
                    cancelStop = null
                    if (holds.isEmpty() && !manual) stopNowLocked("grace over")
                }
            }
        }
    }

    /**
     * A rejoin policy keeps the service while a rejoin is scheduled or running (only when the service is in use: it
     * never starts one). True when it holds.
     */
    internal fun holdIfActive(holder: Any): Boolean {
        synchronized(lock) {
            val opts = activeOptions ?: return false
            cancelStopLocked()
            if (holds.put(holder, opts) == null) log("hold: rejoin pending")
            return true
        }
    }

    // ── implementation (under [lock]) ──

    private fun ensureStartedLocked(opts: GravixForegroundServiceOptions) {
        activeOptions = opts
        if (opts.claimMicrophoneAtStart) mic = true
        watchForegroundLocked()
        if (running) {
            applyUpdateLocked()
            return
        }
        if (starting) return
        val ctx = appContext ?: return
        starting = true
        val config = GravixCallService.Config(
            opts.notificationTitle,
            opts.notificationText,
            opts.notificationIcon,
            opts.showLeaveAction,
            opts.leaveActionLabel,
            mic,
            camera,
            opts.backgroundKeepAlive,
        )
        GravixCallService.listener = serviceListener
        driver.start(ctx, config) { outcome -> onStarted(outcome) }
    }

    private fun onStarted(outcome: GravixCallService.Outcome) {
        synchronized(lock) {
            starting = false
            if (outcome.error != null) {
                running = false
                updatePending = holds.isNotEmpty() || manual
                log("did not start (retried on the next foreground): ${outcome.error}")
                return
            }
            running = true
            typeNames = CallServiceTypes.names(outcome.types)
            updatePending = mic && "microphone" !in typeNames
            log("started: $typeNames")
            if (holds.isEmpty() && !manual && cancelStop == null) stopNowLocked("released while starting")
        }
    }

    private fun applyUpdateLocked() {
        if (!running) {
            // a hold exists but the start failed (e.g. joined from the background): retry when visible
            if ((holds.isNotEmpty() || manual) && driver.isVisible && activeOptions != null && !starting) {
                ensureStartedLocked(activeOptions!!)
            } else {
                updatePending = holds.isNotEmpty() || manual
            }
            return
        }
        if (!driver.isVisible) {
            updatePending = true // Android 14 refuses microphone/camera added from the background
            return
        }
        val outcome = driver.update(mic, camera)
        typeNames = CallServiceTypes.names(outcome.types)
        updatePending = outcome.error != null || (mic && "microphone" !in typeNames)
        if (outcome.error != null) log("type update refused: ${outcome.error}")
    }

    private fun stopNowLocked(why: String) {
        cancelStopLocked()
        val wasActive = running || starting || activeOptions != null
        running = false
        starting = false
        typeNames = emptyList()
        activeOptions = null
        mic = false
        camera = false
        updatePending = false
        val ctx = appContext
        if (wasActive && ctx != null) {
            log("stop ($why)")
            driver.stop(ctx)
        }
    }

    private fun cancelStopLocked() {
        cancelStop?.invoke()
        cancelStop = null
    }

    private fun watchForegroundLocked() {
        if (foregroundWatch) return
        foregroundWatch = true
        driver.onForeground {
            synchronized(lock) {
                if (updatePending && (holds.isNotEmpty() || manual)) {
                    log("foreground: applying the pending start / type upgrade")
                    applyUpdateLocked()
                }
            }
        }
    }

    private val serviceListener = object : GravixCallService.Listener {
        override fun onLeaveRequested() {
            leave.tryEmit(Unit)
            for (l in leaveListeners) {
                try {
                    l.run()
                } catch (t: Throwable) {
                    log("leave listener threw: $t")
                }
            }
        }

        override fun onStopped() {
            synchronized(lock) {
                if (running) log("stopped")
                running = false
                typeNames = emptyList()
            }
        }
    }

    private fun log(line: String) {
        try {
            Log.i(TAG, "[GravixCallService] $line")
        } catch (_: RuntimeException) {
            // JVM unit tests without android.util.Log
        }
    }

    // ── tests ──

    internal fun setDriverForTest(d: Driver?) {
        synchronized(lock) { driver = d ?: AndroidDriver }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            cancelStopLocked()
            defaults = GravixForegroundServiceOptions()
            stopGraceMs = STOP_GRACE_MS
            running = false
            starting = false
            typeNames = emptyList()
            activeOptions = null
            mic = false
            camera = false
            updatePending = false
            manual = false
            holds.clear()
            foregroundWatch = false
            appContext = null
            driver = AndroidDriver
        }
    }

    internal val holdCount: Int get() = synchronized(lock) { holds.size }

    private object AndroidDriver : Driver {
        private val main by lazy { Handler(Looper.getMainLooper()) }

        override val isVisible: Boolean
            get() = try {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            } catch (_: Throwable) {
                true
            }

        override fun start(context: Context, config: GravixCallService.Config, onResult: (GravixCallService.Outcome) -> Unit) {
            main.post { GravixCallService.start(context, config, onResult) }
        }

        override fun update(micWanted: Boolean?, cameraWanted: Boolean?): GravixCallService.Outcome =
            GravixCallService.update(micWanted, cameraWanted)

        override fun stop(context: Context) {
            main.post { GravixCallService.stop(context) }
        }

        override fun onForeground(callback: () -> Unit) {
            main.post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) = callback()
                    },
                )
            }
        }

        override fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit {
            val r = Runnable(block)
            main.postDelayed(r, delayMs)
            return { main.removeCallbacks(r) }
        }
    }
}
