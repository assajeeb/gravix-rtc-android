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
import com.gravixcloud.rtc.regions.RegionTestKit.useVirtualClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * GRAVIX: measured failover order (port of the Flutter SDK's region_failover_order_test), with the field numbers
 * of 2026-10-09 (Bangladesh: sgp1 61 ms, blr1 ~120 ms, doh1 ~260 ms; blr1's `/settings/regions` = [doh1, sgp1, blr1]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixRegionFailoverOrderTest {

    private data class R(val region: String, val url: String)

    private fun r(region: String) = R(region, "wss://$region.gravixcloud.run")
    private fun order(c: List<R>) =
        GravixRegionFailoverOrder.order(c, GravixRegionMeasurements.current(), { it.region }, { it.url }).map { it.region }

    private suspend fun TestScope.measure(rtts: Map<String, Long?>) {
        GravixRegionMeasurements.measure(
            onTestScheduler(
                GravixRegionMeasureOptions().apply {
                    regionUrls = rtts.keys.map { GravixRegionUrl(it, "wss://$it.gravixcloud.run") }
                    sampler = GravixRegionSampler { g -> rtts[g.region] }
                },
            ),
        )
    }

    @Before
    fun setUp() = GravixRegionMeasurements.clear()

    @After
    fun tearDown() {
        GravixRegionMeasurements.clear()
        RegionTestKit.resetClock()
    }

    @Test
    fun nearOnlyCap() {
        assertEquals(161L, GravixRegionFailoverOrder.nearOnlyCapMs(61))
        assertEquals(300L, GravixRegionFailoverOrder.nearOnlyCapMs(150))
        assertEquals(100L, GravixRegionFailoverOrder.nearOnlyCapMs(0))
    }

    @Test
    fun fieldCaseFromBlr1Sgp1FirstDoh1AfterIt() = runTest {
        useVirtualClock()
        measure(mapOf("sgp1" to 61L, "blr1" to 120L, "doh1" to 260L))
        assertEquals(listOf("sgp1", "doh1"), order(listOf(r("doh1"), r("sgp1"))))
    }

    @Test
    fun theCapIsTakenAgainstTheBestRemainingCandidate() = runTest {
        useVirtualClock()
        // sgp1 (the best) failed already; blr1 120 is now the best: doh1 260 > 240 still far
        measure(mapOf("sgp1" to 61L, "blr1" to 120L, "doh1" to 260L))
        assertEquals(listOf("blr1", "doh1"), order(listOf(r("doh1"), r("blr1"))))
        // with only a far one left it is still offered: the server list is the last resort
        assertEquals(listOf("doh1"), order(listOf(r("doh1"))))
    }

    @Test
    fun unmeasuredAfterNearBeforeFarFailedLast() = runTest {
        useVirtualClock()
        measure(mapOf("sgp1" to 61L, "doh1" to 260L, "fra1" to null))
        assertEquals(listOf("sgp1", "ams1", "doh1", "fra1"), order(listOf(r("fra1"), r("doh1"), r("ams1"), r("sgp1"))))
    }

    @Test
    fun noFreshMeasurementKeepsTheServerOrder() {
        assertEquals(listOf("doh1", "sgp1", "blr1"), order(listOf(r("doh1"), r("sgp1"), r("blr1"))))
    }

    @Test
    fun urlMatchingWhenSlugsDiffer() = runTest {
        useVirtualClock()
        measure(mapOf("sgp1" to 61L, "doh1" to 260L))
        val c = listOf(R("gulf", "wss://doh1.gravixcloud.run/"), R("asia", "WSS://SGP1.gravixcloud.run"))
        assertEquals(listOf("asia", "gulf"), order(c))
    }

    @Test
    fun regionOfUrlKnownListFirstThenTheMeasurement() = runTest {
        useVirtualClock()
        measure(mapOf("sgp1" to 61L))
        assertEquals("sgp1", GravixRegionFailoverOrder.regionOfUrl("wss://sgp1.gravixcloud.run/"))
        assertEquals("x1", GravixRegionFailoverOrder.regionOfUrl("wss://x.example", listOf(GravixRegionUrl("x1", "wss://x.example"))))
        assertNull(GravixRegionFailoverOrder.regionOfUrl("wss://nowhere.example"))
        assertEquals("https and a trailing slash match wss", "sgp1", GravixRegionFailoverOrder.regionOfUrl("https://SGP1.gravixcloud.run/"))
    }

    @Test
    fun normalizeUrl() {
        assertEquals("wss://a.example.com/x", GravixRegionFailoverOrder.normalizeUrl(" HTTPS://A.Example.com/x// "))
        assertEquals("ws://10.0.2.2:7880", GravixRegionFailoverOrder.normalizeUrl("http://10.0.2.2:7880/"))
    }
}
