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

import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX (Phase 3, B2): probes of Flutter SDK recovery fields against the Kotlin engine. Each test states the
 * behaviour the Flutter SDK's field gives; a failure here means the hazard the field fixes exists in this engine.
 * Hazards that reproduce and are not ported yet stay @Ignore'd with the reason (the failing run is the evidence).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixB2HazardProbeMockE2ETest : MockE2ETest() {

    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long = 30_000): MockWebSocket? {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < maxMs) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun isResume() = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"

    private fun TestScope.completeFullRejoin(ws: MockWebSocket) {
        wsFactory.listener.onOpen(ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.JOIN)
        testScheduler.runCurrent()
        connectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()
    }

    /**
     * keepParticipantsOnRejoin: a full rejoin keeps the RemoteParticipant objects of identities still in the room (no
     * ParticipantDisconnected / ParticipantConnected for them). Reproduced 2026-10-12 (p3/b2-probe-before.log): the
     * engine reports every remote participant left and joined again. Not ported (Phase 5: object reuse across
     * rejoins touches track subscription bookkeeping; with resumeOnPeerDisconnected/waitForNetwork most short drops
     * now resume instead).
     */
    @Ignore("hazard reproduced, not ported yet (keepParticipantsOnRejoin, Phase 5)")
    @Test
    fun aFullRejoinKeepsTheParticipantsStillInTheRoom() = runTest {
        connect()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        testScheduler.runCurrent()
        val identity = TestData.REMOTE_PARTICIPANT.identity
        assertTrue(room.remoteParticipants.keys.any { it.value == identity })
        val events = EventCollector(room.events, coroutineRule.scope)
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val full = dialAfter(before, 5_000) ?: throw AssertionError("no full rejoin")
        wsFactory.listener.onOpen(full, createOpenResponse(wsFactory.request))
        val join = TestData.JOIN.toBuilder().apply {
            join = join.toBuilder().addOtherParticipants(TestData.REMOTE_PARTICIPANT).build()
        }.build()
        simulateMessageFromServer(join)
        testScheduler.runCurrent()
        connectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        val seen = events.stopCollecting()
        val left = seen.filterIsInstance<RoomEvent.ParticipantDisconnected>().count { it.participant.identity?.value == identity }
        val joined = seen.filterIsInstance<RoomEvent.ParticipantConnected>().count { it.participant.identity?.value == identity }
        assertEquals("ParticipantDisconnected for a participant still in the room", 0, left)
        assertEquals("ParticipantConnected for a participant that never left", 0, joined)
    }

    /**
     * upstreamReconnectFixes (part: a full-reconnect request made during a reconnect is honoured by THAT reconnect,
     * not left for the next one). The server's Leave{RECONNECT} during a resume wait sets fullReconnectOnNext; the
     * reconnect then rejoins (leaveAbortsResumeWait). A later, unrelated drop must start with a resume.
     */
    @Test
    fun aFullRequestHonouredByARejoinDoesNotForceTheNextReconnectFull() = runTest {
        connect()
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before, 5_000) ?: throw AssertionError("no resume dial")
        assertTrue(isResume())
        wsFactory.listener.onOpen(resume, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        testScheduler.runCurrent()
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setLeave(
                GravixcloudRtc.LeaveRequest.newBuilder().setReason(GravixcloudModels.DisconnectReason.MIGRATION)
                    .setAction(GravixcloudRtc.LeaveRequest.Action.RECONNECT),
            ).build(),
        )
        val full = dialAfter(resume) ?: throw AssertionError("no full rejoin after the Leave")
        assertTrue("the Leave asked for a full rejoin", !isResume())
        completeFullRejoin(full)
        assertEquals(Room.State.CONNECTED, room.state)

        // a later, unrelated drop
        disconnectPeerConnection()
        dialAfter(full, 5_000) ?: throw AssertionError("no reconnect after the second drop")
        assertTrue("the second reconnect starts with a resume (no stale full request)", isResume())
    }

    @Test
    fun rejoinClearsFullRequestOffKeepsTheStaleRequestAsUpstream() = runTest {
        room.gravixRecovery = com.gravixcloud.rtc.GravixRecoveryOptions(rejoinClearsFullRequest = false)
        connect()
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before, 5_000) ?: throw AssertionError("no resume dial")
        wsFactory.listener.onOpen(resume, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        testScheduler.runCurrent()
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setLeave(
                GravixcloudRtc.LeaveRequest.newBuilder().setReason(GravixcloudModels.DisconnectReason.MIGRATION)
                    .setAction(GravixcloudRtc.LeaveRequest.Action.RECONNECT),
            ).build(),
        )
        val full = dialAfter(resume) ?: throw AssertionError("no full rejoin after the Leave")
        completeFullRejoin(full)
        disconnectPeerConnection()
        dialAfter(full, 5_000) ?: throw AssertionError("no reconnect after the second drop")
        assertTrue("upstream: the stale request makes the next reconnect full", !isResume())
    }
}
