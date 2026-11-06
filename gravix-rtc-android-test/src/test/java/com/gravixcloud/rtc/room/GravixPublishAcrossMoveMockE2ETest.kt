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

import android.Manifest
import android.app.Application
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.regions.GravixRegionLog
import com.gravixcloud.rtc.regions.GravixRegionMeasurement
import com.gravixcloud.rtc.regions.GravixRegionMeasurementResult
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionMedia
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.camera.MockCameraProvider
import com.gravixcloud.rtc.util.GravixLogSink
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/**
 * GRAVIX (2026-11-05): a publish and a full reconnect at the same time (port of the Flutter SDK 0.8.2 / 0.8.3
 * test/room/publish_interrupted_by_move_test.dart). The session is on blr1 (audio-only here) and moves to sgp1. The
 * fake server stays silent on a cid it already has on a socket, which is what the SFU does.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixPublishAcrossMoveMockE2ETest : MockE2ETest() {

    private val blr = TestData.EXAMPLE_URL
    private val sgp = "wss://sgp1.gravixcloud.test"
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The AddTracks of each socket, duplicates included. */
    private val addTracks = Collections.synchronizedMap(HashMap<MockWebSocket, MutableList<GravixcloudRtc.AddTrackRequest>>())

    /** Sockets that answer no AddTrack at all: what an SFU does in the moment before the session goes. */
    private val mute = Collections.synchronizedSet(HashSet<MockWebSocket>())

    @Before
    fun setUpRegions() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
        val now = GravixRegionMeasurements.clock.wallMs()
        fun m(region: String, url: String, rtt: Long) = GravixRegionMeasurement(GravixRegionUrl(region, url), rtt, listOf(rtt + 200, rtt))
        GravixRegionMeasurements.setCurrent(
            GravixRegionMeasurementResult(now, listOf(m("sgp1", sgp, 61), m("blr1", blr, 120)), best = m("sgp1", sgp, 61)),
        )
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr, media = listOf("audio"))))
        GravixRegionLog.sink = GravixLogSink { _, line -> lines.add(line) }
    }

    @After
    fun tearDownRegions() {
        GravixRegionLog.sink = null
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
    }

    private fun seedRegionList() {
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("sgp1", sgp, 0), RegionInfo("blr1", blr, 1))))
            }
    }

    private fun sfuThatIgnoresADuplicateCid() = wsFactory.registerSignalRequestHandler { r ->
        if (!r.hasAddTrack()) return@registerSignalRequestHandler false
        val ws = wsFactory.ws
        val seen = addTracks.getOrPut(ws) { Collections.synchronizedList(mutableListOf()) }
        val duplicate = seen.any { it.cid == r.addTrack.cid }
        seen.add(r.addTrack)
        duplicate || ws in mute
    }

    private fun join() = TestData.JOIN.toBuilder().apply {
        join = join.toBuilder().apply { serverInfo = serverInfo.toBuilder().setRegion("").build() }.build()
    }.build()

    private fun refuse() = simulateMessageFromServer(
        GravixcloudRtc.SignalResponse.newBuilder().setRequestResponse(
            GravixcloudRtc.RequestResponse.newBuilder()
                .setRequestId(7)
                .setReason(GravixcloudRtc.RequestResponse.Reason.NOT_ALLOWED)
                .setMessage(GravixRegionMedia.MEDIA_NOT_ALLOWED),
        ).build(),
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

    /** The new session's JoinResponse: its peer connections are not up, so the reconnect's republish has not run. */
    private fun TestScope.newSessionJoined() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join())
        testScheduler.runCurrent()
        settle(4)
    }

    private fun TestScope.peerConnectionsUp() {
        connectPeerConnection()
        settle()
    }

    private fun addTracksOn(ws: MockWebSocket, type: GravixcloudModels.TrackType = GravixcloudModels.TrackType.AUDIO) =
        addTracks[ws].orEmpty().toList().filter { it.type == type }

    private val mic get() = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)

    private suspend fun TestScope.onBlrWithPublishedMic() {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertNotNull(mic)
    }

    /**
     * Android's shape of the 0.8.3 failure. The mic is on the old session, so the reconnect holds it for its republish.
     * A publish of that same track during the move lands on the new session before the republish. Before the fix the
     * republish unpublished that publication, sent the same cid again, the SFU answered nothing, and 20 s later the mic
     * was stopped: the app says on, nothing is published.
     */
    @Test
    fun aMicOnTheNewSessionBeforeItsRepublishIsKeptWithNoSecondAddTrack() = runTest {
        onBlrWithPublishedMic()
        val track = mic!!.track as com.gravixcloud.rtc.room.track.LocalAudioTrack
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        val published = async { room.localParticipant.publishAudioTrack(track) }
        settle()
        assertEquals("its AddTrack is on the new session", 1, addTracksOn(after).size)
        assertTrue(published.await())
        val onNewSession = mic
        assertNotNull(onNewSession)

        peerConnectionsUp()
        testScheduler.advanceTimeBy(21_000)
        settle()

        assertEquals("a second AddTrack gets no answer; lines=$lines", 1, addTracksOn(after).size)
        assertSame("kept; lines=$lines", onNewSession, mic)
        assertTrue("the track is still live", mic!!.track!!.enabled)
        assertTrue(lines.toString(), "[GravixRegion] mediaRepublish microphone skip reason=on-new-session" in lines)
    }

    /** The rollback is the old behaviour, duplicate included. */
    @Test
    fun theOptionOffSendsTheDuplicate() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(republishKeepsNewSessionTracks = false)
        onBlrWithPublishedMic()
        val track = mic!!.track as com.gravixcloud.rtc.room.track.LocalAudioTrack
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        launch { room.localParticipant.publishAudioTrack(track) }
        settle()
        peerConnectionsUp()
        assertEquals("the duplicate; lines=$lines", 2, addTracksOn(after).size)
    }

    /**
     * The sequence as Flutter hit it: the mic was never published when the move began, its capture took ~150 ms, and its
     * AddTrack lands on the new session before the republish. Android's republish holds a list taken when the move began,
     * so it knows nothing of this track: this one passed before the fix too. It stays as the guard.
     */
    @Test
    fun aFirstPublishLandingOnTheNewSessionIsNotSentTwice() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        val track = room.localParticipant.createAudioTrack()
        val published = async { room.localParticipant.publishAudioTrack(track) }
        settle()
        peerConnectionsUp()
        testScheduler.advanceTimeBy(21_000)
        settle()
        assertTrue(published.await())
        assertEquals(1, addTracksOn(after).size)
        assertNotNull(mic)
    }

    /** Gulf test 2026-11-04, step b: the mic's AddTrack is unanswered when the room's video moves the session. */
    @Test
    fun aMicPublishingWhenTheMoveStartsGoesOutOnTheNewSession() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        mute.add(before)
        var enabled: Boolean? = null
        val enable = launch { enabled = room.localParticipant.setMicrophoneEnabled(true) }
        settle()
        assertEquals(1, addTracksOn(before).size)
        assertTrue(!enable.isCompleted)
        val startedAt = testScheduler.currentTime

        refuse()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()

        assertTrue("still waiting ${testScheduler.currentTime - startedAt} ms; lines=$lines", enable.isCompleted)
        assertTrue("the 20 s deadline: ${testScheduler.currentTime - startedAt} ms", testScheduler.currentTime - startedAt < 10_000)
        assertEquals(true, enabled)
        assertNotNull(mic)
        assertEquals(1, addTracksOn(after).size)
        assertTrue(mic!!.track!!.enabled)
        assertTrue(lines.toString(), "[GravixRegion] mediaRepublish microphone ok" in lines)
    }

    /** With the option off the publish fails and the mic stays off, as before. */
    @Test
    fun theOptionOffLosesTheInterruptedPublish() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(publishFollowsReconnect = false)
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        mute.add(before)
        var enabled: Boolean? = null
        launch { enabled = room.localParticipant.setMicrophoneEnabled(true) }
        settle()
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        testScheduler.advanceTimeBy(21_000)
        settle()
        assertEquals(false, enabled)
        assertEquals(null, mic)
        assertEquals(0, addTracksOn(after).size)
    }

    @Test
    fun aMuteTappedWhileAnInterruptedPublishWaitsWins() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        mute.add(before)
        var enabled: Boolean? = null
        launch { enabled = room.localParticipant.setMicrophoneEnabled(true) }
        settle()
        refuse()
        val after = nextDial(before)
        // the same tap the app makes; it waits for the publish lock, but counts at once
        val tap = launch { room.localParticipant.setMicrophoneEnabled(false) }
        settle(2)
        newSessionJoined()
        peerConnectionsUp()
        settle()
        assertTrue(tap.isCompleted)
        assertEquals(false, enabled)
        assertEquals("nothing goes out; lines=$lines", 0, addTracksOn(after).size)
        assertEquals(null, mic)
        assertTrue(lines.toString(), "[GravixRegion] mediaRepublish microphone skip reason=not-wanted" in lines)
    }

    /** A published, live mic: a tap during the move finds no publication, and the republish used to bring the mic back live. */
    @Test
    fun aMuteTappedDuringTheMoveBeatsTheRepublish() = runTest {
        onBlrWithPublishedMic()
        val track = mic!!.track!!
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("nothing goes out; lines=$lines", 0, addTracksOn(after).size)
        assertEquals(null, mic)
        assertTrue("its capture is closed", !track.enabled)

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertEquals(1, addTracksOn(after).size)
        assertTrue("the next enable is live", mic!!.track!!.enabled)
    }

    /** A publish that begins while the session is being replaced: the AddTrack used to wait on a socket with no session. */
    @Test
    fun aPublishStartingBetweenSessionsGoesOutOnTheNewOne() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        var enabled: Boolean? = null
        val startedAt = testScheduler.currentTime
        launch { enabled = room.localParticipant.setMicrophoneEnabled(true) }
        settle(4)
        assertEquals("nothing is sent to a socket with no session", 0, addTracksOn(before).size)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals(true, enabled)
        assertTrue(testScheduler.currentTime - startedAt < 10_000)
        assertEquals(1, addTracksOn(after).size)
        assertNotNull(mic)
    }

    /** What landed on a session a later attempt replaced is republished; upstream kept only the first attempt's list. */
    @Test
    fun aMicThatLandedOnAFailedAttemptIsRepublishedByTheNext() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        refuse()
        val attempt1 = nextDial(before)
        newSessionJoined()
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertEquals(1, addTracksOn(attempt1).size)
        // the server gives the attempt up before its peer connections are up (the stall of 2026-11-04 did this)
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setLeave(
                GravixcloudRtc.LeaveRequest.newBuilder()
                    .setAction(GravixcloudRtc.LeaveRequest.Action.RECONNECT)
                    .setReason(GravixcloudModels.DisconnectReason.CONNECTION_TIMEOUT),
            ).build(),
        )
        val attempt2 = nextDial(attempt1)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", 1, addTracksOn(attempt2).size)
        assertNotNull(mic)
    }

    /** Item 8 of the port: a muted mic is not on the new session at all; the next enable is live. */
    @Test
    fun aMutedMicStaysOffTheNewSessionAndTheNextEnableIsLive() = runTest {
        onBlrWithPublishedMic()
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        val track = mic!!.track!!
        assertTrue(!track.enabled)
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals(0, addTracksOn(after).size)
        assertEquals(null, mic)
        assertTrue("never live there", !track.enabled)
        assertTrue(lines.toString(), "[GravixRegion] mediaRepublish microphone skip reason=not-wanted" in lines)

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertEquals(1, addTracksOn(after).size)
        assertTrue(mic!!.track!!.enabled)
    }

    /** An unmute during the move lands on the new session; the republish of the muted one must not take it down. */
    @Test
    fun anUnmuteDuringTheMoveIsNotTakenDownByTheRepublish() = runTest {
        onBlrWithPublishedMic()
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        var enabled: Boolean? = null
        launch { enabled = room.localParticipant.setMicrophoneEnabled(true) }
        settle(4)
        peerConnectionsUp()
        assertEquals(true, enabled)
        assertNotNull(mic)
        assertEquals(1, addTracksOn(after).size)
        assertTrue(mic!!.track!!.enabled)
    }

    /** Any full reconnect, not only a media move. The session stays where it is here, on a region that carries video. */
    @Test
    fun aCameraPublishingWhenAnyFullReconnectStartsGoesOutOnTheNewSession() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        MockCameraProvider.register()
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        mute.add(before)
        var enabled: Boolean? = null
        launch { enabled = room.localParticipant.setCameraEnabled(true) }
        settle()
        assertEquals(1, addTracksOn(before, GravixcloudModels.TrackType.VIDEO).size)

        disconnectPeerConnection()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()

        assertEquals("lines=$lines", true, enabled)
        assertNotNull(room.localParticipant.getTrackPublication(Track.Source.CAMERA))
        assertEquals(1, addTracksOn(after, GravixcloudModels.TrackType.VIDEO).size)
        assertTrue(lines.toString(), "[GravixRegion] mediaRepublish camera ok" in lines)
        room.disconnect()
        settle(2)
    }

    @Test
    fun anAppsOwnMicTrackInterruptedByAMoveGoesOutOnTheNewSessionToo() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        val before = wsFactory.ws
        mute.add(before)
        val mine = room.localParticipant.createAudioTrack()
        val published = async { room.localParticipant.publishAudioTrack(mine) }
        settle()
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        assertTrue(lines.toString(), published.await())
        assertSame(mine, mic!!.track)
        assertEquals(1, addTracksOn(after).size)
    }

    // ------------------------------------------------------------------ the code review of 2026-11-05

    private fun grantCamera() {
        MockCameraProvider.register()
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
    }

    private val cameraPub get() = room.localParticipant.getTrackPublication(Track.Source.CAMERA)

    private fun answerAddTrack(cid: String, info: GravixcloudModels.TrackInfo = TestData.LOCAL_AUDIO_TRACK) = simulateMessageFromServer(
        GravixcloudRtc.SignalResponse.newBuilder().setTrackPublished(
            GravixcloudRtc.TrackPublishedResponse.newBuilder().setCid(cid).setTrack(info),
        ).build(),
    )

    private val videoPeer get() = TestData.REMOTE_PARTICIPANT.toBuilder()
        .clearTracks()
        .addTracks(GravixcloudModels.TrackInfo.newBuilder().setSid("TR_x").setType(GravixcloudModels.TrackType.VIDEO))
        .build()

    /** Privacy (A): an enable that failed is not a wish the next move carries out. */
    @Test
    fun aFailedCameraEnableIsNotTurnedOnByALaterMove() = runTest {
        connect(join())
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        // no region list yet: the move-first can't move, and the enable fails
        assertEquals(false, room.localParticipant.setCameraEnabled(true))
        assertEquals(false, room.localParticipant.gravixCameraWanted)

        seedRegionList()
        val before = wsFactory.ws
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setUpdate(GravixcloudRtc.ParticipantUpdate.newBuilder().addParticipants(videoPeer)).build(),
        )
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals(null, cameraPub)
        assertEquals("lines=$lines", 0, addTracksOn(after, GravixcloudModels.TrackType.VIDEO).size)
        assertTrue(lines.toString(), "[GravixRegion] mediaRepublish camera skip reason=not-wanted" in lines)
    }

    /** Privacy (B): what was wanted in one room is not wanted in the next. */
    @Test
    fun aCameraLeftOnInOneSessionIsNotWantedInTheNext() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        connect(join())
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        room.disconnect()
        settle(2)
        assertEquals(false, room.localParticipant.gravixCameraWanted)
    }

    /** A mute in the window where the republish's AddTrack is out: no publication to mute, and it must not land live. */
    @Test
    fun aMuteWhileTheRepublishIsInFlightWins() = runTest {
        onBlrWithPublishedMic()
        val track = mic!!.track!!
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        mute.add(after)
        newSessionJoined()
        connectPeerConnection()
        settle()
        assertEquals("the republish's AddTrack is out", 1, addTracksOn(after).size)
        assertEquals(null, mic)
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))

        answerAddTrack(addTracksOn(after).single().cid)
        settle()
        assertTrue("live while the app says off; lines=$lines", mic == null || mic!!.muted)
        assertTrue("live while the app says off", !track.enabled)
    }

    /** An enable in that window: the same cid is pending, and its failure used to stop the track the publication then used. */
    @Test
    fun anEnableWhileTheRepublishIsInFlightLeavesTheTrackLive() = runTest {
        onBlrWithPublishedMic()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        mute.add(after)
        newSessionJoined()
        connectPeerConnection()
        settle()
        var enabled: Boolean? = null
        launch { enabled = room.localParticipant.setMicrophoneEnabled(true) }
        settle(4)
        answerAddTrack(addTracksOn(after).single().cid)
        settle()
        assertEquals("lines=$lines", true, enabled)
        assertNotNull(mic)
        assertTrue("a publication with no audio", mic!!.track!!.enabled)
        assertEquals(1, addTracksOn(after).size)
    }

    /** Turned off while the move-first runs: nothing goes out, and the capture never opened. */
    @Test
    fun aCameraTurnedOffDuringItsMoveIsNotPublished() = runTest {
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        val before = wsFactory.ws
        var enabled: Boolean? = null
        com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts = 0
        launch { enabled = room.localParticipant.setCameraEnabled(true) }
        val after = nextDial(before)
        assertEquals("the camera opened before the move", 0, com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts)
        launch { room.localParticipant.setCameraEnabled(false) }
        settle(2)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", 0, addTracksOn(after, GravixcloudModels.TrackType.VIDEO).size)
        assertEquals(null, cameraPub)
        assertEquals(false, enabled)
        assertEquals(false, room.localParticipant.gravixCameraWanted)
        assertEquals("never opened", 0, com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts)
    }

    /** A second interruption during the republish: what it holds goes out on the session after. */
    @Test
    fun aSecondInterruptionDuringTheRepublishLosesNothing() = runTest {
        onBlrWithPublishedMic()
        val before = wsFactory.ws
        refuse()
        val second = nextDial(before)
        mute.add(second)
        newSessionJoined()
        connectPeerConnection()
        settle()
        assertEquals("the republish's AddTrack is out", 1, addTracksOn(second).size)

        wsFactory.listener.onFailure(second, java.io.IOException("the socket went"), null)
        val third = nextDial(second)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", 1, addTracksOn(third).size)
        assertNotNull("the app says on: lines=$lines", mic)
        assertTrue(mic!!.track!!.enabled)
    }

    /** The full rollback: with autoDetect off the server is asked, as in 0.3.x, whatever the region is known to carry. */
    @Test
    fun withAutoDetectOffACameraAsksTheServerAsIn03() = runTest {
        GravixRegionMedia.autoDetect = false
        try {
            room.gravixRecovery = GravixRecoveryOptions(mediaRefusalFailover = false)
            connect(join())
            seedRegionList()
            sfuThatIgnoresADuplicateCid()
            grantCamera()
            val ws = wsFactory.ws
            launch { room.localParticipant.setCameraEnabled(true) }
            settle()
            assertEquals(1, addTracksOn(ws, GravixcloudModels.TrackType.VIDEO).size)
            assertNotNull(cameraPub)
        } finally {
            GravixRegionMedia.autoDetect = true
        }
    }

    /** `publication.muted = false` is the app turning the mic back on: the next reconnect must not close it. */
    @Test
    fun aMicUnmutedThroughItsPublicationIsRepublished() = runTest {
        onBlrWithPublishedMic()
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        mic!!.muted = false
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", 1, addTracksOn(after).size)
        assertNotNull(mic)
    }

    /** A camera-on move is the SDK's own foresight: the region refused nothing. */
    @Test
    fun aCameraOnMoveNotesNoRefusal() = runTest {
        connect(join())
        seedRegionList()
        grantCamera()
        val before = wsFactory.ws
        launch { room.localParticipant.setCameraEnabled(true) }
        nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        assertTrue(lines.toString(), lines.none { it.startsWith("[GravixRegion] mediaRefused") })
    }

    // ------------------------------------------------------------------ the Flutter 0.8.4 review, checked here

    /** Off, on, off during a move: the waiting on call must not outlive the last tap. */
    @Test
    fun offOnOffDuringAMoveEndsOff() = runTest {
        onBlrWithPublishedMic()
        val track = mic!!.track!!
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        newSessionJoined()
        var on: Boolean? = null
        launch { on = room.localParticipant.setMicrophoneEnabled(true) }
        settle(2)
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        peerConnectionsUp()
        assertEquals("the last tap was off; lines=$lines", 0, addTracksOn(after).size)
        assertEquals(null, mic)
        assertEquals(false, on)
        assertTrue("live while the last tap was off", !track.enabled)
    }

    /** The waiting on call and a wait that ended badly: the camera must not open for a room that is gone. */
    @Test
    fun anEnableWaitingOnAReconnectThatEndsInALeaveOpensNothing() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        disconnectPeerConnection()
        nextDial(before)
        val opened = com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts
        var on: Boolean? = null
        launch { on = room.localParticipant.setCameraEnabled(true) }
        settle(2)
        room.disconnect()
        settle()
        assertEquals(false, on)
        assertEquals("opened for a room that is gone", opened, com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts)
    }

    /** Off while the republish's AddTrack is out, then that session goes: the off isn't forgotten, and the camera closes. */
    @Test
    fun aCameraTurnedOffWhileItsRepublishIsOutClosesAcrossASecondInterruption() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        disconnectPeerConnection()
        val second = nextDial(before)
        mute.add(second)
        newSessionJoined()
        connectPeerConnection()
        settle()
        assertEquals("the republish's AddTrack is out", 1, addTracksOn(second, GravixcloudModels.TrackType.VIDEO).size)
        val stops = com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStops
        assertTrue(room.localParticipant.setCameraEnabled(false))

        wsFactory.listener.onFailure(second, java.io.IOException("the socket went"), null)
        val third = nextDial(second)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", 0, addTracksOn(third, GravixcloudModels.TrackType.VIDEO).size)
        assertEquals(null, cameraPub)
        assertTrue("the capture keeps running", com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStops > stops)
    }

    // ------------------------------------------------------------------ the JS second review, checked here

    /** On, off, on while a publish is pending, no move: the last call was on, so the user must not end up silent. */
    @Test
    fun onOffOnWhileAPublishIsPendingEndsOn() = runTest {
        connect(join())
        sfuThatIgnoresADuplicateCid()
        val ws = wsFactory.ws
        mute.add(ws)
        val a = launch { room.localParticipant.setMicrophoneEnabled(true) }
        settle(2)
        val b = launch { room.localParticipant.setMicrophoneEnabled(false) }
        settle(1)
        val c = launch { room.localParticipant.setMicrophoneEnabled(true) }
        settle(1)
        answerAddTrack(addTracksOn(ws).single().cid)
        settle()
        assertTrue(a.isCompleted && b.isCompleted && c.isCompleted)
        assertNotNull(mic)
        assertEquals("silent while the last call was on", false, mic!!.muted)
        assertTrue(mic!!.track!!.enabled)
    }

    /** The same across a move: the publish waits for the reconnect, with an off and an on behind it. */
    @Test
    fun onOffOnDuringAMoveEndsOn() = runTest {
        onBlrWithPublishedMic()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        launch { room.localParticipant.setMicrophoneEnabled(true) }
        settle(1)
        launch { room.localParticipant.setMicrophoneEnabled(false) }
        settle(1)
        var last: Boolean? = null
        launch { last = room.localParticipant.setMicrophoneEnabled(true) }
        settle(1)
        peerConnectionsUp()
        assertEquals("lines=$lines", true, last)
        assertNotNull(mic)
        assertEquals(false, mic!!.muted)
        assertTrue(mic!!.track!!.enabled)
        assertTrue(addTracksOn(after).size == 1)
    }

    /** A leave with an enable still queued behind a pending publish: it isn't for the next session. */
    @Test
    fun anEnableQueuedAcrossALeaveDoesNotPublishInTheNextSession() = runTest {
        connect(join())
        sfuThatIgnoresADuplicateCid()
        val first = wsFactory.ws
        mute.add(first)
        launch { room.localParticipant.setMicrophoneEnabled(true) }
        settle(2)
        val queued = launch { room.localParticipant.setMicrophoneEnabled(true) }
        settle(1)
        room.disconnect()
        // the app connects again in the same breath, before the old calls have run on
        val next = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { room.connect(TestData.EXAMPLE_URL, "token") }
        testScheduler.runCurrent()
        repeat(100) {
            if (wsFactory.ws !== first) return@repeat
            Thread.sleep(2)
            testScheduler.advanceTimeBy(20)
            testScheduler.runCurrent()
        }
        assertTrue("dialled again", wsFactory.ws !== first)
        val second = wsFactory.ws
        wsFactory.listener.onOpen(second, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join())
        testScheduler.runCurrent()
        connectPeerConnection()
        settle()
        next.join()
        queued.join()
        assertEquals("the old session's call went out in the new one; lines=$lines", 0, addTracksOn(second).size)
        assertEquals(null, mic)
    }

    /** A camera asked for after a leave, as the second half of a call opening both: nothing opens. */
    @Test
    fun aCameraAskedForAfterALeaveOpensNothing() = runTest {
        connect(join())
        grantCamera()
        room.disconnect()
        settle(2)
        val opened = com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts
        assertEquals(false, room.localParticipant.setCameraEnabled(true))
        assertEquals("opened with no session", opened, com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts)
    }

    /** A republish that fails for good: no retry on Android, but the app is told. */
    @Test
    fun aRepublishThatFailsTellsTheApp() = runTest {
        onBlrWithPublishedMic()
        val failed = Collections.synchronizedList(mutableListOf<String>())
        backgroundScope.launch {
            room.events.collect { if (it is com.gravixcloud.rtc.events.RoomEvent.TrackPublicationFailed) failed.add(it.toString()) }
        }
        testScheduler.runCurrent()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        mute.add(after)
        newSessionJoined()
        peerConnectionsUp()
        testScheduler.advanceTimeBy(21_000)
        settle()
        assertEquals(null, mic)
        assertTrue("the app is told nothing; lines=$lines", failed.isNotEmpty())
    }

    /** The named rollback: what 0.4.0 added is off; the recovery 0.3.x already had stays on. */
    @Test
    fun thePresetFor03KeepsTheRecovery03Had() {
        val p = GravixRecoveryOptions.LIKE_0_3
        assertTrue(!p.keepParticipantsOnRejoin && !p.publishFollowsReconnect && !p.republishKeepsNewSessionTracks)
        val d = GravixRecoveryOptions()
        assertEquals(
            d.copy(keepParticipantsOnRejoin = false, publishFollowsReconnect = false, republishKeepsNewSessionTracks = false),
            p,
        )
        assertTrue(p.retrySameUrlFirst && p.waitForNetwork && p.mediaRefusalFailover && p.retryServerRestart)
    }

    /** With the preset the keep and off checks are off too: the behaviour is 0.3.x's. */
    @Test
    fun withThePresetAnOffDuringAMoveDoesNotStopTheRepublish() = runTest {
        room.gravixRecovery = GravixRecoveryOptions.LIKE_0_3
        onBlrWithPublishedMic()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("0.3.x republished it", 1, addTracksOn(after).size)
    }

    // ------------------------------------------------------------------ second review of 6dac89cd

    private val captureStarts get() = com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStarts
    private val captureStops get() = com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.captureStops

    /** D1, the regression from 0.3.2: an unmute during an outage reaches the publication. UI on, silent, otherwise. */
    @Test
    fun anUnmuteDuringAnOutageUnmutes() = runTest {
        onBlrWithPublishedMic()
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        disconnectPeerConnection()
        testScheduler.runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, component.rtcEngine().connectionState)
        assertEquals(true, room.localParticipant.setMicrophoneEnabled(true))
        assertEquals(false, mic!!.muted)
        assertTrue(mic!!.track!!.enabled)
        room.disconnect()
        settle(2)
    }

    /** D1, read-only side: the answer matches what happens to a held mic while the new session's primary has failed. */
    @Test
    fun anOffThenOnDuringARejoinWhosePrimaryFailedAnswersWhatHappens() = runTest {
        onBlrWithPublishedMic()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        getSubscriberPeerConnection().moveToIceConnectionState(org.webrtc.PeerConnection.IceConnectionState.FAILED)
        settle(2)
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        var on: Boolean? = null
        launch { on = room.localParticipant.setMicrophoneEnabled(true) }
        settle(2)
        peerConnectionsUp()
        assertEquals("lines=$lines", true, on)
        assertNotNull(mic)
        assertEquals(1, addTracksOn(after).size)
    }

    /** D2: the camera's gate waits on the reconnect, and the room is left meanwhile. */
    @Test
    fun aLeaveWhileTheCameraGateWaitsOpensNothing() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        grantCamera()
        val before = wsFactory.ws
        disconnectPeerConnection()
        nextDial(before)
        val opened = captureStarts
        var on: Boolean? = null
        launch { on = room.localParticipant.setCameraEnabled(true) }
        settle(2)
        room.disconnect()
        settle()
        assertEquals(false, on)
        assertEquals("opened for a room that is gone", opened, captureStarts)
    }

    /** D2: the mic waits for noise suppression's setup, and the room is left meanwhile. */
    @Test
    fun aLeaveWhileTheMicWaitsForItsSetupOpensNothing() = runTest {
        connect(join())
        val setup = kotlinx.coroutines.Job()
        com.gravixcloud.rtc.noise.GravixNoiseSuppression.gravixMicWaitsForConfigure = true
        com.gravixcloud.rtc.noise.GravixNoiseSuppression.gravixPendingConfigureForTest(setup)
        var on: Boolean? = null
        launch { on = room.localParticipant.setMicrophoneEnabled(true) }
        settle(2)
        room.disconnect()
        setup.complete()
        settle()
        com.gravixcloud.rtc.noise.GravixNoiseSuppression.gravixPendingConfigureForTest(null)
        assertEquals(false, on)
        assertTrue(lines.toString(), lines.any { it.contains("microphone skip reason=session-left") })
    }

    private val capturing get() = com.gravixcloud.rtc.test.mock.camera.MockCameraVideoCapturer.capturing

    /** A camera published on a region that carries video, its session going by a full reconnect: the new socket answers nothing. */
    private suspend fun TestScope.cameraRepublishOut(): MockWebSocket {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        disconnectPeerConnection()
        val second = nextDial(before)
        mute.add(second)
        newSessionJoined()
        connectPeerConnection()
        settle()
        assertEquals("the republish's AddTrack is out", 1, addTracksOn(second, GravixcloudModels.TrackType.VIDEO).size)
        return second
    }

    /** Known limit: an off during a reconnect sends nothing, and the republish closes the capture. */
    @Test
    fun aCameraTurnedOffDuringAReconnectIsClosedByTheRepublish() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        disconnectPeerConnection()
        val after = nextDial(before)
        assertTrue(room.localParticipant.setCameraEnabled(false))
        assertTrue("runs until the republish", capturing)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals(0, addTracksOn(after, GravixcloudModels.TrackType.VIDEO).size)
        assertTrue("closed by the republish", !capturing)
    }

    /** Off then on during a reconnect, before its republish: live. */
    @Test
    fun aCameraOffThenOnDuringAReconnectIsLive() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        disconnectPeerConnection()
        val after = nextDial(before)
        assertTrue(room.localParticipant.setCameraEnabled(false))
        var on: Boolean? = null
        launch { on = room.localParticipant.setCameraEnabled(true) }
        settle(2)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", true, on)
        assertEquals(1, addTracksOn(after, GravixcloudModels.TrackType.VIDEO).size)
        assertEquals(false, cameraPub!!.muted)
        assertTrue(cameraPub!!.track!!.enabled)
        assertTrue(capturing)
    }

    /** Reviewer's D1, camera: off then on while the republish's AddTrack is out. */
    @Test
    fun aCameraOffThenOnWhileTheRepublishIsOutIsReallyLive() = runTest {
        val second = cameraRepublishOut()
        assertTrue(room.localParticipant.setCameraEnabled(false))
        var on: Boolean? = null
        launch { on = room.localParticipant.setCameraEnabled(true) }
        settle(2)
        answerAddTrack(addTracksOn(second, GravixcloudModels.TrackType.VIDEO).single().cid, TestData.LOCAL_VIDEO_TRACK)
        settle()
        assertEquals("lines=$lines", true, on)
        assertEquals(false, cameraPub!!.muted)
        assertTrue("muted=false with a disabled track", cameraPub!!.track!!.enabled)
        assertTrue("no capture behind it", capturing)
    }

    /** Reviewer's D1, mic. */
    @Test
    fun aMicOffThenOnWhileTheRepublishIsOutIsReallyLive() = runTest {
        onBlrWithPublishedMic()
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        mute.add(after)
        newSessionJoined()
        connectPeerConnection()
        settle()
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))
        var on: Boolean? = null
        launch { on = room.localParticipant.setMicrophoneEnabled(true) }
        settle(2)
        answerAddTrack(addTracksOn(after).single().cid)
        settle()
        assertEquals("lines=$lines", true, on)
        assertEquals(false, mic!!.muted)
        assertTrue("muted=false with a disabled track", mic!!.track!!.enabled)
    }

    /** Reviewer's D1b: off while out, the signal drops, on during the next reconnect, republish: live. */
    @Test
    fun anOffWhileOutThenADropThenAnOnIsLiveAfterTheNextRepublish() = runTest {
        val second = cameraRepublishOut()
        assertTrue(room.localParticipant.setCameraEnabled(false))
        wsFactory.listener.onFailure(second, java.io.IOException("the socket went"), null)
        val third = nextDial(second)
        var on: Boolean? = null
        launch { on = room.localParticipant.setCameraEnabled(true) }
        settle(2)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("lines=$lines", true, on)
        assertEquals(1, addTracksOn(third, GravixcloudModels.TrackType.VIDEO).size)
        assertEquals(false, cameraPub!!.muted)
        assertTrue(cameraPub!!.track!!.enabled)
        assertTrue(capturing)
    }

    /** Reviewer's D2: off while out, on later, then a later reconnect with an off: nothing stale; the republish closes it. */
    @Test
    fun anOffWhileOutThenOnThenALaterReconnectWithAnOffLeavesNothingStale() = runTest {
        val second = cameraRepublishOut()
        assertTrue(room.localParticipant.setCameraEnabled(false))
        answerAddTrack(addTracksOn(second, GravixcloudModels.TrackType.VIDEO).single().cid, TestData.LOCAL_VIDEO_TRACK)
        settle()
        assertEquals("the off wins by muting on landing", true, cameraPub!!.muted)
        assertTrue(!capturing)
        assertTrue(room.localParticipant.setCameraEnabled(true))
        assertEquals(false, cameraPub!!.muted)
        assertTrue(cameraPub!!.track!!.enabled)
        assertTrue(capturing)

        mute.remove(second)
        disconnectPeerConnection()
        val third = nextDial(second)
        assertTrue(room.localParticipant.setCameraEnabled(false))
        newSessionJoined()
        peerConnectionsUp()
        assertEquals(0, addTracksOn(third, GravixcloudModels.TrackType.VIDEO).size)
        assertEquals(null, cameraPub)
        assertTrue("closed by the republish", !capturing)
    }

    /** D6: the signal went between two republishes: the next isn't sent to a dead socket to wait out the deadline. */
    @Test
    fun aRepublishStopsAtOnceWhenTheSignalWentBetweenTwoTracks() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect(join())
        seedRegionList()
        grantCamera()
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertTrue(room.localParticipant.setCameraEnabled(true))
        val before = wsFactory.ws
        disconnectPeerConnection()
        val second = nextDial(before)
        val sent = Collections.synchronizedList(mutableListOf<GravixcloudRtc.AddTrackRequest>())
        wsFactory.registerSignalRequestHandler { r ->
            if (r.hasAddTrack() && wsFactory.ws === second) {
                sent.add(r.addTrack)
                if (sent.size == 1) {
                    answerAddTrack(r.addTrack.cid, if (r.addTrack.type == GravixcloudModels.TrackType.AUDIO) TestData.LOCAL_AUDIO_TRACK else TestData.LOCAL_VIDEO_TRACK)
                    wsFactory.listener.onFailure(second, java.io.IOException("the socket went"), null)
                }
                true
            } else {
                false
            }
        }
        newSessionJoined()
        connectPeerConnection()
        settle()
        assertEquals("the second went to a dead socket; lines=$lines", 1, sent.size)
    }

    /** D7: the server muted the camera: the move that follows must not turn it live. */
    @Test
    fun aMoveDoesNotTurnOnACameraTheServerMuted() = runTest {
        GravixRegionMedia.noteDirectory(listOf(GravixRegionUrl("sgp1", sgp), GravixRegionUrl("blr1", blr)))
        connect(join())
        seedRegionList()
        sfuThatIgnoresADuplicateCid()
        grantCamera()
        assertTrue(room.localParticipant.setCameraEnabled(true))
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setMute(
                GravixcloudRtc.MuteTrackRequest.newBuilder().setSid(cameraPub!!.sid).setMuted(true),
            ).build(),
        )
        settle(2)
        assertTrue(cameraPub!!.muted)
        val before = wsFactory.ws
        refuse()
        val after = nextDial(before)
        newSessionJoined()
        peerConnectionsUp()
        assertEquals("live against the server's mute; lines=$lines", 0, addTracksOn(after, GravixcloudModels.TrackType.VIDEO).size)
        assertEquals(null, cameraPub)
    }

    /** Dials until none comes, up to [rounds]; every session is lost during its republish. The number of dials. */
    private fun TestScope.killEverySessionDuringRepublish(first: MockWebSocket, rounds: Int): Int {
        var ws = first
        var dials = 0
        repeat(rounds) {
            val next = try {
                nextDial(ws)
            } catch (_: AssertionError) {
                return dials
            }
            dials++
            ws = next
            mute.add(ws)
            newSessionJoined()
            connectPeerConnection()
            settle()
            wsFactory.listener.onFailure(ws, java.io.IOException("the socket went"), null)
        }
        return dials
    }

    /** N1: a leave and a new connect on the same Room start the count from 0. */
    @Test
    fun aNewConnectOnTheSameRoomStartsTheFollowUpCountFromZero() = runTest {
        onBlrWithPublishedMic()
        val first = wsFactory.ws
        refuse()
        assertEquals(4, killEverySessionDuringRepublish(first, 4))
        room.disconnect()
        settle(2)
        val old = wsFactory.ws
        val next = launch { room.connect(TestData.EXAMPLE_URL, "token") }
        repeat(100) {
            if (wsFactory.ws === old) {
                Thread.sleep(2)
                testScheduler.advanceTimeBy(20)
                testScheduler.runCurrent()
            }
        }
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join())
        testScheduler.runCurrent()
        connectPeerConnection()
        settle()
        next.join()
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val second = wsFactory.ws
        wsFactory.listener.onFailure(second, java.io.IOException("the socket went"), null)
        val dials = killEverySessionDuringRepublish(second, 4)
        assertEquals("the old session's count ended this one; lines=${lines.takeLast(6)}", 4, dials)
        room.disconnect()
        settle(2)
    }

    /** A server that kills every session during the republish: the follow-up reconnects are bounded. */
    @Test
    fun followUpReconnectsAreBounded() = runTest {
        onBlrWithPublishedMic()
        val ws = wsFactory.ws
        refuse()
        val dials = killEverySessionDuringRepublish(ws, 8)
        assertTrue("unbounded: $dials dials; lines=$lines", dials <= 6)
        assertEquals("dials=$dials lines=${lines.takeLast(8)}", com.gravixcloud.rtc.room.Room.State.DISCONNECTED, room.state)
    }
}
