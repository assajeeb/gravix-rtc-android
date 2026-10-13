/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package com.gravixcloud.rtc.dagger

import android.content.Context
import android.javax.sdp.SdpFactory
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.Reusable
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.audio.AudioBufferCallbackDispatcher
import com.gravixcloud.rtc.audio.AudioProcessingController
import com.gravixcloud.rtc.audio.AudioProcessorOptions
import com.gravixcloud.rtc.audio.AudioRecordPrewarmer
import com.gravixcloud.rtc.audio.AudioRecordSamplesDispatcher
import com.gravixcloud.rtc.audio.CommunicationWorkaround
import com.gravixcloud.rtc.audio.JavaAudioRecordPrewarmer
import com.gravixcloud.rtc.audio.NoAudioRecordPrewarmer
import com.gravixcloud.rtc.e2ee.DataPacketCryptorManager
import com.gravixcloud.rtc.e2ee.DataPacketCryptorManagerImpl
import com.gravixcloud.rtc.memory.CloseableManager
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.LoggingLevel
import com.gravixcloud.rtc.webrtc.CustomAudioProcessingFactory
import com.gravixcloud.rtc.webrtc.CustomVideoDecoderFactory
import com.gravixcloud.rtc.webrtc.CustomVideoEncoderFactory
import com.gravixcloud.rtc.webrtc.PeerConnectionFactoryManager
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadToken
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadTokenImpl
import com.gravixcloud.rtc.webrtc.peerconnection.executeBlockingOnRTCThread
import com.gravixcloud.rtc.webrtc.peerconnection.executeOnRTCThread
import org.webrtc.AudioProcessingFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Named
import javax.inject.Singleton

/**
 * @suppress
 */
typealias CapabilitiesGetter = @JvmSuppressWildcards (MediaStreamTrack.MediaType) -> RtpCapabilities

/**
 * @suppress
 */
@Module
internal object RTCModule {

    /**
     * To only be written to on the WebRTC thread.
     */
    private var hasInitializedWebrtc = false

