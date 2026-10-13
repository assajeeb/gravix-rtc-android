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

import com.gravixcloud.rtc.regions.GravixRegionMeasurement
import com.gravixcloud.rtc.regions.GravixRegionMeasurementResult
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * GRAVIX: RegionUrlProvider's failover lookup (port of the "RegionUrlProvider.getNextBestRegionUrl" group of the
 * Flutter SDK's region_list_fallback_test): measured order with the failed url last, the last good list on a failed
 * fetch (not on a refusal), and no fetch from the host that just failed. The list host is a MockWebServer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GravixRegionUrlProviderTest : BaseTest() {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(r: String) = "wss://$r.gravixcloud.run"
    private val sgp = url("sgp1")
    private val blr = url("blr1")
    private val doh = url("doh1")

    private fun list(vararg regions: String) = RegionSettings(regions.mapIndexed { i, r -> RegionInfo(r, url(r), i.toLong()) })
    private fun listJson(vararg regions: String) =
        """{"regions":[${regions.mapIndexed { i, r -> """{"region":"$r","url":"${url(r)}","distance":"$i"}""" }.joinToString(",")}]}"""

    private fun provider() = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), json)

    /** A list kept from earlier in a long session (the provider's 30 s cache has expired). */
    private fun kept(): RegionUrlProvider = provider().apply {
        setServerReportedRegions(list("doh1", "sgp1", "blr1"))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
    }

    private fun measure() {
        val now = GravixRegionMeasurements.clock.wallMs()
        fun m(r: String, rtt: Long) = GravixRegionMeasurement(GravixRegionUrl(r, url(r)), rtt, listOf(rtt, rtt))
        GravixRegionMeasurements.setCurrent(
            GravixRegionMeasurementResult(now, listOf(m("sgp1", 61), m("blr1", 120), m("doh1", 260)), best = m("sgp1", 61)),
        )
    }

    @Before
    fun setUp() {
        setRegionUrlProviderTesting(true)
        GravixRegionMeasurements.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
        server.shutdown()
    }

    @Test
    fun failedFetchUsesTheKeptListNearFirstTheFailedUrlLast() = runTest {
        measure()
        for (failure in listOf(
            MockResponse().setResponseCode(500),
            MockResponse().setResponseCode(503),
        )) {
            val p = kept()
            repeat(4) { server.enqueue(failure) }
            val before = server.requestCount
            suspend fun next() = p.getNextBestRegionUrl(currentUrl = doh, measuredOrder = true, lastGoodFallback = true, currentUrlFailed = true)
            assertEquals(sgp, next())
            assertEquals("measured 120 ms: near (cap 161 ms)", blr, next())
            assertEquals(doh, next())
            assertNull(next())
            assertEquals("the list host is not the url that failed: it is asked every time", 4, server.requestCount - before)
        }
    }

    @Test
    fun theListHostIsTheUrlThatJustFailedNoFetch() = runTest {
        measure()
        val p = kept()
        val listHostUrl = server.url("").toString().replaceFirst("http://", "ws://")
        assertEquals(sgp, p.getNextBestRegionUrl(currentUrl = listHostUrl, measuredOrder = true, lastGoodFallback = true, currentUrlFailed = true))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSameHostOnAnotherPortIsAnotherServerItIsAsked() = runTest {
        // review 2026-10-11: the check compared hosts only; a region on the same host but another port (the sim's
        // regions, a self-hosted pair behind one IP) is another server and must still be asked
        val p = kept()
        val otherPort = "ws://${server.hostName}:${server.port + 1}"
        server.enqueue(MockResponse().setBody(listJson("fra1", "blr1")))
        assertEquals(url("fra1"), p.getNextBestRegionUrl(currentUrl = otherPort, measuredOrder = false, lastGoodFallback = true, currentUrlFailed = true))
        assertEquals("the list host (another port) is asked", 1, server.requestCount)
    }

    @Test
    fun sameServerComparesHostAndEffectivePort() {
        fun same(a: String, b: String) = RegionUrlProvider.gravixSameServer(a, java.net.URI(b))
        assertEquals(true, same("wss://blr1.gravixcloud.run", "https://blr1.gravixcloud.run"))
        assertEquals(true, same("wss://blr1.gravixcloud.run:443/rtc", "https://BLR1.gravixcloud.run"))
        assertEquals(true, same("ws://10.0.2.2:7980", "http://10.0.2.2:7980"))
        assertEquals(false, same("ws://10.0.2.2:7990", "http://10.0.2.2:7980"))
        assertEquals(false, same("ws://blr1.gravixcloud.run", "https://blr1.gravixcloud.run"))
        assertEquals(false, same("wss://sgp1.gravixcloud.run", "https://blr1.gravixcloud.run"))
        assertEquals(false, same("not a url", "https://blr1.gravixcloud.run"))
    }

    @Test
    fun notAFailureAtThatUrlOrNothingKeptTheListHostIsStillAsked() = runTest {
        val listHostUrl = server.url("").toString().replaceFirst("http://", "ws://")
        val refused = kept()
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(doh, refused.getNextBestRegionUrl(currentUrl = listHostUrl, measuredOrder = false, lastGoodFallback = true, currentUrlFailed = false))
        assertEquals(1, server.requestCount)

        val empty = provider()
        server.enqueue(MockResponse().setBody(listJson("sgp1", "blr1")))
        assertEquals(sgp, empty.getNextBestRegionUrl(currentUrl = listHostUrl, measuredOrder = false, lastGoodFallback = true, currentUrlFailed = true))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun offTheFetchErrorEscapes() = runTest {
        val p = kept()
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = true, lastGoodFallback = false, currentUrlFailed = true)
            fail("expected the fetch error")
        } catch (e: RoomException.ConnectException) {
        }
    }

    @Test
    fun aRefusalIsTheTokenNotTheRegionItStillEscapes() = runTest {
        val p = kept()
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = true, lastGoodFallback = true, currentUrlFailed = true)
            fail("a 401 must escape")
        } catch (e: RoomException.ConnectException) {
        }
    }

    @Test
    fun nothingKeptTheFetchErrorEscapes() = runTest {
        val p = provider()
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = false, lastGoodFallback = true, currentUrlFailed = true)
            fail("nothing kept")
        } catch (e: RoomException.ConnectException) {
        }
    }

    @Test
    fun aFetchThatWorksReplacesTheKeptList() = runTest {
        val p = kept()
        server.enqueue(MockResponse().setBody(listJson("fra1", "blr1")))
        assertEquals(url("fra1"), p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = false, lastGoodFallback = true, currentUrlFailed = true))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun withoutAFreshMeasurementTheServerOrderStands() = runTest {
        val p = provider().apply { setServerReportedRegions(list("doh1", "sgp1", "blr1")) }
        assertEquals(doh, p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = true, lastGoodFallback = true, currentUrlFailed = true))
        assertEquals(sgp, p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = true, lastGoodFallback = true, currentUrlFailed = true))
        assertEquals("the failed url last", blr, p.getNextBestRegionUrl(currentUrl = blr, measuredOrder = true, lastGoodFallback = true, currentUrlFailed = true))
    }
}
