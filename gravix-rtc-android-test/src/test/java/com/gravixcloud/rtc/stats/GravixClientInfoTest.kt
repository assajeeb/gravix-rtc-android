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

package com.gravixcloud.rtc.stats

import com.gravixcloud.rtc.Version
import gravixcloud.GravixcloudModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX: the ClientInfo sent on /rtc keeps upstream's wire identity. `sdk` stays ANDROID, and `version`
 * is the upstream engine version (gradle.properties UPSTREAM_ENGINE_VERSION), not the Gravix SDK version,
 * so server rules keyed on the Android SDK version see the engine that is actually running.
 */
@RunWith(RobolectricTestRunner::class)
class GravixClientInfoTest {

    @Test
    fun reportsAndroidSdkAndUpstreamEngineVersion() {
        val info = getClientInfo()
        assertEquals(GravixcloudModels.ClientInfo.SDK.ANDROID, info.sdk)
        assertEquals(Version.ENGINE_VERSION, info.version)
        assertEquals("android", info.os)
    }

    @Test
    fun gravixVersionIsNotWhatTheServerSees() {
        // The two versions are different numbering schemes (0.x Gravix vs 2.x upstream); if they ever
        // become equal by accident this test makes someone look at which one goes on the wire.
        assertNotEquals(Version.CLIENT_VERSION, Version.ENGINE_VERSION)
        assertNotEquals(Version.CLIENT_VERSION, getClientInfo().version)
    }
}
