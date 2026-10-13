/*
 * Copyright 2023-2024 LiveKit, Inc.
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

import com.google.protobuf.ByteString
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.assert.assertIsClass
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockDataChannel
import com.gravixcloud.rtc.test.mock.MockPeerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import gravixcloud.GravixcloudModels.DataPacket
import gravixcloud.GravixcloudModels.UserPacket
import org.webrtc.DataChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDataMockE2ETest : MockE2ETest() {
    @Test
    fun dataReceivedEvent() = runTest {
        connect()
        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        val subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)

        val collector = EventCollector(room.events, coroutineRule.scope)
        val dataPacket = with(DataPacket.newBuilder()) {
            user = with(UserPacket.newBuilder()) {
                payload = ByteString.copyFrom("hello", Charsets.UTF_8)
                build()
            }
            build()
        }
        val dataBuffer = DataChannel.Buffer(
            ByteBuffer.wrap(dataPacket.toByteArray()),
            true,
        )

        subDataChannel.observer?.onMessage(dataBuffer)
        val events = collector.stopCollecting()

        assertEquals(1, events.size)
        assertIsClass(RoomEvent.DataReceived::class.java, events[0])

        val event = events[0] as RoomEvent.DataReceived
        assertEquals("hello", event.data.decodeToString())
    }
}
