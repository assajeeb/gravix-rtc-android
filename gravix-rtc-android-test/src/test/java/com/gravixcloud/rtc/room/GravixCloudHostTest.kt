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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI

/**
 * GRAVIX: region discovery/failover is gated on the server host. Gravix Cloud hosts must enable it and
 * the upstream vendor's cloud hosts must not (owner decision 2026-10-11). Runs without the
 * `setRegionUrlProviderTesting` bypass that RegionUrlProviderTest uses.
 */
class GravixCloudHostTest {

    @Before
    fun noTestingBypass() {
        setRegionUrlProviderTesting(false)
    }

    @Test
    fun gravixCloudHostsAreRecognised() {
        for (url in listOf(
            "wss://sgp1.gravixcloud.com",
            "wss://blr1.gravixcloud.com:443/rtc",
            "https://my-app.gravixcloud.run",
            "ws://a.b.gravixcloud.com",
        )) {
            assertTrue(url, URI(url).isGravixCloud())
        }
    }

    @Test
    fun otherHostsAreNot() {
        for (url in listOf(
            "wss://project.livekit.cloud",
            "wss://project.livekit.run",
            "ws://10.0.2.2:7880",
            "ws://127.0.0.1:7880",
            "wss://gravixcloud.com.example.net",
            "wss://notgravixcloud.com",
            "wss://example.com",
        )) {
            assertFalse(url, URI(url).isGravixCloud())
        }
    }

    @Test
    fun suffixListIsExactlyGravix() {
        assertTrue(GRAVIX_CLOUD_HOST_SUFFIXES == listOf(".gravixcloud.com", ".gravixcloud.run"))
    }
}
