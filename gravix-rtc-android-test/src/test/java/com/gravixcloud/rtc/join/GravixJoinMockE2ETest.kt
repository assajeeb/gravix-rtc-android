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
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.dagger.DaggerTestGravixRtcComponent
import com.gravixcloud.rtc.test.mock.dagger.TestCoroutinesModule
import com.gravixcloud.rtc.util.GravixLogSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/** A fakeLifecycle the test drives: foreground / background and frames. */
internal class FakeAppLifecycle : GravixAppLifecycle {
    var background = false
    private val observers = mutableListOf<Pair<() -> Unit, () -> Unit>>()
    private val frames = mutableListOf<() -> Unit>()

    override val isInBackground: Boolean
        get() = background

    override fun observe(onBackground: () -> Unit, onForeground: () -> Unit): AutoCloseable {
        val o = onBackground to onForeground
        observers.add(o)
        return AutoCloseable { observers.remove(o) }
    }

    override fun afterNextFrame(action: () -> Unit) {
        frames.add(action)
    }

    val observing: Int
        get() = observers.size

    fun goBackground() {
        background = true
        observers.toList().forEach { it.first() }
    }

    fun goForeground() {
        background = false
        observers.toList().forEach { it.second() }
    }

    fun drawFrame() {
        val f = frames.toList()
        frames.clear()
        f.forEach { it() }
    }
}

