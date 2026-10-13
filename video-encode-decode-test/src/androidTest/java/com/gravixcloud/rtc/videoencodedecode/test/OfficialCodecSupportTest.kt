/*
 * Copyright 2024 LiveKit, Inc.
 * Modifications Copyright 2026 Gravity Compile
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

package com.gravixcloud.rtc.videoencodedecode.test

import androidx.test.platform.app.InstrumentationRegistry
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class OfficialCodecSupportTest {
    val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(EglBase.create().eglBaseContext, true, true)

    @Before
    fun setup() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(InstrumentationRegistry.getInstrumentation().targetContext)
                .createInitializationOptions()
        )
    }

    @Test
    fun isVP8Supported() {
        Assert.assertTrue(defaultVideoEncoderFactory.supportedCodecs.any { it.name == "VP8" })
    }

    @Test
    fun isH264Supported() {
        Assert.assertTrue(defaultVideoEncoderFactory.supportedCodecs.any { it.name == "H264" })
    }
}
