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

import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.test.BaseTest
import com.gravixcloud.rtc.util.GravixLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GRAVIX (0.1.2): the viewer rejoin policy: reasons, backoff, caps, the entry check, live end / back. */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixRejoinPolicyTest : BaseTest() {

    private var live = true
    private var rejoins = 0
    private var rejoinResult = true
    private var gaveUp = 0
    private var allow = true
    private val lines = mutableListOf<String>()
    private var nowMs = 0L
    private val policies = mutableListOf<GravixRejoinPolicy>()

    @After
    fun releaseAll() = policies.forEach { it.release() }

    private fun policy(configure: GravixRejoinPolicy.Builder.() -> Unit = {}): GravixRejoinPolicy =
        GravixRejoinPolicy.Builder(GravixRejoinAction { rejoins++; rejoinResult }, GravixLiveCheck { live })
            .setOnGaveUp { gaveUp++ }
            .setLogSink(GravixLogSink { _, l -> lines.add(l) })
            .apply(configure)
            .apply { setTestEnvironment(coroutineRule.dispatcher) { nowMs } }
            .build()
            .also { policies.add(it) }

    @Test
    fun reasons() {
        assertTrue(GravixRejoinPolicy.rejoinable(null))
        assertTrue(GravixRejoinPolicy.rejoinable(DisconnectReason.SIGNAL_CLOSE))
        assertTrue(GravixRejoinPolicy.rejoinable(DisconnectReason.SERVER_SHUTDOWN))
        assertFalse(GravixRejoinPolicy.rejoinable(DisconnectReason.PARTICIPANT_REMOVED))
        assertFalse(GravixRejoinPolicy.rejoinable(DisconnectReason.DUPLICATE_IDENTITY))
        assertFalse(GravixRejoinPolicy.rejoinable(DisconnectReason.CLIENT_INITIATED))
        assertFalse(GravixRejoinPolicy.rejoinable(DisconnectReason.JOIN_FAILURE))
    }

    @Test
    fun rejoinsAfterOneSecond() = runTest {
        val p = policy()
        assertTrue(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
        assertTrue(p.pending)
        testScheduler.advanceTimeBy(999)
        assertEquals(0, rejoins)
        testScheduler.advanceTimeBy(2)
        assertEquals(1, rejoins)
        assertFalse(p.pending)
        assertTrue(lines.any { it.startsWith("[GravixJoin] rejoinOk key=- n=1") })
    }

    @Test
    fun neverAfterAKickNorWhenTheLiveIsOff() = runTest {
        val p = policy()
        assertFalse(p.sessionEnded(DisconnectReason.PARTICIPANT_REMOVED))
        live = false
        assertFalse(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
        testScheduler.advanceTimeBy(20_000)
        assertEquals(0, rejoins)
    }

    @Test
    fun failedRejoinsBackOffThenGiveUp() = runTest {
        rejoinResult = false
        val p = policy()
        p.sessionEnded(null)
        testScheduler.advanceTimeBy(1_001) // attempt 1
        testScheduler.advanceTimeBy(2_000) // attempt 2
        testScheduler.advanceTimeBy(4_000) // attempt 3
        assertEquals(3, rejoins)
        testScheduler.advanceTimeBy(8_000) // attempt 4, then give up
        assertEquals(4, rejoins)
        assertEquals(1, gaveUp)
        testScheduler.advanceTimeBy(60_000)
        assertEquals(4, rejoins)
        assertEquals(4, p.total)
    }

    @Test
    fun threeRejoinsThatEachEndAtOnceIsAnIncident() = runTest {
        val p = policy()
        repeat(3) {
            assertTrue(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
            testScheduler.advanceTimeBy(1_001)
        }
        assertEquals(3, rejoins)
        assertFalse(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
        assertFalse(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
        assertEquals("onGaveUp once per incident", 1, gaveUp)
        assertEquals(3, rejoins)
    }

    @Test
    fun aRejoinThatStayedUpEndsTheIncident() = runTest {
        val p = policy()
        repeat(3) {
            p.sessionEnded(DisconnectReason.SIGNAL_CLOSE)
            testScheduler.advanceTimeBy(1_001)
        }
        nowMs += 60_000 // the last rejoin stayed up a minute
        assertTrue(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
        testScheduler.advanceTimeBy(1_001)
        assertEquals(4, rejoins)
    }

    @Test
    fun theEntryCheckCanRefuse() = runTest {
        val p = policy { setMayRejoin(GravixRejoinAction { allow }) }
        allow = false
        p.sessionEnded(DisconnectReason.SIGNAL_CLOSE)
        testScheduler.advanceTimeBy(1_001)
        assertEquals(0, rejoins)
        assertTrue(p.refused)
        assertFalse(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
    }

    @Test
    fun capPerLive() = runTest {
        rejoinResult = false
        val p = policy {
            setMaxTotal(2)
            setBackoffMs(1_000, 1_000, 1_000, 1_000)
        }
        p.sessionEnded(DisconnectReason.SIGNAL_CLOSE)
        testScheduler.advanceTimeBy(5_000)
        assertEquals(2, rejoins)
        assertEquals(1, gaveUp)
    }

    @Test
    fun liveEndedCancelsAndLiveBackJoinsNow() = runTest {
        val p = policy()
        p.sessionEnded(DisconnectReason.SIGNAL_CLOSE)
        p.liveEnded()
        testScheduler.advanceTimeBy(5_000)
        assertEquals(0, rejoins)
        assertFalse(p.sessionEnded(DisconnectReason.SIGNAL_CLOSE))
        p.liveBack()
        testScheduler.advanceTimeBy(1)
        assertEquals(1, rejoins)
    }
}
