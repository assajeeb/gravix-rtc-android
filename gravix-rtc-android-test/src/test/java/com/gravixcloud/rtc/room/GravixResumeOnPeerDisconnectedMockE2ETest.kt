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
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockPeerConnection
import com.gravixcloud.rtc.test.mock.MockWebSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.PeerConnection

/**
 * GRAVIX (GravixRecoveryOptions.resumeOnPeerDisconnected), found on the sim (2026-10-12, runs b1-pre-pause-default /
 * b1-pre-drop-default: region A paused / dropped): the primary peer connection reported DISCONNECTED 6.5 s after the
 * fault, but the upstream engine reconnects only on FAILED / CLOSED, which libwebrtc reported 10 s later (16.5 s);
 * the signal ping timeout comes at about the same time. The Flutter SDK's engine starts its reconnect (a resume) on
 * DISCONNECTED already.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixResumeOnPeerDisconnectedMockE2ETest : MockE2ETest() {

    /** Steps virtual time (and a little real time: PC states arrive on the RTC thread) until a new dial; null if none. */
    private fun TestScope.dialAfter(previous: MockWebSocket, steps: Int = 100): MockWebSocket? {
        repeat(steps) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(2)
            testScheduler.advanceTimeBy(20)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    private fun primaryDisconnected() =
        (getSubscriberPeerConnection() as MockPeerConnection).moveToIceConnectionState(PeerConnection.IceConnectionState.DISCONNECTED)

    @Test
    fun aDisconnectedPrimaryStartsAResumeAtOnce() = runTest {
        connect()
        val before = wsFactory.ws
        primaryDisconnected()
        val dial = dialAfter(before) ?: fail("no reconnect within 2 s of virtual time after DISCONNECTED").let { return@runTest }
        assertTrue("a resume (attempt 0), not a full rejoin", isResume())
        assertFalse(dial.isClosed)
    }

    @Test
    fun switchedOffADisconnectedPrimaryWaitsForFailedAsUpstream() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(resumeOnPeerDisconnected = false)
        connect()
        val before = wsFactory.ws
        primaryDisconnected()
        assertSame("upstream: DISCONNECTED alone starts nothing", null, dialAfter(before))
        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun upstreamIsOff() {
        assertFalse(GravixRecoveryOptions.UPSTREAM.resumeOnPeerDisconnected)
        assertTrue(GravixRecoveryOptions().resumeOnPeerDisconnected)
    }
}
