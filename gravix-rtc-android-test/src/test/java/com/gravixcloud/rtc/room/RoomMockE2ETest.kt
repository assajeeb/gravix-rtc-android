/*
 * Copyright 2023-2026 LiveKit, Inc.
 * Modifications Copyright 2026 Gravity Compile
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

import android.net.Network
import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.events.ParticipantEvent
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.convert
import com.gravixcloud.rtc.room.participant.ConnectionQuality
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.assert.assertIsClassList
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.events.FlowCollector
import com.gravixcloud.rtc.test.mock.MockAudioStreamTrack
import com.gravixcloud.rtc.test.mock.MockMediaStream
import com.gravixcloud.rtc.test.mock.MockRtpReceiver
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.createMediaStreamId
import com.gravixcloud.rtc.test.mock.room.track.createMockLocalAudioTrack
import com.gravixcloud.rtc.util.flow
import com.gravixcloud.rtc.util.toOkioByteString
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomMockE2ETest : MockE2ETest() {

    @Test
    fun connectTest() = runTest {
        val collector = FlowCollector(room::state.flow, coroutineRule.scope)
        connect()
        val events = collector.stopCollecting()
        assertEquals(3, events.size)
        assertEquals(Room.State.DISCONNECTED, events[0])
        assertEquals(Room.State.CONNECTING, events[1])
        assertEquals(Room.State.CONNECTED, events[2])
    }

    @Test
    fun connectEvent() = runTest {
        val collector = EventCollector(room.events, coroutineRule.scope)
        connect()
        val events = collector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.Connected::class.java,
                // GRAVIX: the first connect also reports the connected url
                RoomEvent.ConnectedUrlChanged::class.java,
            ),
            events,
        )
    }

    @Test
    fun connectNoEventsWithRemoteParticipant() = runTest {
        val joinResponse = with(TestData.JOIN.toBuilder()) {
            join = with(TestData.JOIN.join.toBuilder()) {
                addOtherParticipants(TestData.REMOTE_PARTICIPANT)
                build()
            }
            build()
        }

        val collector = EventCollector(room.events, coroutineRule.scope)
        connect(joinResponse)
        val events = collector.stopCollecting()
        assertIsClassList(
            listOf(
                RoomEvent.Connected::class.java,
                // GRAVIX: the first connect also reports the connected url
                RoomEvent.ConnectedUrlChanged::class.java,
            ),
            events,
        )
    }

    @Test
    fun connectFailureProperlyContinues() = runTest {
        var didThrowException = false
        val job = coroutineRule.scope.launch {
            try {
                room.connect(
                    url = TestData.EXAMPLE_URL,
                    token = "",
                )
            } catch (e: Throwable) {
                didThrowException = true
            }
        }

        wsFactory.listener.onFailure(wsFactory.ws, Exception(), null)

        job.join()

        Assert.assertTrue(didThrowException)
    }

    @Test
    fun roomUpdateTest() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val roomUpdate = TestData.ROOM_UPDATE
        wsFactory.listener.onMessage(wsFactory.ws, TestData.ROOM_UPDATE.toOkioByteString())
        val events = eventCollector.stopCollecting()

        assertEquals(
            roomUpdate.roomUpdate.room.metadata,
            room.metadata,
        )
        assertEquals(
            roomUpdate.roomUpdate.room.activeRecording,
            room.isRecording,
        )
        assertIsClassList(
            listOf(
                RoomEvent.RoomMetadataChanged::class.java,
                RoomEvent.RecordingStatusChanged::class.java,
            ),
            events,
        )
    }

    @Test
    fun connectionQualityUpdateTest() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.CONNECTION_QUALITY.toOkioByteString(),
        )
        val events = eventCollector.stopCollecting()

        assertEquals(ConnectionQuality.EXCELLENT, room.localParticipant.connectionQuality)
        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.ConnectionQualityChanged)
    }

    @Test
    fun participantConnected() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        val events = eventCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.ParticipantConnected::class.java,
                RoomEvent.TrackPublished::class.java,
                RoomEvent.TrackPublished::class.java,
            ),
            events,
        )
    }

    @Test
    fun participantSubscribesRemoteTrack() = runTest {
        connect()

        simulateMessageFromServer(TestData.PARTICIPANT_JOIN)
        val remoteParticipant = room.getParticipantBySid(TestData.REMOTE_PARTICIPANT.sid)!!
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventsCollector = EventCollector(remoteParticipant.events, coroutineRule.scope)
        room.onAddTrack(
            MockRtpReceiver.create(),
            MockAudioStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_AUDIO_TRACK.sid,
                    ),
                ),
            ),
        )
        val events = eventCollector.stopCollecting()
        val participantEvents = participantEventsCollector.stopCollecting()

        assertIsClassList(
            listOf(RoomEvent.TrackSubscribed::class.java),
            events,
        )

        assertIsClassList(
            listOf(ParticipantEvent.TrackSubscribed::class.java),
            participantEvents,
        )

        val micPub = remoteParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(micPub)
        assertNotNull(micPub?.track)
    }

    @Test
    fun participantDisconnected() = runTest {
        connect()
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_DISCONNECT.toOkioByteString(),
        )
        val events = eventCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.ParticipantDisconnected::class.java,
            ),
            events,
        )
    }

    @Test
    fun onActiveSpeakersChanged() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.ACTIVE_SPEAKER_UPDATE.toOkioByteString(),
        )
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.ActiveSpeakersChanged)
    }

    @Test
    fun trackStreamStateChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        // We intentionally don't emit if the track isn't subscribed, so need to
        // add track.
        room.onAddTrack(
            receiver = MockRtpReceiver.create(),
            track = MockAudioStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_AUDIO_TRACK.sid,
                    ),
                ),
            ),
        )
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.STREAM_STATE_UPDATE.toOkioByteString(),
        )
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackStreamStateChanged)

        val event = events[0] as RoomEvent.TrackStreamStateChanged
        assertEquals(Track.StreamState.ACTIVE, event.streamState)
    }

    @Test
    fun trackSubscriptionPermissionChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )
        room.onAddTrack(
            MockRtpReceiver.create(),
            MockAudioStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_AUDIO_TRACK.sid,
                    ),
                ),
            ),
        )
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.SUBSCRIPTION_PERMISSION_UPDATE.toOkioByteString(),
        )
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackSubscriptionPermissionChanged)

        val event = events[0] as RoomEvent.TrackSubscriptionPermissionChanged
        assertEquals(TestData.REMOTE_PARTICIPANT.sid, event.participant.sid.value)
        assertEquals(TestData.REMOTE_AUDIO_TRACK.sid, event.trackPublication.sid)
        assertEquals(false, event.subscriptionAllowed)
    }

    @Test
    fun onConnectionAvailableWillReconnect() = runTest {
        connect()
        val engine = component.rtcEngine()
        val eventCollector = FlowCollector(engine::connectionState.flow, coroutineRule.scope)
        val network = Mockito.mock(Network::class.java)

        val networkCallbackRegistry = component.networkCallbackRegistry()

        networkCallbackRegistry.networkCallbacks.forEach { callback ->
            callback.onLost(network)
        }
        networkCallbackRegistry.networkCallbacks.forEach { callback ->
            callback.onAvailable(network)
        }

        coroutineRule.dispatcher.scheduler.advanceTimeBy(1000)
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)

        val events = eventCollector.stopCollecting()

        assertEquals(
            listOf(
                ConnectionState.CONNECTED,
                ConnectionState.RESUMING,
                ConnectionState.CONNECTED,
            ),
            events,
        )
    }

    @Test
    fun leave() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.LEAVE.toOkioByteString(),
        )
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
    }

    @Test
    fun disconnectCleansLocalParticipant() = runTest {
        connect()

        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.LEAVE.toOkioByteString(),
        )
        room.disconnect()
        val events = eventCollector.stopCollecting()

        assertEquals(2, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        assertEquals(true, events[1] is RoomEvent.Disconnected)
    }

    /**
     *
     */
    @Test
    fun disconnectWithTracks() = runTest {
        connect()

        val differentThread = CoroutineScope(Dispatchers.IO + SupervisorJob())
        wsFactory.registerSignalRequestHandler {
            if (it.hasLeave()) {
                differentThread.launch {
                    val leaveResponse = with(GravixcloudRtc.SignalResponse.newBuilder()) {
                        leave = with(GravixcloudRtc.LeaveRequest.newBuilder()) {
                            canReconnect = false
                            reason = gravixcloud.GravixcloudModels.DisconnectReason.CLIENT_INITIATED
                            build()
                        }
                        build()
                    }
                    wsFactory.receiveMessage(leaveResponse)
                }
                return@registerSignalRequestHandler true
            }
            return@registerSignalRequestHandler false
        }
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()

        assertEquals(2, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        assertEquals(true, events[1] is RoomEvent.Disconnected)
    }

    @Test
    fun disconnectCleansUpParticipants() = runTest {
        connect()

        room.onUpdateParticipants(TestData.PARTICIPANT_JOIN.update.participantsList)
        room.onAddTrack(
            MockRtpReceiver.create(),
            MockAudioStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_AUDIO_TRACK.sid,
                    ),
                ),
            ),
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onEngineDisconnected(DisconnectReason.CLIENT_INITIATED)
        val events = eventCollector.stopCollecting()

        assertIsClassList(
            listOf(
                RoomEvent.TrackUnsubscribed::class.java,
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.TrackUnpublished::class.java,
                RoomEvent.ParticipantDisconnected::class.java,
                RoomEvent.Disconnected::class.java,
            ),
            events,
        )
        Assert.assertTrue(room.remoteParticipants.isEmpty())
    }

    @Test
    fun serverDisconnectReason() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        wsFactory.listener.onMessage(wsFactory.ws, TestData.LEAVE.toOkioByteString())
        val events = eventCollector.stopCollecting()
        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(TestData.LEAVE.leave.reason.convert(), (events[0] as RoomEvent.Disconnected).reason)
    }

    @Test
    fun clientDisconnectReason() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()
        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(DisconnectReason.CLIENT_INITIATED, (events[0] as RoomEvent.Disconnected).reason)
    }

    @Test
    fun connectAfterDisconnect() = runTest {
        connect()
        room.disconnect()
        connect()
        assertEquals(room.state, Room.State.CONNECTED)
    }
}
