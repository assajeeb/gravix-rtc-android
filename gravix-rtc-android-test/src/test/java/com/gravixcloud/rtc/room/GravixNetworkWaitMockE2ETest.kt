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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX (GravixRecoveryOptions.waitForNetwork / networkWaitProbeIntervalMs), found on the sim (2026-10-12, run
 * n1-short-*: 5 s of airplane mode): the upstream loop kept dialling while the device had no network (9 attempts,
 * each failing at once), so the resume was spent offline and the back-off grew; when the network came back the next
 * attempt was a FULL rejoin 6.7 s later (the other participants saw this one leave and join). The Flutter SDK's
 * engine waits for connectivity before an attempt (and probes the server while it waits).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixNetworkWaitMockE2ETest : MockE2ETest() {

    private var online = true
    private var serverAnswers = false

    @Before
    fun fakeNetwork() {
        val engine = component.rtcEngine()
        engine.gravixNetworkCheck = { online }
        val real = engine.gravixFailoverEvidence
        engine.gravixFailoverEvidence = object : GravixFailoverEvidence by real {
            override suspend fun reachable(url: String, timeoutMs: Long) = serverAnswers
        }
    }

    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long): Pair<MockWebSocket, Long>? {
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

    @Test
    fun noAttemptWhileOfflineThenAResumeAsSoonAsTheNetworkIsBack() = runTest {
        connect()
        val before = wsFactory.ws
        online = false
        disconnectPeerConnection()
        assertNull("no dial while the device has no network", dialAfter(before, maxMs = 8_000))
        online = true
        val next = dialAfter(before, maxMs = 3_000)
        assertNotNull("an attempt once the network is back", next)
        assertTrue("within the network check interval (took ${next!!.second} ms)", next.second <= 500)
        assertTrue("the resume was not spent offline: attempt 0 is still a resume", isResume())
    }

    @Test
    fun aResumeLostToTheNetworkGoingAwayIsTriedAgainWhenItIsBack() = runTest {
        // sim n1-short-default-1: the socket failed 250 ms after airplane mode, before the network reading changed;
        // the resume was dialled and failed, and the attempt after the network wait was a full rejoin
        connect()
        val before = wsFactory.ws
        disconnectPeerConnection()
        val (resume, _) = dialAfter(before, maxMs = 3_000) ?: throw AssertionError("no resume dial")
        assertTrue(isResume())
        online = false
        resume.cancel()
        assertNull("waits offline", dialAfter(resume, maxMs = 5_000))
        online = true
        assertNotNull(dialAfter(resume, maxMs = 3_000))
        assertTrue("the resume is tried again, not a full rejoin", isResume())
    }

    @Test
    fun aServerThatAnswersWhileTheReadingSaysOfflineEndsTheWait() = runTest {
        connect()
        val before = wsFactory.ws
        online = false // a stale reading
        serverAnswers = true
        disconnectPeerConnection()
        val next = dialAfter(before, maxMs = 5_000)
        assertNotNull("the probe found the server: an attempt", next)
        assertTrue("one probe interval (took ${next!!.second} ms)", next.second <= GravixRecoveryOptions.DEFAULT_NETWORK_WAIT_PROBE_INTERVAL_MS + 500)
    }

    @Test
    fun aDisconnectDuringTheNetworkWaitEndsIt() = runTest {
        // the wait can last up to the reconnect's 60 s budget: a user disconnect meanwhile must end it, so a returning
        // network never resumes the old session
        connect()
        val before = wsFactory.ws
        online = false
        disconnectPeerConnection()
        assertNull(dialAfter(before, maxMs = 2_000))
        room.disconnect()
        testScheduler.runCurrent()
        online = true
        assertNull("no dial after the disconnect", dialAfter(before, maxMs = 3_000))
        assertTrue(room.state == Room.State.DISCONNECTED)
    }

    @Test
    fun waitForNetworkOffDialsWhileOfflineAsUpstream() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(waitForNetwork = false)
        connect()
        val before = wsFactory.ws
        online = false
        disconnectPeerConnection()
        assertNotNull("upstream: the resume is dialled offline", dialAfter(before, maxMs = 3_000))
    }

    @Test
    fun upstreamIsOff() {
        assertTrue(!GravixRecoveryOptions.UPSTREAM.waitForNetwork && GravixRecoveryOptions.UPSTREAM.networkWaitProbeIntervalMs == null)
        assertTrue(GravixRecoveryOptions().waitForNetwork)
    }
}
