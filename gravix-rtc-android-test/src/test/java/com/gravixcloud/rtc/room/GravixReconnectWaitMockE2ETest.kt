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
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * GRAVIX: the reconnect loop's wait for the peer connections (upstream: up to 20 s, MAX_ICE_CONNECT_TIMEOUT_MS) and
 * the Flutter SDK's recovery fields that bound it (Phase 3, B2):
 *
 * - resumePrimaryDeadline: a resume whose primary connection is not up this long after the ReconnectResponse
 *   becomes a full rejoin (Dart: 3 s instead of its 10 s wait; Kotlin upstream waits 20 s);
 * - leaveAbortsResumeWait: a server Leave (or the socket closing) while a resume waits ends the wait at once
 *   (upstream: the reconnect request it makes is dropped -- "Reconnection is already in progress" -- and the resume
 *   sits out its wait with no signal);
 * - restartAbortsOnSignalLoss: a full reconnect whose own signal socket closes while it waits for the peer
 *   connections fails at once (flapping network) instead of waiting out the 20 s.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixReconnectWaitMockE2ETest : MockE2ETest() {

    /**
     * Steps virtual time (and a little real time: PC states arrive on the RTC thread) until a new dial. Returns it and
     * the virtual ms it took, or null after [maxMs].
     */
    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long = 30_000): Pair<MockWebSocket, Long>? {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < maxMs) {
            if (wsFactory.ws !== previous) return wsFactory.ws to (testScheduler.currentTime - start)
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    /** Connected, then the primary connection fails: the resume dials and the server accepts it; ICE stays down. */
    private fun TestScope.resumeAcceptedIceDown(): MockWebSocket {
        val before = wsFactory.ws
        disconnectPeerConnection()
        val (resume, _) = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        assertTrue("attempt 0 is a resume", isResume())
        wsFactory.listener.onOpen(resume, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        testScheduler.runCurrent()
        return resume
    }

    private fun leave(action: GravixcloudRtc.LeaveRequest.Action) = GravixcloudRtc.SignalResponse.newBuilder().apply {
        leave = GravixcloudRtc.LeaveRequest.newBuilder()
            .setReason(GravixcloudModels.DisconnectReason.MIGRATION)
            .setAction(action)
            .build()
    }.build()

    // ---------------------------------------------------------------- resumePrimaryDeadline

    @Test
    fun aResumeWhosePrimaryStaysDownBecomesAFullRejoinAfterTheDeadline() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(leaveAbortsResumeWait = false)
        connect()
        val resume = resumeAcceptedIceDown()
        val (_, waited) = dialAfter(resume) ?: throw AssertionError("no further attempt within 30 s")
        assertFalse("the next attempt is a full rejoin", isResume())
        assertTrue("left the resume ${GravixRecoveryOptions.DEFAULT_RESUME_PRIMARY_DEADLINE_MS} ms after it was accepted, not 20 s (took $waited ms)", waited < 5_000)
    }

    @Test
    fun resumePrimaryDeadlineOffWaitsTheUpstreamTwentySeconds() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(resumePrimaryDeadlineMs = null, leaveAbortsResumeWait = false)
        connect()
        val resume = resumeAcceptedIceDown()
        val (_, waited) = dialAfter(resume) ?: throw AssertionError("no further attempt within 30 s")
        assertTrue("upstream: the 20 s wait (took $waited ms)", waited >= 19_000)
    }

    // ---------------------------------------------------------------- leaveAbortsResumeWait

    @Test
    fun aLeaveWhileTheResumeWaitsEndsTheWaitAtOnce() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(resumePrimaryDeadlineMs = null)
        connect()
        val resume = resumeAcceptedIceDown()
        simulateMessageFromServer(leave(GravixcloudRtc.LeaveRequest.Action.RECONNECT))
        val (_, waited) = dialAfter(resume) ?: throw AssertionError("no further attempt within 30 s")
        assertFalse("Leave{RECONNECT}: a full rejoin", isResume())
        assertTrue("the wait ended with the Leave (took $waited ms)", waited < 1_500)
    }

    @Test
    fun leaveAbortsResumeWaitOffSitsOutTheWait() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(resumePrimaryDeadlineMs = null, leaveAbortsResumeWait = false)
        connect()
        val resume = resumeAcceptedIceDown()
        simulateMessageFromServer(leave(GravixcloudRtc.LeaveRequest.Action.RECONNECT))
        val (_, waited) = dialAfter(resume) ?: throw AssertionError("no further attempt within 30 s")
        assertTrue("upstream: the Leave's reconnect request is dropped, the wait runs out (took $waited ms)", waited >= 19_000)
    }

    // ---------------------------------------------------------------- restartAbortsOnSignalLoss

    /** The resume fails, the full rejoin joins, ICE stays down; returns the full rejoin's socket. */
    private fun TestScope.fullRejoinJoinedIceDown(): MockWebSocket {
        val before = wsFactory.ws
        disconnectPeerConnection()
        val (resume, _) = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        resume.cancel()
        val (full, _) = dialAfter(resume, maxMs = 5_000) ?: throw AssertionError("no full dial")
        assertFalse(isResume())
        wsFactory.listener.onOpen(full, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN)
        testScheduler.runCurrent()
        return full
    }

    @Test
    fun aFullRejoinThatLosesItsSocketWhileWaitingMovesOnAtOnce() = runTest {
        connect()
        val full = fullRejoinJoinedIceDown()
        wsFactory.listener.onFailure(full, IOException("Wi-Fi flapped"), null)
        val next = dialAfter(full)
        assertNotNull("no further attempt within 30 s", next)
        assertTrue("the signal loss ended the wait (took ${next!!.second} ms)", next.second < 1_500)
    }

    @Test
    fun restartAbortsOnSignalLossOffWaitsOutTheTwentySeconds() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(restartAbortsOnSignalLoss = false)
        connect()
        val full = fullRejoinJoinedIceDown()
        wsFactory.listener.onFailure(full, IOException("Wi-Fi flapped"), null)
        val (_, waited) = dialAfter(full) ?: throw AssertionError("no further attempt within 30 s")
        assertTrue("upstream: the wait runs out (took $waited ms)", waited >= 19_000)
    }

    // ---------------------------------------------------------------- resumeWaitsForLiveState

    /**
     * Sim head-short-verbose-1 (5 s of airplane mode): the connections came back by themselves before the resume; the
     * resume's ICE restart took the subscriber to DISCONNECTED while its observer still said CONNECTED, so the wait
     * returned at once, the live check failed, and the resume escalated to a full rejoin 80 ms later.
     */
    private fun TestScope.resumeWithStaleObserver(): MockWebSocket {
        val before = wsFactory.ws
        disconnectPeerConnection()
        val (resume, _) = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        // the live connection is restarting (not connected) ...
        val pc = getSubscriberPeerConnection()
        pc.moveToIceConnectionState(org.webrtc.PeerConnection.IceConnectionState.CHECKING)
        Thread.sleep(50) // the observer update is delivered on the RTC thread
        // ... while the observer still reports the CONNECTED from before the restart
        val observer = RTCEngine::class.java.getDeclaredField("subscriberObserver").apply { isAccessible = true }
            .get(component.rtcEngine()) as SubscriberTransportObserver
        SubscriberTransportObserver::class.java
            .getDeclaredMethod("setConnectionState", org.webrtc.PeerConnection.PeerConnectionState::class.java)
            .apply { isAccessible = true }
            .invoke(observer, org.webrtc.PeerConnection.PeerConnectionState.CONNECTED)
        wsFactory.listener.onOpen(resume, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        // the restart completes (unless the attempt already gave up and closed this connection for a full rejoin)
        runCatching { pc.moveToIceConnectionState(org.webrtc.PeerConnection.IceConnectionState.CONNECTED) }
        return resume
    }

    @Test
    fun aResumeWaitsForTheLiveConnectionNotAStaleObserver() = runTest {
        connect()
        val resume = resumeWithStaleObserver()
        assertTrue("no full rejoin: the resume completes on the live state", dialAfter(resume, maxMs = 5_000) == null)
        assertTrue(room.state == Room.State.CONNECTED)
    }

    @Test
    fun resumeWaitsForLiveStateOffEscalatesAsUpstream() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(resumeWaitsForLiveState = false)
        connect()
        val resume = resumeWithStaleObserver()
        assertNotNull("upstream: the attempt fails on the live check, a full rejoin follows", dialAfter(resume, maxMs = 5_000))
    }

    @Test
    fun upstreamIsOff() {
        val up = GravixRecoveryOptions.UPSTREAM
        assertTrue(up.resumePrimaryDeadlineMs == null && !up.leaveAbortsResumeWait && !up.restartAbortsOnSignalLoss)
        val d = GravixRecoveryOptions()
        assertTrue(d.resumePrimaryDeadlineMs == 3000L && d.leaveAbortsResumeWait && d.restartAbortsOnSignalLoss)
        assertTrue(d.resumeWaitsForLiveState && !up.resumeWaitsForLiveState)
    }
}
