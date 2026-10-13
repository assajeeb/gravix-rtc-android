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
import com.gravixcloud.rtc.stats.NetworkInfo
import com.gravixcloud.rtc.stats.NetworkType
import com.gravixcloud.rtc.test.BaseTest
import com.gravixcloud.rtc.test.mock.MockWebSocketFactory
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * GRAVIX (GravixRecoveryOptions.signalDialCapMs / silentHostFailFast), found on the sim (2026-10-12, runs
 * n5-badpath-*: emulator uplink netem delay 2000 ms +-500 ms, loss 35 %): 1 of 3 first joins failed at the 10 s
 * signal deadline on a path that works, slowly (the others joined in 4.4 / 7.3 s). The Flutter SDK gives a FRESH
 * join's signal connect up to 30 s (signalDialCap). A host with no TCP connection still fails at OkHttp's 10 s
 * connect timeout, and (silentHostFailFast) a dial that got no connection is not followed by a validate request to
 * the same host, which would wait out another connect timeout before the join could fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixSignalDialCapTest : BaseTest() {

    private lateinit var wsFactory: MockWebSocketFactory
    private lateinit var client: SignalClient
    private var validates = 0

    @Before
    fun setup() {
        wsFactory = MockWebSocketFactory()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                validates++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .body("".toResponseBody()).build()
            }
            .build()
        client = SignalClient(
            wsFactory,
            Json,
            okHttpClient = http,
            ioDispatcher = coroutineRule.dispatcher,
            networkInfo = object : NetworkInfo {
                override fun getNetworkType() = NetworkType.WIFI
            },
        )
    }

    private fun open(): Response = Response.Builder().request(wsFactory.request).code(101).protocol(Protocol.HTTP_1_1).message("").build()

    @Test
    fun aSlowFreshJoinGetsTheCap() = runTest {
        val join = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        testScheduler.advanceTimeBy(15_000) // the upgrade's answer and the JoinResponse after 15 s
        testScheduler.runCurrent()
        client.onOpen(wsFactory.ws, open())
        client.onMessage(wsFactory.ws, TestData.JOIN.toOkioByteString())
        assertTrue("a fresh join is given ${GravixRecoveryOptions.DEFAULT_SIGNAL_DIAL_CAP_MS} ms", join.await().isSuccess)
    }

    @Test
    fun aResumeKeepsTheTenSeconds() = runTest {
        val resume = async { runCatching { client.reconnect(TestData.EXAMPLE_URL, "token", "PA_x") } }
        testScheduler.advanceTimeBy(10_500)
        testScheduler.runCurrent()
        assertTrue("a resume still gives up at 10 s", resume.await().isFailure)
    }

    @Test
    fun capOffAFreshJoinFailsAtTenSecondsAsUpstream() = runTest {
        client.gravixSignalDialCapMs = null
        val join = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        testScheduler.advanceTimeBy(10_500)
        testScheduler.runCurrent()
        assertTrue(join.await().isFailure)
    }

    @Test
    fun aDialThatGotNoConnectionIsNotValidated() = runTest {
        val join = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        client.onFailure(wsFactory.ws, SocketTimeoutException("failed to connect to /10.0.2.2 (port 7980) after 10000ms"), null)
        assertTrue(join.await().isFailure)
        assertEquals("no validate request to a host that never connected", 0, validates)

        val again = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        client.onFailure(wsFactory.ws, ConnectException("failed to connect: ECONNREFUSED"), null)
        assertTrue(again.await().isFailure)
        assertEquals(0, validates)

        // a hung server (TCP up, the upgrade never answered: the sim's paused container) times out reading
        val hung = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        client.onFailure(wsFactory.ws, SocketTimeoutException("timeout"), null)
        assertTrue(hung.await().isFailure)
        assertEquals("no validate request to a host that does not answer", 0, validates)
    }

    @Test
    fun aDialThatConnectedIsStillValidated() = runTest {
        val join = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        client.onFailure(wsFactory.ws, java.io.EOFException("the upgrade was answered with a close"), null)
        assertTrue(join.await().isFailure)
        assertEquals("a connection came up: validate (it tells a 401/403 apart)", 1, validates)
    }

    @Test
    fun silentHostFailFastOffValidatesAsUpstream() = runTest {
        client.gravixSilentHostFailFast = false
        val join = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        client.onFailure(wsFactory.ws, ConnectException("failed to connect: ECONNREFUSED"), null)
        assertTrue(join.await().isFailure)
        assertEquals(1, validates)
    }

    @Test
    fun upstreamIsOff() {
        assertTrue(GravixRecoveryOptions.UPSTREAM.signalDialCapMs == null && !GravixRecoveryOptions.UPSTREAM.silentHostFailFast)
        assertTrue(GravixRecoveryOptions().signalDialCapMs == 30_000L && GravixRecoveryOptions().silentHostFailFast)
    }
}
