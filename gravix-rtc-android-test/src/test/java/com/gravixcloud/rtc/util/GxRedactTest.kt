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

package com.gravixcloud.rtc.util

import com.gravixcloud.rtc.GravixRtc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** GRAVIX (security): the redaction rules, and the switch (off = upstream: messages verbatim). */
class GxRedactTest {

    private val jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJmYWtlIn0.ZmFrZS1zaWc"

    @After
    fun restore() {
        GxLog.redactSecrets = true
        GravixRtc.loggingLevel = LoggingLevel.OFF
        GravixRtc.logger = GxLog.defaultLogger
    }

    @Test
    fun queryTokensKeepTheRestOfTheLine() {
        assertEquals(
            "connecting to wss://sgp1.gravixcloud.com/rtc?access_token=<redacted>&protocol=13&sdk=android",
            GxRedact.redact("connecting to wss://sgp1.gravixcloud.com/rtc?access_token=$jwt&protocol=13&sdk=android"),
        )
        assertEquals("GET https://x.test/v1/t?room=a&token=<redacted>", GxRedact.redact("GET https://x.test/v1/t?room=a&token=opaque-123"))
        assertEquals("u?ACCESS_TOKEN=<redacted> end", GxRedact.redact("u?ACCESS_TOKEN=abc end"))
    }

    @Test
    fun bearerValues() {
        assertEquals("Authorization: Bearer <redacted>", GxRedact.redact("Authorization: Bearer $jwt"))
        assertEquals("authorization: bearer <redacted>, x", GxRedact.redact("authorization: bearer opaque.value_1=, x"))
    }

    @Test
    fun jwtShapesAnywhere() {
        assertEquals("refresh_token: \"<redacted>\"\n", GxRedact.redact("refresh_token: \"$jwt\"\n"))
        assertEquals("a <redacted> b", GxRedact.redact("a eyJhbGciOiJub25lIn0.eyJzdWIiOiJmYWtlIn0. b"))
    }

    @Test
    fun ordinaryLinesAreUnchanged() {
        for (s in listOf(
            "",
            "connecting to ws://10.0.2.2:7880/rtc?protocol=13&auto_subscribe=1",
            "next region: RegionInfo(region=sgp1, url=wss://sgp1.gravixcloud.com, distance=0)",
            "track sid TR_AMYCFqaDA8KyWN published, tokens left 3",
        )) {
            assertEquals(s, GxRedact.redact(s))
        }
    }

    @Test
    fun gxLogRedactsUnlessSwitchedOff() {
        val got = mutableListOf<String>()
        GravixRtc.loggingLevel = LoggingLevel.VERBOSE
        GravixRtc.logger = object : GxLog.Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                got.add(message)
            }
        }
        GxLog.i { "token=$jwt" }
        GxLog.redactSecrets = false
        GxLog.i { "token=$jwt" }
        assertEquals(listOf("token=<redacted>", "token=$jwt"), got)
    }
}
