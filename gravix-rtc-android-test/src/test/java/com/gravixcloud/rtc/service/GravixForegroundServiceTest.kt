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
import com.gravixcloud.rtc.RoomOptions
import com.gravixcloud.rtc.join.GravixLiveCheck
import com.gravixcloud.rtc.join.GravixRejoinAction
import com.gravixcloud.rtc.join.GravixRejoinPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** GRAVIX (2026-10-21): the call foreground service's holds (default off, rejoin backoff, start refused in the background). */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixForegroundServiceTest {
    /** A fake platform: start/update/stop recorded, a manual clock for the stop grace, a visible flag. */
    private class FakeDriver : GravixForegroundService.Driver {
        var visible = true
        var refuseStart: String? = null
        var starts = 0
        var stops = 0
        var now = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        private var foreground: (() -> Unit)? = null

        override val isVisible: Boolean get() = visible

        override fun start(context: Context, config: GravixCallService.Config, onResult: (GravixCallService.Outcome) -> Unit) {
            starts++
            val types = CallServiceTypes.MEDIA_PLAYBACK or (if (config.micWanted) CallServiceTypes.MICROPHONE else 0)
            onResult(GravixCallService.Outcome(if (refuseStart == null) types else 0, refuseStart))
        }

        override fun update(micWanted: Boolean?, cameraWanted: Boolean?) =
            GravixCallService.Outcome(CallServiceTypes.MEDIA_PLAYBACK or CallServiceTypes.MICROPHONE, null)

        override fun stop(context: Context) {
            stops++
        }

        override fun onForeground(callback: () -> Unit) {
            foreground = callback
        }

        fun comeToForeground() {
            visible = true
            foreground?.invoke()
        }

        override fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit {
            val t = (now + delayMs) to block
            timers.add(t)
            return { timers.remove(t) }
        }

        fun advance(ms: Long) {
            now += ms
            timers.filter { it.first <= now }.forEach {
                timers.remove(it)
                it.second()
            }
        }
    }

    private val driver = FakeDriver()
    private val context: Context = Mockito.mock(Context::class.java).also {
        Mockito.`when`(it.applicationContext).thenReturn(it)
    }
    private val room = Any()
    private val on = GravixForegroundServiceOptions(enabled = true)

    @Before
    fun setUp() {
        GravixForegroundService.resetForTest()
        GravixForegroundService.setDriverForTest(driver)
    }

    @After
    fun tearDown() = GravixForegroundService.resetForTest()

    @Test
    fun offByDefault() {
        assertFalse(GravixForegroundServiceOptions().enabled)
        assertFalse(GravixForegroundService.defaults.enabled)
        assertNull(RoomOptions().foregroundService)
        GravixForegroundService.roomConnecting(room, context, RoomOptions().foregroundService)
        assertEquals("nothing started", 0, driver.starts)
        assertFalse(GravixForegroundService.isRunning)
        // per room on
        GravixForegroundService.roomConnecting(room, context, on)
        assertEquals(1, driver.starts)
        assertTrue(GravixForegroundService.isRunning)
        assertEquals(listOf("microphone", "mediaPlayback"), GravixForegroundService.activeTypes)
    }

    @Test
    fun heldThroughTheWholeRejoinBackoff() {
        val scope = TestScope(StandardTestDispatcher())
        var rejoins = 0
        val policy = GravixRejoinPolicy.Builder(GravixRejoinAction { rejoins++; false }, GravixLiveCheck { true })
            .setTestEnvironment(StandardTestDispatcher(scope.testScheduler)) { scope.testScheduler.currentTime }
            .build()
        GravixForegroundService.roomConnecting(room, context, on)
        assertTrue(GravixForegroundService.isRunning)

        // the session ends by itself: the room lets go (grace), the policy schedules rejoins 1/2/4/8 s
        GravixForegroundService.release(room)
        assertTrue(policy.sessionEnded(null))
        // the room's grace (3 s) is long over while the backoff goes on: still running, nobody had to restart it
        for (step in listOf(1_000L, 2_000L, 4_000L)) {
            scope.advanceTimeBy(step)
            scope.runCurrent()
            driver.advance(step)
            assertEquals(0, driver.stops)
            assertTrue(GravixForegroundService.isRunning)
        }
        scope.advanceTimeBy(8_000)
        scope.runCurrent()
        assertEquals(4, rejoins)
        assertFalse("gave up", policy.pending)
        assertEquals(0, GravixForegroundService.holdCount)
        driver.advance(GravixForegroundService.STOP_GRACE_MS - 1)
        assertEquals("grace after the last holder", 0, driver.stops)
        driver.advance(1)
        assertEquals(1, driver.stops)
        assertEquals("never restarted", 1, driver.starts)
        policy.release()
    }

    @Test
    fun aRejoinThatWorksHandsOverToTheRoom() {
        val scope = TestScope(StandardTestDispatcher())
        val policy = GravixRejoinPolicy.Builder(
            GravixRejoinAction {
                GravixForegroundService.roomConnecting(room, context, on) // the rejoin's connect takes the room's hold
                true
            },
            GravixLiveCheck { true },
        ).setTestEnvironment(StandardTestDispatcher(scope.testScheduler)) { scope.testScheduler.currentTime }.build()
        GravixForegroundService.roomConnecting(room, context, on)
        GravixForegroundService.release(room)
        policy.sessionEnded(null)
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        driver.advance(10_000)
        assertEquals(0, driver.stops)
        assertEquals(1, GravixForegroundService.holdCount)
        assertEquals(1, driver.starts)
        policy.release()
    }

    @Test
    fun anAppHoldKeepsTheServiceThroughItsOwnRejoin() {
        assertNull("nothing to hold while the service is not in use", GravixForegroundService.hold())
        GravixForegroundService.roomConnecting(room, context, on)
        GravixForegroundService.release(room) // the final disconnect: grace
        val hold = GravixForegroundService.hold()!!
        driver.advance(60_000)
        assertEquals("held past the grace", 0, driver.stops)
        assertTrue(GravixForegroundService.isRunning)
        hold.release()
        hold.release() // idempotent
        driver.advance(GravixForegroundService.STOP_GRACE_MS - 1)
        assertEquals(0, driver.stops)
        driver.advance(1)
        assertEquals(1, driver.stops)
        assertEquals("never restarted", 1, driver.starts)
        assertNull("the grace is over: nothing to hold", GravixForegroundService.hold())
    }

    @Test
    fun anAppHoldNeverDropsTheRoomsHold() {
        GravixForegroundService.roomConnecting(room, context, on)
        GravixForegroundService.hold()!!.release()
        driver.advance(60_000)
        assertEquals(0, driver.stops)
        assertEquals(1, GravixForegroundService.holdCount)
    }

    @Test
    fun aStartRefusedInTheBackgroundIsRetriedOnTheNextForeground() {
        driver.visible = false
        driver.refuseStart = "ForegroundServiceStartNotAllowedException: app in background"
        GravixForegroundService.roomConnecting(room, context, on)
        assertEquals(1, driver.starts)
        assertFalse(GravixForegroundService.isRunning)
        driver.refuseStart = null
        driver.comeToForeground()
        assertEquals("retried", 2, driver.starts)
        assertTrue(GravixForegroundService.isRunning)
        // no further retry once running
        driver.comeToForeground()
        assertEquals(2, driver.starts)
    }

    @Test
    fun muteDoesNotStopAndManualStopDoes() {
        GravixForegroundService.roomConnecting(room, context, on)
        GravixForegroundService.update(micPublished = false, cameraPublished = null) // a mute-like change of types
        driver.advance(60_000)
        assertEquals(0, driver.stops)
        GravixForegroundService.stop()
        assertEquals(1, driver.stops)
        assertFalse(GravixForegroundService.isRunning)
    }
}
