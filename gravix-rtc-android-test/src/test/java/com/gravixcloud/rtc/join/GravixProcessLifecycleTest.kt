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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX (0.1.2): the real background guard ([ProcessAppLifecycle]) on a process lifecycle: a cold start's first
 * onCreate (never started yet) and a removed startup initializer (never initialized) are not "background"; started
 * then stopped is.
 */
@RunWith(RobolectricTestRunner::class)
class GravixProcessLifecycleTest {

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    @Test
    fun aColdStartsFirstOnCreateIsNotBackground() {
        val o = Owner()
        o.registry.currentState = Lifecycle.State.CREATED // the first activity's onCreate: no activity started yet
        val l = ProcessAppLifecycle(o.registry)
        assertFalse(l.isInBackground)
    }

    @Test
    fun neverInitializedMeansNoGuard() {
        val o = Owner() // INITIALIZED: the startup initializer removed
        val l = ProcessAppLifecycle(o.registry)
        assertFalse(l.isInBackground)
        assertFalse(ProcessAppLifecycle(null).isInBackground)
    }

    @Test
    fun startedThenStoppedIsBackgroundAndObserversHearIt() {
        val o = Owner()
        o.registry.currentState = Lifecycle.State.CREATED
        val l = ProcessAppLifecycle(o.registry)
        val heard = mutableListOf<String>()
        val handle = l.observe({ heard.add("background") }, { heard.add("foreground") })
        o.registry.currentState = Lifecycle.State.RESUMED
        assertFalse(l.isInBackground)
        o.registry.currentState = Lifecycle.State.CREATED // ON_PAUSE, ON_STOP
        assertTrue(l.isInBackground)
        o.registry.currentState = Lifecycle.State.STARTED
        assertFalse(l.isInBackground)
        handle.close()
        o.registry.currentState = Lifecycle.State.CREATED
        assertEquals(listOf("foreground", "background", "foreground"), heard)
    }

    @Test
    fun alreadyStartedWhenBuiltThenStopped() {
        val o = Owner()
        o.registry.currentState = Lifecycle.State.STARTED
        val l = ProcessAppLifecycle(o.registry)
        o.registry.currentState = Lifecycle.State.CREATED
        assertTrue(l.isInBackground)
    }
}
