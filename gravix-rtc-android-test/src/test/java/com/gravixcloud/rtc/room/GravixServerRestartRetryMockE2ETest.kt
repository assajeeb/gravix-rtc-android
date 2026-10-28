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
import com.gravixcloud.rtc.join.GravixJoinLog
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import okhttp3.Protocol
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * GRAVIX (0.3.1): GravixRecoveryOptions.retryServerRestart. A first connect whose (last) url answered the WebSocket
 * with 502/503/504 and no reason text (the SFU restarting behind its proxy) dials the same url again after 0.5 s and
 * 1.5 s, at most twice; a refusal (401/403) and a reason text stay final; off = one attempt, as before. No region list
 * here (example.com is not a Gravix Cloud host): the url is the last one at once.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixServerRestartRetryMockE2ETest : MockE2ETest() {

    private val lines = mutableListOf<String>()

    @Before
    fun captureJoinLines() {
        GravixJoinLog.sink = GravixLogSink { _, line -> synchronized(lines) { lines.add(line) } }
    }

    @After
    fun releaseJoinLines() {
        GravixJoinLog.sink = null
    }

    private suspend fun awaitConnecting() {
        room::state.flow.takeWhile { it != Room.State.CONNECTING }.collect()
    }

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    // the upgrade answered with [status]; the validate request gets no answer in the mock (no reason text)
    private fun failDial(ws: MockWebSocket, status: Int) {
        val response = Response.Builder().request(wsFactory.request).protocol(Protocol.HTTP_1_1).code(status).message("").build()
        wsFactory.listener.onFailure(ws, IOException("Expected HTTP 101 response but was '$status'"), response)
    }

    private fun retryLines() = synchronized(lines) { lines.filter { it.startsWith("[GravixJoin] joinRetry ") } }

    @Test
    fun aGatewayErrorWithoutReasonIsDialledAgainAndConnects() = runTest {
        val job = coroutineRule.scope.launch { room.connect(TestData.EXAMPLE_URL, "token") }
        awaitConnecting()
        val first = wsFactory.ws
        failDial(first, 502)
        advance(400)
        assertSame("no dial before the 0.5 s wait", first, wsFactory.ws)
        advance(150)
        val second = wsFactory.ws
        assertTrue("dialled again after 0.5 s", second !== first)
        assertEquals("the same url", "www.example.com", wsFactory.request.url.host)
        failDial(second, 503)
        advance(1400)
        assertSame("no dial before the 1.5 s wait", second, wsFactory.ws)
        advance(150)
        assertTrue("dialled a third time", wsFactory.ws !== second)

        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN)
        job.join()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        assertEquals(Room.State.CONNECTED, room.state)
        val retries = retryLines()
        assertEquals(retries.joinToString("\n"), 2, retries.size)
        assertTrue(retries[0], retries[0].contains("n=1 in=500ms status=502"))
        assertTrue(retries[1], retries[1].contains("n=2 in=1500ms status=503"))
    }

    @Test
    fun theRetriesAreBounded() = runTest {
        var error: Throwable? = null
        val job = coroutineRule.scope.launch { runCatching { room.connect(TestData.EXAMPLE_URL, "token") }.onFailure { error = it } }
        awaitConnecting()
        var dials = 0
        repeat(3) {
            dials++
            failDial(wsFactory.ws, 504)
            advance(1600)
        }
        job.join()
        assertNotNull("the connect failed after two retries", error)
        assertEquals(3, dials)
        assertEquals(2, retryLines().size)
        assertEquals(Room.State.DISCONNECTED, room.state)
    }

    @Test
    fun aRefusalStaysFinal() = runTest {
        var error: Throwable? = null
        val job = coroutineRule.scope.launch { runCatching { room.connect(TestData.EXAMPLE_URL, "token") }.onFailure { error = it } }
        awaitConnecting()
        val first = wsFactory.ws
        failDial(first, 403)
        advance(2000)
        job.join()
        assertNotNull(error)
        assertSame("never dialled again", first, wsFactory.ws)
        assertTrue(retryLines().isEmpty())
    }

    @Test
    fun offFailsAtOnce() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(retryServerRestart = false)
        var error: Throwable? = null
        val job = coroutineRule.scope.launch { runCatching { room.connect(TestData.EXAMPLE_URL, "token") }.onFailure { error = it } }
        awaitConnecting()
        val first = wsFactory.ws
        failDial(first, 502)
        advance(2000)
        job.join()
        assertNotNull(error)
        assertSame(first, wsFactory.ws)
        assertTrue(retryLines().isEmpty())
    }

    @Test
    fun cancellingTheConnectEndsTheWait() = runTest {
        val job = coroutineRule.scope.launch { room.connect(TestData.EXAMPLE_URL, "token") }
        awaitConnecting()
        val first = wsFactory.ws
        failDial(first, 502)
        advance(100)
        job.cancel()
        advance(1000)
        assertSame("no dial after the cancel", first, wsFactory.ws)
        assertEquals(Room.State.DISCONNECTED, room.state)
    }

    @Test
    fun theClassification() {
        assertTrue(GravixRecoveryOptions.isServerRestart(502, hadReason = false))
        assertTrue(GravixRecoveryOptions.isServerRestart(503, hadReason = false))
        assertTrue(GravixRecoveryOptions.isServerRestart(504, hadReason = false))
        assertFalse("a reason text: a refusal", GravixRecoveryOptions.isServerRestart(503, hadReason = true))
        assertFalse(GravixRecoveryOptions.isServerRestart(500, hadReason = false))
        assertFalse(GravixRecoveryOptions.isServerRestart(401, hadReason = false))
        assertFalse(GravixRecoveryOptions.isServerRestart(403, hadReason = false))
        assertFalse("no answer at all", GravixRecoveryOptions.isServerRestart(null, hadReason = false))
        assertEquals(true, GravixRecoveryOptions().retryServerRestart)
        assertEquals(false, GravixRecoveryOptions.UPSTREAM.retryServerRestart)
    }
}
