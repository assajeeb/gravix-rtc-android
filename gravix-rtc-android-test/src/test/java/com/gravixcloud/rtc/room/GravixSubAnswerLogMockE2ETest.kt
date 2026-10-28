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

import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.GravixPubLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** GRAVIX (0.3.1): the subscriber offer -> answer steps as [GravixPub] lines, with the ms since the offer arrived. */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixSubAnswerLogMockE2ETest : MockE2ETest() {

    private val lines = mutableListOf<String>()

    @Before
    fun capture() {
        GravixPubLog.sink = GravixLogSink { _, line -> synchronized(lines) { lines.add(line) } }
    }

    @After
    fun release() {
        GravixPubLog.sink = null
    }

    @Test
    fun theAnswerStepsAreLoggedWithTheirTimeSinceTheOffer() = runTest {
        connect()
        val sub = synchronized(lines) { lines.filter { it.contains(" pc=sub#") } }
        val offer = sub.filter { it.startsWith("[GravixPub] subOffer ") }
        assertEquals(sub.joinToString("\n"), 1, offer.size)
        assertTrue(offer[0], offer[0].contains(" received id="))
        val steps = sub.filter { it.startsWith("[GravixPub] subAnswer ") }
            .map { Regex("""subAnswer pc=sub#\d+ (\w+) \+\d+ms""").find(it)?.groupValues?.get(1) ?: "BAD: $it" }
        assertEquals(listOf("setRemoteDescriptionDone", "createAnswerDone", "setLocalDescriptionDone", "answerSent"), steps)
    }
}
