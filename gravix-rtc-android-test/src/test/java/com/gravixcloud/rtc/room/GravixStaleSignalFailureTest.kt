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

import com.gravixcloud.rtc.stats.NetworkInfo
import com.gravixcloud.rtc.stats.NetworkType
import com.gravixcloud.rtc.test.BaseTest
import com.gravixcloud.rtc.test.mock.MockWebSocketFactory
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.toOkioByteString
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import java.io.IOException

/**
 * GRAVIX (GravixRecoveryOptions.ignoreStaleSignalFailures), found on the sim (2026-10-12, run fo3: region A paused):
 * SignalClient.onFailure checks that the failing WebSocket is the current one, then makes a blocking validate request
 * (10 s against a silent host) and then fails whatever join is current. A newer connect that started meanwhile (the
 * next reconnect attempt, or the failover's join in another region) got the stale failure.
 *
 * Here the validate request itself starts the newer connect, which reproduces the interleaving deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixStaleSignalFailureTest : BaseTest() {

    private lateinit var wsFactory: MockWebSocketFactory
    private lateinit var client: SignalClient
    private var duringValidate: (() -> Unit)? = null
    private var validates = 0

    @Before
    fun setup() {
        wsFactory = MockWebSocketFactory()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                validates++
                duringValidate?.let {
                    duringValidate = null
                    it()
                }
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

    /** ws1 fails; while its validate runs, a newer join starts (ws2). Returns the newer join. */
    private fun CoroutineScope.staleFailureDuringNewerJoin(): Deferred<Result<GravixcloudRtc.JoinResponse>> {
        async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        val ws1 = wsFactory.ws
        var newer: Deferred<Result<GravixcloudRtc.JoinResponse>>? = null
        duringValidate = { newer = async { runCatching { client.join("ws://region-b.example.test", "token") } } }
        client.onFailure(ws1, IOException("silent host"), null)
        return newer!!
    }

    @Test
    fun aStaleFailureDoesNotFailTheNewerJoin() = runTest {
        val newer = staleFailureDuringNewerJoin()
        assertEquals("region-b.example.test", wsFactory.request.url.host)
        client.onOpen(wsFactory.ws, open())
        client.onMessage(wsFactory.ws, TestData.JOIN.toOkioByteString())
        assertTrue("the newer join succeeds", newer.await().isSuccess)
        assertTrue(client.isConnected)
    }

    @Test
    fun switchedOffTheStaleFailureFailsTheNewerJoinAsUpstream() = runTest {
        client.gravixIgnoreStaleFailures = false
        val newer = staleFailureDuringNewerJoin()
        assertTrue("upstream: the stale failure fails the newer join", newer.await().isFailure)
    }

    @Test
    fun aSilentHostTheDialBoundGaveUpOnIsNotValidated() = runTest {
        client.gravixNextDialTimeoutMs = 2500
        val join = async { runCatching { client.join(TestData.EXAMPLE_URL, "token") } }
        testScheduler.advanceTimeBy(2600)
        testScheduler.runCurrent()
        assertTrue("the dial bound failed the join", join.await().isFailure)
        assertEquals("no validate request to the silent host", 0, validates)
    }
}
