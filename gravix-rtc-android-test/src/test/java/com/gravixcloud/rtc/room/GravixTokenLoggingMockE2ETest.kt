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

import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.LoggingLevel
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX (security): with SDK logging on (VERBOSE), no token may reach the log: not a refresh token the
 * server sends (upstream logs every SignalResponse with its string fields), not a token an app put into
 * the url's query, not a bearer value. The rest of each line stays.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixTokenLoggingMockE2ETest : MockE2ETest() {

    private val lines = mutableListOf<String>()
    private var oldLevel = LoggingLevel.OFF
    private var oldLogger: GxLog.Logger? = null

    @Before
    fun captureLogs() {
        oldLevel = GravixRtc.loggingLevel
        oldLogger = GravixRtc.logger
        GravixRtc.loggingLevel = LoggingLevel.VERBOSE
        GravixRtc.logger = object : GxLog.Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                synchronized(lines) { lines.add(message + (t?.let { "\n" + it.stackTraceToString() } ?: "")) }
            }
        }
    }

    @After
    fun restoreLogs() {
        GravixRtc.loggingLevel = oldLevel
        GravixRtc.logger = oldLogger
    }

    private fun logged(): String = synchronized(lines) { lines.joinToString("\n") }

    @Test
    fun refreshTokenFromTheServerIsNotLogged() = runTest {
        connect()
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setRefreshToken(FAKE_REFRESHED_JWT).build(),
        )
        testScheduler.advanceUntilIdle()

        val log = logged()
        assertTrue("the response is still logged", log.contains("refresh_token"))
        assertFalse("refresh token logged:\n$log", log.contains(FAKE_REFRESHED_JWT))
        assertFalse("token signature logged", log.contains(FAKE_SIGNATURE))
    }

    @Test
    fun tokenInTheConnectUrlIsNotLogged() = runTest {
        val job = coroutineRule.scope.launch {
            room.connect(url = "${TestData.EXAMPLE_URL}?access_token=$FAKE_JOIN_JWT", token = FAKE_JOIN_JWT)
        }
        prepareSignal()
        job.join()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()

        val log = logged()
        assertTrue("the connect line is still logged:\n$log", log.contains("connecting to ws://www.example.com"))
        assertTrue("the query key stays, its value is redacted", log.contains("access_token=<redacted>"))
        assertFalse("join token logged:\n$log", log.contains(FAKE_JOIN_JWT))
        assertFalse("token signature logged", log.contains(FAKE_SIGNATURE))
    }

    companion object {
        // Obviously fake JWT-shaped strings (base64url of {"alg":"none"} / {"sub":"fake"}); never real tokens.
        private const val FAKE_SIGNATURE = "ZmFrZS1zaWduYXR1cmUtZm9yLXRlc3Rz"
        const val FAKE_JOIN_JWT = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJmYWtlLWpvaW4ifQ.$FAKE_SIGNATURE"
        const val FAKE_REFRESHED_JWT = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJmYWtlLXJlZnJlc2gifQ.$FAKE_SIGNATURE"
    }
}
