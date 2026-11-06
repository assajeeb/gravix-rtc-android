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
import com.gravixcloud.rtc.util.GravixLogSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * GRAVIX (2026-11-05, R1; port of the Flutter SDK's test/connect/video_region_probe_test.dart). Gulf test: a Saudi
 * tester's shortlist measured doh1, blr1 and dac1 only; fra1 was never measured, so "the nearest region with video" was
 * the nearest of two, and blr1 222-232 ms and dac1 230-242 ms flipped between steps. When the fastest measured region is
 * audio-only the other video regions are measured too: in the background, never on the join path, once per shortlist ttl
 * and network. The move's target is the hysteresis choice, so a near-tie is stable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixVideoRegionProbeTest {

    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val rtt = HashMap<String, Long?>()
    private val sampled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var holdExtra: CompletableDeferred<Unit>? = null

    private fun url(region: String) = "wss://$region.gravixcloud.test"

    private val directory = GravixRegionDirectory(
        regions = listOf(
            GravixRegionUrl("doh1", url("doh1"), media = listOf("audio")),
            GravixRegionUrl("blr1", url("blr1")),
            GravixRegionUrl("dac1", url("dac1")),
            GravixRegionUrl("fra1", url("fra1")),
            GravixRegionUrl("sgp1", url("sgp1")),
        ),
        shortlist = listOf("doh1", "blr1", "dac1"),
    )

    private suspend fun TestScope.measure(store: GravixRegionListStore = GravixMemoryRegionListStore()) =
        GravixRegionMeasurements.measure(
            onTestScheduler(
                GravixRegionMeasureOptions().apply {
                    regionsUrl = "https://gw.example.test/v1/regions"
                    fetchDirectory = GravixRegionDirectoryFetcher { directory }
                    sampler = GravixRegionSampler { r ->
                        sampled.add(r.region)
                        if (r.region == "fra1" || r.region == "sgp1") holdExtra?.await()
                        rtt[r.region]
                    }
                    this.store = store
                    budgetMs = 5000L
                    networkType = GravixNetworkReader { "wifi" }
                },
            ),
        )

    private fun held() = GravixRegionMeasurements.current()!!

    @Before
    fun setUp() {
        GravixRegionMeasurements.clear()
        GravixRegionMedia.autoDetect = true
        GravixRegionLog.sink = GravixLogSink { _, line -> lines.add(line) }
        rtt.putAll(mapOf("doh1" to 180L, "blr1" to 230L, "dac1" to 240L, "fra1" to 120L, "sgp1" to 300L))
    }

    @After
    fun tearDown() {
        GravixRegionMedia.autoDetect = true
        GravixRegionLog.sink = null
        GravixRegionMeasurements.clear()
        RegionTestKit.resetClock()
    }

    @Test
    fun theFastestIsAudioOnlyTheOtherVideoRegionsAreMeasuredAndTheNearestWins() = runTest {
        useVirtualClock()
        val r = measure()
        assertEquals("the shortlist first: nothing waited", "blr1", r.bestFor(GravixRoomMedia.VIDEO)?.region)
        GravixRegionMeasurements.videoProbesDone()
        assertEquals(lines.toString(), "fra1", held().bestFor(GravixRoomMedia.VIDEO)?.region)
        assertEquals("an audio join is unchanged", "doh1", held().best?.region)
        assertTrue(held().regions.map { it.region }.containsAll(listOf("fra1", "sgp1")))
        assertTrue(lines.toString(), lines.any { it.contains("videoProbe measured=") })
        assertTrue(lines.toString(), lines.any { it.contains("select media=video -> fra1") && it.contains("source=videoProbe") })
    }

    @Test
    fun itNeverHoldsTheMeasurementUp() = runTest {
        useVirtualClock()
        holdExtra = CompletableDeferred()
        val r = measure()
        assertEquals("doh1", r.best?.region)
        assertEquals("blr1", held().bestFor(GravixRoomMedia.VIDEO)?.region)
        holdExtra!!.complete(Unit)
        GravixRegionMeasurements.videoProbesDone()
    }

    @Test
    fun theFastestCarriesVideoNothingMoreIsMeasured() = runTest {
        useVirtualClock()
        rtt["dac1"] = 50L
        measure()
        GravixRegionMeasurements.videoProbesDone()
        assertFalse(sampled.toString(), "fra1" in sampled)
        assertTrue(lines.none { it.contains("videoProbe") })
    }

    @Test
    fun theRollbackMeasuresNothingMore() = runTest {
        useVirtualClock()
        GravixRegionMedia.autoDetect = false
        measure()
        GravixRegionMeasurements.videoProbesDone()
        assertFalse(sampled.toString(), "fra1" in sampled)
    }

    @Test
    fun theRateLimitIsOncePerTtlPerNetworkWithTheNumbersKeptMeanwhile() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        measure(store)
        GravixRegionMeasurements.videoProbesDone()
        val count = sampled.count { it == "fra1" }
        assertTrue(count > 0)
        measure(store)
        GravixRegionMeasurements.videoProbesDone()
        assertEquals("measured again before the ttl", count, sampled.count { it == "fra1" })
        assertEquals("fra1", held().bestFor(GravixRoomMedia.VIDEO)?.region)
        assertTrue(held().regions.any { it.region == "fra1" })
    }

    @Test
    fun aRestartedProcessKeepsTheProbedNumbersFromTheStore() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        measure(store)
        GravixRegionMeasurements.videoProbesDone()
        val count = sampled.count { it == "fra1" }
        GravixRegionMeasurements.clear()
        measure(store)
        GravixRegionMeasurements.videoProbesDone()
        assertEquals("the wire keys videoExtra / videoExtraAtMs", count, sampled.count { it == "fra1" })
        assertEquals("fra1", held().bestFor(GravixRoomMedia.VIDEO)?.region)
    }

    @Test
    fun aNearTieKeepsTheHeldRegionAndTheMoveGoesThere() = runTest {
        useVirtualClock()
        rtt["fra1"] = null
        rtt["sgp1"] = null
        val store = GravixMemoryRegionListStore()
        measure(store)
        GravixRegionMeasurements.videoProbesDone()
        assertEquals("blr1", held().bestFor(GravixRoomMedia.VIDEO)?.region)
        rtt["dac1"] = 228L // 2 ms ahead of blr1, the field's flip
        measure(store)
        GravixRegionMeasurements.videoProbesDone()
        assertEquals("blr1", held().bestFor(GravixRoomMedia.VIDEO)?.region)
        assertEquals("blr1", GravixRegionMeasurements.mediaMoveTarget(GravixRoomMedia.VIDEO)?.region)
        val why = GravixRegionMeasurements.mediaMoveWhy(GravixRoomMedia.VIDEO)
        assertTrue(why, why.contains("why=kept"))
        assertTrue(why, why.contains("fastest=dac1"))
    }

    @Test
    fun aRegionThatRefusedVideoIsNotATargetWhateverIsHeld() = runTest {
        useVirtualClock()
        measure()
        GravixRegionMeasurements.videoProbesDone()
        GravixRegionMedia.noteRefused("fra1", GravixRoomMedia.VIDEO)
        assertNotEquals("fra1", GravixRegionMeasurements.mediaMoveTarget(GravixRoomMedia.VIDEO)?.region)
    }

    @Test
    fun aHeldVideoRegionARivalMustWinTwice() {
        val r = GravixRegionSelector.select(
            listOf(GravixRegionSample("blr1", "ok", 230L), GravixRegionSample("fra1", "ok", 120L)),
            anchor = "blr1",
            anchorKnown = true,
        )
        assertEquals(GravixRegionSelectReason.CONFIRMING, r?.reason)
        assertEquals("blr1", r?.region)
    }

    @Test
    fun otherVideoRegionsAreLowestEstimateFirstAtMostTheConcurrency() {
        val dir = GravixRegionDirectory(
            regions = listOf(
                GravixRegionUrl("a", url("a"), estRttMs = 300.0),
                GravixRegionUrl("b", url("b")),
                GravixRegionUrl("c", url("c"), estRttMs = 100.0),
                GravixRegionUrl("d", url("d"), media = listOf("audio")),
                GravixRegionUrl("e", url("e"), estRttMs = 200.0),
                GravixRegionUrl("f", url("f"), estRttMs = 50.0),
            ),
        )
        val got = GravixRegionMeasurements.unmeasuredVideoRegions(dir, listOf("f")).map { it.region }
        assertEquals(listOf("c", "e", "a", "b").take(GravixRegionPlanning.MEASURE_CONCURRENCY), got)
    }
}
