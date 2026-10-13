/*
 * Copyright 2023-2025 LiveKit, Inc.
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

package com.gravixcloud.rtc.test.mock.dagger

import android.content.Context
import android.javax.sdp.SdpFactory
import dagger.Module
import dagger.Provides
import dagger.Reusable
import com.gravixcloud.rtc.audio.AudioBufferCallbackDispatcher
import com.gravixcloud.rtc.audio.AudioProcessingController
import com.gravixcloud.rtc.audio.AudioRecordPrewarmer
import com.gravixcloud.rtc.audio.AudioRecordSamplesDispatcher
import com.gravixcloud.rtc.audio.NoAudioRecordPrewarmer
import com.gravixcloud.rtc.dagger.CapabilitiesGetter
import com.gravixcloud.rtc.dagger.InjectionNames
import com.gravixcloud.rtc.e2ee.DataPacketCryptorManager
import com.gravixcloud.rtc.e2ee.KeyProvider
import com.gravixcloud.rtc.test.mock.MockAudioDeviceModule
import com.gravixcloud.rtc.test.mock.MockAudioProcessingController
import com.gravixcloud.rtc.test.mock.MockEglBase
import com.gravixcloud.rtc.test.mock.e2ee.ReversingDataPacketCryptorManager
import com.gravixcloud.rtc.webrtc.PeerConnectionFactoryManager
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadToken
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.MockPeerConnectionFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.WebRTCInitializer
import org.webrtc.audio.AudioDeviceModule
import javax.inject.Named
import javax.inject.Singleton

@Module
object TestRTCModule {

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER)
    @Singleton
    fun localAudioBufferCallbackDispatcher(): AudioBufferCallbackDispatcher {
        return AudioBufferCallbackDispatcher()
    }

    @Provides
    @Singleton
    fun eglBase(): EglBase {
        return MockEglBase()
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext

    @Provides
    fun audioProcessingController(): AudioProcessingController {
        return MockAudioProcessingController()
    }

    @Provides
    @Singleton
    fun audioDeviceModule(): AudioDeviceModule {
        return MockAudioDeviceModule()
    }

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER)
    @Singleton
    fun localAudioSamplesDispatcher(): AudioRecordSamplesDispatcher {
        return AudioRecordSamplesDispatcher()
    }

    @Provides
    fun audioPrewarmer(): AudioRecordPrewarmer {
        return NoAudioRecordPrewarmer()
    }

    @Provides
    @Singleton
    fun peerConnectionFactory(
        appContext: Context,
    ): PeerConnectionFactory {
        WebRTCInitializer.initialize(appContext)
        return MockPeerConnectionFactory()
    }

    @Provides
    @Singleton
    fun peerConnectionFactoryManager(
        peerConnectionFactory: PeerConnectionFactory,
    ): PeerConnectionFactoryManager {
        return PeerConnectionFactoryManager(peerConnectionFactory)
    }

    @Provides
    @Reusable
    fun rtcThreadToken(
        manager: PeerConnectionFactoryManager,
    ): RTCThreadToken {
        return RTCThreadTokenImpl(manager)
    }

    private class RTCThreadTokenImpl(private val manager: PeerConnectionFactoryManager) : RTCThreadToken {
        override val isDisposed: Boolean
            get() = manager.isDisposed
    }

    @Provides
    @Named(InjectionNames.SENDER)
    fun senderCapabilitiesGetter(peerConnectionFactory: PeerConnectionFactory): CapabilitiesGetter {
        return { mediaType: MediaStreamTrack.MediaType ->
            peerConnectionFactory.getRtpSenderCapabilities(mediaType)
        }
    }

    @Provides
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true

    @Provides
    fun sdpFactory() = SdpFactory.getInstance()

    @Provides
    fun dataPacketCryptorManagerFactory(): DataPacketCryptorManager.Factory = object : DataPacketCryptorManager.Factory {
        override fun create(keyProvider: KeyProvider): DataPacketCryptorManager {
            return ReversingDataPacketCryptorManager()
        }
    }
}
