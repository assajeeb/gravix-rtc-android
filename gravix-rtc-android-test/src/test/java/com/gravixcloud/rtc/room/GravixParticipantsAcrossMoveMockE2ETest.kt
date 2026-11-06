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
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.regions.GravixRegionLog
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionMedia
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.room.participant.Participant
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockAudioStreamTrack
import com.gravixcloud.rtc.test.mock.MockMediaStream
import com.gravixcloud.rtc.test.mock.MockRtpReceiver
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.createMediaStreamId
import com.gravixcloud.rtc.test.util.toPBByteString
import com.gravixcloud.rtc.util.GravixLogSink
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * GRAVIX (2026-11-05): participants across a full rejoin (port of the Flutter SDK's
 * test/room/region_move_keeps_participants_test.dart). The session moves from blr1 to sgp1, where the room may be new:
 * the JoinResponse may not name the others, and the server's guaranteed repair is its 30 s relay sweep.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixParticipantsAcrossMoveMockE2ETest : MockE2ETest() {

    private val blr = TestData.EXAMPLE_URL
    private val sgp = "wss://sgp1.gravixcloud.test"
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val host = TestData.REMOTE_PARTICIPANT.toBuilder().clearTracks().addTracks(TestData.REMOTE_AUDIO_TRACK).build()
    private val hostId = Participant.Identity(host.identity)

    @Before
    fun setUpRegions() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr, media = listOf("audio"))))
        GravixRegionLog.sink = GravixLogSink { _, line -> lines.add(line) }
    }

    @After
    fun tearDownRegions() {
        GravixRegionLog.sink = null
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
    }

    private fun join(vararg others: GravixcloudModels.ParticipantInfo) = TestData.JOIN.toBuilder().apply {
        join = join.toBuilder()
            .setServerInfo(join.serverInfo.toBuilder().setRegion(""))
            .clearOtherParticipants()
            .addAllOtherParticipants(others.toList())
            .build()
    }.build()

    private fun update(vararg peers: GravixcloudModels.ParticipantInfo) = simulateMessageFromServer(
        GravixcloudRtc.SignalResponse.newBuilder()
            .setUpdate(GravixcloudRtc.ParticipantUpdate.newBuilder().addAllParticipants(peers.toList()))
            .build(),
    )

    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            Thread.sleep(2)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
    }

    private fun TestScope.nextDial(previous: MockWebSocket): MockWebSocket {
        repeat(400) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(2)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        fail("the engine did not dial again; lines=$lines")
        throw IllegalStateException()
    }

    private suspend fun TestScope.joined(recovery: GravixRecoveryOptions = GravixRecoveryOptions()) {
        room.gravixRecovery = recovery
        connect(join(host))
        assertNotNull(room.remoteParticipants[hostId])
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("sgp1", sgp, 0), RegionInfo("blr1", blr, 1))))
            }
        backgroundScope.launch {
            room.events.collect {
                when (it) {
                    is RoomEvent.ParticipantConnected -> log.add("+${it.participant.identity?.value}")
                    is RoomEvent.ParticipantDisconnected -> log.add("-${it.participant.identity?.value}")
                    else -> {}
                }
            }
        }
        testScheduler.runCurrent()
    }

    private fun refuse() = simulateMessageFromServer(
        GravixcloudRtc.SignalResponse.newBuilder().setRequestResponse(
            GravixcloudRtc.RequestResponse.newBuilder()
                .setRequestId(7)
                .setReason(GravixcloudRtc.RequestResponse.Reason.NOT_ALLOWED)
                .setMessage(GravixRegionMedia.MEDIA_NOT_ALLOWED),
        ).build(),
    )

    /** Moves to sgp1, whose JoinResponse names [others]. */
    private fun TestScope.moved(vararg others: GravixcloudModels.ParticipantInfo): MockWebSocket {
        val after = nextDial(wsFactory.ws.also { refuse() })
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join(*others))
        testScheduler.runCurrent()
        return after
    }

    private fun TestScope.upAfterMove() {
        connectPeerConnection()
        settle()
    }

    /** The host's audio arrives on the new subscriber connection. */
    private fun mediaArrives() {
        val stream = MockMediaStream(id = createMediaStreamId(host.sid, TestData.REMOTE_AUDIO_TRACK.sid))
        room.onAddTrack(MockRtpReceiver.create(), MockAudioStreamTrack(), arrayOf(stream))
    }

    private val hostHere get() = room.remoteParticipants[hostId]

    @Test
    fun aMoveKeepsTheParticipantInTheJoinResponseWithNoEvents() = runTest {
        joined()
        val same = hostHere
        moved(host)
        upAfterMove()
        testScheduler.advanceTimeBy(40_000)
        settle()
        assertEquals("nobody left or came; lines=$lines", emptyList<String>(), log.toList())
        assertSame("the same object", same, hostHere)
    }

    /** Gulf test 2026-11-04, step c: an update right behind the JoinResponse. */
    @Test
    fun anUpdateRightBehindTheJoinResponseCounts() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 100))
        moved()
        update(host)
        upAfterMove()
        testScheduler.advanceTimeBy(1000)
        settle()
        assertEquals("lines=$lines", emptyList<String>(), log.toList())
        assertNotNull(hostHere)
    }

    /** Field: "left" with its audio and video playing. */
    @Test
    fun mediaArrivingProvesTheParticipantIsThere() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 3000))
        moved()
        upAfterMove()
        mediaArrives()
        settle()
        testScheduler.advanceTimeBy(3000)
        settle()
        assertEquals("lines=$lines", emptyList<String>(), log.toList())
        val pub = hostHere?.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)
        assertNotNull("subscribed on the same publication", pub?.track)
        assertTrue(lines.toString(), "[GravixRegion] participantAbsent kept reason=media-flowing" in lines)
    }

    @Test
    fun aReallyGoneParticipantIsReportedAfterTheMoveGrace() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentGraceMs = 50, rejoinAbsentMoveGraceMs = 35_000))
        moved()
        upAfterMove()
        testScheduler.advanceTimeBy(33_000)
        settle(2)
        assertEquals("the relay sweep may still bring it", emptyList<String>(), log.toList())
        testScheduler.advanceTimeBy(2000)
        settle(2)
        assertEquals(listOf("-${host.identity}"), log.toList())
        assertEquals(null, hostHere)
    }

    @Test
    fun theSameNodeKeepsTheShortGrace() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentGraceMs = 500, rejoinAbsentMoveGraceMs = 30_000))
        val before = wsFactory.ws
        component.rtcEngine().gravixRestartConnection(blr)
        nextDial(before)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join())
        testScheduler.runCurrent()
        upAfterMove()
        testScheduler.advanceTimeBy(1000)
        settle(2)
        assertEquals("lines=$lines", listOf("-${host.identity}"), log.toList())
        room.disconnect()
        settle(2)
    }

    @Test
    fun aParticipantDroppedByTheGraceAndBackIsSubscribedAgain() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 50))
        val ws = moved()
        upAfterMove()
        testScheduler.advanceTimeBy(100)
        settle(2)
        assertEquals(listOf("-${host.identity}"), log.toList())

        val sentBefore = ws.sentRequests.size
        update(host)
        settle()
        assertEquals(listOf("-${host.identity}", "+${host.identity}"), log.toList())
        val subs = ws.sentRequests.drop(sentBefore)
            .map { GravixcloudRtc.SignalRequest.parseFrom(it.toPBByteString()) }
            .filter { it.hasSubscription() }
            .map { it.subscription }
        assertEquals("the server holds its subscription; only a new pull sends the track: $subs", listOf(false, true), subs.map { it.subscribe })
        assertEquals(listOf(TestData.REMOTE_AUDIO_TRACK.sid), subs.last().participantTracksList.single().trackSidsList)
    }

    @Test
    fun someoneNewWithThatIdentityIsNotPulledAgain() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 50))
        val ws = moved()
        upAfterMove()
        testScheduler.advanceTimeBy(100)
        settle(2)
        val sentBefore = ws.sentRequests.size
        update(host.toBuilder().setSid("PA_someoneelse").build())
        settle()
        assertTrue(log.toString(), "+${host.identity}" in log)
        val subs = ws.sentRequests.drop(sentBefore)
            .map { GravixcloudRtc.SignalRequest.parseFrom(it.toPBByteString()) }
            .filter { it.hasSubscription() }
        assertTrue(subs.toString(), subs.isEmpty())
    }

    @Test
    fun theRollbackReportsEveryoneAsLeft() = runTest {
        joined(GravixRecoveryOptions(keepParticipantsOnRejoin = false))
        moved(host)
        upAfterMove()
        assertTrue(log.toString(), "-${host.identity}" in log)
    }

    @Test
    fun aParticipantWhoLeftDuringTheGraceIsReportedByTheServersWord() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 30_000))
        moved()
        upAfterMove()
        update(TestData.PARTICIPANT_DISCONNECT.update.participantsList.single())
        settle(2)
        assertEquals(listOf("-${host.identity}"), log.toList())
        testScheduler.advanceTimeBy(31_000)
        settle(2)
        assertEquals("once", 1, log.size)
        assertFalse(hostHere != null)
    }

    private fun MockWebSocket.requests() = sentRequests.map { GravixcloudRtc.SignalRequest.parseFrom(it.toPBByteString()) }

    /** Review 2026-11-05: what the app set on a kept publication goes to the new session. */
    @Test
    fun aTrackDisabledByTheAppStaysDisabledAfterAMove() = runTest {
        joined()
        mediaArrives()
        settle()
        val pub = hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!
        pub.setEnabled(false)
        settle()
        val ws = moved(host)
        upAfterMove()
        mediaArrives()
        settle()
        val settings = ws.requests().filter { it.hasTrackSetting() }.map { it.trackSetting }
        assertTrue("no settings on the new session: $settings", settings.any { it.disabled && it.trackSidsList.contains(pub.sid) })
    }

    @Test
    fun aTrackUnsubscribedByTheAppStaysUnsubscribedAfterAMove() = runTest {
        joined()
        val pub = hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!
        pub.setSubscribed(false)
        settle()
        val ws = moved(host)
        upAfterMove()
        val subs = ws.requests().filter { it.hasSubscription() }.map { it.subscription }
        assertTrue("the new session subscribes by default: $subs", subs.any { !it.subscribe && it.participantTracksList.any { t -> t.trackSidsList.contains(pub.sid) } })
    }

    @Test
    fun withAutoSubscribeOffAChosenTrackComesBackAfterAMove() = runTest {
        room.gravixRecovery = GravixRecoveryOptions()
        val job = launch { room.connect(TestData.EXAMPLE_URL, "token", com.gravixcloud.rtc.ConnectOptions(autoSubscribe = false)) }
        prepareSignal(join(host))
        job.join()
        connectPeerConnection()
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("sgp1", sgp, 0), RegionInfo("blr1", blr, 1))))
            }
        val pub = hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!
        pub.setSubscribed(true)
        settle()
        val ws = moved(host)
        upAfterMove()
        val subs = ws.requests().filter { it.hasSubscription() }.map { it.subscription }
        assertTrue("nothing asks for it on the new session: $subs", subs.any { it.subscribe && it.participantTracksList.any { t -> t.trackSidsList.contains(pub.sid) } })
    }

    /** The update bringing a kept participant comes after the post-reconnect step: its subscription goes out then. */
    @Test
    fun aSubscriptionIsRestoredForAParticipantWhoArrivesAfterTheJoinResponse() = runTest {
        joined()
        val pub = hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!
        pub.setSubscribed(false)
        settle()
        val ws = moved()
        upAfterMove()
        val sent = ws.sentRequests.size
        update(host)
        settle()
        val subs = ws.sentRequests.drop(sent).map { GravixcloudRtc.SignalRequest.parseFrom(it.toPBByteString()) }.filter { it.hasSubscription() }.map { it.subscription }
        assertTrue("the server didn't know them before now: $subs", subs.any { !it.subscribe })
    }

    /** Dropped by the grace and back: a new object, which must not forget what the app chose. */
    @Test
    fun aParticipantBackAfterTheGraceKeepsWhatTheAppChose() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 50))
        val pub = hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!
        pub.setSubscribed(false)
        settle()
        val ws = moved()
        upAfterMove()
        testScheduler.advanceTimeBy(100)
        settle(2)
        assertEquals(listOf("-${host.identity}"), log.toList())
        val sent = ws.sentRequests.size
        update(host)
        settle()
        val back = hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!
        assertFalse("subscribed again by itself", back.isDesired)
        val subs = ws.sentRequests.drop(sent).map { GravixcloudRtc.SignalRequest.parseFrom(it.toPBByteString()) }.filter { it.hasSubscription() }.map { it.subscription }
        assertTrue(subs.toString(), subs.none { it.subscribe })
    }

    @Test
    fun aParticipantBackAfterTheGraceStaysDisabled() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 50))
        mediaArrives()
        settle()
        hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!.setEnabled(false)
        settle()
        val ws = moved()
        upAfterMove()
        testScheduler.advanceTimeBy(100)
        settle(2)
        val sent = ws.sentRequests.size
        update(host)
        settle()
        mediaArrives()
        settle()
        val settings = ws.sentRequests.drop(sent).map { GravixcloudRtc.SignalRequest.parseFrom(it.toPBByteString()) }.filter { it.hasTrackSetting() }.map { it.trackSetting }
        assertTrue("unmuted by coming back: $settings", settings.any { it.disabled })
    }

    /** D4: a second full rejoin soon after a move: the room may still be new here, so the long grace stands. */
    @Test
    fun aRejoinSoonAfterAMoveKeepsTheLongGrace() = runTest {
        joined(GravixRecoveryOptions(rejoinAbsentGraceMs = 50, rejoinAbsentMoveGraceMs = 30_000))
        moved(host)
        upAfterMove()
        val before = wsFactory.ws
        component.rtcEngine().gravixRestartConnection(sgp)
        nextDial(before)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join())
        testScheduler.runCurrent()
        upAfterMove()
        testScheduler.advanceTimeBy(1000)
        settle(2)
        assertEquals("lines=$lines", emptyList<String>(), log.toList())
        room.disconnect()
        settle(2)
    }

    /** D5: autoSubscribe off, a track the app chose, dropped by the grace, back: the choice stands. */
    @Test
    fun withAutoSubscribeOffAChosenTrackSurvivesTheGrace() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(rejoinAbsentMoveGraceMs = 50)
        val job = launch { room.connect(TestData.EXAMPLE_URL, "token", com.gravixcloud.rtc.ConnectOptions(autoSubscribe = false)) }
        prepareSignal(join(host))
        job.join()
        connectPeerConnection()
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("sgp1", sgp, 0), RegionInfo("blr1", blr, 1))))
            }
        hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!.setSubscribed(true)
        settle()
        moved()
        upAfterMove()
        testScheduler.advanceTimeBy(100)
        settle(2)
        update(host)
        settle()
        assertTrue("the choice is lost", hostHere!!.getTrackPublication(TestData.REMOTE_AUDIO_TRACK.sid)!!.isDesired)
    }
}
