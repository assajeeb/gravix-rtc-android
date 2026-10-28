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

package com.gravixcloud.rtc.join

import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.RoomException
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.GravixLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import okhttp3.Protocol
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.Executor

/**
 * GRAVIX (0.3.1): GravixJoin.Builder.setRetryCapacityRefusals. A join refused at the owner's concurrent-user cap
 * ("app user limit reached") is tried again on the same Room after 1, 2, 3, 5, 8, then every 10 s (+-20 %; the jitter
 * is pinned to the middle here), silently, until it connects or the join ends; off by default; "app plan limit
 * reached" and other errors fail at once.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixJoinCapacityRetryMockE2ETest : MockE2ETest() {

    private val fakeLifecycle = FakeAppLifecycle()
    private val events = mutableListOf<GravixJoinEvent>()
    private val lines = mutableListOf<String>()
    private val request = GravixJoinRequest("roomname", "user-42")
    private lateinit var tokens: GravixJoinTokenProvider

    private val userCap = RoomException.ConnectException("app user limit reached")
    private val planCap = RoomException.ConnectException("app plan limit reached")

    @Before
    fun setUpJoin() {
        tokens = GravixJoinTokenProvider(
            GravixJoinTokenFetcher { GravixJoinCredentials(TestData.EXAMPLE_URL, "token") },
            GravixTokenReuse.FAST_JOIN,
            coroutineRule.dispatcher,
        )
    }

    private fun join(retry: Boolean?): GravixJoin =
        GravixJoin.Builder(context, tokens)
            .setAttachTimeoutMs(0)
            .setListener { events.add(it) }
            .setLogSink(GravixLogSink { _, line -> lines.add(line) })
            .apply { if (retry != null) setRetryCapacityRefusals(retry) }
            .apply {
                // the virtual clock (the waited= of capacityRetryOk)
                setTestEnvironment(coroutineRule.dispatcher, Executor { it.run() }, fakeLifecycle, nanoTime = { coroutineRule.dispatcher.scheduler.currentTime * 1_000_000 })
                random = { 0.5 } // the jitter's middle: the waits exactly
            }
            .build()

    /** A connect that throws [errors] in turn, then connects; counts its calls. */
    private class ScriptedConnect(private val errors: List<Throwable>) {
        var calls = 0
        val connect = GravixJoinConnect { _, _ ->
            val e = errors.getOrNull(calls)
            calls++
            if (e != null) throw e
        }
    }

    private fun hooks(c: ScriptedConnect) = GravixJoinHooks.Builder()
        .setCreateRoom { room }
        .setConnect(c.connect)
        .setLeave(GravixJoinLeave { })
        .build()

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun lines(step: String) = lines.filter { it.startsWith("[GravixJoin] $step ") }

    @Test
    fun aCapacityRefusalIsRetriedOnTheSameRoomUntilItConnects() = runTest {
        val c = ScriptedConnect(listOf(userCap, userCap, userCap))
        val h = join(true).prepare(request, hooks(c))
        assertEquals(1, c.calls)
        assertEquals("silently: still connecting", GravixPrejoinState.CONNECTING, h.state)
        advance(999)
        assertEquals("no attempt before 1 s", 1, c.calls)
        advance(1)
        assertEquals(2, c.calls)
        advance(2_000)
        assertEquals(3, c.calls)
        advance(3_000)
        assertEquals(4, c.calls)
        assertEquals(GravixPrejoinState.CONNECTED, h.state)
        assertSame(room, h.room)
        assertTrue("no failure surfaced", events.none { it.kind == GravixJoinEventKind.CONNECT_FAILED })
        val waits = lines("capacityRetry")
        assertEquals(waits.joinToString("\n"), 3, waits.size)
        assertTrue(waits[0], waits[0].contains("n=1 in=1000ms"))
        assertTrue(waits[1], waits[1].contains("n=2 in=2000ms"))
        assertTrue(waits[2], waits[2].contains("n=3 in=3000ms"))
        val ok = lines("capacityRetryOk")
        assertEquals(1, ok.size)
        assertTrue(ok[0], ok[0].contains("after=3 waited=6000ms"))
        h.abandon()
    }

    @Test
    fun theWaitsGrowToTenSecondsAndRepeat() = runTest {
        val c = ScriptedConnect(List(7) { userCap })
        val h = join(true).prepare(request, hooks(c))
        advance(1_000 + 2_000 + 3_000 + 5_000 + 8_000 + 10_000 + 10_000)
        assertEquals(8, c.calls)
        assertEquals(GravixPrejoinState.CONNECTED, h.state)
        assertEquals(
            listOf(1000, 2000, 3000, 5000, 8000, 10000, 10000).mapIndexed { i, ms -> "n=${i + 1} in=${ms}ms" },
            lines("capacityRetry").map { Regex("n=\\d+ in=\\d+ms").find(it)!!.value },
        )
        h.abandon()
    }

    @Test
    fun theJitterStaysWithinTwentyPercent() = runTest {
        val lo = ScriptedConnect(listOf(userCap))
        val j = GravixJoin.Builder(context, tokens)
            .setAttachTimeoutMs(0)
            .setRetryCapacityRefusals(true)
            .setLogSink(GravixLogSink { _, line -> lines.add(line) })
            .apply {
                setTestEnvironment(coroutineRule.dispatcher, Executor { it.run() }, fakeLifecycle)
                random = { 0.0 }
            }
            .build()
        val h = j.prepare(request, hooks(lo))
        advance(799)
        assertEquals(1, lo.calls)
        advance(1)
        assertEquals("-20 %: 0.8 s", 2, lo.calls)
        assertTrue(lines("capacityRetry")[0].contains("in=800ms"))
        h.abandon()
    }

    @Test
    fun leavingDuringTheWaitStopsTheRetries() = runTest {
        val c = ScriptedConnect(List(10) { userCap })
        val h = join(true).prepare(request, hooks(c))
        advance(500)
        h.abandon()
        assertEquals(GravixPrejoinState.ENDED, h.state)
        assertEquals(GravixPrejoinEndReason.ABANDONED, h.endReason)
        advance(30_000)
        assertEquals("no attempt after the abandon", 1, c.calls)
        assertNull(h.error)
    }

    @Test
    fun offByDefault() = runTest {
        val c = ScriptedConnect(listOf(userCap))
        val h = join(null).prepare(request, hooks(c))
        advance(5_000)
        assertEquals(1, c.calls)
        assertEquals(GravixPrejoinEndReason.FAILED, h.endReason)
        assertSame(userCap, h.error)
        assertTrue(lines("capacityRetry").isEmpty())
    }

    @Test
    fun thePlanLimitAndOtherErrorsFailAtOnce() = runTest {
        for (e in listOf(planCap, RoomException.ConnectException("unauthorized"), IllegalStateException("boom"))) {
            val c = ScriptedConnect(listOf(e))
            val h = join(true).prepare(request, hooks(c))
            advance(5_000)
            assertEquals(e.message, 1, c.calls)
            assertEquals(GravixPrejoinEndReason.FAILED, h.endReason)
            assertSame(e, h.error)
        }
        assertTrue(lines("capacityRetry").isEmpty())
    }

    @Test
    fun theRealRoomConnectsAgainAfterACapacityRefusal() = runTest {
        // the default connect (Room.connect); the mock's validate request gets no answer, so the refusal's text
        // reaches the ConnectException through the WebSocket failure itself
        val hooks = GravixJoinHooks.Builder().setCreateRoom { room }.setLeave(GravixJoinLeave { it.disconnect() }).build()
        val h = join(true).prepare(request, hooks)
        val first = wsFactory.ws
        val refused = Response.Builder().request(wsFactory.request).protocol(Protocol.HTTP_1_1).code(403).message("").build()
        wsFactory.listener.onFailure(first, IOException("app user limit reached"), refused)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertEquals(GravixPrejoinState.CONNECTING, h.state)
        advance(1_000)
        assertTrue("the same Room dialled again", wsFactory.ws !== first)
        prepareSignal()
        connectPeerConnection()
        assertEquals(GravixPrejoinState.CONNECTED, h.state)
        assertSame(room, h.room)
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals(1, lines("capacityRetryOk").size)
        h.leave()
    }

    @Test
    fun isCapacityRefusal() {
        assertTrue(GravixJoin.isCapacityRefusal(userCap))
        assertTrue("a copy that keeps the original as its cause", GravixJoin.isCapacityRefusal(RuntimeException("x", userCap)))
        assertFalse(GravixJoin.isCapacityRefusal(planCap))
        assertFalse("not a connect refusal", GravixJoin.isCapacityRefusal(IllegalStateException("app user limit reached")))
        assertFalse(GravixJoin.isCapacityRefusal(RoomException.ConnectException(null)))
    }
}
