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

package com.gravixcloud.rtc.room

import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.events.FlowCollector
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX(Phase 4): apps see a session RESUME. Upstream emits RoomEvent.Reconnecting/Reconnected only for a full
 * rejoin, so during a resume (the common recovery: a short Wi-Fi drop; the first real-fleet run's 5 s Wi-Fi-off
 * tests all resumed, 7.6-8.1 s from the drop) an app had nothing to show "reconnecting" on. The SDK now emits
 * RoomEvent.Resuming as soon as the reconnect starts with a resume (before any network wait), then
 * RoomEvent.Resumed, or Reconnecting -> Reconnected when the resume escalates to a full rejoin. Room.state and the
 * full-rejoin events are unchanged.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixResumeEventMockE2ETest : MockE2ETest() {

    private var online = true

    @Before
    fun fakeNetwork() {
        component.rtcEngine().gravixNetworkCheck = { online }
    }

    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long): MockWebSocket? {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < maxMs) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    private fun answer(ws: MockWebSocket) {
        wsFactory.listener.onOpen(ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(if (isResume()) TestData.RECONNECT else TestData.JOIN)
    }

    private fun reconnectEvents(events: List<RoomEvent>) = events.filter {
        it is RoomEvent.Resuming || it is RoomEvent.Resumed || it is RoomEvent.Reconnecting || it is RoomEvent.Reconnected
    }.map { it::class.java.simpleName }

    @Test
    fun aResumeEmitsResumingThenResumed() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val states = FlowCollector(room::state.flow, coroutineRule.scope)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        assertTrue("attempt 0 is a resume", isResume())
        answer(resume)
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Resuming", "Resumed"), reconnectEvents(events.stopCollecting()))
        assertEquals("Room.state stays CONNECTED during a resume (upstream)", listOf(Room.State.CONNECTED), states.stopCollecting())
    }

    @Test
    fun resumingIsEmittedWhileTheReconnectWaitsForTheNetwork() = runTest {
        // the app can show "reconnecting" from the drop, not only once the network is back and the resume dials
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        online = false
        disconnectPeerConnection()
        assertNull("no dial while offline", dialAfter(before, maxMs = 3_000))
        assertEquals(listOf("Resuming"), reconnectEvents(events.stopCollecting()))
        room.disconnect() // ends the network wait (else it outlives the test)
        testScheduler.runCurrent()
    }

    @Test
    fun aResumeThatEscalatesEmitsResumingThenReconnectingThenReconnected() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        assertTrue(isResume())
        // the server accepts the resume but the media connection stays down -> full rejoin (resumePrimaryDeadline)
        answer(resume)
        testScheduler.runCurrent()
        val full = dialAfter(resume, maxMs = 30_000) ?: throw AssertionError("no full rejoin")
        assertFalse("the next attempt is a full rejoin", isResume())
        answer(full)
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Resuming", "Reconnecting", "Reconnected"), reconnectEvents(events.stopCollecting()))
    }

    @Test
    fun aForcedFullReconnectEmitsNoResumeEvents() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val full = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no dial")
        assertFalse(isResume())
        answer(full)
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Reconnecting", "Reconnected"), reconnectEvents(events.stopCollecting()))
    }
}
