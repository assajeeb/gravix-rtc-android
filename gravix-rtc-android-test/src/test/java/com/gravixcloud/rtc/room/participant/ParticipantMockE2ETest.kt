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
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.assert.assertIsClass
import com.gravixcloud.rtc.test.assert.assertIsClassList
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.room.track.createMockLocalAudioTrack
import com.gravixcloud.rtc.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class ParticipantMockE2ETest : MockE2ETest() {

    @Test
    fun trackUnpublished() = runTest {
        connect()

        // publish track
        room.localParticipant.publishAudioTrack(
            track = createMockLocalAudioTrack(),
        )

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        // remote unpublish
        simulateMessageFromServer(TestData.LOCAL_TRACK_UNPUBLISHED)
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.TrackUnpublished)
        assertEquals(0, room.localParticipant.trackPublications.size)
    }

    @Test
    fun participantPermissions() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventCollector = EventCollector(room.localParticipant.events, coroutineRule.scope)
        simulateMessageFromServer(TestData.PERMISSION_CHANGE)
        val events = eventCollector.stopCollecting()
        val participantEvents = participantEventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertIsClass(RoomEvent.ParticipantPermissionsChanged::class.java, events[0])

        assertEquals(1, participantEvents.size)
        assertIsClass(ParticipantEvent.ParticipantPermissionsChanged::class.java, participantEvents[0])

        val newPermissions = (participantEvents[0] as ParticipantEvent.ParticipantPermissionsChanged).newPermissions!!
        val permissionData = TestData.PERMISSION_CHANGE.update.participantsList[0].permission
        assertEquals(permissionData.canPublish, newPermissions.canPublish)
        assertEquals(permissionData.canPublishSourcesList.map { Track.Source.fromProto(it) }, newPermissions.canPublishSources)
    }

    @Test
    fun participantMetadataChanged() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        val remoteParticipant = room.remoteParticipants.values.first()
        val roomEventsCollector = EventCollector(room.events, coroutineRule.scope)
        val participantEventsCollector = EventCollector(remoteParticipant.events, coroutineRule.scope)
        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.REMOTE_PARTICIPANT_METADATA_CHANGED.toOkioByteString(),
        )
        val roomEvents = roomEventsCollector.stopCollecting()
        val participantEvents = participantEventsCollector.stopCollecting()

        val updateData = TestData.REMOTE_PARTICIPANT_METADATA_CHANGED.update.getParticipants(0)
        assertEquals(updateData.metadata, remoteParticipant.metadata)
        assertEquals(updateData.name, remoteParticipant.name)

        assertIsClassList(
            listOf(
                RoomEvent.ParticipantMetadataChanged::class.java,
                RoomEvent.ParticipantNameChanged::class.java,
            ),
            roomEvents,
        )

        assertIsClassList(
            listOf(
                ParticipantEvent.MetadataChanged::class.java,
                ParticipantEvent.NameChanged::class.java,
            ),
            participantEvents,
        )
    }
}
