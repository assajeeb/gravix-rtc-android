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
import com.gravixcloud.rtc.regions.GravixRegionMeasurement
import com.gravixcloud.rtc.regions.GravixRegionMeasurementResult
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX: the region strategy inside the engine's reconnect loop (port of the Flutter SDK's
 * region_failover_engine_test, failover_evidence_test and region_list_fallback_test engine cases). The session is
 * on blr1 (TestData.EXAMPLE_URL); its `/settings/regions` is [doh1, sgp1, blr1] (configuration order, as the
 * field case of 2026-10-09); the start-up measurement says sgp1 61 ms, blr1 120 ms, doh1 260 ms.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixRegionFailoverMockE2ETest : MockE2ETest() {

    private val blr = TestData.EXAMPLE_URL
    private val sgp = "wss://sgp1.gravixcloud.test"
    private val doh = "wss://doh1.gravixcloud.test"

    private var networkChanged = false
    private var reachableHosts = setOf("sgp1.gravixcloud.test", "doh1.gravixcloud.test")
    private val probed = mutableListOf<String>()

    @Before
    fun setUpRegions() {
        // the Gravix host check stays on during connect(): Room.connect would otherwise fetch /settings/regions through
        // the test OkHttp client, whose failure resumes the unconfined test dispatcher on an OkHttp thread
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
        val now = GravixRegionMeasurements.clock.wallMs()
        fun m(region: String, url: String, rtt: Long) = GravixRegionMeasurement(GravixRegionUrl(region, url), rtt, listOf(rtt + 200, rtt))
        GravixRegionMeasurements.setCurrent(
            GravixRegionMeasurementResult(
                measuredAtMs = now,
                regions = listOf(m("sgp1", sgp, 61), m("blr1", blr, 120), m("doh1", doh, 260)),
                best = m("sgp1", sgp, 61),
            ),
        )
        component.rtcEngine().gravixFailoverEvidence = object : GravixFailoverEvidence {
            override fun networkChangedSince(sinceMs: Long) = networkChanged
            override fun networkAvailable() = true
            override suspend fun reachable(url: String, timeoutMs: Long): Boolean {
                probed.add(url)
                return java.net.URI(url).host in reachableHosts
            }
        }
    }

    @After
    fun tearDownRegions() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
    }

    /** The session's region list ([doh1, sgp1, blr1], as blr1's /settings/regions would serve it), no HTTP. */
    private fun seedRegionList() {
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("doh1", doh, 0), RegionInfo("sgp1", sgp, 1), RegionInfo("blr1", blr, 2))))
            }
    }

    /**
     * Advances virtual time until the engine dials a new WebSocket; returns it. Peer-connection state changes reach
     * the engine through the real RTC executor thread, so each step also yields a little real time (without it the
     * loop could finish before a disconnect is even delivered).
     */
    private fun TestScope.nextDial(previous: MockWebSocket): MockWebSocket {
        repeat(400) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(2)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        fail("the engine did not dial again; t=${testScheduler.currentTime}")
        throw IllegalStateException()
    }

    private fun dialedHost() = wsFactory.request.url.host
    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    /**
     * Starts a reconnect on the test thread (as an ICE failure would; a mocked ICE failure reaches the engine through
     * the real RTC executor thread, which races the virtual clock) and fails its resume attempt.
     */
    private fun TestScope.failResume(): MockWebSocket {
        component.rtcEngine().reconnect()
        val resume = nextDial(wsFactory.ws)
        assertTrue("attempt 0 is a resume", isResume())
        assertEquals("www.example.com", dialedHost())
        resume.cancel()
        return resume
    }

    /** The server names [serverRegion] in its JoinResponse ("" = names none). */
    private fun TestScope.succeedJoin(serverRegion: String = "") {
        val join = TestData.JOIN.toBuilder().apply {
            join = join.toBuilder().apply { serverInfo = serverInfo.toBuilder().setRegion(serverRegion).build() }.build()
        }.build()
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join)
        testScheduler.runCurrent()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun recoveryOptionsDefaultsUpstreamAndRoomOptions() {
        assertTrue(GravixRecoveryOptions().regionStrategyEnabled)
        assertFalse(GravixRecoveryOptions.UPSTREAM.regionStrategyEnabled)
        assertNull(GravixRecoveryOptions.UPSTREAM.sameUrlRetryDialTimeoutMs)
        assertEquals(GravixRecoveryOptions(), room.gravixRecovery)
        room.setRoomOptions(com.gravixcloud.rtc.RoomOptions(gravixRecovery = GravixRecoveryOptions.UPSTREAM))
        assertEquals(GravixRecoveryOptions.UPSTREAM, room.gravixRecovery)
        assertNull("UPSTREAM: the engine uses no region strategy", component.rtcEngine().gravixRegionStrategy())
        room.setRoomOptions(com.gravixcloud.rtc.RoomOptions())
        assertEquals("null leaves the room's value", GravixRecoveryOptions.UPSTREAM, room.gravixRecovery)
    }

    @Test
    fun aFreshConnectIsNeverBoundByALeftoverSameUrlDialTimeout() = runTest {
        // a reconnect that armed the same-url dial bound and then ended without dialing (policy exhausted, closed)
        component.rtcEngine().client.gravixNextDialTimeoutMs = GravixRecoveryOptions.DEFAULT_SAME_URL_RETRY_DIAL_TIMEOUT_MS
        val job = coroutineRule.scope.launch { room.connect(TestData.EXAMPLE_URL, "token") }
        testScheduler.advanceTimeBy(GravixRecoveryOptions.DEFAULT_SAME_URL_RETRY_DIAL_TIMEOUT_MS + 500)
        testScheduler.runCurrent()
        assertFalse("a slow WebSocket open of a fresh connect is not cut at 2.5 s", wsFactory.ws.isClosed)
        prepareSignal()
        job.join()
        connectPeerConnection()
        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun connectReportsTheConnectedUrlAndClearsItOnDisconnect() = runTest {
        val seen = mutableListOf<RoomEvent.ConnectedUrlChanged>()
        room.addConnectedUrlListener { seen.add(it) }
        connect()
        assertEquals(blr, room.connectedUrl)
        assertEquals(1, seen.size)
        assertEquals(RoomEvent.ConnectedUrlChangeReason.CONNECT, seen[0].reason)
        assertNull(seen[0].previousUrl)
        assertEquals("the JoinResponse's region wins", "Earth", room.connectedRegion)
        room.disconnect()
        assertNull(room.connectedUrl)
        assertNull(room.connectedRegion)
    }

    @Test
    fun aFailedFullReconnectRetriesTheSameUrlOnceThenGoesToTheNearestMeasuredRegion() = runTest {
        connect()
        seedRegionList()
        val events = EventCollector(room.events, coroutineRule.scope)

        val resume = failResume()

        // attempt 1: a full rejoin at the session url, with the short dial (2.5 s to the WebSocket)
        val sameUrl = nextDial(resume)
        assertEquals("www.example.com", dialedHost())
        assertFalse("a full rejoin, not a resume", isResume())
        testScheduler.advanceTimeBy(GravixRecoveryOptions.DEFAULT_SAME_URL_RETRY_DIAL_TIMEOUT_MS + 100)
        testScheduler.runCurrent()
        assertTrue("the short dial gave up on the silent session url", sameUrl.isClosed)

        // attempt 2: the nearest measured region (sgp1 61 ms), not the server list head (doh1)
        nextDial(sameUrl)
        assertEquals("sgp1.gravixcloud.test", dialedHost())
        assertTrue("evidence: the session url was probed", probed.any { it.contains("www.example.com") })
        succeedJoin(serverRegion = "") // the server names no region: the start-up measurement names it

        assertEquals(sgp, room.connectedUrl)
        assertEquals("sgp1", room.connectedRegion)
        val changed = events.stopCollecting().filterIsInstance<RoomEvent.ConnectedUrlChanged>()
        assertEquals(1, changed.size)
        assertEquals(RoomEvent.ConnectedUrlChangeReason.REJOIN, changed[0].reason)
        assertEquals(blr, changed[0].previousUrl)
        assertEquals("sgp1", changed[0].region)

        // the session url follows the session: the next resume dials sgp1, not blr1
        component.rtcEngine().reconnect()
        nextDial(wsFactory.ws)
        assertTrue(isResume())
        assertEquals("sgp1.gravixcloud.test", dialedHost())
    }

    @Test
    fun withoutEvidenceTheSessionStaysOnItsUrl() = runTest {
        reachableHosts = setOf("www.example.com", "sgp1.gravixcloud.test") // blr1 answers its probe
        connect()
        seedRegionList()
        val resume = failResume()
        val sameUrl = nextDial(resume)
        sameUrl.cancel()
        nextDial(sameUrl)
        assertEquals("nothing shows blr1 failed: stay", "www.example.com", dialedHost())
        succeedJoin()
        assertEquals(blr, room.connectedUrl)
    }

    @Test
    fun aNetworkChangeDuringTheReconnectKeepsTheSessionOnItsUrl() = runTest {
        connect()
        seedRegionList()
        networkChanged = true // the phone's Wi-Fi flapped: not evidence against the region
        val resume = failResume()
        val sameUrl = nextDial(resume)
        sameUrl.cancel()
        nextDial(sameUrl)
        assertEquals("www.example.com", dialedHost())
    }

    @Test
    fun upstreamOptionsKeepTheUpstreamOrderAndNoSameUrlRetry() = runTest {
        room.gravixRecovery = GravixRecoveryOptions.UPSTREAM
        connect()
        seedRegionList()
        val resume = failResume()
        nextDial(resume)
        assertEquals("upstream: the first full reconnect goes to the list head", "doh1.gravixcloud.test", dialedHost())
        assertTrue("upstream asks for no evidence", probed.isEmpty())
        succeedJoin()
        assertEquals("connectedUrl is reported with the upstream ordering too", doh, room.connectedUrl)
    }

    @Test
    fun measuredOrderOffUsesTheServerOrderAfterTheSameUrlRetry() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(measuredRegionFailover = false, failoverNeedsEvidence = false)
        connect()
        seedRegionList()
        val resume = failResume()
        val sameUrl = nextDial(resume)
        assertEquals("www.example.com", dialedHost())
        sameUrl.cancel()
        nextDial(sameUrl)
        assertEquals("server order: doh1 first", "doh1.gravixcloud.test", dialedHost())
    }
}
