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
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockWebSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * GRAVIX (GravixRecoveryOptions.resumeAfterNetworkSocketError; the Flutter SDK 0.4.18's switch of the same name),
 * from the 0.4.18 device run (an Android host, Wi-Fi off 5-15 s, x1): the signal socket died with the interface, the
 * first attempt (a resume) dialled while the connectivity reading still said Wi-Fi, its dial failed with a socket
 * error, and the next attempt was a FULL rejoin -- started the instant the Wi-Fi came back, although the server still
 * held the session (the others saw the host leave and join again).
 *
 * The Kotlin loop has the same shape: attempt 0 resumes, every later attempt is full (waitForNetwork resumes again
 * only when the reading said offline at the next attempt).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixResumeAfterNetworkSocketErrorMockE2ETest : MockE2ETest() {

    private var online = true

    @Before
    fun fakeNetwork() {
        component.rtcEngine().gravixNetworkCheck = { online }
    }

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

    /** The dial fails as it does when the interface goes away under it (no HTTP answer, no validate). */
    private fun failWithSocketError(ws: MockWebSocket) =
        wsFactory.listener.onFailure(ws, java.net.ConnectException("Failed to connect: Network is unreachable"), null)

    /** The phone's network changed as Room's NetworkCallback reports it (Wi-Fi lost; the reading may still say online). */
    private fun networkLost() {
        val network = Mockito.mock(Network::class.java)
        component.networkCallbackRegistry().networkCallbacks.forEach { it.onLost(network) }
    }

    private fun TestScope.resumeDial(): MockWebSocket {
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before) ?: throw AssertionError("no resume dial")
        assertTrue("attempt 0 is a resume", isResume())
        return resume
    }

    @Test
    fun aResumeDialLostToANetworkChangeIsResumedAgain() = runTest {
        connect()
        networkLost()
        val resume = resumeDial()
        failWithSocketError(resume)
        dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertTrue("the session is still on the server: the next attempt resumes again", isResume())
    }

    @Test
    fun twoFailedResumesInARowBecomeAFullRejoin() = runTest {
        connect()
        networkLost()
        val resume = resumeDial()
        failWithSocketError(resume)
        val second = dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertTrue(isResume())
        failWithSocketError(second)
        dialAfter(second) ?: throw AssertionError("no third attempt")
        assertFalse("two failed resumes in a row: a full rejoin", isResume())
    }

    @Test
    fun theNetworksPresentAtConnectAreNotANetworkChange() = runTest {
        // review F3: registerNetworkCallback answers at once with onAvailable for every network that already exists;
        // that must not stamp a "network change" (else every outage in the first 30 s resumed twice)
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        assertTrue("the mock registry delivered the initial onAvailable", component.networkCallbackRegistry().initialAvailableCalls > 0)
        val resume = resumeDial()
        failWithSocketError(resume)
        dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertFalse("a steady network: a full rejoin", isResume())
        val attempts = events.stopCollecting().filterIsInstance<RoomEvent.ReconnectAttempt>()
        assertTrue("afterNetworkChange false: $attempts", attempts.none { it.afterNetworkChange })
    }

    @Test
    fun aNetworkThatAppearsAfterTheConnectIsANetworkChange() = runTest {
        connect()
        val network = Mockito.mock(Network::class.java)
        component.networkCallbackRegistry().networkCallbacks.forEach { it.onAvailable(network) } // e.g. a handover
        val resume = resumeDial()
        failWithSocketError(resume)
        dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertTrue("a new network after the connect: resumed again", isResume())
    }

    @Test
    fun onASteadyNetworkASocketErrorIsAFullRejoinAsBefore() = runTest {
        connect()
        val resume = resumeDial()
        failWithSocketError(resume)
        dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertFalse("no network change: a full rejoin (upstream)", isResume())
    }

    @Test
    fun everyAttemptIsReportedWithItsModeAndCause() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        networkLost()
        val resume = resumeDial()
        failWithSocketError(resume)
        val second = dialAfter(resume) ?: throw AssertionError("no second attempt")
        failWithSocketError(second)
        dialAfter(second) ?: throw AssertionError("no third attempt")
        val attempts = events.stopCollecting().filterIsInstance<RoomEvent.ReconnectAttempt>()
        assertEquals(
            listOf(
                "RESUME peerConnectionFailed 1 true",
                "RESUME reconnectRetry 2 true",
                "FULL reconnectRetry 3 true",
            ),
            attempts.map { "${it.mode} ${it.cause} ${it.attempt} ${it.afterNetworkChange}" },
        )
    }

    @Test
    fun aResumeThatTimesOutIsAFullRejoinAsBefore() = runTest {
        // no answer within the signal deadline is not a socket error: the server may be gone
        connect()
        networkLost()
        val resume = resumeDial()
        dialAfter(resume, maxMs = 15_000) ?: throw AssertionError("no second attempt after the resume's deadline")
        assertFalse("a timed-out resume: a full rejoin", isResume())
    }

    @Test
    fun switchedOffASocketErrorAfterANetworkChangeIsAFullRejoin() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(resumeAfterNetworkSocketError = false)
        connect()
        networkLost()
        val resume = resumeDial()
        failWithSocketError(resume)
        dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertFalse("upstream: every attempt after the first is full", isResume())
    }

    @Test
    fun upstreamIsOff() {
        assertFalse(GravixRecoveryOptions.UPSTREAM.resumeAfterNetworkSocketError)
        assertTrue(GravixRecoveryOptions().resumeAfterNetworkSocketError)
        assertEquals(30_000L, GravixRecoveryOptions().networkChangeWindowMs)
    }

    @Test
    fun aNetworkChangeOutsideTheWindowIsASteadyNetwork() = runTest {
        connect()
        networkLost()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        val resume = resumeDial()
        failWithSocketError(resume)
        dialAfter(resume) ?: throw AssertionError("no second attempt")
        assertFalse("the change was 31 s before the reconnect began: a full rejoin", isResume())
    }
}
