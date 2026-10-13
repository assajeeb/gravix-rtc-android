/*
 * Copyright 2025 LiveKit, Inc.
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

package com.gravixcloud.rtc.e2ee

import com.google.protobuf.ByteString
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.room.RTCEngine
import com.gravixcloud.rtc.room.participant.Participant
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockDataChannel
import com.gravixcloud.rtc.test.mock.MockPeerConnection
import com.gravixcloud.rtc.test.mock.e2ee.NoopKeyProvider
import com.gravixcloud.rtc.test.mock.e2ee.ReversingDataPacketCryptorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudModels.DataPacket
import gravixcloud.GravixcloudRtc
import org.webrtc.DataChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class DataPacketCryptorMockE2ETest : MockE2ETest() {

    lateinit var pubDataChannel: MockDataChannel
    lateinit var subDataChannel: MockDataChannel

    override suspend fun connect(joinResponse: GravixcloudRtc.SignalResponse) {
        super.connect(joinResponse)

        val pubPeerConnection = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        pubDataChannel = pubPeerConnection.dataChannels[RTCEngine.RELIABLE_DATA_CHANNEL_LABEL] as MockDataChannel

        val subPeerConnection = component.rtcEngine().getSubscriberPeerConnection() as MockPeerConnection
        subDataChannel = MockDataChannel(RTCEngine.RELIABLE_DATA_CHANNEL_LABEL)
        subPeerConnection.observer?.onDataChannel(subDataChannel)
    }

    @Test
    fun sendsDataEncrypted() = runTest {
        room.e2eeOptions = E2EEOptions(
            keyProvider = NoopKeyProvider(),
        )

        connect()

        room.e2eeManager?.dataChannelEncryptionEnabled = true

        // The mock data cryptor used just reverses the bytes.
        val data = "1234".toByteArray()
        assertTrue(room.localParticipant.publishData(data).isSuccess)

        assertEquals(1, pubDataChannel.sentBuffers.size)

        val encryptedPacket = DataPacket.parseFrom(ByteString.copyFrom(pubDataChannel.sentBuffers[0].data))
        assertTrue(encryptedPacket.hasEncryptedPacket())

        val dataCryptor = ReversingDataPacketCryptorManager()
        val decryptedBytes = dataCryptor.decrypt(Participant.Identity(""), encryptedPacket.encryptedPacket.toSdkType())
        val payload = GravixcloudModels.EncryptedPacketPayload.parseFrom(decryptedBytes)

        assertTrue(data.contentEquals(payload.user.payload.toByteArray()))
    }

    @Test
    fun receivesDataDecrypted() = runTest {
        room.e2eeOptions = E2EEOptions(
            keyProvider = NoopKeyProvider(),
        )

        connect()

        // The mock data cryptor used just reverses the bytes.
        val data = "1234".toByteArray()
        assertTrue(room.localParticipant.publishData(data).isSuccess)

        val dataCryptor = ReversingDataPacketCryptorManager()
        val encryptedPacketPayload = with(GravixcloudModels.EncryptedPacketPayload.newBuilder()) {
            user = with(GravixcloudModels.UserPacket.newBuilder()) {
                payload = ByteString.copyFrom(data)
                build()
            }
            build()
        }
        val encrypted = dataCryptor.encrypt(Participant.Identity(""), 0, encryptedPacketPayload.toByteArray())!!
        val dataPacket = with(DataPacket.newBuilder()) {
            encryptedPacket = with(GravixcloudModels.EncryptedPacket.newBuilder()) {
                encryptedValue = ByteString.copyFrom(encrypted.payload)
                iv = ByteString.copyFrom(encrypted.iv)
                keyIndex = encrypted.keyIndex
                encryptionType = GravixcloudModels.Encryption.Type.CUSTOM
                build()
            }
            build()
        }

        val eventCollector = EventCollector(room.events, coroutineRule.scope)

        subDataChannel.simulateBufferReceived(DataChannel.Buffer(ByteBuffer.wrap(dataPacket.toByteArray()), true))

        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        val event = events[0] as RoomEvent.DataReceived

        assertTrue(data.contentEquals(event.data))
        assertEquals(GravixcloudModels.Encryption.Type.CUSTOM, event.encryptionType)
    }
}
