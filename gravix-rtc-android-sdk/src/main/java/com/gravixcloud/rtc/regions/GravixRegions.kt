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

package com.gravixcloud.rtc.regions

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.gravixcloud.rtc.util.GxLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/**
 * GRAVIX: a running start-up region measurement: measured now, then every refresh interval and whenever the
 * device's default network changes. The last answer stays until its TTL after [stop].
 */
class GravixRegionMeasurementHandle internal constructor(
    private val scope: CoroutineScope,
    private val runOnce: suspend () -> GravixRegionMeasurementResult,
    private val onStop: () -> Unit,
) {
    @Volatile
    private var running: Deferred<GravixRegionMeasurementResult>? = null

    @Volatile
    private var confirm: Job? = null

    @Volatile
    private var stopped = false

    /** The first run's answer. */
    lateinit var ready: Deferred<GravixRegionMeasurementResult>
        private set

    internal fun start(refreshMs: Long) {
        ready = runAndConfirm()
        if (refreshMs > 0) {
            scope.launch {
                while (isActive) {
                    delay(refreshMs)
                    runAndConfirm().await()
                }
            }
        }
    }

    /** One run at a time: a request while one runs gets that run's answer. */
    @Synchronized
    private fun run(): Deferred<GravixRegionMeasurementResult> {
        running?.takeIf { it.isActive }?.let { return it }
        return scope.async { runOnce() }.also { running = it }
    }

    /**
     * A rival that won once (`confirming`) is measured again soon (20 s) instead of at the next refresh: joins move
     * only after a second win, and that second run should not be minutes away when the held region got worse.
     */
    internal fun runAndConfirm(): Deferred<GravixRegionMeasurementResult> {
        val d = run()
        scope.launch {
            val r = runCatching { d.await() }.getOrNull() ?: return@launch
            if (!stopped && r.selectReason == GravixRegionSelectReason.CONFIRMING && confirm?.isActive != true) {
                confirm = scope.launch {
                    delay(GravixRegionSelector.CONFIRM_DELAY_MS)
                    if (!stopped) runAndConfirm()
                }
            }
        }
        return d
    }

    /** Measure again now. */
    suspend fun refresh(): GravixRegionMeasurementResult = runAndConfirm().await()

    /** [ready] for Java. */
    fun readyFuture(): CompletableFuture<GravixRegionMeasurementResult> = ready.asFuture()

    /** [refresh] for Java. */
    fun refreshFuture(): CompletableFuture<GravixRegionMeasurementResult> = runAndConfirm().asFuture()

    /** Stop re-measuring. The last answer stays until its TTL. */
    fun stop() {
        stopped = true
        onStop()
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<GravixRegionMeasurementResult>.asFuture(): CompletableFuture<GravixRegionMeasurementResult> {
        val f = CompletableFuture<GravixRegionMeasurementResult>()
        invokeOnCompletion { t ->
            if (t != null) f.completeExceptionally(t) else f.complete(getCompleted())
        }
        return f
    }
}

/** GRAVIX: the public entry points of the start-up region measurement. */
object GravixRegions {

    /**
     * Measure now, then every [refreshMs] and whenever the default network changes. Call once when the app
     * starts, e.g. with `regionsUrl = "https://<console host>/v1/regions"`, or with `regionUrls`. Never throws.
     *
     * Defaults filled in from [context]: the list store (shared preferences, so a cold start with the gateway
     * unreachable still measures the last list), the network type and cache key. [currentRegion] is asked at every
     * run (the app's region in use may change between runs).
     */
    @JvmStatic
    @JvmOverloads
    fun startMeasurement(
        context: Context,
        options: GravixRegionMeasureOptions,
        refreshMs: Long = GravixRegionMeasurements.REFRESH_MS,
        watchNetwork: Boolean = true,
        currentRegion: (() -> String?)? = null,
    ): GravixRegionMeasurementHandle {
        val appContext = context.applicationContext
        val base = options.copy().apply {
            if (store == null && regionUrls == null && regionsUrl != null) store = GravixSharedPrefsRegionListStore(appContext)
            if (networkType == null) networkType = GravixNetworkReader { GravixRegionNetwork.type(appContext) }
            if (networkKey == null) networkKey = GravixNetworkReader { GravixRegionNetwork.key(appContext) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var callback: ConnectivityManager.NetworkCallback? = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        lateinit var handle: GravixRegionMeasurementHandle
        handle = GravixRegionMeasurementHandle(
            scope = scope,
            runOnce = {
                val o = base.copy()
                currentRegion?.invoke()?.let { o.currentRegion = it }
                GravixRegionMeasurements.measure(o)
            },
            onStop = {
                callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
            },
        )
        handle.start(refreshMs)
        if (watchNetwork && cm != null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                @Volatile
                private var last: Network? = null

                override fun onAvailable(network: Network) {
                    val previous = last
                    last = network
                    // the first call reports the network present at registration: the first run covers it
                    if (previous != null && previous != network) handle.runAndConfirm()
                }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                callback = cb
            } catch (e: Exception) {
                GxLog.w(e) { "region measurement: no network callback; the periodic refresh still runs" }
            }
        }
        return handle
    }

    /** The current fresh answer (null when none or stale). */
    @JvmStatic
    fun current(): GravixRegionMeasurementResult? = GravixRegionMeasurements.current()
}
