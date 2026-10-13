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

package com.gravixcloud.rtc.room.track

import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockMediaStream
import com.gravixcloud.rtc.test.mock.MockRtpReceiver
import com.gravixcloud.rtc.test.mock.MockVideoStreamTrack
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.createMediaStreamId
import com.gravixcloud.rtc.test.util.toPBByteString
import com.gravixcloud.rtc.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import gravixcloud.GravixcloudModels.VideoQuality
import gravixcloud.GravixcloudRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RemoteTrackPublicationTest : MockE2ETest() {

    @Test
    fun trackSetting() = runTest {
        room.adaptiveStream = false

        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        val remoteVideoPub = room.remoteParticipants.values.first()
            .videoTrackPublications.first()
            .first as RemoteTrackPublication

        remoteVideoPub.setVideoQuality(com.gravixcloud.rtc.room.track.VideoQuality.LOW)
        remoteVideoPub.setVideoFps(100)

        advanceUntilIdle()

        val lastRequest = GravixcloudRtc.SignalRequest.newBuilder()
            .mergeFrom(wsFactory.ws.sentRequests.last().toPBByteString())
            .build()

        assertTrue(lastRequest.hasTrackSetting())
        assertEquals(100, lastRequest.trackSetting.fps)
        assertEquals(VideoQuality.LOW, lastRequest.trackSetting.quality)
    }

    @Test
    fun subscriptionStatus() = runTest {
        connect()

        wsFactory.listener.onMessage(
            wsFactory.ws,
            TestData.PARTICIPANT_JOIN.toOkioByteString(),
        )

        room.onAddTrack(
            MockRtpReceiver.create(),
            MockVideoStreamTrack(),
            arrayOf(
                MockMediaStream(
                    id = createMediaStreamId(
                        TestData.REMOTE_PARTICIPANT.sid,
                        TestData.REMOTE_VIDEO_TRACK.sid,
                    ),
                ),
            ),
        )

        advanceUntilIdle()
        wsFactory.ws.clearRequests()

        val remoteVideoPub = room.remoteParticipants.values.first()
            .videoTrackPublications.first()
            .first as RemoteTrackPublication

        assertEquals(RemoteTrackPublication.SubscriptionStatus.SUBSCRIBED, remoteVideoPub.subscriptionStatus)

        remoteVideoPub.setSubscribed(false)

        assertEquals(RemoteTrackPublication.SubscriptionStatus.UNSUBSCRIBED, remoteVideoPub.subscriptionStatus)

        val lastRequest = GravixcloudRtc.SignalRequest.newBuilder()
            .mergeFrom(wsFactory.ws.sentRequests.last().toPBByteString())
            .build()

        assertTrue(lastRequest.hasSubscription())
        assertFalse(lastRequest.subscription.subscribe)
        val trackList = lastRequest.subscription.trackSidsList
        assertEquals(1, trackList.size)
        assertEquals(TestData.REMOTE_VIDEO_TRACK.sid, trackList.first())
    }
}
