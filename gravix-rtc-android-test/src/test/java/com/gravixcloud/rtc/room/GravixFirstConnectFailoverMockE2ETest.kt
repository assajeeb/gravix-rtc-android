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
import com.gravixcloud.rtc.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * GRAVIX: a FIRST connect that fails at its url and moves to another region (Room.connect's failover; Phase 2 proved
 * only the reconnect path). The room keeps the region list of an earlier connect to the same url (as an app that
 * connects the same Room again does); the list host answers 503 now (its region is down).
 *
 * - the room reports where it landed: connectedUrl / connectedRegion = the failover region, one ConnectedUrlChanged
 *   (CONNECT, no previous url);
 * - GravixRecoveryOptions.refusalEndsConnect: a refused connect (HTTP 401/403: the token, not the region) is not
 *   failed over (the Flutter SDK's behaviour: `_gravixConnectAttempt` rethrows a NotAllowed ConnectException);
 *   switched off, the upstream engine tries the next region with the same token.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixFirstConnectFailoverMockE2ETest : MockE2ETest() {

    private val blr = TestData.EXAMPLE_URL
    private val sgp = "wss://sgp1.gravixcloud.test"
    private val doh = "wss://doh1.gravixcloud.test"

    /** Runs OkHttp's async calls inline: the region-list fetch never resumes the room on an OkHttp thread. */
    private object Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    @Before
    fun setUpRegions() {
        GravixRegionMeasurements.clear()
        val now = GravixRegionMeasurements.clock.wallMs()
        fun m(region: String, url: String, rtt: Long) = GravixRegionMeasurement(GravixRegionUrl(region, url), rtt, listOf(rtt, rtt))
        GravixRegionMeasurements.setCurrent(
            GravixRegionMeasurementResult(now, listOf(m("sgp1", sgp, 61), m("blr1", blr, 120), m("doh1", doh, 260)), best = m("sgp1", sgp, 61)),
        )
        setRegionUrlProviderTesting(true)
        // the list kept from an earlier connect of this room to blr1; blr1's /settings/regions now answers 503
        val http = OkHttpClient.Builder()
            .dispatcher(Dispatcher(Inline))
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("down")
                    .body("".toResponseBody()).build()
            }
            .build()
        val provider = RegionUrlProvider(java.net.URI(blr), "token", http, Json { ignoreUnknownKeys = true }).apply {
            setServerReportedRegions(RegionSettings(listOf(RegionInfo("doh1", doh, 0), RegionInfo("sgp1", sgp, 1), RegionInfo("blr1", blr, 2))))
        }
        Room::class.java.getDeclaredField("regionUrlProvider").apply { isAccessible = true }.set(room, provider)
    }

    @After
    fun tearDownRegions() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
    }

    private suspend fun awaitConnecting() {
        room::state.flow.takeWhile { it != Room.State.CONNECTING }.collect()
    }

    private fun TestScope.advance() {
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
    }

    private fun failDial(ws: MockWebSocket, status: Int?) {
        val response = status?.let {
            Response.Builder().request(wsFactory.request).protocol(Protocol.HTTP_1_1).code(it).message("").build()
        }
        wsFactory.listener.onFailure(ws, IOException("dial failed"), response)
    }

    @Test
    fun aFirstConnectFailoverReportsTheRegionItLandedOn() = runTest {
        val seen = mutableListOf<RoomEvent.ConnectedUrlChanged>()
        room.addConnectedUrlListener { seen.add(it) }
        val events = EventCollector(room.events, coroutineRule.scope)
        val job = coroutineRule.scope.launch { room.connect(blr, "token") }
        awaitConnecting()
        val first = wsFactory.ws
        assertEquals("www.example.com", wsFactory.request.url.host)
        failDial(first, status = null) // blr1 is down: no answer
        advance()
        assertTrue("the failover dialled again", wsFactory.ws !== first)
        assertEquals("the nearest measured region", "sgp1.gravixcloud.test", wsFactory.request.url.host)

        // sgp1 accepts; its JoinResponse names no region (the measurement names it)
        val join = TestData.JOIN.toBuilder().apply {
            join = join.toBuilder().apply { serverInfo = serverInfo.toBuilder().setRegion("").build() }.build()
        }.build()
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join)
        job.join()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals(sgp, room.connectedUrl)
        assertEquals("sgp1", room.connectedRegion)
        val changed = events.stopCollecting().filterIsInstance<RoomEvent.ConnectedUrlChanged>()
        assertEquals("one ConnectedUrlChanged event", 1, changed.size)
        assertEquals(1, seen.size)
        assertEquals(RoomEvent.ConnectedUrlChangeReason.CONNECT, changed[0].reason)
        assertEquals(sgp, changed[0].url)
        assertEquals("sgp1", changed[0].region)
        assertNull("a first connect has no previous url", changed[0].previousUrl)
    }

    @Test
    fun aRefusedFirstConnectIsNotFailedOver() = runTest {
        var error: Throwable? = null
        val job = coroutineRule.scope.launch { runCatching { room.connect(blr, "token") }.onFailure { error = it } }
        awaitConnecting()
        val first = wsFactory.ws
        failDial(first, status = 401) // the token is refused: every region would refuse it
        advance()
        job.join()
        assertNotNull("the connect failed", error)
        assertSame("no other region was dialled", first, wsFactory.ws)
        assertEquals(Room.State.DISCONNECTED, room.state)
    }

    @Test
    fun refusalEndsConnectOffFailsOverAsUpstream() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(refusalEndsConnect = false)
        val job = coroutineRule.scope.launch { runCatching { room.connect(blr, "token") } }
        awaitConnecting()
        val first = wsFactory.ws
        failDial(first, status = 403)
        advance()
        assertTrue("off: the refusal is failed over (upstream)", wsFactory.ws !== first)
        job.cancel()
    }

    @Test
    fun upstreamOptionsAreAllOff() {
        assertEquals(false, GravixRecoveryOptions.UPSTREAM.refusalEndsConnect)
        assertEquals(true, GravixRecoveryOptions().refusalEndsConnect)
    }
}
