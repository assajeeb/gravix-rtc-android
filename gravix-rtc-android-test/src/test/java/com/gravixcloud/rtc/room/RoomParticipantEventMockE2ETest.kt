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

import com.gravixcloud.rtc.events.ParticipantEvent
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.room.participant.AudioTrackPublishOptions
import com.gravixcloud.rtc.room.participant.RemoteParticipant
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.room.track.TrackException
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.assert.assertIsClass
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.room.track.createMockLocalAudioTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import gravixcloud.GravixcloudRtc.ParticipantUpdate
import gravixcloud.GravixcloudRtc.SignalResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomParticipantEventMockE2ETest : MockE2ETest() {

    @Test
    fun localParticipantAttributesChangedEvent() = runTest {
        connect()
        wsFactory.ws.clearRequests()
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasUpdateMetadata()) {
                val newInfo = with(TestData.LOCAL_PARTICIPANT.toBuilder()) {
                    putAllAttributes(request.updateMetadata.attributesMap)
                    build()
                }

                val response = with(SignalResponse.newBuilder()) {
                    update = with(ParticipantUpdate.newBuilder()) {
                        addParticipants(newInfo)
                        build()
                    }
                    build()
                }
                wsFactory.receiveMessage(response)
                return@registerSignalRequestHandler true
            }
            return@registerSignalRequestHandler false
        }

        val newAttributes = mapOf("attribute" to "changedValue")

        val collector = EventCollector(room.events, coroutineRule.scope)
        room.localParticipant.updateAttributes(newAttributes)

        val events = collector.stopCollecting()

        assertEquals(1, events.size)
        assertIsClass(RoomEvent.ParticipantAttributesChanged::class.java, events.first())
    }

    @Test
    fun localTrackSubscribed() = runTest {
        connect()
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
            options = AudioTrackPublishOptions(
                source = Track.Source.MICROPHONE,
            ),
        )
        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        val participantCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)

        wsFactory.receiveMessage(
            with(SignalResponse.newBuilder()) {
                trackSubscribed = with(GravixcloudRtc.TrackSubscribed.newBuilder()) {
                    trackSid = TestData.LOCAL_AUDIO_TRACK.sid
                    build()
                }
                build()
            },
        )

        val roomEvents = roomCollector.stopCollecting()
        val participantEvents = participantCollector.stopCollecting()

        // Verify room events
        run {
            assertEquals(1, roomEvents.size)
            assertIsClass(RoomEvent.LocalTrackSubscribed::class.java, roomEvents[0])

            val event = roomEvents.first() as RoomEvent.LocalTrackSubscribed
            assertEquals(room, event.room)
            assertEquals(room.localParticipant, event.participant)
            assertEquals(room.localParticipant.getTrackPublication(Track.Source.MICROPHONE), event.publication)
        }

        // Verify participant events
        run {
            assertEquals(1, participantEvents.size)
            assertIsClass(ParticipantEvent.LocalTrackSubscribed::class.java, participantEvents[0])
        }
    }

    @Test
    fun trackSubscriptionFailed() = runTest {
        connect()

        wsFactory.receiveMessage(TestData.PARTICIPANT_JOIN)

        val remoteParticipant = room.getParticipantBySid(TestData.REMOTE_PARTICIPANT.sid) as RemoteParticipant
        val roomCollector = EventCollector(room.events, coroutineRule.scope)
        val participantCollector = EventCollector(remoteParticipant.events, coroutineRule.scope)

        wsFactory.receiveMessage(
            with(SignalResponse.newBuilder()) {
                subscriptionResponse = with(GravixcloudRtc.SubscriptionResponse.newBuilder()) {
                    trackSid = TestData.REMOTE_AUDIO_TRACK.sid
                    err = GravixcloudModels.SubscriptionError.SE_CODEC_UNSUPPORTED
                    build()
                }
                build()
            },
        )

        val roomEvents = roomCollector.stopCollecting()
        val participantEvents = participantCollector.stopCollecting()

        // Verify room events
        run {
            assertEquals(1, roomEvents.size)
            assertIsClass(RoomEvent.TrackSubscriptionFailed::class.java, roomEvents[0])

            val event = roomEvents.first() as RoomEvent.TrackSubscriptionFailed
            assertEquals(room, event.room)
            assertEquals(TestData.REMOTE_AUDIO_TRACK.sid, event.sid)
            assertEquals(remoteParticipant, event.participant)
            assertIsClass(TrackException.MediaException::class.java, event.exception)
            assertEquals("Codec not supported", event.exception.message)
        }

        // Verify participant events
        run {
            assertEquals(1, participantEvents.size)
            assertIsClass(ParticipantEvent.TrackSubscriptionFailed::class.java, participantEvents[0])

            val event = participantEvents.first() as ParticipantEvent.TrackSubscriptionFailed
            assertEquals(remoteParticipant, event.participant)
            assertEquals(TestData.REMOTE_AUDIO_TRACK.sid, event.sid)
            assertIsClass(TrackException.MediaException::class.java, event.exception)
            assertEquals("Codec not supported", event.exception.message)
        }
    }
}