/**
 * GRAVIX (0.1.2): [GravixJoin] / [GravixPrejoin] on the MockE2E engine: the tap's connect handed to the screen, the
 * background guards, one session at a time, the switch, a dead Room, adopt, the attach timeout and the step lines.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixJoinMockE2ETest : MockE2ETest() {

    private val fakeLifecycle = FakeAppLifecycle()
    private val events = mutableListOf<GravixJoinEvent>()
    private val lines = mutableListOf<String>()
    private val left = mutableListOf<String>()
    private var fetches = 0
    private var failToken = false
    private val request = GravixJoinRequest("roomname", "user-42")
    private val other = GravixJoinRequest("room-b", "user-42")
    private val extraRooms = mutableListOf<Room>()

    private lateinit var tokens: GravixJoinTokenProvider

    @Before
    fun setUpJoin() {
        tokens = GravixJoinTokenProvider(
            GravixJoinTokenFetcher {
                fetches++
                if (failToken) throw IllegalStateException("refused")
                GravixJoinCredentials(TestData.EXAMPLE_URL, "token")
            },
            GravixTokenReuse.FAST_JOIN,
            coroutineRule.dispatcher,
        )
    }

    @After
    fun releaseExtraRooms() {
        extraRooms.forEach { it.release() }
    }

    private fun newRoom(): Room {
        val c = DaggerTestGravixRtcComponent.factory().create(context, TestCoroutinesModule(coroutineRule.dispatcher))
        return c.roomFactory().create(context).apply { enableMetrics = false }.also { extraRooms.add(it) }
    }

    private fun recordingLeave(name: String) = GravixJoinLeave { r ->
        left.add(name)
        r.disconnect()
    }

    private fun join(configure: GravixJoin.Builder.() -> Unit = {}): GravixJoin =
        GravixJoin.Builder(context, tokens)
            .setAttachTimeoutMs(0)
            .setListener { events.add(it) }
            .setLogSink(GravixLogSink { _, line -> lines.add(line) })
            .apply(configure)
            .apply { setTestEnvironment(coroutineRule.dispatcher, Executor { it.run() }, fakeLifecycle) }
            .build()

    private fun hooks(room: Room, leaveName: String = "a", connect: GravixJoinConnect? = null) =
        GravixJoinHooks.Builder()
            .setCreateRoom { room }
            .setLeave(recordingLeave(leaveName))
            .apply { if (connect != null) setConnect(connect) }
            .build()

    /** A connect the test completes ([CompletableDeferred.complete]) or fails. */
    private fun gatedConnect(gate: CompletableDeferred<Unit>) = GravixJoinConnect { _, _ -> gate.await() }

    private fun kinds() = events.map { it.kind }

    @Test
    fun theTapsConnectIsTheScreensRoom() = runTest {
        val j = join()
        val h = j.prepare(request, hooks(room))
        prepareSignal()
        connectPeerConnection()
        assertEquals(GravixPrejoinState.CONNECTED, h.state)
        assertSame("a double tap is the same join", h, j.prepare(request))
        val got = j.attach(request)
        assertSame(room, got)
        assertEquals(GravixPrejoinState.ATTACHED, h.state)
        assertEquals(1, fetches)
        assertEquals(
            listOf(
                GravixJoinEventKind.PREJOIN_START,
                GravixJoinEventKind.TOKEN_READY,
                GravixJoinEventKind.URL_CHOSEN,
                GravixJoinEventKind.CONNECT_START,
                GravixJoinEventKind.CONNECTED,
                GravixJoinEventKind.ATTACHED,
            ),
            kinds(),
        )
        assertEquals("fetched", events[1].detail)
        // the step lines: no identity, no token
        assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("[GravixJoin] connectOk key=roomname|${GravixJoinLog.shortId("user-42")}|pub=false") })
        assertTrue(lines.none { it.contains("user-42") || it.contains("token=") })
        j.leave()
        assertEquals(listOf("a"), left)
        assertEquals(GravixPrejoinEndReason.LEFT, h.endReason)
        assertEquals(Room.State.DISCONNECTED, room.state)
    }

    @Test
    fun aWarmedTokenIsCachedAtTheTap() = runTest {
        val j = join()
        assertNotNull(j.warm(request))
        val gate = CompletableDeferred<Unit>()
        val h = j.prepare(request, hooks(room, connect = gatedConnect(gate)))
        assertEquals(1, fetches)
        assertEquals("cached", events.first { it.kind == GravixJoinEventKind.TOKEN_READY }.detail)
        gate.complete(Unit)
        h.abandon()
    }

    @Test
    fun backgroundBeforeAttachAbandonsAndLeaves() = runTest {
        val j = join()
        val gate = CompletableDeferred<Unit>()
        val h = j.prepare(request, hooks(room, connect = gatedConnect(gate)))
        gate.complete(Unit)
        assertEquals(GravixPrejoinState.CONNECTED, h.state)
        fakeLifecycle.goBackground()
        assertEquals(GravixPrejoinEndReason.BACKGROUND, h.endReason)
        assertEquals(listOf("a"), left)
        assertNull(h.attach())
        assertNull(j.pending(request))
        assertEquals(0, fakeLifecycle.observing)
    }

    @Test
    fun startedInTheBackgroundDialsNothing() = runTest {
        fakeLifecycle.background = true
        var built = 0
        val j = join()
        val h = j.prepare(request, GravixJoinHooks.Builder().setCreateRoom { built++; room }.build())
        assertEquals(GravixPrejoinEndReason.BACKGROUND, h.endReason)
        assertEquals("no Room built, nothing dialled", 0, built)
    }

    @Test
    fun abandonInBackgroundFalseKeepsTheJoin() = runTest {
        val j = join()
        val gate = CompletableDeferred<Unit>()
        val keep = GravixJoinHooks.Builder().setCreateRoom { room }.setLeave(recordingLeave("a"))
            .setConnect(gatedConnect(gate)).setAbandonInBackground(false).build()
        val h = j.prepare(request, keep)
        fakeLifecycle.goBackground()
        gate.complete(Unit)
        assertSame(room, h.attach())
        assertTrue(left.isEmpty())
        h.leave()
    }

    @Test
    fun claimedThenShownKeepsTheRoomOnHome() = runTest {
        val j = join()
        val gate = CompletableDeferred<Unit>()
        val h = j.prepare(request, hooks(room, connect = gatedConnect(gate)))
        val attached = async { h.attach() } // the screen calls attach while the connect runs
        fakeLifecycle.drawFrame() // its first frame, in the foreground
        assertTrue(kinds().contains(GravixJoinEventKind.SHOWN))
        fakeLifecycle.goBackground() // Home with the room on screen
        gate.complete(Unit)
        assertSame(room, attached.await())
        assertEquals(GravixPrejoinState.ATTACHED, h.state)
        assertTrue(left.isEmpty())
        h.leave()
    }

    @Test
    fun claimedButNeverShownLeavesOnBackground() = runTest {
        val j = join()
        val gate = CompletableDeferred<Unit>()
        val h = j.prepare(request, hooks(room, connect = gatedConnect(gate)))
        val attached = async { h.attach() }
        fakeLifecycle.goBackground() // before the screen's first frame
        assertEquals(GravixPrejoinEndReason.NEVER_SHOWN, h.endReason)
        assertNull(attached.await())
        fakeLifecycle.drawFrame()
        assertFalse(kinds().contains(GravixJoinEventKind.SHOWN))
    }

    @Test
    fun markShownBeforeAttachKeepsTheRoom() = runTest {
        val j = join()
        val gate = CompletableDeferred<Unit>()
        val h = j.prepare(request, hooks(room, connect = gatedConnect(gate)))
        h.markShown()
        fakeLifecycle.goBackground()
        gate.complete(Unit)
        assertSame(room, h.attach())
        h.leave()
    }

    @Test
    fun nobodyAttachesLeavesAfterTheTimeout() = runTest {
        val j = join { setAttachTimeoutMs(1_000) }
        val gate = CompletableDeferred<Unit>()
        val h = j.prepare(request, hooks(room, connect = gatedConnect(gate)))
        gate.complete(Unit)
        testScheduler.advanceTimeBy(999)
        assertEquals(GravixPrejoinState.CONNECTED, h.state)
        testScheduler.advanceTimeBy(2)
        assertEquals(GravixPrejoinEndReason.ATTACH_TIMEOUT, h.endReason)
        assertEquals(listOf("a"), left)
    }

    @Test
    fun abandonWhileConnectingEndsTheConnectAtOnce() = runTest {
        val j = join()
        val h = j.prepare(request, GravixJoinHooks.Builder().setCreateRoom { room }.build()) // default connect + leave
        // the Room is dialling (no server answer yet)
        assertEquals(Room.State.CONNECTING, room.state)
        j.abandon(request)
        assertEquals(GravixPrejoinEndReason.ABANDONED, h.endReason)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertFalse(kinds().contains(GravixJoinEventKind.CONNECTED))
        assertFalse(h.awaitConnected())
    }

    @Test
    fun oneSessionAtATimeTheOldLeaveFirst() = runTest {
        val j = join()
        val order = mutableListOf<String>()
        val roomB = newRoom()
        val a = j.prepare(request, hooks(room, "a", GravixJoinConnect { _, _ -> order.add("connect a") }))
        assertSame(room, a.attach())
        val b = j.prepare(
            other,
            GravixJoinHooks.Builder().setCreateRoom { roomB }.setLeave(recordingLeave("b"))
                .setConnect(GravixJoinConnect { _, _ -> order.add("connect b (a left: ${left.contains("a")})") }).build(),
        )
        assertEquals(GravixPrejoinEndReason.SUPERSEDED, a.endReason)
        assertEquals(listOf("connect a", "connect b (a left: true)"), order)
        assertSame(b, j.current)
        assertTrue(lines.any { it.contains("supersedes=roomname|") })
        j.leave()
        assertEquals(listOf("a", "b"), left)
    }

    @Test
    fun switchWaitsForTheOldLeaveAndBeforeConnect() = runTest {
        val j = join()
        val roomB = newRoom()
        val a = j.prepare(request, hooks(room, "a", GravixJoinConnect { _, _ -> }))
        a.attach()
        val app = CompletableDeferred<Boolean>()
        val b = j.switchTo(
            other,
            GravixSwitchBeforeConnect { app.await() },
            GravixJoinHooks.Builder().setCreateRoom { roomB }.setLeave(recordingLeave("b")).setConnect(GravixJoinConnect { _, _ -> }).build(),
        )
        assertEquals(listOf("a"), left)
        assertEquals(GravixPrejoinState.STARTING, b.state) // waits for the app's beforeConnect
        app.complete(true)
        assertSame(roomB, b.attach())
        val switchKinds = kinds().filter { it.name.startsWith("SWITCH") }
        assertEquals(listOf(GravixJoinEventKind.SWITCH_START, GravixJoinEventKind.SWITCH_OLD_LEFT, GravixJoinEventKind.SWITCH_CONNECTED), switchKinds)
        b.leave()
    }

    @Test
    fun aSwitchTheAppCancelsDoesNotConnect() = runTest {
        val j = join()
        var connected = false
        val b = j.switchTo(
            other,
            GravixSwitchBeforeConnect { false },
            GravixJoinHooks.Builder().setCreateRoom { room }.setConnect(GravixJoinConnect { _, _ -> connected = true }).build(),
        )
        assertEquals(GravixPrejoinEndReason.CANCELLED, b.endReason)
        assertFalse(connected)
        assertNull(j.pending(other))
    }

    @Test
    fun aDeadRoomIsEndedAndNeverLeftAgain() = runTest {
        val j = join()
        val h = j.prepare(request, hooks(room))
        prepareSignal()
        connectPeerConnection()
        assertSame(room, h.attach())
        simulateMessageFromServer(TestData.LEAVE) // the server ends the session
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertEquals(GravixPrejoinEndReason.ROOM_GONE, h.endReason)
        assertTrue("the leave hook never runs on a dead Room", left.isEmpty())
        assertNull(j.pending(request))
        assertNull(j.current)
        j.leave()
        assertTrue(left.isEmpty())
        // the next tap is a fresh join
        val gate = CompletableDeferred<Unit>()
        val again = j.prepare(request, hooks(newRoom(), "again", gatedConnect(gate)))
        assertNotSame(h, again)
        gate.complete(Unit)
        again.leave()
    }

    @Test
    fun adoptReplacesTheAttachedSessionWithoutItsLeave() = runTest {
        val j = join()
        val a = j.prepare(request, hooks(room, "a", GravixJoinConnect { _, _ -> }))
        a.attach()
        val guest = GravixJoinRequest("roomname", "user-42", true)
        val roomB = newRoom()
        val adopted = j.adopt(guest, roomB, recordingLeave("adopted"))
        assertEquals(GravixPrejoinEndReason.REPLACED, a.endReason)
        assertTrue(left.isEmpty())
        assertEquals(GravixPrejoinState.ATTACHED, adopted.state)
        assertSame(roomB, j.attach(guest))
        assertSame(adopted, j.current)
        assertTrue(kinds().contains(GravixJoinEventKind.ADOPTED))
        j.leave()
        assertEquals(listOf("adopted"), left)
    }

    @Test
    fun aFailedTokenEndsTheJoin() = runTest {
        failToken = true
        val j = join()
        val h = j.prepare(request, hooks(room))
        assertEquals(GravixPrejoinEndReason.FAILED, h.endReason)
        assertTrue(h.error is GravixJoinTokenException)
        assertNull(j.pending(request))
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("connectFail") && it.contains("GravixJoinTokenException, cause IllegalStateException") })
        assertTrue(kinds().contains(GravixJoinEventKind.CONNECT_FAILED))
    }

    @Test
    fun attachWithoutATapIsAColdJoin() = runTest {
        val j = join()
        val got = j.attach(request, hooks(room, connect = GravixJoinConnect { _, _ -> }))
        assertSame(room, got)
        assertTrue(lines.any { it.contains("attachCold") })
        j.leave()
    }

    @Test
    fun javaFacadeFutures() = runTest {
        val j = join()
        val h = j.prepare(request, hooks(room, connect = GravixJoinConnect { _, _ -> }))
        assertSame(room, h.attachAsync().get())
        assertEquals(true, h.connectedFuture().get())
        j.leaveAsync().get()
        assertEquals(GravixPrejoinEndReason.LEFT, h.endedFuture().get())
    }
}
