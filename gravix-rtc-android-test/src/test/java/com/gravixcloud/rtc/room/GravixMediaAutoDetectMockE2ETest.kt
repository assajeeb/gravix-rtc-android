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

import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.regions.GravixRegionLog
import com.gravixcloud.rtc.regions.GravixRegionMeasurement
import com.gravixcloud.rtc.regions.GravixRegionMeasurementResult
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionMedia
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.regions.GravixRoomMedia
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.flow
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * GRAVIX (2026-11-04, auto-detect): nobody has to declare a room's media. A join goes to the fastest region, and the
 * session moves once when the room turns out to carry video (port of the Flutter SDK's
 * test/room/media_auto_detect_test.dart). The session is on blr1 (TestData.EXAMPLE_URL, audio-only here); its region
 * list is [doh1, sgp1, blr1]; the measurement says sgp1 61 ms, blr1 120 ms, doh1 260 ms.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixMediaAutoDetectMockE2ETest : MockE2ETest() {

    private val blr = TestData.EXAMPLE_URL
    private val sgp = "wss://sgp1.gravixcloud.test"
    private val doh = "wss://doh1.gravixcloud.test"
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUpRegions() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
        GravixRegionMedia.autoDetect = true
        val now = GravixRegionMeasurements.clock.wallMs()
        fun m(region: String, url: String, rtt: Long) = GravixRegionMeasurement(GravixRegionUrl(region, url), rtt, listOf(rtt + 200, rtt))
        GravixRegionMeasurements.setCurrent(
            GravixRegionMeasurementResult(now, listOf(m("sgp1", sgp, 61), m("blr1", blr, 120), m("doh1", doh, 260)), best = m("sgp1", sgp, 61)),
        )
        directory(blrMedia = listOf("audio"))
        GravixRegionLog.sink = GravixLogSink { _, line -> lines.add(line) }
    }

    @After
    fun tearDownRegions() {
        GravixRegionLog.sink = null
        GravixRegionMedia.autoDetect = true
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
    }

    private fun directory(blrMedia: List<String>?) = GravixRegionMedia.noteDirectory(
        listOf(GravixRegionUrl("doh1", doh), GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr, media = blrMedia)),
    )

    private fun seedRegionList() {
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("doh1", doh, 0), RegionInfo("sgp1", sgp, 1), RegionInfo("blr1", blr, 2))))
            }
    }

    private fun peer(type: GravixcloudModels.TrackType, source: GravixcloudModels.TrackSource) = TestData.REMOTE_PARTICIPANT.toBuilder()
        .clearTracks()
        .addTracks(GravixcloudModels.TrackInfo.newBuilder().setSid("TR_x").setType(type).setSource(source))
        .build()

    private val videoPeer get() = peer(GravixcloudModels.TrackType.VIDEO, GravixcloudModels.TrackSource.CAMERA)
    private val audioPeer get() = peer(GravixcloudModels.TrackType.AUDIO, GravixcloudModels.TrackSource.MICROPHONE)

    private fun update(vararg peers: GravixcloudModels.ParticipantInfo) = simulateMessageFromServer(
        GravixcloudRtc.SignalResponse.newBuilder()
            .setUpdate(GravixcloudRtc.ParticipantUpdate.newBuilder().addAllParticipants(peers.toList()))
            .build(),
    )

    private fun joinWith(vararg others: GravixcloudModels.ParticipantInfo) = TestData.JOIN.toBuilder().apply {
        join = join.toBuilder()
            .setServerInfo(join.serverInfo.toBuilder().setRegion(""))
            .addAllOtherParticipants(others.toList())
            .build()
    }.build()

    private fun TestScope.settle() {
        repeat(20) {
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

    private fun TestScope.succeedJoin() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(joinWith())
        testScheduler.runCurrent()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()
    }

    /**
     * A media move has no first retry delay, so it can close the peer connections while the server's offer is handled
     * here: the helper then finds no subscriber, and the next dial is what the test goes on to check.
     */
    private fun connectUnlessTheMoveBegan() {
        try {
            connectPeerConnection()
        } catch (_: NullPointerException) {
            // the move began
        }
    }

    private suspend fun connectWithMedia(media: GravixRoomMedia) {
        val job = coroutineRule.scope.launch {
            room.connect(url = TestData.EXAMPLE_URL, token = "token", options = ConnectOptions(media = media))
        }
        prepareSignal(joinWith())
        job.join()
        connectPeerConnection()
    }

    private fun failovers() = synchronized(lines) { lines.filter { it.startsWith("[GravixRegion] mediaFailover") } }
    private fun moves() = failovers().filter { it.contains("reason=moved") }

    @Test
    fun nothingDeclaredIsAudioAndAudioTracksNeverMoveAnyone() = runTest {
        connect(joinWith())
        seedRegionList()
        assertEquals(GravixRoomMedia.AUDIO, room.gravixJoinMedia)
        assertTrue(lines.toString(), "[GravixRegion] media media=audio cause=auto" in lines)
        val before = wsFactory.ws
        update(audioPeer)
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(failovers().toString(), failovers().isEmpty())
        assertEquals(GravixRoomMedia.AUDIO, room.gravixJoinMedia)
        assertFalse(GravixRegionMedia.roomHadVideo("roomname"))
    }

    @Test
    fun videoAlreadyInTheRoomAtJoinMovesOnceConnected() = runTest {
        connectSignal(joinWith(videoPeer))
        seedRegionList()
        val before = wsFactory.ws
        settle()
        assertSame("not before the session is up", before, wsFactory.ws)
        assertTrue(lines.toString(), lines.none { it.contains("stay reason=") })

        connectUnlessTheMoveBegan()
        nextDial(before)
        assertEquals("sgp1.gravixcloud.test", wsFactory.request.url.host)
        assertFalse("a full rejoin, not a resume", wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1")
        succeedJoin()
        settle()

        assertEquals(sgp, room.connectedUrl)
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
        assertTrue("a later join goes straight to a region with video", GravixRegionMedia.roomHadVideo("roomname"))
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=room-has-video (was audio)" in lines)
        assertEquals(moves().toString(), listOf("[GravixRegion] mediaFailover media=video from=blr1 -> sgp1 reason=moved cause=room-has-video"), moves())
    }

    @Test
    fun videoStartingMidCallMovesOnceHoweverOftenTheUpdateIsResent() = runTest {
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        val moved = nextDial(before)
        succeedJoin()
        settle()
        assertEquals(sgp, room.connectedUrl)
        assertEquals("later failovers order for video", GravixRoomMedia.VIDEO, component.rtcEngine().regionUrlProvider?.gravixMedia)

        repeat(50) { update(videoPeer) }
        settle()
        assertSame("no other dial", moved, wsFactory.ws)
        assertEquals(moves().toString(), 1, moves().size)
        assertEquals(1, lines.count { it.startsWith("[GravixRegion] media media=video cause=room-has-video") })
    }

    @Test
    fun aScreenShareIsVideo() = runTest {
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(peer(GravixcloudModels.TrackType.VIDEO, GravixcloudModels.TrackSource.SCREEN_SHARE))
        nextDial(before)
        assertEquals("sgp1.gravixcloud.test", wsFactory.request.url.host)
        succeedJoin()
        settle()
    }

    @Test
    fun theFastestMeasuredRegionBeatsTheFirstListed() = runTest {
        // doh1 is listed first and carries video here; sgp1 is the fastest
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        nextDial(before)
        assertEquals("sgp1.gravixcloud.test", wsFactory.request.url.host)
        assertTrue(moves().toString(), moves().single().contains("-> sgp1"))
        succeedJoin()
        settle()
    }

    @Test
    fun aMissingMeasurementFallsBackToTheFailoverOrderAndStillSkipsAudioOnly() = runTest {
        GravixRegionMeasurements.setCurrent(null)
        directory(blrMedia = listOf("audio"))
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        nextDial(before)
        assertFalse("blr1.gravixcloud.test" == wsFactory.request.url.host)
        succeedJoin()
        settle()
    }

    @Test
    fun videoBeforeTheJoinResponseNamesTheRoomIsStillRemembered() = runTest {
        val job = coroutineRule.scope.launch { room.connect(url = TestData.EXAMPLE_URL, token = "token") }
        room::state.flow.first { it == Room.State.CONNECTING }
        testScheduler.runCurrent()
        room.onUpdateParticipants(listOf(videoPeer))
        assertFalse("no name yet", GravixRegionMedia.roomHadVideo("roomname"))
        assertEquals(0, GravixRegionMedia.roomsWithVideoCount())

        prepareSignal(joinWith())
        job.join()
        assertTrue(GravixRegionMedia.roomHadVideo("roomname"))
        seedRegionList()
        val before = wsFactory.ws
        connectUnlessTheMoveBegan()
        nextDial(before)
        assertEquals(moves().toString(), 1, moves().size)
        succeedJoin()
        settle()
    }

    @Test
    fun anUpdateInTheSameBurstAsTheJoinResponseIsRememberedAndMoves() = runTest {
        // Android's signal client holds updates until the join response is handled, so this is the real path
        connectSignal(joinWith())
        update(videoPeer)
        testScheduler.runCurrent()
        assertTrue(GravixRegionMedia.roomHadVideo("roomname"))
        seedRegionList()
        val before = wsFactory.ws
        connectUnlessTheMoveBegan()
        nextDial(before)
        succeedJoin()
        settle()
    }

    @Test
    fun aServerRegionNameOutsideTheDirectoryStillMovesByTheUrl() = runTest {
        connect(TestData.JOIN)
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        nextDial(before)
        assertEquals("sgp1.gravixcloud.test", wsFactory.request.url.host)
        succeedJoin()
        settle()
    }

    @Test
    fun aRegionCarryingVideoNeverMovesButTheSessionBecomesVideo() = runTest {
        directory(blrMedia = null)
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        settle()
        assertSame(before, wsFactory.ws)
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
        assertEquals(GravixRoomMedia.VIDEO, component.rtcEngine().regionUrlProvider?.gravixMedia)
        assertTrue(GravixRegionMedia.roomHadVideo("roomname"))
        assertEquals(
            failovers().toString(),
            listOf("[GravixRegion] mediaFailover media=video stay reason=region-carries-video cause=room-has-video"),
            failovers(),
        )
    }

    @Test
    fun theFailoverOptionOffStays() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(mediaRefusalFailover = false)
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(failovers().toString(), failovers().single().endsWith("stay reason=option-off cause=room-has-video"))
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
    }

    @Test
    fun theOffSwitchKeepsTheOldDefaultAndDetectsNothing() = runTest {
        GravixRegionMedia.autoDetect = false
        connect(joinWith())
        seedRegionList()
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=default" in lines)
        val before = wsFactory.ws
        update(videoPeer)
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(failovers().toString(), failovers().isEmpty())
        assertFalse(GravixRegionMedia.roomHadVideo("roomname"))
    }

    @Test
    fun declaredAudioOnAnAudioOnlyRegionStillMovesOnceForRemoteVideo() = runTest {
        connectWithMedia(GravixRoomMedia.AUDIO)
        seedRegionList()
        assertEquals(GravixRoomMedia.AUDIO, room.gravixJoinMedia)
        val before = wsFactory.ws
        update(videoPeer)
        val moved = nextDial(before)
        assertEquals("sgp1.gravixcloud.test", wsFactory.request.url.host)
        succeedJoin()
        settle()
        update(videoPeer)
        settle()
        assertSame("no other dial", moved, wsFactory.ws)
        assertEquals(moves().toString(), 1, moves().size)
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
    }

    @Test
    fun declaredVideoOnARegionCarryingVideoNeverMoves() = runTest {
        directory(blrMedia = null)
        connectWithMedia(GravixRoomMedia.VIDEO)
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(moves().toString(), moves().isEmpty())
    }

    @Test
    fun theLocalParticipantsOwnVideoAndALeavingPeerDoNotCount() = runTest {
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(
            TestData.LOCAL_PARTICIPANT.toBuilder().clearTracks().addTracks(TestData.REMOTE_VIDEO_TRACK).build(),
            videoPeer.toBuilder().setState(GravixcloudModels.ParticipantInfo.State.DISCONNECTED).build(),
        )
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(failovers().toString(), failovers().isEmpty())
    }

    @Test
    fun leavingBeforeConnectedNeverDials() = runTest {
        connectSignal(joinWith(videoPeer))
        seedRegionList()
        val before = wsFactory.ws
        room.disconnect()
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(moves().toString(), moves().isEmpty())
    }

    @Test
    fun aSecondConnectOfARememberedRoomStartsAsVideo() = runTest {
        directory(blrMedia = null)
        connect(joinWith(videoPeer))
        settle()
        room.disconnect()
        assertTrue(GravixRegionMedia.roomHadVideo("roomname"))

        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val claims = enc.encodeToString("""{"video":{"room":"roomname","roomJoin":true}}""".toByteArray())
        val job = coroutineRule.scope.launch { room.connect(url = TestData.EXAMPLE_URL, token = "e30.$claims.c2ln") }
        prepareSignal(joinWith())
        job.join()
        connectPeerConnection()
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=room-had-video" in lines)
    }

    // ------------------------------------------------------------------ R1: the move's target

    /** Gulf test: `select media=video -> blr1 reason=kept`, then the move -> dac1, 2 ms ahead. Here doh1 is the held choice. */
    @Test
    fun theMoveGoesToTheChoiceVideoJoinsUseNotTheRawFastest() = runTest {
        heldVideoChoice("doh1", doh, 260)
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        nextDial(before)
        assertEquals("doh1.gravixcloud.test", wsFactory.request.url.host)
        val target = lines.firstOrNull { it.startsWith("[GravixRegion] mediaTarget") }.orEmpty()
        assertTrue(target, target.contains("media=video -> doh1") && target.contains("why=kept") && target.contains("fastest=sgp1"))
        room.disconnect()
        settle()
    }

    @Test
    fun aHeldChoiceThatRefusedVideoLeavesItToTheFailoverOrder() = runTest {
        heldVideoChoice("doh1", doh, 260)
        GravixRegionMedia.noteRefused("doh1", GravixRoomMedia.VIDEO)
        connect(joinWith())
        seedRegionList()
        val before = wsFactory.ws
        update(videoPeer)
        nextDial(before)
        assertEquals("sgp1.gravixcloud.test", wsFactory.request.url.host)
        val target = lines.firstOrNull { it.startsWith("[GravixRegion] mediaTarget") }.orEmpty()
        assertTrue(target, target.contains("why=failover-order"))
        room.disconnect()
        settle()
    }

    private fun heldVideoChoice(region: String, url: String, rtt: Long) {
        val m = GravixRegionMeasurements.current()!!
        val chosen = GravixRegionMeasurement(GravixRegionUrl(region, url), rtt, listOf(rtt + 200, rtt))
        GravixRegionMeasurements.setCurrent(
            m.copy(
                bestByMedia = mapOf(GravixRoomMedia.VIDEO to chosen),
                mediaSelectReason = mapOf(GravixRoomMedia.VIDEO to com.gravixcloud.rtc.regions.GravixRegionSelectReason.KEPT),
            ),
        )
    }

    // ------------------------------------------------------------------ 0.8.3: it keeps trying, with its first target

    @Test
    fun theMoveIsAskedForAgainWithItsFirstTargetAndGoesThrough() = runTest {
        connect(joinWith())
        seedRegionList()
        var asked = 0
        component.rtcEngine().gravixRestartBusyForTest = { ++asked <= 2 }
        val before = wsFactory.ws
        update(videoPeer)
        val after = nextDial(before)
        assertEquals("the provider's list moved on per ask; the first target stands", "sgp1.gravixcloud.test", wsFactory.request.url.host)
        assertEquals(3, asked)
        assertEquals(moves().toString(), 1, moves().size)
        assertTrue(failovers().toString(), failovers().count { it.contains("stay reason=reconnect-busy") } == 2)
        assertSame(after, wsFactory.ws)
        room.disconnect()
        settle()
    }

    @Test
    fun itGivesUpAfterTheWindowAndSaysWhy() = runTest {
        room.gravixRoomVideoMoveWindowMs = 3000
        connect(joinWith())
        seedRegionList()
        var asked = 0
        component.rtcEngine().gravixRestartBusyForTest = {
            asked++
            true
        }
        val before = wsFactory.ws
        update(videoPeer)
        settle()
        testScheduler.advanceTimeBy(5000)
        settle()
        assertSame("no move", before, wsFactory.ws)
        assertTrue(moves().toString(), moves().isEmpty())
        assertTrue(lines.toString(), "[GravixRegion] mediaFailover media=video stay reason=reconnect-busy cause=room-has-video" in lines)
        val total = asked
        testScheduler.advanceTimeBy(5000)
        settle()
        assertEquals("nothing runs after giving up", total, asked)
    }

    @Test
    fun leavingEndsTheAsking() = runTest {
        connect(joinWith())
        seedRegionList()
        var asked = 0
        component.rtcEngine().gravixRestartBusyForTest = {
            asked++
            true
        }
        update(videoPeer)
        settle()
        assertTrue(asked >= 1)
        room.disconnect()
        val total = asked
        testScheduler.advanceTimeBy(5000)
        settle()
        assertEquals(total, asked)
    }

    @Test
    fun aSessionNotUpInTenSecondsStillMoves() = runTest {
        val job = launch { room.connect(url = TestData.EXAMPLE_URL, token = "token") }
        prepareSignal(joinWith(videoPeer))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(12_000)
        testScheduler.runCurrent()
        seedRegionList()
        val before = wsFactory.ws
        connectUnlessTheMoveBegan()
        job.join()
        settle()
        nextDial(before)
        assertEquals(moves().toString(), 1, moves().size)
        room.disconnect()
        settle()
    }

    // ------------------------------------------------------------------ R3: the memory is settled at the leave

    private val roomName = "roomname"

    @Test
    fun aMutedCameraInTheRoomMovesNobodyUntilItIsUnmuted() = runTest {
        val muted = TestData.REMOTE_PARTICIPANT.toBuilder()
            .clearTracks()
            .addTracks(GravixcloudModels.TrackInfo.newBuilder().setSid("TR_x").setType(GravixcloudModels.TrackType.VIDEO).setMuted(true))
            .setVersion(2)
            .build()
        connect(joinWith(muted))
        seedRegionList()
        settle()
        val before = wsFactory.ws
        assertSame(before, wsFactory.ws)
        assertTrue(moves().toString(), moves().isEmpty())
        update(videoPeer)
        nextDial(before)
        assertEquals(1, moves().size)
        room.disconnect()
        settle()
    }

    @Test
    fun videoLeftTheRoomBeforeTheLeaveForgottenTheNextJoinIsAudio() = runTest {
        directory(blrMedia = null)
        connect(joinWith(videoPeer))
        settle()
        assertTrue(GravixRegionMedia.roomHadVideo(roomName))
        update(audioPeer)
        settle()
        val before = wsFactory.ws
        assertSame("the last video gone mid-call: no reconnect", before, wsFactory.ws)
        room.disconnect()
        assertFalse(lines.toString(), GravixRegionMedia.roomHadVideo(roomName))
    }

    @Test
    fun theRoomStillHasRemoteVideoAtTheLeaveRemembered() = runTest {
        directory(blrMedia = null)
        connect(joinWith(videoPeer))
        settle()
        room.disconnect()
        assertTrue(GravixRegionMedia.roomHadVideo(roomName))
    }

    @Test
    fun aRoomMutedAtTheLeaveIsForgotten() = runTest {
        directory(blrMedia = null)
        connect(joinWith(videoPeer))
        settle()
        update(
            TestData.REMOTE_PARTICIPANT.toBuilder()
                .clearTracks()
                .addTracks(GravixcloudModels.TrackInfo.newBuilder().setSid("TR_x").setType(GravixcloudModels.TrackType.VIDEO).setMuted(true))
                .setVersion(3)
                .build(),
        )
        settle()
        room.disconnect()
        assertFalse(GravixRegionMedia.roomHadVideo(roomName))
    }

    @Test
    fun aMemoryOffRollbackLeavesTheNotesAlone() = runTest {
        directory(blrMedia = null)
        GravixRegionMedia.noteRoomHasVideo(roomName)
        GravixRegionMedia.autoDetect = false
        connect(joinWith())
        settle()
        room.disconnect()
        assertTrue(GravixRegionMedia.roomHadVideo(roomName))
    }

    private fun grantCamera() {
        com.gravixcloud.rtc.test.mock.camera.MockCameraProvider.register()
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.CAMERA)
    }

    @Test
    fun ownCameraOnAtTheLeaveCounts() = runTest {
        directory(blrMedia = null)
        connect(joinWith())
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        room.disconnect()
        assertTrue(GravixRegionMedia.roomHadVideo(roomName))
    }

    /** Owner (R2 and R3): camera off: stay on the current region, no reconnect, and forgotten at the leave. */
    @Test
    fun ownCameraOffBeforeTheLeaveNoReconnectAndForgotten() = runTest {
        directory(blrMedia = null)
        connect(joinWith())
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        assertTrue(room.localParticipant.setCameraEnabled(false))
        settle()
        assertSame(before, wsFactory.ws)
        room.disconnect()
        assertFalse(GravixRegionMedia.roomHadVideo(roomName))
    }

    @Test
    fun aMemoryOlderThanItsLifeDoesNotMakeTheJoinVideo() = runTest {
        var offsetMs = 0L
        val real = GravixRegionMeasurements.clock
        GravixRegionMeasurements.clock = object : com.gravixcloud.rtc.regions.GravixClock {
            override fun wallMs() = real.wallMs() + offsetMs
            override fun elapsedMs() = real.elapsedMs() + offsetMs
        }
        try {
            GravixRegionMedia.noteRoomHasVideo(roomName)
            assertTrue(GravixRegionMedia.roomHadVideo(roomName))
            offsetMs = GravixRegionMedia.ROOM_VIDEO_MEMORY_LIFE_MS + 1000
            assertFalse(GravixRegionMedia.roomHadVideo(roomName))
        } finally {
            GravixRegionMeasurements.clock = real
        }
    }

    /** Review 2026-11-05: with a measured target the region list isn't needed, so its fetch failing can't stop the move. */
    @Test
    fun aRegionListThatCannotBeFetchedDoesNotStopAMoveWithAMeasuredTarget() = runTest {
        heldVideoChoice("sgp1", sgp, 61)
        connect(joinWith())
        val failing = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("the list is down") }.build()
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", failing, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        val before = wsFactory.ws
        room.gravixRecovery = GravixRecoveryOptions(lastGoodRegionList = false)
        update(videoPeer)
        nextDial(before)
        assertEquals(lines.toString(), "sgp1.gravixcloud.test", wsFactory.request.url.host)
        room.disconnect()
        settle()
    }
}
