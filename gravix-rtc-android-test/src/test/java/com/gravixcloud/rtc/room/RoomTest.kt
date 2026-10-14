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

import android.content.Context
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.gravixcloud.rtc.audio.NoAudioHandler
import com.gravixcloud.rtc.audio.NoAudioRecordPrewarmer
import com.gravixcloud.rtc.audio.NoopCommunicationWorkaround
import com.gravixcloud.rtc.e2ee.E2EEManager
import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.events.EventListenable
import com.gravixcloud.rtc.events.ParticipantEvent
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.memory.CloseableManager
import com.gravixcloud.rtc.room.datastream.incoming.IncomingDataStreamManagerImpl
import com.gravixcloud.rtc.room.network.NetworkCallbackManagerImpl
import com.gravixcloud.rtc.room.participant.LocalParticipant
import com.gravixcloud.rtc.test.assert.assertIsClassList
import com.gravixcloud.rtc.test.coroutines.TestCoroutineRule
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockAudioDeviceModule
import com.gravixcloud.rtc.test.mock.MockAudioProcessingController
import com.gravixcloud.rtc.test.mock.MockEglBase
import com.gravixcloud.rtc.test.mock.MockGxObjects
import com.gravixcloud.rtc.test.mock.MockNetworkCallbackRegistry
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.room.participant.TestRemoteParticipantFactory
import com.gravixcloud.rtc.test.mock.room.util.MockConnectionWarmer
import gravixcloud.GravixcloudRtc.JoinResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.webrtc.EglBase

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomTest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    lateinit var context: Context

    @Mock
    lateinit var rtcEngine: RTCEngine

    @Mock
    lateinit var e2EEManagerFactory: E2EEManager.Factory

    @Mock
    lateinit var regionUrlProviderFactory: RegionUrlProvider.Factory

    lateinit var networkCallbackRegistry: MockNetworkCallbackRegistry

    var eglBase: EglBase = MockEglBase()

    val localParticipantFactory = object : LocalParticipant.Factory {
        override fun create(dynacast: Boolean): LocalParticipant {
            return Mockito.mock(LocalParticipant::class.java)
                .apply {
                    whenever(this.events).thenReturn(
                        object : EventListenable<ParticipantEvent> {
                            override val events: SharedFlow<ParticipantEvent> = MutableSharedFlow()
                        },
                    )
                }
        }
    }

    lateinit var room: Room

    // GRAVIX: kept so the retired-server tests can assert that nothing was warmed
    val connectionWarmer = MockConnectionWarmer()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        networkCallbackRegistry = MockNetworkCallbackRegistry()
        room = Room(
            context = context,
            engine = rtcEngine,
            eglBase = eglBase,
            localParticipantFactory = localParticipantFactory,
            defaultsManager = DefaultsManager(),
            defaultDispatcher = coroutineRule.dispatcher,
            ioDispatcher = coroutineRule.dispatcher,
            audioHandler = NoAudioHandler(),
            closeableManager = CloseableManager(),
            e2EEManagerFactory = e2EEManagerFactory,
            communicationWorkaround = NoopCommunicationWorkaround(),
            audioProcessingController = MockAudioProcessingController(),
            gxObjects = MockGxObjects.get(),
            networkCallbackManagerFactory = { networkCallback: NetworkCallback ->
                NetworkCallbackManagerImpl(networkCallback, networkCallbackRegistry)
            },
            audioDeviceModule = MockAudioDeviceModule(),
            regionUrlProviderFactory = regionUrlProviderFactory,
            connectionWarmer = connectionWarmer,
            audioRecordPrewarmer = NoAudioRecordPrewarmer(),
            incomingDataStreamManager = IncomingDataStreamManagerImpl(),
            rpcClientManager = com.gravixcloud.rtc.room.rpc.RpcClientManager(
                engine = rtcEngine,
                outgoingDataStreamManager = Mockito.mock(com.gravixcloud.rtc.room.datastream.outgoing.OutgoingDataStreamManager::class.java),
            ),
            rpcServerManager = com.gravixcloud.rtc.room.rpc.RpcServerManager(
                engine = rtcEngine,
                outgoingDataStreamManager = Mockito.mock(com.gravixcloud.rtc.room.datastream.outgoing.OutgoingDataStreamManager::class.java),
            ),
            remoteParticipantFactory = TestRemoteParticipantFactory(
                rtcEngine = rtcEngine,
                ioDispatcher = coroutineRule.dispatcher,
                defaultDispatcher = coroutineRule.dispatcher,
            ),
        )
    }

    suspend fun connect(joinResponse: JoinResponse = TestData.JOIN.join) {
        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull(), anyOrNull()) }
                .doSuspendableAnswer {
                    room.onJoinResponse(joinResponse)
                    joinResponse
                }
        }
        rtcEngine.stub {
            onBlocking { rtcEngine.client }
                .doReturn(Mockito.mock(SignalClient::class.java))
        }

        room.connect(
            url = TestData.EXAMPLE_URL,
            token = "",
        )
    }

    @Test
    fun connectTest() = runTest {
        connect()
        val roomInfo = TestData.JOIN.join.room

        assertEquals(roomInfo.name, room.name)
        assertEquals(Room.Sid(roomInfo.sid), room.sid)
        assertEquals(roomInfo.metadata, room.metadata)
        assertEquals(roomInfo.activeRecording, room.isRecording)
    }

    @Test
    fun roomUpdate() = runTest {
        connect()
        val update = TestData.ROOM_UPDATE.roomUpdate.room

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onRoomUpdate(update)
        val events = eventCollector.stopCollecting()

        assertEquals(update.sid, room.sid?.sid)
        assertEquals(update.metadata, room.metadata)
        assertEquals(update.activeRecording, room.isRecording)

        assertIsClassList(
            listOf(
                RoomEvent.RoomMetadataChanged::class.java,
                RoomEvent.RecordingStatusChanged::class.java,
            ),
            events,
        )
    }

    @Test
    fun onConnectionAvailableWillReconnect() = runTest {
        connect()

        val network = Mockito.mock(Network::class.java)

        networkCallbackRegistry.networkCallbacks.forEach { callback ->
            callback.onLost(network)
        }
        networkCallbackRegistry.networkCallbacks.forEach { callback ->
            callback.onAvailable(network)
        }

        // GRAVIX(0.1.1): the engine's reconnect with its cause (RoomEvent.ReconnectAttempt)
        Mockito.verify(rtcEngine).gravixReconnect(RTCEngine.CAUSE_NETWORK)
    }

    @Test
    fun onServerLeave() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.onEngineDisconnected(DisconnectReason.SERVER_SHUTDOWN)
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(DisconnectReason.SERVER_SHUTDOWN, (events[0] as RoomEvent.Disconnected).reason)

        // Verify Room state
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertNull(room.sid)
        assertNull(room.metadata)
        assertNull(room.name)
        assertFalse(room.isRecording)
    }

    @Test
    fun onDisconnect() = runTest {
        connect()
        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        room.disconnect()
        val events = eventCollector.stopCollecting()

        assertEquals(1, events.size)
        assertEquals(true, events[0] is RoomEvent.Disconnected)
        assertEquals(DisconnectReason.CLIENT_INITIATED, (events[0] as RoomEvent.Disconnected).reason)

        // Verify Room state
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertNull(room.sid)
        assertNull(room.metadata)
        assertNull(room.name)
        assertFalse(room.isRecording)
    }

    @Test
    fun getSidSuspendsUntilPopulated() = runTest {
        val job = async {
            room.getSid()
        }

        assertFalse(job.isCompleted)
        connect()
        assertFalse(job.isCompleted)
        val update = TestData.ROOM_UPDATE.roomUpdate.room
        room.onRoomUpdate(update)

        advanceUntilIdle()
        assertTrue(job.isCompleted)
        val sid = job.await()

        assertEquals(update.sid, sid.sid)
    }

    @Test
    fun connectFailureResetsStateToDisconnected() = runTest {
        val connectException = RuntimeException("Connection failed")
        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull(), anyOrNull()) }
                .doSuspendableAnswer {
                    throw connectException
                }
        }
        rtcEngine.stub {
            onBlocking { rtcEngine.client }
                .doReturn(Mockito.mock(SignalClient::class.java))
        }

        val eventCollector = EventCollector(room.events, coroutineRule.scope)

        var caughtException: Throwable? = null
        try {
            room.connect(
                url = TestData.EXAMPLE_URL,
                token = "",
            )
        } catch (e: Throwable) {
            caughtException = e
        }

        val events = eventCollector.stopCollecting()

        // Verify exception was thrown (check message since coroutines may wrap exceptions)
        assertEquals("Connection failed", caughtException?.message)

        // Verify room state is reset to DISCONNECTED
        assertEquals(Room.State.DISCONNECTED, room.state)

        // Verify Disconnected event was posted with JOIN_FAILURE reason
        val disconnectedEvents = events.filterIsInstance<RoomEvent.Disconnected>()
        assertEquals(1, disconnectedEvents.size)
        assertEquals(DisconnectReason.JOIN_FAILURE, disconnectedEvents[0].reason)
    }

    @Test
    fun connectRetryAfterFailureSucceeds() = runTest {
        val connectException = RuntimeException("Connection failed")
        var shouldFail = true

        rtcEngine.stub {
            onBlocking { rtcEngine.join(any(), any(), anyOrNull(), anyOrNull()) }
                .doSuspendableAnswer {
                    if (shouldFail) {
                        throw connectException
                    }
                    room.onJoinResponse(TestData.JOIN.join)
                    TestData.JOIN.join
                }
        }
        rtcEngine.stub {
            onBlocking { rtcEngine.client }
                .doReturn(Mockito.mock(SignalClient::class.java))
        }

        // First connect attempt fails
        try {
            room.connect(
                url = TestData.EXAMPLE_URL,
                token = "",
            )
        } catch (e: Throwable) {
            // Expected
        }

        // Verify room is in DISCONNECTED state after failure
        assertEquals(Room.State.DISCONNECTED, room.state)

        // Second connect attempt should succeed
        shouldFail = false
        room.connect(
            url = TestData.EXAMPLE_URL,
            token = "",
        )

        // Verify room connected successfully
        val roomInfo = TestData.JOIN.join.room
        assertEquals(roomInfo.name, room.name)
        assertEquals(Room.Sid(roomInfo.sid), room.sid)
    }

    // GRAVIX(retired servers, 2026-10-12): the retired deployment is refused before the room does anything: no state
    // change, no engine call, no region lookup, no warm-up request.
    @Test
    fun connectToARetiredGravixServerFailsBeforeAnyWork() = runTest {
        Mockito.clearInvocations(rtcEngine, regionUrlProviderFactory)
        val eventCollector = EventCollector(room.events, coroutineRule.scope)

        val error = runCatching { room.connect(url = RETIRED_RTC_URL, token = "") }.exceptionOrNull()

        assertTrue("$error", error is RetiredServerException)
        assertEquals(RETIRED_RTC_HOST, (error as RetiredServerException).host)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertTrue(eventCollector.stopCollecting().isEmpty())
        Mockito.verifyNoInteractions(rtcEngine, regionUrlProviderFactory)
        assertTrue(connectionWarmer.fetchedUrls.isEmpty())
    }

    @Test
    fun prepareConnectionToARetiredGravixServerFailsWithoutContactingIt() = runTest {
        Mockito.clearInvocations(rtcEngine, regionUrlProviderFactory)

        val withToken = runCatching { room.prepareConnection(RETIRED_RTC_URL, "token") }.exceptionOrNull()
        val withoutToken = runCatching { room.prepareConnection(RETIRED_BLR_URL) }.exceptionOrNull()

        assertTrue("$withToken", withToken is RetiredServerException)
        assertTrue("$withoutToken", withoutToken is RetiredServerException)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertTrue(connectionWarmer.fetchedUrls.toString(), connectionWarmer.fetchedUrls.isEmpty())
        Mockito.verifyNoInteractions(regionUrlProviderFactory)
    }

    @Test
    fun prepareConnectionStillWarmsACurrentServer() = runTest {
        room.prepareConnection(TestData.EXAMPLE_URL)
        assertEquals(listOf(TestData.EXAMPLE_URL), connectionWarmer.fetchedUrls)
    }
}
