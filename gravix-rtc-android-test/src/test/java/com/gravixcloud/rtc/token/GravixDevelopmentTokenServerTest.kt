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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GRAVIX: upstream's development token source silently defaulted to the upstream vendor's hosted sandbox
 * service, so an app that forgot `baseUrl` would send requests to a third-party cloud. Gravix Cloud hosts
 * no such service: the base url must be given explicitly.
 */
class GravixDevelopmentTokenServerTest {

    @Test
    fun noImplicitHostedDefault() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            TokenSource.fromDevelopmentTokenServer("server-id")
        }
        assertTrue(e.message!!, e.message!!.contains("baseUrl"))
    }

    @Test
    fun explicitBaseUrlIsUsed() {
        val source = TokenSource.fromDevelopmentTokenServer(
            "server-id",
            DevelopmentTokenServerOptions(baseUrl = "http://10.0.2.2:8080"),
        ) as DevelopmentTokenSource
        assertEquals("http://10.0.2.2:8080/api/v2/sandbox/connection-details", source.url.toString())
        assertEquals("server-id", source.headers["X-Sandbox-ID"])
    }
}
