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

package com.gravixcloud.rtc.join

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * GRAVIX (0.1.2): the app's foreground / background as a [GravixPrejoin] needs it (an interface so tests can drive it).
 */
internal interface GravixAppLifecycle {
    /** No activity of the app is started (nothing of it is visible). */
    val isInBackground: Boolean

    /** Calls [onBackground] when the app goes to the background, [onForeground] when it comes back. */
    fun observe(onBackground: () -> Unit, onForeground: () -> Unit): AutoCloseable

    /** Runs [action] after the next frames were drawn (main thread). */
    fun afterNextFrame(action: () -> Unit)
}

/**
 * [ProcessLifecycleOwner]: background = the process lifecycle was STARTED (an activity visible) at least once and is
 * below STARTED now (ON_STOP, which it reports about 700 ms after the last activity stopped, so a rotation is not a
 * background). Never started yet (a cold start's first `Activity.onCreate`, a join made from a background push before
 * any activity) is not "background": the attach timeout covers a join nobody opens. When ProcessLifecycleOwner is not
 * initialized (the app removed its startup initializer) it never starts, so the guard stays off.
 */
internal class ProcessAppLifecycle internal constructor(private val lifecycle: Lifecycle?) : GravixAppLifecycle {
    constructor() : this(processLifecycle())

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var everStarted = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    init {
        val lc = lifecycle
        if (lc != null) {
            // one permanent observer: notes the first start (its catch-up dispatch covers "already started")
            onMain {
                lc.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) everStarted = true
                    },
                )
                if (lc.currentState == Lifecycle.State.INITIALIZED) {
                    GravixJoinLog.step("error", detail = "ProcessLifecycleOwner not initialized: no background guard")
                }
            }
        }
    }

    override val isInBackground: Boolean
        get() {
            val state = lifecycle?.currentState ?: return false
            if (state.isAtLeast(Lifecycle.State.STARTED)) {
                everStarted = true
                return false
            }
            return everStarted
        }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    override fun observe(onBackground: () -> Unit, onForeground: () -> Unit): AutoCloseable {
        val lc = lifecycle ?: return AutoCloseable { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onBackground()
                Lifecycle.Event.ON_START -> onForeground()
                else -> Unit
            }
        }
        onMain { lc.addObserver(observer) }
        return AutoCloseable { onMain { lc.removeObserver(observer) } }
    }

    override fun afterNextFrame(action: () -> Unit) {
        // two frames: the first draw of a screen attached in onCreate happens in the frame after the one that runs it
        onMain {
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback { choreographer.postFrameCallback { action() } }
        }
    }

    private companion object {
        fun processLifecycle(): Lifecycle? = try {
            ProcessLifecycleOwner.get().lifecycle
        } catch (e: Exception) {
            GravixJoinLog.step("error", detail = "no ProcessLifecycleOwner (${e.javaClass.simpleName}): no background guard")
            null
        }
    }
}
