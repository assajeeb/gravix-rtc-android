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
import com.gravixcloud.rtc.test.mock.MockPeerConnection
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.MockCandidatePairChangeEvent
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

/**
 * GRAVIX (GravixRecoveryOptions.iceTransportWatchMs): Room.selectedTransport / publisherTransport /
 * subscriberTransport, RoomEvent.IceTransportChanged and RoomEvent.SlowTcpTransport on the mock engine. The
 * transport comes from libwebrtc's selected-candidate-pair callback; the round trip is read only while on TCP.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixIceTransportMockE2ETest : MockE2ETest() {

    private fun TestScope.step(ms: Long) {
        var left = ms
        while (left > 0) {
            Thread.sleep(1)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            left -= 100
        }
    }

    private fun MockPeerConnection.selects(localSdp: String) =
        observer!!.onSelectedCandidatePairChanged(MockCandidatePairChangeEvent.create(localSdp))

    private fun tcpStats(rttS: Double) = RTCStatsReport(
        0,
        mapOf(
            "T1" to RTCStats(0, "transport", "T1", mapOf("selectedCandidatePairId" to "CP1")),
            "CP1" to RTCStats(0, "candidate-pair", "CP1", mapOf("localCandidateId" to "L1", "currentRoundTripTime" to rttS)),
            "L1" to RTCStats(0, "local-candidate", "L1", mapOf("candidateType" to "host", "protocol" to "tcp")),
        ),
    )

    @Test
    fun theSelectedPairSetsTheTransportAndEmitsTheChange() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        getSubscriberPeerConnection().selects(MockCandidatePairChangeEvent.UDP_HOST)
        getPublisherPeerConnection().selects(MockCandidatePairChangeEvent.TCP_HOST)
        testScheduler.runCurrent()
        val transports = Triple(room.selectedTransport, room.publisherTransport, room.subscriberTransport)
        endSession()
        assertEquals(Triple(GravixIceTransport.TCP, GravixIceTransport.TCP, GravixIceTransport.UDP), transports)
        val changes = events.stopCollecting().filterIsInstance<RoomEvent.IceTransportChanged>()
        assertEquals(listOf(null to GravixIceTransport.UDP, GravixIceTransport.UDP to GravixIceTransport.TCP), changes.map { it.previous to it.transport })
        assertEquals(GravixIceTransport.TCP, changes.last().publisher)
        assertEquals(GravixIceTransport.UDP, changes.last().subscriber)
    }

    /** Ends the session: the TCP round-trip reading stops with it (else the test scheduler never goes idle). */
    private fun TestScope.endSession() {
        room.disconnect()
        testScheduler.runCurrent()
        assertNull(room.selectedTransport)
    }

    @Test
    fun aSlowTcpSessionIsReportedOnce() = runTest {
        component.rtcEngine().gravixIceClock = { testScheduler.currentTime }
        connect()
        val sub = getSubscriberPeerConnection()
        sub.statsReport = tcpStats(rttS = 4.2)
        val events = EventCollector(room.events, coroutineRule.scope)
        sub.selects(MockCandidatePairChangeEvent.TCP_HOST)
        step(40_000)
        val requests = sub.statsRequests
        endSession()
        val slow = events.stopCollecting().filterIsInstance<RoomEvent.SlowTcpTransport>()
        assertEquals("once per episode: $slow (stats requests $requests)", 1, slow.size)
        assertEquals(4200L, slow.single().rttMs)
        assertTrue(slow.single().overMs >= GravixRecoveryOptions.DEFAULT_SLOW_TCP_FOR_MS)
    }

    @Test
    fun noStatsReadingWhileAResumeRuns() = runTest {
        // review F1: the TCP-only reading must not run while the session is being recovered
        connect()
        val sub = getSubscriberPeerConnection()
        sub.statsReport = tcpStats(rttS = 0.2)
        sub.selects(MockCandidatePairChangeEvent.TCP_HOST)
        step(6_000)
        val steady = sub.statsRequests
        val before = wsFactory.ws
        before.close(1006, "gone") // the signal drops: a resume starts; both connections still read connected
        step(100)
        val resume = wsFactory.ws
        val during = sub.statsRequests
        step(6_000) // the resume dial is not answered yet
        val afterWait = sub.statsRequests
        wsFactory.listener.onOpen(resume, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        connectPeerConnection()
        step(6_000) // resumed: the reading goes on
        val resumed = sub.statsRequests
        endSession()
        assertTrue("read while steady ($steady)", steady > 0)
        assertEquals("no getStats while the resume runs", during, afterWait)
        assertTrue("reading again once resumed ($afterWait -> $resumed)", resumed > afterWait)
    }

    @Test
    fun aFullRejoinStartsTheTransportAfresh() = runTest {
        // review F2: the dead session's TCP must not carry over (getters, previous, polling)
        connect()
        getSubscriberPeerConnection().apply {
            statsReport = tcpStats(rttS = 0.2)
            selects(MockCandidatePairChangeEvent.TCP_HOST)
        }
        val before = wsFactory.ws
        disconnectPeerConnection()
        var dial: MockWebSocket? = null
        repeat(100) { if (dial == null) { step(50); if (wsFactory.ws !== before) dial = wsFactory.ws } }
        dial!!.cancel() // the resume fails: full rejoin
        val resumeWs = dial!!
        dial = null
        repeat(100) { if (dial == null) { step(50); if (wsFactory.ws !== resumeWs) dial = wsFactory.ws } }
        val events = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onOpen(dial!!, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN)
        connectPeerConnection()
        val newSub = getSubscriberPeerConnection()
        step(6_000)
        val duringRejoin = room.selectedTransport
        val statsOnNew = newSub.statsRequests
        newSub.selects(MockCandidatePairChangeEvent.UDP_HOST)
        step(100)
        val transport = room.selectedTransport
        endSession()
        val changes = events.stopCollecting().filterIsInstance<RoomEvent.IceTransportChanged>()
        assertNull("the new session's transport is not known yet", duringRejoin)
        assertEquals("no getStats on the new session from the old TCP state", 0, statsOnNew)
        assertEquals(GravixIceTransport.UDP, transport)
        assertEquals(listOf<GravixIceTransport?>(null), changes.map { it.previous })
    }

    @Test
    fun aStatsReadingThatNeverAnswersDoesNotStopTheWatch() = runTest {
        // review F7: a getStats whose callback never comes must not wedge the reading
        connect()
        val sub = getSubscriberPeerConnection()
        sub.statsReport = tcpStats(rttS = 0.2)
        sub.statsNeverDelivers = true
        sub.selects(MockCandidatePairChangeEvent.TCP_HOST)
        step(21_000)
        val requests = sub.statsRequests
        sub.statsNeverDelivers = false
        endSession()
        assertTrue("the reading went on after an unanswered one ($requests requests)", requests >= 2)
    }

    @Test
    fun aUdpSessionCostsNoStatsReading() = runTest {
        connect()
        val sub = getSubscriberPeerConnection()
        sub.selects(MockCandidatePairChangeEvent.UDP_HOST)
        val before = sub.statsRequests
        step(30_000)
        assertEquals("no periodic getStats while on UDP", before, sub.statsRequests)
    }

    @Test
    fun theTransportIsForgottenWhenTheSessionEnds() = runTest {
        connect()
        getSubscriberPeerConnection().selects(MockCandidatePairChangeEvent.RELAY)
        assertEquals(GravixIceTransport.RELAY, room.selectedTransport)
        room.disconnect()
        testScheduler.runCurrent()
        assertNull(room.selectedTransport)
    }

    @Test
    fun switchedOffNothingIsObserved() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(iceTransportWatchMs = null)
        connect()
        val sub = getSubscriberPeerConnection()
        sub.statsReport = tcpStats(rttS = 4.2)
        val events = EventCollector(room.events, coroutineRule.scope)
        sub.selects(MockCandidatePairChangeEvent.TCP_HOST)
        step(30_000)
        assertNull(room.selectedTransport)
        val observed = events.stopCollecting().filter { it is RoomEvent.IceTransportChanged || it is RoomEvent.SlowTcpTransport }
        assertTrue(observed.toString(), observed.isEmpty())
        assertEquals("no getStats at all", 0, sub.statsRequests)
        endSession()
    }

    @Test
    fun upstreamIsOff() {
        assertNull(GravixRecoveryOptions.UPSTREAM.iceTransportWatchMs)
        assertEquals(GravixRecoveryOptions.DEFAULT_ICE_TRANSPORT_WATCH_MS, GravixRecoveryOptions().iceTransportWatchMs)
    }
}
