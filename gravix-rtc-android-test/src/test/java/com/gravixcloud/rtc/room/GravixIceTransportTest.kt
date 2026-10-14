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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.MockCandidatePairChangeEvent
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

/**
 * GRAVIX (GravixRecoveryOptions.iceTransportWatchMs; the Flutter SDK 0.4.18's ice_transport_watch_test): the
 * session's ICE transport from the selected candidate pair, and the slow-TCP signal. Device run 2026-10-14: after a
 * Wi-Fi drop every session ran over ICE/TCP (the server's per-participant TCP preference) at 1.6-16 s round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixIceTransportTest {

    // ------------------------------------------------------------------ the candidate line

    @Test
    fun aCandidateLineGivesItsTransport() {
        assertEquals(GravixIceTransport.UDP, GravixIceTransport.ofCandidateSdp(MockCandidatePairChangeEvent.UDP_HOST))
        assertEquals(GravixIceTransport.TCP, GravixIceTransport.ofCandidateSdp(MockCandidatePairChangeEvent.TCP_HOST))
        assertEquals(GravixIceTransport.RELAY, GravixIceTransport.ofCandidateSdp(MockCandidatePairChangeEvent.RELAY))
        assertEquals(
            "srflx over UDP",
            GravixIceTransport.UDP,
            GravixIceTransport.ofCandidateSdp("candidate:4 1 UDP 1686052607 198.51.100.4 40000 typ srflx raddr 10.0.0.2 rport 40000"),
        )
        assertEquals(
            "a relay is RELAY whatever the protocol to it",
            GravixIceTransport.RELAY,
            GravixIceTransport.ofCandidateSdp("candidate:5 1 tcp 1 203.0.113.7 443 typ relay raddr 0.0.0.0 rport 0"),
        )
        assertEquals(GravixIceTransport.TCP, GravixIceTransport.ofCandidateSdp("a=" + MockCandidatePairChangeEvent.TCP_HOST))
        assertNull(GravixIceTransport.ofCandidateSdp(null))
        assertNull(GravixIceTransport.ofCandidateSdp(""))
        assertNull(GravixIceTransport.ofCandidateSdp("not a candidate"))
    }

    @Test
    fun theSessionIsTcpWhenEitherIsTcpElseRelayWhenEitherIsRelayed() {
        assertEquals(GravixIceTransport.TCP, GravixIceTransport.session(GravixIceTransport.UDP, GravixIceTransport.TCP))
        assertEquals(GravixIceTransport.TCP, GravixIceTransport.session(GravixIceTransport.RELAY, GravixIceTransport.TCP))
        assertEquals(GravixIceTransport.RELAY, GravixIceTransport.session(null, GravixIceTransport.RELAY))
        assertEquals(GravixIceTransport.UDP, GravixIceTransport.session(GravixIceTransport.UDP, null))
        assertNull(GravixIceTransport.session(null, null))
    }

    // ------------------------------------------------------------------ the stats report

    @Test
    fun theSelectedPairOfAStatsReport() {
        val pair = GravixSelectedPair.of(report(protocol = "tcp", type = "host", rttS = 1.6))
        assertEquals(GravixIceTransport.TCP, pair.transport)
        assertEquals(1600L, pair.rttMs)
        val relay = GravixSelectedPair.of(report(protocol = "udp", type = "relay", rttS = 0.03))
        assertEquals(GravixIceTransport.RELAY, relay.transport)
        assertEquals(30L, relay.rttMs)
    }

    @Test
    fun withoutATransportEntryASelectedPairIsUsed() {
        val pair = GravixSelectedPair.of(report(protocol = "udp", type = "srflx", rttS = 0.008, viaTransport = false))
        assertEquals(GravixIceTransport.UDP, pair.transport)
        assertEquals(8L, pair.rttMs)
    }

    @Test
    fun noSelectedPairIsUnknown() {
        assertEquals(GravixSelectedPair.NONE, GravixSelectedPair.of(RTCStatsReport(0, emptyMap())))
        assertEquals(GravixSelectedPair.NONE, GravixSelectedPair.of(null))
    }

    // ------------------------------------------------------------------ the watch

    private class Harness(scope: TestScope, var intervalMs: Long? = 5_000) {
        val changes = mutableListOf<String>()
        val slow = mutableListOf<Pair<Long, Long>>()
        var publisherStats: RTCStatsReport? = null
        var subscriberStats: RTCStatsReport? = null
        var statsRequests = 0
        var steady = true
        val watch = GravixIceTransportWatch(
            settings = { GravixRecoveryOptions(iceTransportWatchMs = intervalMs) }, // slow TCP: 1 s for 10 s (defaults)
            steady = { steady },
            readStats = { pub ->
                statsRequests++
                if (pub) publisherStats else subscriberStats
            },
            now = { scope.testScheduler.currentTime },
            onChanged = { t, prev, p, s -> changes += "$prev->$t ($p/$s)" },
            onSlowTcp = { rtt, over -> slow += rtt to over },
        )

        init {
            watch.start(scope.backgroundScope) // the session's scope (as the engine's join does)
        }
    }

    @Test
    fun theSelectedPairCallbackSetsTheTransportAndReportsOnlyChanges() = runTest {
        val h = Harness(this)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.UDP)
        h.watch.onSelectedPair(isPublisher = true, GravixIceTransport.UDP)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        assertEquals(listOf("null->UDP (null/UDP)", "UDP->TCP (UDP/TCP)"), h.changes)
        assertEquals(GravixIceTransport.TCP, h.watch.transport)
        assertEquals(GravixIceTransport.UDP, h.watch.publisher)
        assertEquals(GravixIceTransport.TCP, h.watch.subscriber)
        assertEquals("the callback costs no getStats", 0, h.statsRequests)
    }

    @Test
    fun aSlowTcpRoundTripIsReportedOnceAfterTenSecondsAndAgainAfterItRecovered() = runTest {
        val h = Harness(this)
        h.subscriberStats = report(protocol = "tcp", type = "host", rttS = 1.6)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        advanceTimeBy(14_000)
        runCurrent()
        assertTrue("not yet: ${h.slow}", h.slow.isEmpty()) // readings at 5 s and 10 s: slow for 5 s
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("reading at 15 s: 10 s over the threshold", listOf(1600L to 10_000L), h.slow)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("once per episode", 1, h.slow.size)
        h.subscriberStats = report(protocol = "tcp", type = "host", rttS = 0.2)
        advanceTimeBy(5_000)
        runCurrent()
        h.subscriberStats = report(protocol = "tcp", type = "host", rttS = 2.0)
        advanceTimeBy(15_100)
        runCurrent()
        assertEquals("a new episode after it recovered", listOf(1600L to 10_000L, 2000L to 10_000L), h.slow)
        h.watch.stop()
    }

    @Test
    fun noRoundTripReadingWhileTheSessionIsOnUdp() = runTest {
        val h = Harness(this)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.UDP)
        h.watch.onSelectedPair(isPublisher = true, GravixIceTransport.RELAY)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no getStats off TCP (lightweight)", 0, h.statsRequests)
        h.watch.stop()
    }

    @Test
    fun noReadingWhileTheSessionIsNotSteady() = runTest {
        // review F1: no getStats while the session is (re)connecting
        val h = Harness(this)
        h.subscriberStats = report(protocol = "tcp", type = "host", rttS = 1.6)
        h.steady = false
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, h.statsRequests)
        h.steady = true
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals("both connections read once steady", 2, h.statsRequests)
        h.watch.stop()
    }

    @Test
    fun aRelayedTcpSessionIsNotSlowTcp() = runTest {
        val h = Harness(this)
        h.publisherStats = report(protocol = "udp", type = "relay", rttS = 3.0)
        h.subscriberStats = report(protocol = "tcp", type = "host", rttS = 0.05)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue("only a TCP connection's round trip counts", h.slow.isEmpty())
        h.watch.stop()
    }

    @Test
    fun offObservesNothing() = runTest {
        val h = Harness(this, intervalMs = null)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(h.watch.transport)
        assertTrue(h.changes.isEmpty())
        assertEquals(0, h.statsRequests)
    }

    @Test
    fun aLateSelectedPairAfterTheSessionEndedIsIgnored() = runTest {
        // review F4: a pair callback in flight when the session closes must not bring the transport back
        val h = Harness(this)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.UDP)
        h.watch.stop()
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        assertNull(h.watch.transport)
        assertEquals(listOf("null->UDP (null/UDP)"), h.changes)
    }

    @Test
    fun leavingTcpEndsTheSlowEpisode() = runTest {
        // review F5: TCP slow for 5 s, then UDP for a minute, then TCP slow again: the new episode starts from zero
        val h = Harness(this)
        h.subscriberStats = report(protocol = "tcp", type = "host", rttS = 1.6)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        advanceTimeBy(10_100) // readings at 5 s and 10 s: slow for 5 s
        runCurrent()
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.UDP)
        advanceTimeBy(60_000)
        runCurrent()
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        advanceTimeBy(5_100) // first reading of the new episode
        runCurrent()
        assertTrue("not at the new episode's first reading: ${h.slow}", h.slow.isEmpty())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(1600L to 10_000L), h.slow)
        h.watch.stop()
    }

    @Test
    fun stopForgetsTheTransport() = runTest {
        val h = Harness(this)
        h.watch.onSelectedPair(isPublisher = false, GravixIceTransport.TCP)
        h.watch.stop()
        assertNull(h.watch.transport)
        assertNull(h.watch.subscriber)
    }

    private fun report(protocol: String, type: String, rttS: Double, viaTransport: Boolean = true): RTCStatsReport {
        val stats = linkedMapOf<String, RTCStats>()
        fun add(type: String, id: String, members: Map<String, Any>) {
            stats[id] = RTCStats(0, type, id, members)
        }
        if (viaTransport) add("transport", "T1", mapOf("selectedCandidatePairId" to "CP1"))
        add(
            "candidate-pair",
            "CP1",
            mapOf("localCandidateId" to "L1", "currentRoundTripTime" to rttS, "state" to "succeeded", "nominated" to true),
        )
        add("candidate-pair", "CP2", mapOf("localCandidateId" to "L2", "state" to "failed", "nominated" to false))
        add("local-candidate", "L1", mapOf("candidateType" to type, "protocol" to protocol))
        add("local-candidate", "L2", mapOf("candidateType" to "host", "protocol" to "udp"))
        return RTCStatsReport(0, stats)
    }
}
