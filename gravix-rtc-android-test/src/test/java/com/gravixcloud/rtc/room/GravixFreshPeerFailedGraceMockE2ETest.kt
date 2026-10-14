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

import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.PeerConnection.IceConnectionState

/**
 * GRAVIX (GravixRecoveryOptions.freshPeerFailedGraceMs; the Flutter SDK 0.4.18's freshPeerFailedGrace), from the
 * 0.4.18 device runs: over ICE/TCP a NEW peer connection's first checks can fail at once (checking -> failed ->
 * connected within ~0.1-1.7 s). In the Flutter SDK each such FAILED queued another full rejoin (8 in 2 minutes).
 *
 * The Kotlin engine drops a reconnect request while one runs, so that storm does not exist here; what such a FAILED
 * does in Kotlin is checked below: during a full rejoin it turned the rejoin's [RoomEvent.Reconnected] into a
 * [RoomEvent.Connected] (the engine state went DISCONNECTED, then CONNECTED "from DISCONNECTED"), and in
 * subscriber-primary mode a publisher that had never connected started a reconnect at once.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixFreshPeerFailedGraceMockE2ETest : MockE2ETest() {

    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long = 5_000): MockWebSocket? {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < maxMs) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun TestScope.step(ms: Long) {
        var left = ms
        while (left > 0) {
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
            left -= 50
        }
    }

    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    private fun reconnectEvents(events: List<RoomEvent>) = events.filter {
        it is RoomEvent.Resuming || it is RoomEvent.Resumed || it is RoomEvent.Reconnecting ||
            it is RoomEvent.Reconnected || it is RoomEvent.Connected
    }.map { it::class.java.simpleName }

    /** Connected; the primary fails, the resume dial fails: the full rejoin's dial (answered with a JoinResponse). */
    private fun TestScope.fullRejoinJoined(): MockWebSocket {
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before) ?: throw AssertionError("no resume dial")
        resume.cancel()
        val full = dialAfter(resume) ?: throw AssertionError("no full dial")
        assertFalse(isResume())
        wsFactory.listener.onOpen(full, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN)
        testScheduler.runCurrent()
        return full
    }

    @Test
    fun aRejoinsNewConnectionThatFailsOnceAndConnectsIsAReconnectNotAConnect() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val full = fullRejoinJoined()
        // the rejoin's new subscriber connection: its first checks fail at once, then it connects (ICE/TCP)
        val sub = getSubscriberPeerConnection()
        sub.moveToIceConnectionState(IceConnectionState.CHECKING)
        sub.moveToIceConnectionState(IceConnectionState.FAILED)
        step(500)
        simulateMessageFromServer(TestData.OFFER) // answered: the mock connects
        step(1_000)
        assertNull("no further attempt", dialAfter(full, maxMs = 10_000))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("Resuming", "Reconnecting", "Reconnected"), reconnectEvents(events.stopCollecting()))
    }

    @Test
    fun aRejoinsNewConnectionDownLongerThanTheGraceStillEndsWithReconnected() = runTest {
        // review F8: down > 3 s, then connected within the rejoin's 20 s wait: still the rejoin's Reconnected
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val full = fullRejoinJoined()
        val sub = getSubscriberPeerConnection()
        sub.moveToIceConnectionState(IceConnectionState.CHECKING)
        sub.moveToIceConnectionState(IceConnectionState.FAILED)
        step(GravixRecoveryOptions.DEFAULT_FRESH_PEER_FAILED_GRACE_MS + 1_000)
        simulateMessageFromServer(TestData.OFFER) // answered: the mock connects
        step(1_000)
        assertNull("no further attempt", dialAfter(full, maxMs = 5_000))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("Resuming", "Reconnecting", "Reconnected"), reconnectEvents(events.stopCollecting()))
    }

    @Test
    fun aPublisherThatNeverConnectedAndFailsOnceStartsNoReconnect() = runTest {
        connect() // TestData.JOIN: subscriber primary
        val before = wsFactory.ws
        val pub = getPublisherPeerConnection()
        pub.moveToIceConnectionState(IceConnectionState.CHECKING)
        pub.moveToIceConnectionState(IceConnectionState.FAILED)
        step(800)
        pub.moveToIceConnectionState(IceConnectionState.CONNECTED)
        assertNull("the publisher connected within the grace: no reconnect", dialAfter(before, maxMs = 6_000))
        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun aPublisherThatNeverConnectsReconnectsAfterTheGrace() = runTest {
        connect()
        val before = wsFactory.ws
        val pub = getPublisherPeerConnection()
        pub.moveToIceConnectionState(IceConnectionState.CHECKING)
        pub.moveToIceConnectionState(IceConnectionState.FAILED)
        assertNull("not within the grace", dialAfter(before, maxMs = GravixRecoveryOptions.DEFAULT_FRESH_PEER_FAILED_GRACE_MS - 500))
        assertNotNull("after the grace: the reconnect the FAILED asked for", dialAfter(before, maxMs = 2_000))
        assertTrue(isResume())
    }

    @Test
    fun switchedOffARejoinsTransientFailedEndsTheRejoinWithConnected() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(freshPeerFailedGraceMs = null)
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        fullRejoinJoined()
        val sub = getSubscriberPeerConnection()
        sub.moveToIceConnectionState(IceConnectionState.CHECKING)
        sub.moveToIceConnectionState(IceConnectionState.FAILED)
        step(500)
        simulateMessageFromServer(TestData.OFFER)
        step(1_000)
        testScheduler.advanceUntilIdle()
        assertEquals("upstream", listOf("Resuming", "Reconnecting", "Connected"), reconnectEvents(events.stopCollecting()))
    }

    @Test
    fun switchedOffANeverConnectedPublishersFailedReconnectsAtOnce() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(freshPeerFailedGraceMs = null)
        connect()
        val before = wsFactory.ws
        val pub = getPublisherPeerConnection()
        pub.moveToIceConnectionState(IceConnectionState.CHECKING)
        pub.moveToIceConnectionState(IceConnectionState.FAILED)
        assertNotNull("upstream: at once", dialAfter(before, maxMs = 1_000))
    }

    @Test
    fun upstreamIsOff() {
        assertNull(GravixRecoveryOptions.UPSTREAM.freshPeerFailedGraceMs)
        assertEquals(GravixRecoveryOptions.DEFAULT_FRESH_PEER_FAILED_GRACE_MS, GravixRecoveryOptions().freshPeerFailedGraceMs)
    }

    @Test
    fun aPublisherThatWasConnectedAndFailsReconnectsAtOnce() = runTest {
        connect()
        val before = wsFactory.ws
        val pub = getPublisherPeerConnection()
        pub.moveToIceConnectionState(IceConnectionState.CONNECTED)
        step(200)
        pub.moveToIceConnectionState(IceConnectionState.FAILED)
        dialAfter(before, maxMs = 1_000) ?: throw AssertionError("a connected publisher's FAILED reconnects at once")
        assertTrue("a resume first", isResume())
    }
}
