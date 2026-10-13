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

import com.gravixcloud.rtc.regions.RegionTestKit.Table
import com.gravixcloud.rtc.regions.RegionTestKit.net
import com.gravixcloud.rtc.regions.RegionTestKit.onTestScheduler
import com.gravixcloud.rtc.regions.RegionTestKit.unknownNet
import com.gravixcloud.rtc.regions.RegionTestKit.useVirtualClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * GRAVIX: the start-up region measurement (port of the Flutter SDK's region_shortlist_test, gravix_init_measure_test,
 * region_selection_test and region_failover_order_test; behaviour, not line by line). The parity cases run the
 * fixture shared with the Flutter and React SDKs. Every timing assertion runs on virtual time (the Flutter "50
 * regions" test was flaky on a busy event loop; here the budget is a coroutine timeout on the test scheduler).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixRegionMeasurementTest {

    private val regionsUrl = "https://gw.example.test/v1/regions"
    private val fixture = RegionTestKit.resource("region_shortlist_cases.json").jsonObject

    private fun list(name: String): JsonArray = fixture["lists"]!!.jsonObject[name]!!.jsonArray
    private fun entries(name: String): List<GravixRegionUrl> =
        GravixRegionDirectory.fromJson(buildJsonObject { put("regions", list(name)) }).regions

    private fun serve(body: JsonObject) = GravixRegionDirectoryFetcher { GravixRegionDirectory.fromJson(body) }
    private fun stateKey(net: String) = "gravix.regionState:$regionsUrl:$net"
    private fun regions(vararg names: String) = names.map { GravixRegionUrl(it, "wss://$it.example.test") }
    private fun body(regions: JsonArray, extra: JsonObject.() -> JsonObject = { this }): JsonObject =
        buildJsonObject { put("regions", regions) }.extra()

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun strings(vararg s: String) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }

    private suspend fun TestScope.measure(block: GravixRegionMeasureOptions.() -> Unit): GravixRegionMeasurementResult =
        GravixRegionMeasurements.measure(onTestScheduler(GravixRegionMeasureOptions().apply(block)))

    @Before
    fun setUp() {
        GravixRegionMeasurements.clear()
    }

    @After
    fun tearDown() {
        GravixRegionMeasurements.clear()
        RegionTestKit.resetClock()
    }

    // ------------------------------------------------------------------ parity fixture (same file as Flutter/React)

    @Test
    fun parityFixture() = runTest {
        useVirtualClock()
        val cases = fixture["cases"]!!.jsonArray
        assertEquals(18, cases.size)
        for (e in cases) {
            GravixRegionMeasurements.clear()
            val c = e.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val store = GravixMemoryRegionListStore()
            val cache = c["cache"]?.takeIf { it != JsonNull }?.jsonObject
            if (cache != null) {
                store.write(
                    stateKey("unknown"),
                    buildJsonObject {
                        put("known", cache["known"]!!)
                        put("cursor", cache["cursor"]!!)
                        put("measuredAtMs", 0)
                        put("ttlMs", 1)
                        cache["challenger"]?.takeIf { it != JsonNull }?.let { put("challenger", it) }
                    }.toString(),
                )
            }
            val rawResponse = c["response"]!!.jsonObject
            val response = rawResponse.with("regions", list(rawResponse["regions"]!!.jsonPrimitive.content))
            val rtt = c["rtt"]!!.jsonObject.mapValues { (_, v) ->
                if (v == JsonNull) null else v.jsonArray.map { (it as? JsonPrimitive)?.longOrNull }
            }
            val table = Table(rtt)
            val current = cache?.get("current")?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            val result = measure {
                this.regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
                fetchDirectory = serve(response)
                sampler = table
                this.store = store
                currentRegion = current
                budgetMs = 10_000
                networkType = unknownNet()
            }
            val ex = c["expect"]!!.jsonObject
            assertEquals(name, ex["mode"]!!.jsonPrimitive.content, result.mode?.wireName)
            val probed = ex["probed"]?.takeIf { it != JsonNull }?.jsonArray?.map { p ->
                p.jsonArray[0].jsonPrimitive.content to p.jsonArray[1].jsonPrimitive.content
            }
            if (probed != null) {
                assertEquals(name, probed.map { it.first }.toSet(), table.calls.toSet())
                val sources = result.regions.associate { it.region to it.source.wireName }
                for ((region, source) in probed) assertEquals("$name: source of $region", source, sources[region])
                val plan = GravixRegionShortlist.plan(
                    GravixRegionDirectory.fromJson(response),
                    state = cache?.let { s ->
                        GravixRegionPlanState(s["known"]!!.jsonArray.map { it.jsonPrimitive.content }, s["cursor"]!!.jsonPrimitive.int)
                    },
                    currentRegion = current,
                )
                assertEquals(name, probed, plan.candidates.map { it.entry.region to it.source.wireName })
            }
            c["probedCount"]?.takeIf { it != JsonNull }?.let { assertEquals(name, it.jsonPrimitive.int, table.calls.toSet().size) }
            assertEquals(name, ex["best"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content, result.best?.region)
            assertEquals(name, ex["bestSource"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content, result.bestSource?.wireName)
            if (ex.containsKey("selectReason")) {
                assertEquals(name, ex["selectReason"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content, result.selectReason?.wireName)
            }
            if (ex.containsKey("challenger")) {
                assertEquals(name, ex["challenger"]?.takeIf { it != JsonNull }, result.challenger?.toJson())
            }
            val saved = kotlinx.serialization.json.Json.parseToJsonElement(store.read(stateKey("unknown"))!!).jsonObject
            ex["known"]?.takeIf { it != JsonNull }?.let { assertEquals(name, it, saved["known"]) }
            ex["cursor"]?.takeIf { it != JsonNull }?.let { assertEquals(name, it.jsonPrimitive.int, saved["cursor"]!!.jsonPrimitive.int) }
        }
    }

    @Test
    fun earlyExitFixture() {
        val cases = fixture["earlyExit"]!!.jsonArray
        assertEquals(8, cases.size)
        for (e in cases) {
            val c = e.jsonObject
            val states = c["states"]!!.jsonArray.map { s ->
                GravixMeasureProgress(s.jsonObject["warm"]!!.jsonArray.map { it.jsonPrimitive.longOrNull!! }, s.jsonObject["done"]!!.jsonPrimitive.boolean)
            }
            assertEquals(c["name"]!!.jsonPrimitive.content, c["expect"]!!.jsonPrimitive.boolean, GravixRegionShortlist.shouldStopMeasuring(states))
        }
    }

    @Test
    fun networkIdsHashLikeTheOtherSdks() {
        assertEquals("2a4558bb", GravixRegionShortlist.hashNetworkId("Home-WiFi 5G ✓"))
        assertEquals("wifi:2a4558bb", GravixRegionShortlist.networkKey("wifi", "Home-WiFi 5G ✓"))
        assertEquals("cellular", GravixRegionShortlist.networkKey("cellular"))
    }

    // ------------------------------------------------------------------------------------------- scale: 50 regions

    @Test
    fun shortlistOfThreeOutOfFiftyWithinTheBudget() = runTest {
        useVirtualClock()
        val fifty = list("fifty")
        val table = Table(
            (0 until 50).associate { i -> fifty[i].jsonObject["region"]!!.jsonPrimitive.content to listOf(150L, 40L + i, 40L + i) },
            delayMs = 30,
        )
        val start = currentTime
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(fifty).with("shortlist", strings("r07", "r03", "r21")).with("probe_budget_ms", JsonPrimitive(1500)))
            sampler = table
            currentRegion = "r40"
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
        }
        assertEquals(setOf("r07", "r03", "r21", "r40"), table.calls.toSet())
        assertEquals(1500L, result.budgetMs)
        assertTrue("virtual elapsed ${currentTime - start}", currentTime - start < 1500)
        // r03 (43 ms) beats the held r40 (80 ms) by more than the margin, but one win only puts it on probation
        assertEquals("r40", result.best?.region)
        assertEquals(GravixRegionSelectReason.CONFIRMING, result.selectReason)
        assertEquals(GravixRegionChallenger("r03", 1), result.challenger)
    }

    @Test
    fun aListGatewayThatNeverAnswersFallsBackToTheSavedList() = runTest {
        // detekt NoWithTimeout (Phase 3, B3): the default list fetch used withTimeout(5 s); its
        // TimeoutCancellationException is a CancellationException, so the saved-list fallback was skipped and
        // measure() failed as if its caller had been cancelled
        useVirtualClock()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val url = server.url("/v1/regions").toString()
            val store = GravixMemoryRegionListStore()
            val saved = GravixRegionDirectory(listOf(GravixRegionUrl("sgp1", "wss://sgp1.gravixcloud.run")))
            store.write("gravix.regions:$url", saved.toJson().toString())
            val result = measure {
                regionsUrl = url
                this.store = store
                okHttpClient = okhttp3.OkHttpClient()
                networkType = unknownNet()
                sampler = GravixRegionSampler { 50L }
            }
            assertEquals("the saved list was measured", listOf("sgp1"), result.regions.map { it.region })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun noShortlistIsConcurrencyCappedAndBudgetBoundedThenExploresInRotation() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        var inFlight = 0
        var maxInFlight = 0
        val probedPerRun = mutableListOf<Set<String>>()
        for (run in 0 until 3) {
            val probed = mutableSetOf<String>()
            val start = currentTime
            val result = measure {
                regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
                fetchDirectory = serve(body(list("fifty")))
                this.store = store
                budgetMs = 400
                networkType = unknownNet()
                sampler = GravixRegionSampler { r ->
                    probed.add(r.region)
                    inFlight++
                    if (inFlight > maxInFlight) maxInFlight = inFlight
                    try {
                        delay(40)
                    } finally {
                        inFlight--
                    }
                    100L + r.region.substring(1).toInt()
                }
            }
            assertTrue(currentTime - start <= 400)
            probedPerRun.add(probed)
            if (run == 0) {
                assertEquals(GravixRegionPlanMode.ALL, result.mode)
                assertEquals(GravixRegionPlanning.MEASURE_CONCURRENCY, maxInFlight)
                assertTrue(probed.size < 50)
                assertTrue(result.budgetHit)
                assertTrue(result.regions.any { it.status == GravixRegionMeasurementStatus.UNMEASURED && it.budgetHit })
            } else {
                assertEquals(GravixRegionPlanMode.EXPLORE, result.mode)
                assertEquals(4, probed.size)
            }
            assertEquals("cut-off samples are cancelled, not left running", 0, inFlight)
        }
        assertTrue("exploration moves on", (probedPerRun[1] - probedPerRun[2]).isNotEmpty())
        assertTrue(probedPerRun[1].intersect(probedPerRun[2]).size >= 2)
    }

    @Test
    fun estRttWithoutShortlistProbesTheThreeLowest() = runTest {
        useVirtualClock()
        val table = Table(mapOf("r31" to listOf(100L, 40L), "r30" to listOf(100L, 35L), "r32" to listOf(100L, 80L)))
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(list("fiftyEst")))
            sampler = table
            store = GravixMemoryRegionListStore()
            budgetMs = 2000
            networkType = unknownNet()
        }
        assertEquals(setOf("r31", "r30", "r32"), table.calls.toSet())
        assertEquals(GravixRegionPlanMode.EST, result.mode)
    }

    // --------------------------------------------------------------------------------- time: early exit and budget

    @Test
    fun earlyExitOnceTheBestClearlyWins() = runTest {
        useVirtualClock()
        val table = Table(
            mapOf("a" to listOf(100L, 50L, 50L), "b" to listOf(100L, 200L, 200L), "c" to listOf(100L, 300L, 300L)),
            delayMs = 50,
        )
        val start = currentTime
        val result = measure {
            regionUrls = regions("a", "b", "c")
            sampler = table
            budgetMs = 5000
            networkType = unknownNet()
        }
        assertTrue(result.earlyExit)
        assertFalse(result.budgetHit)
        assertEquals("a", result.best?.region)
        assertEquals(listOf(2, 2, 2), result.regions.map { it.samplesMs.size })
        assertEquals("two samples of 50 ms each, in parallel", 100L, currentTime - start)
    }

    @Test
    fun aCloseRaceSamplesToTheEnd() = runTest {
        useVirtualClock()
        val table = Table(mapOf("a" to listOf(100L, 50L, 50L), "b" to listOf(100L, 60L, 60L)))
        val result = measure {
            regionUrls = regions("a", "b")
            sampler = table
            budgetMs = 5000
            networkType = unknownNet()
        }
        assertFalse(result.earlyExit)
        assertEquals(6, table.calls.size)
    }

    @Test
    fun budgetExpiryKeepsTheBestSoFarSilentIsUnmeasuredDeadIsFailed() = runTest {
        useVirtualClock()
        val start = currentTime
        val result = measure {
            regionUrls = regions("fast", "slow", "dead")
            budgetMs = 300
            networkType = unknownNet()
            sampler = GravixRegionSampler { r ->
                when (r.region) {
                    "fast" -> 60L
                    "dead" -> null
                    else -> awaitCancellation()
                }
            }
        }
        assertEquals(300L, currentTime - start)
        assertTrue(result.budgetHit)
        assertEquals("fast", result.best?.region)
        val by = result.regions.associateBy { it.region }
        assertEquals(GravixRegionMeasurementStatus.UNMEASURED, by["slow"]!!.status)
        assertTrue(by["slow"]!!.budgetHit)
        assertNull(by["slow"]!!.rttMs)
        assertEquals(GravixRegionMeasurementStatus.FAILED, by["dead"]!!.status)
        assertTrue("a dead region is asked twice (one retry)", by["dead"]!!.retried)
        assertFalse(by["dead"]!!.budgetHit)
        assertEquals(60L, by["fast"]!!.rttMs)
    }

    @Test
    fun defaultBudgets() = runTest {
        useVirtualClock()
        val one = regions("a")
        fun s() = Table(mapOf("a" to listOf(10L, 10L)))
        assertEquals(1500L, measure { regionUrls = one; sampler = s(); networkType = unknownNet() }.budgetMs)
        GravixRegionMeasurements.clear()
        assertEquals(3000L, measure { regionUrls = one; sampler = s(); networkType = net("cellular") }.budgetMs)
        val gw = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            val listA = buildJsonArray { add(buildJsonObject { put("region", "a"); put("url", "wss://a.example.test") }) }
            fetchDirectory = serve(body(listA).with("probe_budget_ms", JsonPrimitive(800)))
            sampler = s()
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
        }
        assertEquals(800L, gw.budgetMs)
        // cellular: the SDK's 3 s is a floor under the gateway's 1.5 s
        val cell = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            val listA = buildJsonArray { add(buildJsonObject { put("region", "a"); put("url", "wss://a.example.test") }) }
            fetchDirectory = serve(body(listA).with("probe_budget_ms", JsonPrimitive(1500)))
            sampler = s()
            store = GravixMemoryRegionListStore()
            networkType = net("cellular")
        }
        assertEquals(3000L, cell.budgetMs)
        assertEquals(250L, measure { regionUrls = one; sampler = s(); budgetMs = 250; networkType = unknownNet() }.budgetMs)
    }

    // ------------------------------------------------------------------------------- fallbacks never move a join

    @Test
    fun nothingMeasuredTheGuessIsShortlistFirstAndNeverAPick() = runTest {
        useVirtualClock()
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(list("fifty")).with("shortlist", strings("r07", "r03")))
            sampler = Table(emptyMap())
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
        }
        assertEquals(GravixRegionBestSource.SHORTLIST, result.bestSource)
        assertEquals("r07", GravixRegionMeasurements.current()?.best?.region)
        assertNull(GravixRegionMeasurements.pickRegion("wss://r03.example.test", entries("fifty")))
    }

    @Test
    fun aGuessNeverReplacesAFreshMeasuredAnswer() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        val serve = serve(body(list("fifty")).with("shortlist", strings("r07", "r03")))
        measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve
            this.store = store
            networkType = unknownNet()
            sampler = Table(mapOf("r03" to listOf(90L, 40L)))
        }
        assertEquals("r03", GravixRegionMeasurements.current()?.best?.region)
        val second = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve
            this.store = store
            networkType = unknownNet()
            sampler = Table(emptyMap())
        }
        assertEquals(GravixRegionBestSource.SHORTLIST, second.bestSource)
        assertEquals("r03", GravixRegionMeasurements.current()?.best?.region)
        assertEquals("r03", GravixRegionMeasurements.pickRegion("wss://r07.example.test", entries("fifty"))?.entry?.region)
    }

    // ------------------------------------------------------------------------------------------------ old gateways

    @Test
    fun oldGatewayFiveRegionsAllProbedReportSaysAll() = runTest {
        useVirtualClock()
        val table = Table(
            mapOf(
                "sgp1" to listOf(190L, 61L, 64L),
                "blr1" to listOf(210L, 118L, 120L),
                "fra1" to listOf(500L, 180L),
                "nyc1" to listOf(780L, 250L),
                "ams3" to listOf(520L, 190L),
            ),
        )
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(list("five")))
            sampler = table
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
        }
        assertEquals(setOf("sgp1", "blr1", "fra1", "nyc1", "ams3"), table.calls.toSet())
        assertEquals(GravixRegionPlanMode.ALL, result.mode)
        assertEquals("sgp1", result.best?.region)
        val report = Json.parseToJsonElement(GravixRegionMeasurements.regionsMeasuredReport(result)!!).jsonObject
        assertEquals(setOf("all"), report["regions"]!!.jsonArray.map { it.jsonObject["source"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun aSavedListLoadsWhenTheGatewayIsDown() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        store.write("gravix.regions:$regionsUrl", body(list("five")).toString())
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = GravixRegionDirectoryFetcher { throw IOException("offline") }
            this.store = store
            sampler = Table(mapOf("sgp1" to listOf(1L, 1L)))
            networkType = unknownNet()
        }
        assertEquals(5, result.regions.size)
    }

    @Test
    fun neverThrowsWithoutAList() = runTest {
        useVirtualClock()
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = GravixRegionDirectoryFetcher { throw IOException("offline") }
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
        }
        assertTrue(result.regions.isEmpty())
        assertNotNull(result.error)
        assertNull(GravixRegionMeasurements.current())
    }

    // ------------------------------------------------------------------------------------------ per-network cache

    @Test
    fun aKnownNetworkHasItsAnswerAtOnceWhileTheNewRunMeasures() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        val serve = serve(body(list("five")))
        var netName = "wifi:aaaa"
        val key = GravixNetworkReader { netName }
        measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve
            this.store = store
            networkKey = key
            networkType = unknownNet()
            sampler = Table(
                mapOf("sgp1" to listOf(100L, 40L), "blr1" to listOf(100L, 200L), "fra1" to listOf(100L, 300L), "nyc1" to listOf(100L, 300L), "ams3" to listOf(100L, 300L)),
            )
        }
        netName = "cellular"
        measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve
            this.store = store
            networkKey = key
            networkType = unknownNet()
            sampler = Table(
                mapOf("sgp1" to listOf(100L, 200L), "blr1" to listOf(100L, 50L), "fra1" to listOf(100L, 300L), "nyc1" to listOf(100L, 300L), "ams3" to listOf(100L, 300L)),
            )
        }
        assertEquals("blr1", GravixRegionMeasurements.current()?.best?.region)

        netName = "wifi:aaaa"
        val pending = launch {
            measure {
                regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
                fetchDirectory = serve
                this.store = store
                networkKey = key
                networkType = unknownNet()
                budgetMs = 200
                sampler = GravixRegionSampler { awaitCancellation() }
            }
        }
        advanceTimeBy(20)
        runCurrent()
        // back on the Wi-Fi: its cached answer, before the new run has a number
        assertEquals(GravixRegionBestSource.CACHE, GravixRegionMeasurements.current()?.bestSource)
        assertEquals("sgp1", GravixRegionMeasurements.current()?.best?.region)
        assertEquals("sgp1", GravixRegionMeasurements.pickRegion("wss://sgp1.example.test", entries("five"))?.entry?.region)
        pending.join()
        assertEquals("nothing measured: the cached answer stands", "sgp1", GravixRegionMeasurements.current()?.best?.region)
    }

    @Test
    fun theTtlComesFromShortlistTtl() = runTest {
        useVirtualClock()
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(list("five")).with("shortlist_ttl_s", JsonPrimitive(60)))
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
            sampler = Table(mapOf("sgp1" to listOf(10L, 10L)))
        }
        assertEquals(60_000L, result.ttlMs)
        assertNotNull(GravixRegionMeasurements.current(result.measuredAtMs + 59_000))
        assertNull(GravixRegionMeasurements.current(result.measuredAtMs + 61_000))
    }

    // ------------------------------------------------------------------------- hysteresis across measurements

    private fun fixed(ms: Map<String, Long>): GravixRegionSampler {
        val seen = HashSet<String>()
        return GravixRegionSampler { r -> ms[r.region]?.let { v -> v + if (seen.add(r.region)) 300 else 0 } }
    }

    private suspend fun TestScope.measureFixed(ms: Map<String, Long>, net: String = "wifi", regionList: List<GravixRegionUrl> = regions("sgp1", "blr1", "fra1")) =
        measure {
            regionUrls = regionList
            sampler = fixed(ms)
            networkType = net(net)
            networkKey = net(net)
        }

    @Test
    fun fieldNoiseNeverMovesTheUser() = runTest {
        useVirtualClock()
        // Bangladesh 2026-10-04: sgp1/blr1 within a few ms; the first answer holds through 6 noisy runs
        val runs = listOf(
            mapOf("sgp1" to 58L, "blr1" to 56L, "fra1" to 186L),
            mapOf("sgp1" to 56L, "blr1" to 58L, "fra1" to 186L),
            mapOf("sgp1" to 55L, "blr1" to 60L, "fra1" to 190L),
            mapOf("sgp1" to 61L, "blr1" to 57L, "fra1" to 186L),
            mapOf("sgp1" to 54L, "blr1" to 59L, "fra1" to 186L),
            mapOf("sgp1" to 57L, "blr1" to 57L, "fra1" to 186L),
        )
        val picks = runs.map { measureFixed(it).best?.region }
        assertEquals(List(6) { "blr1" }, picks)
    }

    @Test
    fun aClearlyBetterRegionNeedsTwoConsecutiveWinsAndALostRunResets() = runTest {
        useVirtualClock()
        assertEquals("blr1", measureFixed(mapOf("blr1" to 50L, "sgp1" to 60L)).best?.region)
        val first = measureFixed(mapOf("blr1" to 150L, "sgp1" to 60L))
        assertEquals("blr1", first.best?.region)
        assertEquals(GravixRegionSelectReason.CONFIRMING, first.selectReason)
        // a run the anchor wins again resets the vote
        assertEquals(GravixRegionSelectReason.KEPT, measureFixed(mapOf("blr1" to 62L, "sgp1" to 60L)).selectReason)
        assertEquals(GravixRegionSelectReason.CONFIRMING, measureFixed(mapOf("blr1" to 150L, "sgp1" to 60L)).selectReason)
        val second = measureFixed(mapOf("blr1" to 150L, "sgp1" to 60L))
        assertEquals("sgp1", second.best?.region)
        assertEquals(GravixRegionSelectReason.SWITCHED, second.selectReason)
    }

    @Test
    fun aFailedAnchorMovesAtOnce() = runTest {
        useVirtualClock()
        assertEquals("blr1", measureFixed(mapOf("blr1" to 50L, "sgp1" to 60L)).best?.region)
        val r = measureFixed(mapOf("sgp1" to 60L)) // blr1 fails
        assertEquals("sgp1", r.best?.region)
        assertEquals(GravixRegionSelectReason.ANCHOR_FAILED, r.selectReason)
    }

    @Test
    fun theAnchorOutlivesTheAnswerTtl() = runTest {
        var wallOffset = 0L
        useVirtualClock { wallOffset }
        assertEquals("blr1", measureFixed(mapOf("blr1" to 50L, "sgp1" to 60L)).best?.region)
        wallOffset = 11L * 60 * 1000 // the 10-minute answer is stale, the 12-hour anchor is not
        assertNull(GravixRegionMeasurements.current())
        val r = measureFixed(mapOf("blr1" to 62L, "sgp1" to 58L))
        assertEquals("blr1", r.best?.region)
        assertEquals(GravixRegionSelectReason.KEPT, r.selectReason)
    }

    @Test
    fun networksKeepTheirOwnAnchors() = runTest {
        useVirtualClock()
        assertEquals("sgp1", measureFixed(mapOf("sgp1" to 50L, "blr1" to 90L), net = "wifi").best?.region)
        assertEquals("blr1", measureFixed(mapOf("sgp1" to 90L, "blr1" to 50L), net = "cellular+vpn").best?.region)
        // back on wifi: its own anchor, not the VPN's
        val r = measureFixed(mapOf("sgp1" to 60L, "blr1" to 55L), net = "wifi")
        assertEquals("sgp1", r.best?.region)
        assertEquals(GravixRegionSelectReason.KEPT, r.selectReason)
    }

    // ------------------------------------------------------------------------------------- pick (the join lookup)

    @Test
    fun pickKeepsThePinnedRegionUnlessClearlyFaster() = runTest {
        useVirtualClock()
        val (sgp, blr) = regions("sgp1", "blr1")
        measureFixed(mapOf("sgp1" to 180L, "blr1" to 167L), regionList = listOf(sgp, blr))
        // field 2026-09-30: sgp1 (180) pinned, blr1 (167) not clearly faster: keep sgp1
        val p = GravixRegionMeasurements.pickRegion(sgp.url, listOf(sgp, blr))!!
        assertEquals("sgp1", p.entry.region)
        assertEquals(GravixRegionPickReason.PINNED, p.reason)
        GravixRegionMeasurements.clear()
        measureFixed(mapOf("sgp1" to 180L, "blr1" to 60L), regionList = listOf(sgp, blr))
        assertEquals("blr1", GravixRegionMeasurements.pickRegion(sgp.url, listOf(sgp, blr))?.entry?.region)
        assertEquals(blr.url, GravixRegionMeasurements.resolveConnectUrl(sgp.url))
        assertEquals("a url outside the measured regions is never redirected", "wss://own.example.test", GravixRegionMeasurements.resolveConnectUrl("wss://own.example.test"))
    }

    @Test
    fun pickHomeRegionWithinItsMargin() = runTest {
        useVirtualClock()
        val (sgp, blr) = regions("sgp1", "blr1")
        measureFixed(mapOf("sgp1" to 70L, "blr1" to 55L), regionList = listOf(sgp, blr))
        val home = GravixRegionMeasurements.pickRegion(blr.url, listOf(sgp, blr), homeRegion = "sgp1")!!
        assertEquals("sgp1", home.entry.region)
        assertEquals(GravixRegionPickReason.HOME, home.reason)
        GravixRegionMeasurements.clear()
        measureFixed(mapOf("sgp1" to 200L, "blr1" to 55L), regionList = listOf(sgp, blr))
        assertEquals("blr1", GravixRegionMeasurements.pickRegion(sgp.url, listOf(sgp, blr), homeRegion = "sgp1")?.entry?.region)
        assertNull("nothing measured: no pick", run { GravixRegionMeasurements.clear(); GravixRegionMeasurements.pickRegion(sgp.url, listOf(sgp, blr)) })
    }

    @Test
    fun reportCarriesSamplesSourcesAndBudgetFlags() = runTest {
        useVirtualClock()
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(list("fifty")).with("shortlist", strings("r07", "r03")))
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
            currentRegion = "r40"
            sampler = Table(mapOf("r07" to listOf(300L, 90L, 95L), "r03" to listOf(250L, 60L, 62L)))
        }
        val report = Json.parseToJsonElement(GravixRegionMeasurements.regionsMeasuredReport(result)!!).jsonObject
        assertEquals("shortlist", report["mode"]!!.jsonPrimitive.content)
        assertEquals("r03", report["best"]!!.jsonPrimitive.content)
        val rs = report["regions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, rs.size)
        val r03 = rs.first { it["region"]!!.jsonPrimitive.content == "r03" }
        assertEquals(
            buildJsonObject {
                put("region", "r03")
                put("rtt_ms", 60)
                put("status", "ok")
                put("samples_ms", buildJsonArray { add(JsonPrimitive(250L)); add(JsonPrimitive(60L)); add(JsonPrimitive(62L)) })
                put("conns", JsonNull)
                put("source", "shortlist")
                put("budget_hit", false)
            }.toString(),
            r03.toString(),
        )
        assertEquals("cache", rs.first { it["region"]!!.jsonPrimitive.content == "r40" }["source"]!!.jsonPrimitive.content)
        assertNull("no report without samples", GravixRegionMeasurements.regionsMeasuredReport(null))
    }

    // GRAVIX(Phase 4): the public report is a JSON String (apps have no kotlinx-serialization at compile time);
    // it carries exactly the wire object the other SDKs send.
    @Test
    fun theReportIsTheWireJsonAsAString() = runTest {
        useVirtualClock()
        val result = measure {
            regionsUrl = this@GravixRegionMeasurementTest.regionsUrl
            fetchDirectory = serve(body(list("five")))
            sampler = Table(mapOf("sgp1" to listOf(40L, 10L, 12L), "blr1" to listOf(60L, 30L, 31L)))
            store = GravixMemoryRegionListStore()
            networkType = unknownNet()
        }
        val text = GravixRegionMeasurements.regionsMeasuredReport(result)!!
        assertEquals(GravixRegionMeasurements.regionsMeasuredReportJson(result)!!.toString(), text)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals("sgp1", parsed["best"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("measured_at", "mode", "network", "budget_ms", "budget_hit", "early_exit", "elapsed_ms", "best", "best_source", "regions"),
            parsed.keys - setOf("select_reason"),
        )
    }

    // A custom GravixRegionDirectoryFetcher gets the gateway body as text: the String parser and writer round-trip.
    @Test
    fun aDirectoryRoundTripsThroughItsJsonString() {
        val dir = GravixRegionDirectory(
            regions = listOf(GravixRegionUrl("sgp1", "wss://sgp1.example.invalid"), GravixRegionUrl("blr1", "wss://blr1.example.invalid")),
            shortlist = listOf("sgp1"),
            probeBudgetMs = 1500L,
            shortlistTtlMs = 600_000L,
        )
        assertEquals(dir, GravixRegionDirectory.fromJson(dir.toJsonString()))
        assertEquals(emptyList<GravixRegionUrl>(), GravixRegionDirectory.fromJson("not json").regions)
    }
}
