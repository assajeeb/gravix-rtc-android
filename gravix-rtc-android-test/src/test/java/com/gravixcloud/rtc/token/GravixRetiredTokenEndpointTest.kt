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

package com.gravixcloud.rtc.token

import com.gravixcloud.rtc.room.RETIRED_RTC_URL
import com.gravixcloud.rtc.room.RETIRED_TOKEN_BASE_URL
import com.gravixcloud.rtc.room.RETIRED_TOKEN_URL
import com.gravixcloud.rtc.room.RetiredServerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/**
 * GRAVIX(retired servers, 2026-10-12): a token source pointed at the retired deployment's token endpoint is refused
 * when it is created (the same place a missing development baseUrl is refused), so no request is ever sent to it.
 * Creation does no I/O, so these tests cannot reach the network even without the guard.
 */
class GravixRetiredTokenEndpointTest {

    private fun refusal(block: () -> Unit): RetiredServerException {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("$error", error is RetiredServerException)
        return error as RetiredServerException
    }

    @Test
    fun endpointAtTheRetiredTokenHostIsRefused() {
        assertEquals("token.gravixcloud.com", refusal { TokenSource.fromEndpoint(RETIRED_TOKEN_URL) }.host)
        assertEquals("token.gravixcloud.com", refusal { TokenSource.fromEndpoint(URL(RETIRED_TOKEN_URL)) }.host)
        refusal { TokenSource.fromEndpoint(RETIRED_RTC_URL.replace("wss://", "https://") + "/token") }
    }

    @Test
    fun developmentServerAtARetiredHostIsRefused() {
        refusal {
            TokenSource.fromDevelopmentTokenServer("server-id", DevelopmentTokenServerOptions(baseUrl = RETIRED_TOKEN_BASE_URL))
        }
    }

    @Test
    fun anyOtherEndpointIsAccepted() {
        TokenSource.fromEndpoint("https://api.example.com/rtc/token")
        TokenSource.fromEndpoint(URL("http://10.0.2.2:8080/v1/token"))
    }
}
