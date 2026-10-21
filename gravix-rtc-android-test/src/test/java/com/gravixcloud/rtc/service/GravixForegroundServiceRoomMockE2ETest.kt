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
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** GRAVIX (2026-10-21): Room.connect / disconnect drive the call foreground service only when it is enabled. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GravixForegroundServiceRoomMockE2ETest : MockE2ETest() {
    private var starts = 0
    private var stops = 0
    private val grace = mutableListOf<() -> Unit>()

    private val driver = object : GravixForegroundService.Driver {
        override val isVisible = true
        override fun start(context: Context, config: GravixCallService.Config, onResult: (GravixCallService.Outcome) -> Unit) {
            starts++
            onResult(GravixCallService.Outcome(CallServiceTypes.MEDIA_PLAYBACK, null))
        }
        override fun update(micWanted: Boolean?, cameraWanted: Boolean?) = GravixCallService.Outcome(CallServiceTypes.MEDIA_PLAYBACK, null)
        override fun stop(context: Context) {
            stops++
        }
        override fun onForeground(callback: () -> Unit) {}
        override fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit {
            grace.add(block)
            return { grace.remove(block) }
        }
    }

    @Before
    fun setUpService() {
        GravixForegroundService.resetForTest()
        GravixForegroundService.setDriverForTest(driver)
    }

    @After
    fun tearDownService() = GravixForegroundService.resetForTest()

    @Test
    fun aDefaultRoomStartsNothing() = runTest {
        connect()
        room.disconnect()
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test
    fun anEnabledRoomStartsAtConnectAndStopsAfterTheFinalDisconnect() = runTest {
        room.foregroundService = GravixForegroundServiceOptions(enabled = true)
        connect()
        assertEquals(1, starts)
        room.disconnect()
        assertEquals("not before the grace", 0, stops)
        grace.toList().forEach { it() }
        assertEquals(1, stops)
    }

    @Test
    fun aCancelledConnectLetsGoOfTheService() = runTest {
        room.foregroundService = GravixForegroundServiceOptions(enabled = true)
        val job = coroutineRule.scope.launch { room.connect(url = TestData.EXAMPLE_URL, token = "token") }
        room::state.flow.takeWhile { it != Room.State.CONNECTING }.collect()
        assertEquals(1, starts)
        job.cancel()
        job.join()
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertEquals("the hold is gone before release()", 0, GravixForegroundService.holdCount)
        grace.toList().forEach { it() }
        assertEquals(1, stops)
        // the room can connect again (it was stuck in CONNECTING)
        connect()
        assertEquals(Room.State.CONNECTED, room.state)
    }
}