    /**
     * Certain classes require libwebrtc to be initialized prior to use.
     *
     * If your provision depends on libwebrtc initialization, just add it
     * as a dependency in your method signature.
     *
     * Example:
     *
     * ```
     * @Provides
     * fun someFactory(
     *     @Suppress("UNUSED_PARAMETER")
     *     @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
     *     webrtcInitialization: LibWebrtcInitialization
     * ): SomeFactory {
     *     ...
     * }
     * ```
     */
    @Provides
    @Singleton
    @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
    fun libWebrtcInitialization(appContext: Context): LibWebrtcInitialization {
        if (!hasInitializedWebrtc) {
            executeBlockingOnRTCThread(LibWebrtcInitializationThreadToken) {
                if (!hasInitializedWebrtc) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions
                            .builder(appContext)
                            .setNativeLibraryName("jingle_peerconnection_so")
                            .setInjectableLogger(
                                { s, severity, s2 ->
                                    if (!GravixRtc.enableWebRTCLogging) {
                                        return@setInjectableLogger
                                    }

                                    val loggingLevel = when (severity) {
                                        Logging.Severity.LS_VERBOSE -> LoggingLevel.VERBOSE
                                        Logging.Severity.LS_INFO -> LoggingLevel.INFO
                                        Logging.Severity.LS_WARNING -> LoggingLevel.WARN
                                        Logging.Severity.LS_ERROR -> LoggingLevel.ERROR
                                        else -> LoggingLevel.OFF
                                    }

                                    GxLog.log(loggingLevel, null) { "$s2: $s" }
                                },
                                Logging.Severity.LS_VERBOSE,
                            )
                            .createInitializationOptions(),
                    )
                    // Only latch after initialize succeeds. If it throws (e.g. the native
                    // library fails to load), latching beforehand would permanently skip
                    // initialization for the process, and the next native call would crash
                    // with an uncatchable-by-catch(Exception) UnsatisfiedLinkError instead
                    // of a catchable failure at the call site.
                    hasInitializedWebrtc = true
                }
            }
        }
        return LibWebrtcInitialization
    }

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER)
    @Singleton
    fun localAudioSamplesDispatcher(): AudioRecordSamplesDispatcher {
        return AudioRecordSamplesDispatcher()
    }

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER)
    @Singleton
    fun localAudioBufferCallbackDispatcher(): AudioBufferCallbackDispatcher {
        return AudioBufferCallbackDispatcher()
    }

    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun audioModule(
        @Named(InjectionNames.OVERRIDE_AUDIO_DEVICE_MODULE)
        audioDeviceModuleOverride: AudioDeviceModule?,
        @Named(InjectionNames.OVERRIDE_JAVA_AUDIO_DEVICE_MODULE_CUSTOMIZER)
        moduleCustomizer: ((builder: JavaAudioDeviceModule.Builder) -> Unit)?,
        audioOutputAttributes: AudioAttributes,
        appContext: Context,
        closeableManager: CloseableManager,
        communicationWorkaround: CommunicationWorkaround,
        @Named(InjectionNames.LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER)
        audioRecordSamplesDispatcher: AudioRecordSamplesDispatcher,
        @Named(InjectionNames.LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER)
        audioBufferCallbackDispatcher: AudioBufferCallbackDispatcher,
    ): AudioDeviceModule {
        if (audioDeviceModuleOverride != null) {
            return audioDeviceModuleOverride
        }

        // Set audio record error callbacks.
        val audioRecordErrorCallback = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                GxLog.e { "onWebRtcAudioRecordInitError: $errorMessage" }
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                errorMessage: String?,
            ) {
                GxLog.e { "onWebRtcAudioRecordStartError: $errorCode. $errorMessage" }
            }

            override fun onWebRtcAudioRecordError(errorMessage: String?) {
                GxLog.e { "onWebRtcAudioRecordError: $errorMessage" }
            }
        }

        val audioTrackErrorCallback = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                GxLog.e { "onWebRtcAudioTrackInitError: $errorMessage" }
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                errorMessage: String?,
            ) {
                GxLog.e { "onWebRtcAudioTrackStartError: $errorCode. $errorMessage" }
            }

            override fun onWebRtcAudioTrackError(errorMessage: String?) {
                GxLog.e { "onWebRtcAudioTrackError: $errorMessage" }
            }
        }

        val audioRecordStateCallback: JavaAudioDeviceModule.AudioRecordStateCallback = object :
            JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                GxLog.v { "Audio recording starts" }
            }

            override fun onWebRtcAudioRecordStop() {
                GxLog.v { "Audio recording stops" }
            }
        }

        // Set audio track state callbacks.
        val audioTrackStateCallback: JavaAudioDeviceModule.AudioTrackStateCallback = object :
            JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                GxLog.v { "Audio playout starts" }
                communicationWorkaround.onStartPlayout()
            }

            override fun onWebRtcAudioTrackStop() {
                GxLog.v { "Audio playout stops" }
                communicationWorkaround.onStopPlayout()
            }
        }

        val useHardwareAudioProcessing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val builder = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(useHardwareAudioProcessing)
            .setUseHardwareNoiseSuppressor(useHardwareAudioProcessing)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .setSamplesReadyCallback(audioRecordSamplesDispatcher)
            // VOICE_COMMUNICATION needs to be used for echo cancelling.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(audioOutputAttributes)
            .setAudioBufferCallback(audioBufferCallbackDispatcher)

        moduleCustomizer?.invoke(builder)
        return builder.createAudioDeviceModule()
            .apply { closeableManager.registerClosable { release() } }
    }

    @Provides
    fun audioPrewarmer(
        @Named(InjectionNames.OVERRIDE_DISABLE_AUDIO_PREWARM)
        disableAudioPrewarm: Boolean,
        audioDeviceModule: AudioDeviceModule,
    ): AudioRecordPrewarmer {
        return if (disableAudioPrewarm) {
            NoAudioRecordPrewarmer()
        } else if (audioDeviceModule is JavaAudioDeviceModule) {
            JavaAudioRecordPrewarmer(audioDeviceModule)
        } else {
            GxLog.w { "Custom audio device module detected. Audio record prewarming is not available." }
            NoAudioRecordPrewarmer()
        }
    }

    @Provides
    @Singleton
    fun eglBase(
        @Named(InjectionNames.OVERRIDE_EGL_BASE)
        eglBaseOverride: EglBase?,
        memoryManager: CloseableManager,
    ): EglBase {
        return eglBaseOverride ?: EglBase
            .create()
            .apply { memoryManager.registerClosable { release() } }
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext

    @Provides
    fun videoEncoderFactory(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
        videoHwAccel: Boolean,
        eglContext: EglBase.Context,
        @Named(InjectionNames.OVERRIDE_VIDEO_ENCODER_FACTORY)
        videoEncoderFactoryOverride: VideoEncoderFactory?,
    ): VideoEncoderFactory {
        return videoEncoderFactoryOverride ?: if (videoHwAccel) {
            CustomVideoEncoderFactory(
                eglContext,
                enableIntelVp8Encoder = true,
                enableH264HighProfile = false,
            )
        } else {
            SoftwareVideoEncoderFactory()
        }
    }

    @Provides
    @Singleton
    fun customAudioProcessingFactory(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        @Named(InjectionNames.OVERRIDE_AUDIO_PROCESSOR_OPTIONS)
        audioProcessorOptions: AudioProcessorOptions?,
    ): CustomAudioProcessingFactory {
        return CustomAudioProcessingFactory(audioProcessorOptions ?: AudioProcessorOptions())
    }

    @Provides
    fun audioProcessingController(customAudioProcessingFactory: CustomAudioProcessingFactory): AudioProcessingController {
        return customAudioProcessingFactory
    }

    @Provides
    fun audioProcessingFactory(customAudioProcessingFactory: CustomAudioProcessingFactory): AudioProcessingFactory {
        return customAudioProcessingFactory.getAudioProcessingFactory()
    }

    @Provides
    fun videoDecoderFactory(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
        videoHwAccel: Boolean,
        eglContext: EglBase.Context,
        @Named(InjectionNames.OVERRIDE_VIDEO_DECODER_FACTORY)
        videoDecoderFactoryOverride: VideoDecoderFactory?,
    ): VideoDecoderFactory {
        return videoDecoderFactoryOverride ?: if (videoHwAccel) {
            CustomVideoDecoderFactory(eglContext)
        } else {
            SoftwareVideoDecoderFactory()
        }
    }

    @Provides
    @Singleton
    fun peerConnectionFactoryManager(
        @Suppress("UNUSED_PARAMETER")
        @Named(InjectionNames.LIB_WEBRTC_INITIALIZATION)
        webrtcInitialization: LibWebrtcInitialization,
        audioDeviceModule: AudioDeviceModule,
        videoEncoderFactory: VideoEncoderFactory,
        videoDecoderFactory: VideoDecoderFactory,
        @Named(InjectionNames.OVERRIDE_PEER_CONNECTION_FACTORY_OPTIONS)
        peerConnectionFactoryOptions: PeerConnectionFactory.Options?,
        memoryManager: CloseableManager,
        audioProcessingFactory: AudioProcessingFactory,
    ): PeerConnectionFactoryManager {
        return executeBlockingOnRTCThread(LibWebrtcInitializationThreadToken) {
            val peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setAudioProcessingFactory(audioProcessingFactory)
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .apply {
                    if (peerConnectionFactoryOptions != null) {
                        setOptions(peerConnectionFactoryOptions)
                    }
                }
                .createPeerConnectionFactory()
            return@executeBlockingOnRTCThread PeerConnectionFactoryManager(peerConnectionFactory)
                .apply {
                    memoryManager.registerClosable {
                        executeOnRTCThread(RTCThreadTokenImpl(this)) {
                            dispose()
                        }
                    }
                }
        }!!
    }

    @Provides
    fun dataPacketCryptorManagerFactory(): DataPacketCryptorManager.Factory {
        return DataPacketCryptorManagerImpl.Factory
    }

    @Provides
    @Singleton
    fun peerConnectionFactory(
        peerConnectionFactoryManager: PeerConnectionFactoryManager,
    ): PeerConnectionFactory {
        return peerConnectionFactoryManager.peerConnectionFactory
    }

    @Provides
    @Reusable
    fun rtcThreadToken(
        peerConnectionFactoryManager: PeerConnectionFactoryManager,
    ): RTCThreadToken {
        return RTCThreadTokenImpl(peerConnectionFactoryManager)
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
    fun sdpFactory(): SdpFactory = SdpFactory.getInstance()
}

/**
 * Used to ensure [RTCModule.libWebrtcInitialization] is called prior to other methods.
 *
 * @suppress
 */
object LibWebrtcInitialization

/**
 * To be used **only** for initialization and creation of PeerConnectionFactories,
 * as those don't need the native threads and cannot deadlock.
 */
private object LibWebrtcInitializationThreadToken : RTCThreadToken {
    override val isDisposed: Boolean
        get() = false
}
