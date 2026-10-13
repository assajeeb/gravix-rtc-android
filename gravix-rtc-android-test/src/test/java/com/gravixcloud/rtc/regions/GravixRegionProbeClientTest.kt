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

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * GRAVIX: the probe connection reuse that makes the warm samples one round trip (port of the Flutter SDK's
 * region_probe_warm_test). Real local sockets (MockWebServer on 127.0.0.1); `sequenceNumber` > 0 proves a request
 * rode an already-open connection.
 */
class GravixRegionProbeClientTest {

    private val servers = mutableListOf<MockWebServer>()

    private fun server(): MockWebServer = MockWebServer().also {
        it.start()
        servers.add(it)
    }

    private fun wsUrl(s: MockWebServer) = s.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/')

    @Before
    fun setUp() {
        GravixRegionMeasurements.clear()
        RegionTestKit.resetClock()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        GravixRegionMeasurements.clear()
    }

    @Test
    fun oneConnectionPerRegionForAllItsSamples() = runBlocking {
        val regions = (1..3).map { i ->
            val s = server()
            repeat(GravixRegionMeasurements.SAMPLES) { s.enqueue(MockResponse().setResponseCode(200)) }
            GravixRegionUrl("r$i", wsUrl(s))
        }
        val result = GravixRegionMeasurements.measure(
            GravixRegionMeasureOptions().apply {
                regionUrls = regions
                budgetMs = 10_000
                networkType = GravixNetworkReader { "unknown" }
            },
        )
        assertEquals(3, result.regions.size)
        for (m in result.regions) {
            assertEquals("${m.region}: one socket for all samples", 1, m.connections)
            assertTrue("${m.region} measured", m.ok)
        }
        for (s in servers) {
            val seq = generateSequence { s.takeRequest(0, TimeUnit.SECONDS) }.map { it.method + ":" + it.sequenceNumber }.toList()
            assertTrue("requests $seq", seq.size >= 2)
            assertEquals("first request opens the connection, the rest reuse it", (seq.indices).map { "HEAD:$it" }, seq)
        }
    }

    @Test
    fun probeClientNRequestsOneSocketClosedMeansClosed() = runBlocking {
        val s = server()
        repeat(4) { s.enqueue(MockResponse().setResponseCode(204)) }
        val client = GravixRegionProbeClient()
        repeat(4) { client.probe(wsUrl(s)) }
        assertEquals(1, client.connections)
        assertEquals(3, (0 until 4).map { s.takeRequest().sequenceNumber }.last())
        client.close()
        try {
            client.probe(wsUrl(s))
            fail("closed client must not send")
        } catch (_: java.io.IOException) {
        }
    }

    @Test
    fun headNotSupportedIsAskedOnceMoreWithGet() = runBlocking {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(405))
        s.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        GravixRegionProbeClient().apply { probe(wsUrl(s)) }.close()
        assertEquals("HEAD", s.takeRequest().method)
        assertEquals("GET", s.takeRequest().method)
    }

    @Test
    fun aRegionAnsweringAsAnotherRegionNeverWins() = runBlocking {
        val good = server()
        val wrong = server()
        repeat(3) {
            good.enqueue(MockResponse().setBody("""{"region":"sgp1","node":"n1"}"""))
            wrong.enqueue(MockResponse().setBody("""{"region":"blr1","node":"n2"}"""))
        }
        val result = GravixRegionMeasurements.measure(
            GravixRegionMeasureOptions().apply {
                regionUrls = listOf(
                    GravixRegionUrl("sgp1", "wss://sgp1.invalid", probeUrl = good.url("/v1/region-probe").toString()),
                    GravixRegionUrl("doh1", "wss://doh1.invalid", probeUrl = wrong.url("/v1/region-probe").toString()),
                )
                budgetMs = 10_000
                networkType = GravixNetworkReader { "unknown" }
            },
        )
        val by = result.regions.associateBy { it.region }
        assertTrue(by["sgp1"]!!.ok)
        assertEquals(GravixRegionMeasurementStatus.FAILED, by["doh1"]!!.status)
        assertEquals("sgp1", result.best?.region)
        assertEquals("GET", good.takeRequest().method)
    }

    @Test
    fun aTimedOutSampleFailsTheRegionWithinTheTimeout() = runBlocking {
        val s = server()
        repeat(2) { s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val client = GravixRegionProbeClient()
        val sampler = GravixRegionMeasurements.defaultSampler(client, timeoutMs = 300)
        val t0 = System.nanoTime()
        assertNull(sampler.sample(GravixRegionUrl("x", wsUrl(s))))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 250..2000)
        client.close()
    }
}
