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

package com.gravixcloud.rtc.service

import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.room.RETIRED_RTC_HOST
import com.gravixcloud.rtc.room.RETIRED_RTC_URL
import com.gravixcloud.rtc.room.ReconnectType
import com.gravixcloud.rtc.room.RetiredServerException
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.SignalClient
import com.gravixcloud.rtc.room.track.VideoTrack
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockAudioStreamTrack
import com.gravixcloud.rtc.test.mock.MockMediaStream
import com.gravixcloud.rtc.test.mock.MockRtpReceiver
import com.gravixcloud.rtc.test.mock.MockVideoStreamTrack
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.createMediaStreamId
import com.gravixcloud.rtc.test.util.toPBByteString
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * GRAVIX(Phase 4): the Java-first GravixRoomService on the mock engine (UNIT). The Java side of the same API is
 * exercised by GravixRoomServiceJavaApiTest (a listener and futures written in Java).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixRoomServiceMockE2ETest : MockE2ETest() {

    private val calls = mutableListOf<String>()
    private val executions = AtomicInteger()
    private lateinit var service: GravixRoomService

    private val remoteUid = TestData.REMOTE_PARTICIPANT.identity

    private val recorder = object : GravixRoomServiceListener() {
        override fun onConnectionStateChanged(state: Room.State) { calls += "state:$state" }
        override fun onConnected() { calls += "connected" }
        override fun onReconnecting(isResume: Boolean) { calls += "reconnecting:$isResume" }
        override fun onReconnected(isResume: Boolean) { calls += "reconnected:$isResume" }
        override fun onDisconnected(reason: DisconnectReason, error: Throwable?) { calls += "disconnected" }
        override fun onUserJoined(uid: String) { calls += "joined:$uid" }
        override fun onUserOffline(uid: String) { calls += "offline:$uid" }
        override fun onUserMuteAudio(uid: String, muted: Boolean) { calls += "muteAudio:$uid:$muted" }
        override fun onRemoteVideoTrack(uid: String, track: VideoTrack) { calls += "video:$uid" }
        override fun onRemoteVideoTrackRemoved(uid: String) { calls += "videoRemoved:$uid" }
        override fun onActiveSpeakers(uids: List<String>) { calls += "speakers:$uids" }
        override fun onConnectedUrlChanged(url: String, region: String?) { calls += "url:$url" }
    }

    @Before
    fun createService() {
        // a direct executor that counts: callbacks and future completions must go through it
        val executor = Executor { r -> executions.incrementAndGet(); r.run() }
        service = GravixRoomService(room, executor, coroutineRule.dispatcher, ownsRoom = false)
        service.addListener(recorder)
    }

    private val joinWithRemote = TestData.JOIN.toBuilder().apply {
        join = join.toBuilder().addOtherParticipants(TestData.REMOTE_PARTICIPANT).build()
    }.build()

    private suspend fun TestScope.connectService(join: GravixcloudRtc.SignalResponse = TestData.JOIN) =
        service.connect(TestData.EXAMPLE_URL, "token").also {
            prepareSignal(join)
            connectPeerConnection()
            advanceUntilIdle()
        }

    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long): MockWebSocket? {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < maxMs) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun answer(ws: MockWebSocket, join: GravixcloudRtc.SignalResponse = TestData.JOIN) {
        wsFactory.listener.onOpen(ws, createOpenResponse(wsFactory.request))
        val resume = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1"
        simulateMessageFromServer(if (resume) TestData.RECONNECT else join)
    }

    private fun subscribe(trackSid: String, video: Boolean) {
        room.onAddTrack(
            MockRtpReceiver.create(),
            if (video) MockVideoStreamTrack() else MockAudioStreamTrack(),
            arrayOf(MockMediaStream(id = createMediaStreamId(TestData.REMOTE_PARTICIPANT.sid, trackSid))),
        )
    }

    @Test
    fun connectCompletesTheFutureAndAnnouncesWhoIsAlreadyThere() = runTest {
        val f = connectService(joinWithRemote)
        assertTrue(f.isDone)
        assertFalse(f.isCompletedExceptionally)
        assertTrue(calls.toString(), calls.containsAll(listOf("state:CONNECTING", "state:CONNECTED", "joined:$remoteUid", "connected", "url:${TestData.EXAMPLE_URL}")))
        assertTrue("onUserJoined for the participant already there comes before onConnected", calls.indexOf("joined:$remoteUid") < calls.indexOf("connected"))
        assertEquals(1, calls.count { it == "joined:$remoteUid" })
        assertEquals(listOf(remoteUid), service.remoteUids)
        assertEquals(TestData.LOCAL_PARTICIPANT.identity, service.localUid)
        assertEquals(Room.State.CONNECTED, service.connectionState)
        assertEquals(TestData.EXAMPLE_URL, service.connectedUrl)
        assertTrue("completions and callbacks ran on the callback executor", executions.get() >= calls.size + 1)
    }

    // GRAVIX(retired servers, 2026-10-12): the Java facade reports the retired deployment as the future's failure
    // (the exception itself, not a timeout), and nothing was dialled or announced.
    @Test
    fun connectToARetiredGravixServerFailsTheFutureWithoutDialling() = runTest {
        val f = service.connect(RETIRED_RTC_URL, "token")
        advanceUntilIdle()

        assertTrue(f.isCompletedExceptionally)
        val cause = runCatching { f.join() }.exceptionOrNull()?.cause
        assertTrue("$cause", cause is RetiredServerException)
        assertEquals(RETIRED_RTC_HOST, (cause as RetiredServerException).host)
        assertEquals(Room.State.DISCONNECTED, service.connectionState)
        assertTrue("no state change, no callback: $calls", calls.isEmpty())
        assertTrue("no WebSocket was opened", runCatching { wsFactory.request }.isFailure)
    }

    @Test
    fun aFailedConnectCompletesExceptionally() = runTest {
        val f = service.connect(TestData.EXAMPLE_URL, "token")
        wsFactory.listener.onFailure(wsFactory.ws, Exception("refused"), null)
        advanceUntilIdle()
        assertTrue(f.isDone)
        assertTrue(f.isCompletedExceptionally)
        assertFalse(calls.contains("connected"))
    }

    @Test
    fun cancellingTheConnectFutureCancelsIt() = runTest {
        val f = service.connect(TestData.EXAMPLE_URL, "token")
        assertTrue(f.cancel(true))
        advanceUntilIdle()
        assertTrue(f.isCancelled)
        assertFalse(calls.contains("connected"))
    }

    @Test
    fun remoteParticipantsTracksMutesAndSpeakers() = runTest {
        connectService()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        subscribe(TestData.REMOTE_VIDEO_TRACK.sid, video = true)
        subscribe(TestData.REMOTE_AUDIO_TRACK.sid, video = false)
        simulateMessageFromServer(TestData.ACTIVE_SPEAKER_UPDATE)
        val muted = TestData.REMOTE_PARTICIPANT.toBuilder().apply {
            setTracks(0, TestData.REMOTE_AUDIO_TRACK.toBuilder().setMuted(true).build())
        }.build()
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder()
                .setUpdate(GravixcloudRtc.ParticipantUpdate.newBuilder().addParticipants(muted))
                .build(),
        )
        advanceUntilIdle()
        assertTrue(calls.toString(), calls.containsAll(listOf("joined:$remoteUid", "video:$remoteUid", "speakers:[$remoteUid]", "muteAudio:$remoteUid:true")))
        assertNotNull(service.remoteVideoTrack(remoteUid))

        // muteRemoteAudio: the server is asked to stop forwarding the subscribed audio track
        wsFactory.ws.clearRequests()
        val mute = service.muteRemoteAudio(remoteUid, true)
        advanceUntilIdle()
        assertEquals(true, mute.get())
        val settings = wsFactory.ws.sentRequests
            .map { GravixcloudRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build() }
            .filter { it.hasTrackSetting() }
        assertTrue(
            "a disabled track setting for the audio track",
            settings.any { it.trackSetting.disabled && it.trackSetting.trackSidsList.contains(TestData.REMOTE_AUDIO_TRACK.sid) }
        )
        assertEquals(false, service.muteRemoteAudio("nobody", true).get())

        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)
        advanceUntilIdle()
        assertTrue(calls.toString(), calls.contains("offline:$remoteUid"))
        assertTrue(calls.toString(), calls.contains("videoRemoved:$remoteUid"))
    }

    @Test
    fun aResumeIsReportedAsReconnectingTrue() = runTest {
        connectService(joinWithRemote)
        calls.clear()
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        answer(resume)
        connectPeerConnection()
        advanceUntilIdle()
        assertEquals(listOf("reconnecting:true", "reconnected:true"), calls.filter { it.startsWith("reconnect") })
        assertTrue("nobody left or joined: $calls", calls.none { it.startsWith("joined") || it.startsWith("offline") })
    }

    @Test
    fun aFullRejoinReportsOnlyRealParticipantChanges() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connectService(joinWithRemote)
        calls.clear()
        val before = wsFactory.ws
        disconnectPeerConnection()
        val full = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no rejoin dial")
        answer(full, joinWithRemote) // the remote participant is still in the room
        connectPeerConnection()
        advanceUntilIdle()
        assertEquals(listOf("reconnecting:false", "reconnected:false"), calls.filter { it.startsWith("reconnect") })
        assertTrue("the participant who stayed is not reported offline/joined: $calls", calls.none { it.startsWith("joined") || it.startsWith("offline") })
        assertEquals(listOf(remoteUid), service.remoteUids)
    }

    @Test
    fun aFullRejoinReportsWhoLeftDuringTheOutage() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connectService(joinWithRemote)
        calls.clear()
        val before = wsFactory.ws
        disconnectPeerConnection()
        val full = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no rejoin dial")
        answer(full, TestData.JOIN) // the remote participant left while we were away
        connectPeerConnection()
        advanceUntilIdle()
        assertEquals(1, calls.count { it == "offline:$remoteUid" })
        assertTrue(calls.toString(), calls.indexOf("offline:$remoteUid") < calls.indexOf("reconnected:false"))
    }

    @Test
    fun disconnectCompletesAndReportsOnce() = runTest {
        connectService(joinWithRemote)
        calls.clear()
        val f = service.disconnect()
        advanceUntilIdle()
        assertTrue(f.isDone)
        assertFalse(f.isCompletedExceptionally)
        assertEquals(1, calls.count { it == "disconnected" })
        assertTrue("no onUserOffline per participant on our own leave: $calls", calls.none { it.startsWith("offline") })
        assertEquals(Room.State.DISCONNECTED, service.connectionState)
    }

    @Test
    fun aJavaListenerReceivesTheCallbacks() = runTest {
        val java = GravixRoomServiceJavaApiTest.RecordingListener()
        service.addListener(java)
        connectService(joinWithRemote)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before, maxMs = 5_000) ?: throw AssertionError("no resume dial")
        answer(resume)
        connectPeerConnection()
        service.disconnect()
        advanceUntilIdle()
        assertEquals(
            listOf("joined:$remoteUid", "connected", "reconnecting:true", "reconnected:true", "disconnected"),
            java.calls.toList(),
        )
    }

    @Test
    fun aThrowingListenerDoesNotStopTheOthersAndReleaseStopsCallbacks() = runTest {
        service.addListener(object : GravixRoomServiceListener() {
            override fun onUserJoined(uid: String) = throw IllegalStateException("app bug")
        })
        connectService()
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        advanceUntilIdle()
        assertTrue(calls.contains("joined:$remoteUid"))

        service.release()
        calls.clear()
        simulateMessageFromServer(TestData.PARTICIPANT_DISCONNECT)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), calls)
        assertTrue("a future after release is cancelled", service.setMicrophoneEnabled(true).isCancelled)
    }
}
