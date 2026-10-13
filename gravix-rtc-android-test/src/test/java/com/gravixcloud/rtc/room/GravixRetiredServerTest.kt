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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retired Gravix deployment (the stock media server that ran before the Gravix fleet) as an app would have it
 * configured. Shared with the Room, GravixRoomService and token source tests so the old hosts are spelled in this
 * file only.
 */
internal const val RETIRED_RTC_URL = "wss://rtc.gravixcloud.com"
internal const val RETIRED_RTC_HOST = "rtc.gravixcloud.com"
internal const val RETIRED_BLR_URL = "https://rtc-blr1.gravixcloud.com"
internal const val RETIRED_TOKEN_URL = "https://token.gravixcloud.com/v1/token"
internal const val RETIRED_TOKEN_BASE_URL = "https://token.gravixcloud.com"

/**
 * GRAVIX(retired servers, 2026-10-12): connecting to the retired deployment fails fast with [RetiredServerException]
 * instead of joining a server that is not the Gravix fleet (unbilled, no regions, no failover).
 */
class GravixRetiredServerTest {

    @Test
    fun retiredHostsAreExactlyTheOldDeployment() {
        assertEquals(
            setOf("rtc.gravixcloud.com", "rtc-blr1.gravixcloud.com", "token.gravixcloud.com"),
            GRAVIX_RETIRED_HOSTS,
        )
    }

    @Test
    fun retiredHostsAreRecognisedInEveryForm() {
        for ((url, host) in listOf(
            RETIRED_RTC_URL to RETIRED_RTC_HOST,
            "ws://rtc.gravixcloud.com:7880/rtc?access_token=x" to RETIRED_RTC_HOST,
            "WSS://RTC.GravixCloud.COM" to RETIRED_RTC_HOST,
            "wss://rtc.gravixcloud.com./" to RETIRED_RTC_HOST,
            "rtc.gravixcloud.com" to RETIRED_RTC_HOST,
            "rtc.gravixcloud.com:443" to RETIRED_RTC_HOST,
            " wss://rtc.gravixcloud.com " to RETIRED_RTC_HOST,
            RETIRED_BLR_URL to "rtc-blr1.gravixcloud.com",
            "wss://rtc-blr1.gravixcloud.com:443" to "rtc-blr1.gravixcloud.com",
            RETIRED_TOKEN_URL to "token.gravixcloud.com",
            "http://user@token.gravixcloud.com/v1/upgrade" to "token.gravixcloud.com",
        )) {
            assertEquals(url, host, retiredGravixServerHost(url))
        }
    }

    @Test
    fun currentAndUnrelatedHostsAreNot() {
        for (url in listOf(
            "wss://sgp1.gravixcloud.com",
            "wss://fra1.gravixcloud.com:443/rtc",
            "wss://blr1.gravixcloud.com",
            "wss://x.rtc.gravixcloud.com",
            "wss://rtc.gravixcloud.com.example.net",
            "wss://gravixcloud.com.example.net",
            "wss://rtc.gravixcloud.run",
            "https://www.gravixcloud.com/docs",
            "ws://10.0.2.2:7880",
            "wss://example.com/rtc.gravixcloud.com",
            "",
            "not a url",
        )) {
            assertNull(url, retiredGravixServerHost(url))
        }
    }

    @Test
    fun theErrorNamesTheHostAndTheFix() {
        val error = RetiredServerException(RETIRED_RTC_HOST)
        assertEquals(RETIRED_RTC_HOST, error.host)
        assertEquals("retired Gravix server ($RETIRED_RTC_HOST): use the url from your token response", error.message)
        assertTrue("an invalid argument, catchable as one", error is IllegalArgumentException)
    }

    @Test
    fun requireNotRetiredPassesCurrentUrlsAndThrowsForRetiredOnes() {
        requireNotRetiredGravixServer("wss://sgp1.gravixcloud.com")
        val error = runCatching { requireNotRetiredGravixServer(RETIRED_BLR_URL) }.exceptionOrNull()
        assertTrue("$error", error is RetiredServerException)
        assertEquals("rtc-blr1.gravixcloud.com", (error as RetiredServerException).host)
    }
}
