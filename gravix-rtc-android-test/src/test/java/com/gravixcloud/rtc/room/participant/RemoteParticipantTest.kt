/*
 * Copyright 2023-2025 LiveKit, Inc.
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

package com.gravixcloud.rtc.room.participant

import com.gravixcloud.rtc.events.ParticipantEvent
import com.gravixcloud.rtc.room.SignalClient
import com.gravixcloud.rtc.room.track.TrackPublication
import com.gravixcloud.rtc.test.BaseTest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.events.FlowCollector
import com.gravixcloud.rtc.test.mock.room.track.TestRemoteAudioTrackFactory
import com.gravixcloud.rtc.test.mock.room.track.TestRemoteVideoTrackFactory
import com.gravixcloud.rtc.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import gravixcloud.GravixcloudModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteParticipantTest : BaseTest() {

    lateinit var signalClient: SignalClient
    lateinit var participant: RemoteParticipant

    @Before
    fun setup() {
        signalClient = Mockito.mock(SignalClient::class.java)
        participant = RemoteParticipant(
            sid = Participant.Sid("sid"),
            identity = null,
            signalClient = signalClient,
            ioDispatcher = coroutineRule.dispatcher,
            defaultDispatcher = coroutineRule.dispatcher,
            audioTrackFactory = TestRemoteAudioTrackFactory,
            videoTrackFactory = TestRemoteVideoTrackFactory(
                dispatcher = coroutineRule.dispatcher,
            ),
        )
    }

    @Test
    fun updateFromInfoAddsTrack() = runTest {
        val newTrackInfo = GravixcloudModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        val collector = EventCollector(participant.events, coroutineRule.scope)
        participant.updateFromInfo(newTrackInfo)
        val events = collector.stopCollecting()

        assertEquals(1, participant.trackPublications.values.size)
        assertNotNull(participant.getTrackPublication(TRACK_INFO.sid))

        val publishes = events.filterIsInstance<ParticipantEvent.TrackPublished>()
        assertEquals(1, publishes.size)
    }

    @Test
    fun tracksFlow() = runTest {
        val newTrackInfo = GravixcloudModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        val collector = FlowCollector(participant::trackPublications.flow, coroutineRule.scope)
        participant.updateFromInfo(newTrackInfo)

        val emissions = collector.stopCollecting()
        assertEquals(2, emissions.size)
        assertEquals(emptyMap<String, TrackPublication>(), emissions[0])
        assertEquals(1, emissions[1].size)
    }

    @Test
    fun audioTracksFlow() = runTest {
        val newTrackInfo = GravixcloudModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        val collector = FlowCollector(participant::trackPublications.flow, coroutineRule.scope)
        participant.updateFromInfo(newTrackInfo)

        val emissions = collector.stopCollecting()
        assertEquals(2, emissions.size)
        assertEquals(emptyMap<String, TrackPublication>(), emissions[0])
        assertEquals(1, emissions[1].size)
    }

    @Test
    fun updateFromInfoRemovesTrack() {
        val newTrackInfo = GravixcloudModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        participant.updateFromInfo(newTrackInfo)
        participant.updateFromInfo(INFO)

        assertEquals(0, participant.trackPublications.values.size)
        assertNull(participant.getTrackPublication(TRACK_INFO.sid))
    }

    @Test
    fun unpublishTrackRemoves() {
        val newTrackInfo = GravixcloudModels.ParticipantInfo.newBuilder(INFO)
            .addTracks(TRACK_INFO)
            .build()

        participant.updateFromInfo(newTrackInfo)
        participant.unpublishTrack(TRACK_INFO.sid)

        assertEquals(0, participant.trackPublications.values.size)
        assertNull(participant.getTrackPublication(TRACK_INFO.sid))
    }

    companion object {
        val INFO = GravixcloudModels.ParticipantInfo.newBuilder()
            .setSid("sid")
            .setIdentity("identity")
            .setMetadata("metadata")
            .build()

        val TRACK_INFO = GravixcloudModels.TrackInfo.newBuilder()
            .setSid("sid")
            .setName("name")
            .setType(GravixcloudModels.TrackType.AUDIO)
            .setMuted(false)
            .build()
    }
}
