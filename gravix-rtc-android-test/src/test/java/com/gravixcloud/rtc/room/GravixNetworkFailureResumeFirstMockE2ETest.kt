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

import android.net.Network
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockWebSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.webrtc.PeerConnection.IceConnectionState

/**
 * GRAVIX: two Flutter SDK 0.4.18 fixes whose hazard this engine does not have (pinned so it stays that way):
 *
 * - resumeBeforeFullOnNetworkFailure (device run x2/x4/x5: a connected peer connection FAILED during a Wi-Fi drop
 *   and the Flutter engine escalated to a full rejoin at once). Here a FAILED starts reconnect(), whose first attempt
 *   is a resume (upstream; only a server Leave{RECONNECT} makes it full).
 * - resumeReasonNetworkWindow (the resume told the server RR_PUBLISHER_FAILED / RR_SUBSCRIBER_FAILED and the SFU,
 *   for a connection younger than 90 s, took it for "UDP blocked" and preferred ICE/TCP for 5 minutes). This SDK
 *   sends no `reconnect_reason` at all (the server reads 0 = RR_UNKNOWN, which its HandleClientReconnect ignores).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixNetworkFailureResumeFirstMockE2ETest : MockE2ETest() {

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

    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    private fun networkLost() {
        val network = Mockito.mock(Network::class.java)
        component.networkCallbackRegistry().networkCallbacks.forEach { it.onLost(network) }
    }

    @Test
    fun aConnectedPrimaryThatFailsDuringANetworkChangeResumesFirst() = runTest {
        connect()
        networkLost()
        val before = wsFactory.ws
        disconnectPeerConnection()
        dialAfter(before) ?: throw AssertionError("no dial")
        assertTrue("a resume, not a full rejoin", isResume())
    }

    @Test
    fun aConnectedPublisherThatFailsDuringANetworkChangeResumesFirst() = runTest {
        connect() // subscriber primary: the publisher's FAILED reconnects too
        val pub = getPublisherPeerConnection()
        pub.moveToIceConnectionState(IceConnectionState.CONNECTED)
        networkLost()
        val before = wsFactory.ws
        pub.moveToIceConnectionState(IceConnectionState.FAILED)
        dialAfter(before) ?: throw AssertionError("no dial")
        assertTrue("a resume, not a full rejoin", isResume())
    }

    @Test
    fun aResumeTellsTheServerNoReconnectReason() = runTest {
        connect()
        networkLost()
        val before = wsFactory.ws
        disconnectPeerConnection()
        dialAfter(before) ?: throw AssertionError("no dial")
        assertTrue(isResume())
        assertNull(
            "no reconnect_reason: the server reads RR_UNKNOWN and never counts the resume as a UDP failure",
            wsFactory.request.url.queryParameter("reconnect_reason"),
        )
    }
}
