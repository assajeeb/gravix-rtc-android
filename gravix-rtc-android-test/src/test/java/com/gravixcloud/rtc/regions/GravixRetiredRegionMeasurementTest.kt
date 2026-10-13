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

package com.gravixcloud.rtc.regions

import com.gravixcloud.rtc.regions.RegionTestKit.onTestScheduler
import com.gravixcloud.rtc.regions.RegionTestKit.unknownNet
import com.gravixcloud.rtc.regions.RegionTestKit.useVirtualClock
import com.gravixcloud.rtc.room.RETIRED_BLR_URL
import com.gravixcloud.rtc.room.RETIRED_RTC_URL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GRAVIX(retired servers, 2026-10-12): the start-up measurement never probes or picks a retired Gravix server, whether
 * the app passes the list or the gateway serves it. The sampler is a fake: nothing is contacted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixRetiredRegionMeasurementTest {

    private val entries = listOf(
        GravixRegionUrl("old", RETIRED_RTC_URL),
        GravixRegionUrl("sgp1", "wss://sgp1.example.test"),
        GravixRegionUrl("oldprobe", "wss://blr1.example.test", probeUrl = RETIRED_BLR_URL),
    )

    @Before
    fun setUp() {
        GravixRegionMeasurements.clear()
    }

    @After
    fun tearDown() {
        GravixRegionMeasurements.clear()
        RegionTestKit.resetClock()
    }

    private fun sampler(sampled: MutableList<String>) = GravixRegionSampler { r ->
        sampled += r.region
        if (r.region == "sgp1") 40L else 10L
    }

    @Test
    fun anAppListWithRetiredServersMeasuresOnlyTheRest() = runTest {
        useVirtualClock()
        val sampled = mutableListOf<String>()
        val result = GravixRegionMeasurements.measure(
            onTestScheduler(
                GravixRegionMeasureOptions().apply {
                    regionUrls = entries
                    sampler = sampler(sampled)
                    budgetMs = 2000
                    networkType = unknownNet()
                },
            ),
        )
        assertEquals(setOf("sgp1"), sampled.toSet())
        assertEquals(listOf("sgp1"), result.regions.map { it.entry.region })
        assertEquals("sgp1", result.best?.entry?.region)
    }

    @Test
    fun aGatewayListWithRetiredServersMeasuresOnlyTheRest() = runTest {
        useVirtualClock()
        val sampled = mutableListOf<String>()
        val served = GravixRegionDirectory(regions = entries, shortlist = listOf("old", "sgp1", "oldprobe"))
        val result = GravixRegionMeasurements.measure(
            onTestScheduler(
                GravixRegionMeasureOptions().apply {
                    regionsUrl = "https://gw.example.test/v1/regions"
                    fetchDirectory = GravixRegionDirectoryFetcher { GravixRegionDirectory.fromJson(Json.parseToJsonElement(served.toJson().toString())) }
                    store = GravixMemoryRegionListStore()
                    sampler = sampler(sampled)
                    budgetMs = 2000
                    networkType = unknownNet()
                },
            ),
        )
        assertEquals(setOf("sgp1"), sampled.toSet())
        assertEquals("sgp1", result.best?.entry?.region)
    }
}
