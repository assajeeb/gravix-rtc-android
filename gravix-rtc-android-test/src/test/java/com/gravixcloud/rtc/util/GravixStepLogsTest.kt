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

import com.gravixcloud.rtc.join.GravixJoinLog
import com.gravixcloud.rtc.room.GravixLogSecretsMockE2ETest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.IceCandidate

/** GRAVIX (0.1.2): the step-log helpers (m-lines, safe errors, ids, hosts) and the level / sink switches. */
class GravixStepLogsTest {

    @After
    fun restore() {
        GravixPubLog.level = LoggingLevel.INFO
        GravixPubLog.sink = null
        GravixJoinLog.level = LoggingLevel.INFO
        GravixJoinLog.sink = null
    }

    @Test
    fun mlinesListKindMidDirectionInOrder() {
        val sdp = GravixLogSecretsMockE2ETest.sdp("zzufragzz", "zzpwdzz", "sendonly")
        assertEquals("audio:0/sendonly,application:1", GravixStepLogs.mlines(sdp))
        assertEquals("-", GravixStepLogs.mlines("local_offer"))
        assertEquals("-", GravixStepLogs.mlines(null))
        assertEquals("video:2/rejected", GravixStepLogs.mlines("m=video 0 UDP/TLS/RTP/SAVPF 96\r\na=mid:2\r\na=inactive\r\n"))
        assertEquals("audio:?/sendrecv", GravixStepLogs.mlines("m=audio 9 UDP/TLS/RTP/SAVPF 111\n"))
    }

    @Test
    fun describeNeverHasTheBody() {
        val sdp = GravixLogSecretsMockE2ETest.sdp("zzufragzz", "zzpwdzz", "recvonly")
        val d = GravixStepLogs.describe("answer", sdp)
        assertEquals("answer mlines=audio:0/recvonly,application:1", d)
    }

    @Test
    fun safeErrorCutsTheSdpAndRedactsSecrets() {
        val sdp = GravixLogSecretsMockE2ETest.sdp("zzufragzz", "zzpwdzz", "sendonly")
        val e = GravixStepLogs.safeError("Failed to set local offer sdp: The order of m-lines in subsequent offer doesn't match.\n$sdp")
        assertTrue(e, e.startsWith("Failed to set local offer sdp: The order of m-lines"))
        assertTrue(e, e.endsWith("<sdp omitted>"))
        assertFalse(e, e.contains("zzpwdzz") || e.contains("zzufragzz") || e.contains("AA:BB"))
        assertEquals("x ice-pwd:<redacted> y", GravixStepLogs.safeError("x ice-pwd:abc y"))
        assertEquals(303, GravixStepLogs.safeError("z".repeat(500)).length)
    }

    @Test
    fun reasonsAreShort() {
        assertEquals(
            "m-line-order",
            GravixStepLogs.reason("Failed to set local offer sdp: The order of m-lines in subsequent offer doesn't match order from previous offer/answer.")
        )
        assertEquals("wrong-state", GravixStepLogs.reason("Called in wrong state: stable"))
        assertEquals("unknown", GravixStepLogs.reason(null))
    }

    @Test
    fun candidateSummaryHasNoUfragNoAddress() {
        val c = IceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.20 54321 typ host generation 0 ufrag zzufragzz network-id 1")
        assertEquals("mid=0 typ=host proto=udp", GravixStepLogs.candidate(c))
    }

    @Test
    fun shortIdIsFnv1aLikeTheFlutterSdk() {
        // FNV-1a 32 bit test vectors ("" and "a"), as the Flutter SDK's GravixJoinLog.shortId
        assertEquals("u811c9dc5", GravixJoinLog.shortId(""))
        assertEquals("ue40c292c", GravixJoinLog.shortId("a"))
        assertEquals(GravixJoinLog.shortId("user-42"), GravixJoinLog.shortId("user-42"))
    }

    @Test
    fun hostOnlyNeverPathOrQuery() {
        assertEquals("sgp1.gravixcloud.com", GravixJoinLog.host("wss://sgp1.gravixcloud.com/rtc?access_token=x&room=y"))
        assertEquals("10.0.2.2:7880", GravixJoinLog.host("ws://10.0.2.2:7880"))
        assertEquals("-", GravixJoinLog.host(null))
        assertEquals("?", GravixJoinLog.host("not a url"))
    }

    @Test
    fun detailsAreScrubbed() {
        // JWT-shaped (alg none, sub fake), joined at runtime so no secret scanner reads a token in the source
        val jwt = listOf("eyJhbGciOiJub25lIn0", "eyJzdWIiOiJmYWtlIn0", "ZmFrZS1zaWc").joinToString(".")
        val got = mutableListOf<String>()
        GravixJoinLog.sink = GravixLogSink { _, line -> got.add(line) }
        GravixJoinLog.step("tokenOk", "r1\u0000user-42\u0000false", 312, "via=fetched $jwt secret=abc")
        assertEquals(
            "[GravixJoin] tokenOk key=r1|${GravixJoinLog.shortId("user-42")}|pub=false +312ms via=fetched <redacted> secret=<redacted>",
            got.single(),
        )
        assertFalse(got.single().contains("user-42"))
    }

    @Test
    fun levelsAndOff() {
        val got = mutableListOf<String>()
        GravixPubLog.sink = GravixLogSink { level, line -> got.add("$level $line") }
        GravixPubLog.step("offerCreate", "pub#1", "id=1")
        GravixPubLog.step("offerRequest", "pub#1", null, LoggingLevel.VERBOSE)
        GravixPubLog.level = LoggingLevel.VERBOSE
        GravixPubLog.step("offerRequest", "pub#1", null, LoggingLevel.VERBOSE)
        GravixPubLog.level = LoggingLevel.OFF
        GravixPubLog.step("offerCreate", "pub#1", "id=2")
        assertEquals(listOf("INFO [GravixPub] offerCreate pc=pub#1 id=1", "VERBOSE [GravixPub] offerRequest pc=pub#1"), got)
    }

    @Test
    fun defaultGoesToTheSdkLoggerWhateverItsLevel() {
        val got = mutableListOf<String>()
        val oldLogger = GxLog.logger
        val oldLevel = GxLog.loggingLevel
        try {
            GxLog.loggingLevel = LoggingLevel.OFF
            GxLog.logger = object : GxLog.Logger {
                override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                    got.add(message)
                }
            }
            GravixPubLog.step("offerSend", "pub#3", "id=4")
            assertEquals(listOf("[GravixPub] offerSend pc=pub#3 id=4"), got)
        } finally {
            GxLog.logger = oldLogger
            GxLog.loggingLevel = oldLevel
        }
    }

    @Test
    fun gxRedactRemovesSdpSecrets() {
        assertEquals("a=ice-pwd:<redacted>\r\na=mid:0", GxRedact.redact("a=ice-pwd:zzpwdzz\r\na=mid:0"))
        assertEquals("a=fingerprint:<redacted>", GxRedact.redact("a=fingerprint:sha-256 AA:BB:CC"))
        assertEquals("typ host generation 0 ufrag <redacted> network-id 1", GxRedact.redact("typ host generation 0 ufrag zzufragzz network-id 1"))
    }
}
