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

package com.gravixcloud.rtc.room

import android.net.Network
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.events.EventCollector
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX: RoomEvent.ReconnectAttempt (the Flutter SDK 0.4.18's RoomReconnectAttemptEvent): one per reconnect attempt,
 * before its dial, with what started the recovery. Until now an app (and a field log) saw only full rejoins
 * (Reconnecting) and, since 0.1.0, that a resume started (Resuming) -- not each attempt, nor why. Observation only:
 * emitted with every switch off too.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixReconnectAttemptEventMockE2ETest : MockE2ETest() {

    private fun TestScope.dialAfter(previous: MockWebSocket, maxMs: Long = 5_000): MockWebSocket? {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < maxMs) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(1)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        return null
    }

    private fun answerResume(ws: MockWebSocket) {
        wsFactory.listener.onOpen(ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(TestData.RECONNECT)
        connectPeerConnection()
    }

    private fun attempts(events: List<RoomEvent>) =
        events.filterIsInstance<RoomEvent.ReconnectAttempt>().map { "${it.mode} ${it.cause} ${it.attempt} ${it.afterNetworkChange}" }

    @Test
    fun aFailedPrimaryIsOneResumeAttempt() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before) ?: throw AssertionError("no dial")
        answerResume(resume)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("RESUME peerConnectionFailed 1 false"), attempts(events.stopCollecting()))
    }

    @Test
    fun aClosedSignalConnectionSaysSignal() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        before.close(1006, "gone")
        val resume = dialAfter(before) ?: throw AssertionError("no dial")
        answerResume(resume)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("RESUME signal 1 false"), attempts(events.stopCollecting()))
    }

    @Test
    fun theNetworkComingBackSaysSoAndThatTheNetworkChanged() = runTest {
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        val network = Mockito.mock(Network::class.java)
        val callbacks = component.networkCallbackRegistry().networkCallbacks
        callbacks.forEach { it.onLost(network) }
        callbacks.forEach { it.onAvailable(network) }
        val resume = dialAfter(before) ?: throw AssertionError("no dial")
        answerResume(resume)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("RESUME networkAvailable 1 true"), attempts(events.stopCollecting()))
    }

    @Test
    fun aForcedFullReconnectIsAFullAttempt() = runTest {
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        disconnectPeerConnection()
        dialAfter(before) ?: throw AssertionError("no dial")
        testScheduler.runCurrent()
        assertEquals(listOf("FULL peerConnectionFailed 1 false"), attempts(events.stopCollecting()))
        room.disconnect()
    }

    @Test
    fun withEverySwitchOffTheAttemptsAreStillReported() = runTest {
        room.gravixRecovery = GravixRecoveryOptions.UPSTREAM
        connect()
        val events = EventCollector(room.events, coroutineRule.scope)
        val before = wsFactory.ws
        disconnectPeerConnection()
        val resume = dialAfter(before) ?: throw AssertionError("no dial")
        resume.cancel()
        dialAfter(resume) ?: throw AssertionError("no second dial")
        testScheduler.runCurrent()
        assertEquals(
            listOf("RESUME peerConnectionFailed 1 false", "FULL reconnectRetry 2 false"),
            attempts(events.stopCollecting()),
        )
        room.disconnect()
    }
}
