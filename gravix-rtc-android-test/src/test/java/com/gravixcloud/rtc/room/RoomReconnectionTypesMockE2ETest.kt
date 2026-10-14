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

import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.assert.assertIsClassList
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.events.FlowCollector
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.flow
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(ParameterizedRobolectricTestRunner::class)
class RoomReconnectionTypesMockE2ETest(
    private val reconnectType: ReconnectType,
) : MockE2ETest() {

    companion object {
        // parameters are provided as arrays, allowing more than one parameter
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "Input: {0}")
        fun params() = listOf(
            ReconnectType.FORCE_SOFT_RECONNECT,
            ReconnectType.FORCE_FULL_RECONNECT,
        )
    }

    private fun reconnectWebsocket() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        val softReconnectParam = wsFactory.request.url
            .queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)
            ?.toIntOrNull()
            ?: 0

        if (softReconnectParam == 0) {
            simulateMessageFromServer(TestData.JOIN)
        } else {
            simulateMessageFromServer(TestData.RECONNECT)
        }
    }

    @Before
    fun setup() {
        room.setReconnectionType(reconnectType)
    }

    private fun expectedEventsForReconnectType(reconnectType: ReconnectType): List<Class<out RoomEvent>> {
        return when (reconnectType) {
            ReconnectType.FORCE_SOFT_RECONNECT -> {
                // GRAVIX(Phase 4): a resume emits RoomEvent.Resuming/Resumed (upstream: nothing); see
                // GravixResumeEventMockE2ETest. GRAVIX(0.1.1): and one RoomEvent.ReconnectAttempt per attempt
                // (GravixReconnectAttemptEventMockE2ETest)
                listOf(
                    RoomEvent.Resuming::class.java,
                    RoomEvent.ReconnectAttempt::class.java,
                    RoomEvent.Resumed::class.java,
                )
            }

            ReconnectType.FORCE_FULL_RECONNECT -> {
                listOf(
                    RoomEvent.ReconnectAttempt::class.java, // GRAVIX(0.1.1)
                    RoomEvent.Reconnecting::class.java,
                    RoomEvent.Reconnected::class.java,
                )
            }

            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    private fun expectedStatesForReconnectType(reconnectType: ReconnectType): List<Room.State> {
        return when (reconnectType) {
            ReconnectType.FORCE_SOFT_RECONNECT -> {
                listOf(Room.State.CONNECTED)
            }

            ReconnectType.FORCE_FULL_RECONNECT -> {
                listOf(
                    Room.State.CONNECTED,
                    Room.State.RECONNECTING,
                    Room.State.CONNECTED,
                )
            }

            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    @Test
    fun reconnectFromPeerConnectionDisconnect() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val stateCollector = FlowCollector(room::state.flow, coroutineRule.scope)
        disconnectPeerConnection()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val events = eventCollector.stopCollecting()
        val states = stateCollector.stopCollecting()

        assertIsClassList(
            expectedEventsForReconnectType(reconnectType),
            events,
        )

        assertEquals(
            expectedStatesForReconnectType(reconnectType),
            states,
        )
    }

    @Test
    fun reconnectFromWebSocketFailure() = runTest {
        connect()

        val eventCollector = EventCollector(room.events, coroutineRule.scope)
        val stateCollector = FlowCollector(room::state.flow, coroutineRule.scope)
        wsFactory.ws.cancel()
        // Wait so that the reconnect job properly starts first.
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()

        testScheduler.advanceUntilIdle()
        val events = eventCollector.stopCollecting()
        val states = stateCollector.stopCollecting()

        assertIsClassList(
            expectedEventsForReconnectType(reconnectType),
            events,
        )

        assertEquals(
            expectedStatesForReconnectType(reconnectType),
            states,
        )
    }
}
