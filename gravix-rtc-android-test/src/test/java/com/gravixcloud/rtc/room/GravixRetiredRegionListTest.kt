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

import com.gravixcloud.rtc.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX(retired servers, 2026-10-12): a region list never hands a retired Gravix server to the failover (the engine
 * dials region urls directly, not through Room.connect). The list host is a MockWebServer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GravixRetiredRegionListTest : BaseTest() {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val sgp = "wss://sgp1.gravixcloud.com"

    private fun provider() = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), json)
    private fun withRetired() = RegionSettings(
        listOf(RegionInfo("old", RETIRED_RTC_URL, 0), RegionInfo("sgp1", sgp, 1), RegionInfo("oldblr", RETIRED_BLR_URL, 2)),
    )

    @Before
    fun setUp() {
        setRegionUrlProviderTesting(true)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        setRegionUrlProviderTesting(false)
        server.shutdown()
    }

    @Test
    fun aFetchedListLeavesTheRetiredServersOut() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"regions":[{"region":"old","url":"$RETIRED_RTC_URL","distance":"0"},""" +
                    """{"region":"sgp1","url":"$sgp","distance":"1"},{"region":"oldblr","url":"$RETIRED_BLR_URL","distance":"2"}]}""",
            ),
        )
        assertEquals(listOf("sgp1"), provider().fetchRegionSettings()?.regions?.map { it.region })
    }

    @Test
    fun upstreamFailoverNeverReturnsARetiredServer() = runTest {
        val p = provider().apply { setServerReportedRegions(withRetired()) }
        assertEquals(sgp, p.getNextBestRegionUrl())
        assertNull(p.getNextBestRegionUrl())
    }

    @Test
    fun gravixFailoverNeverReturnsARetiredServer() = runTest {
        val p = provider().apply { setServerReportedRegions(withRetired()) }
        for (measured in listOf(false, true)) {
            p.clearAttemptedRegions()
            assertEquals(sgp, p.getNextBestRegionUrl(currentUrl = null, measuredOrder = measured, lastGoodFallback = true, currentUrlFailed = false))
            assertNull(p.getNextBestRegionUrl(currentUrl = sgp, measuredOrder = measured, lastGoodFallback = true, currentUrlFailed = true))
        }
        assertEquals(0, server.requestCount)
    }
}
