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

package com.gravixcloud.rtc.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gravixcloud.rtc.dagger.RTCModule
import com.gravixcloud.rtc.memory.CloseableManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A refused AudioRecord start must not abort the process at the engine's next
 * start (SIGABRT at jvm.cc:81). The failure mode is a native abort, so without
 * the guard this test ends with the instrumentation process crashing.
 *
 * Needs a device or emulator with a microphone; it runs real WebRTC capture.
 */
@RunWith(AndroidJUnit4::class)
class RefusedRecordStartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun refusedStartDoesNotAbortAndTheNextStartRetries() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())

        val adm = RTCModule.audioModule(
            audioDeviceModuleOverride = null,
            moduleCustomizer = null,
            audioOutputAttributes = AudioAttributes.Builder().build(),
            appContext = context,
            closeableManager = CloseableManager(),
            communicationWorkaround = NoopCommunicationWorkaround(),
            audioRecordSamplesDispatcher = AudioRecordSamplesDispatcher(),
            audioBufferCallbackDispatcher = AudioBufferCallbackDispatcher(),
        ) as JavaAudioDeviceModule
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        val input = JavaAudioDeviceModule::class.java.getField("audioInput").get(adm)!!

        // Prepare the recorder without its thread, then make its next start fail:
        // AudioRecord.startRecording on a released recorder throws, as a refusal does.
        input.javaClass.getDeclaredMethod("initRecordingIfNeeded").apply { isAccessible = true }.invoke(input)
        val real = assertNotNull(recorder(input))
        setRecorder(input, releasedRecorder())

        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("mic", source)
        val (a, b) = connectLoopback(factory, track)

        // The refused start and, without the guard, the abort both happen here.
        Thread.sleep(SETTLE_MS)
        val startFlag = input.field("nativeCalledStartRecording") as AtomicBoolean
        assertFalse("a refused start must leave the flag clear", startFlag.get())

        // The microphone is available again: the next engine start must use it.
        setRecorder(input, real)
        track.setEnabled(false)
        Thread.sleep(TOGGLE_MS)
        track.setEnabled(true)
        Thread.sleep(TOGGLE_MS)

        assertSame(real, recorder(input))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, real.recordingState)
        assertNotNull(input.field("audioThread"))

        a.dispose()
        b.dispose()
        track.dispose()
        source.dispose()
        factory.dispose()
        adm.release()
    }

    private fun assertNotNull(record: AudioRecord?): AudioRecord {
        assertNotNull("recorder was not prepared", record as Any?)
        return record!!
    }

    private fun Any.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun recorder(input: Any) = input.field("audioRecord") as AudioRecord?

    private fun setRecorder(input: Any, record: AudioRecord) {
        input.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }.set(input, record)
    }

    @SuppressLint("MissingPermission")
    private fun releasedRecorder(): AudioRecord {
        val format = AudioFormat.ENCODING_PCM_16BIT
        val channel = AudioFormat.CHANNEL_IN_MONO
        val size = AudioRecord.getMinBufferSize(RATE, channel, format)
        return AudioRecord(MediaRecorder.AudioSource.MIC, RATE, channel, format, size).apply { release() }
    }

    private fun connectLoopback(factory: PeerConnectionFactory, track: AudioTrack): Pair<PeerConnection, PeerConnection> {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        lateinit var a: PeerConnection
        lateinit var b: PeerConnection
        a = factory.createPeerConnection(config, CandidateForwarder { b.addIceCandidate(it) })!!
        b = factory.createPeerConnection(config, CandidateForwarder { a.addIceCandidate(it) })!!
        a.addTrack(track, listOf("stream"))

        val offer = createSdp { a.createOffer(it, MediaConstraints()) }
        setSdp { a.setLocalDescription(it, offer) }
        setSdp { b.setRemoteDescription(it, offer) }
        val answer = createSdp { b.createAnswer(it, MediaConstraints()) }
        setSdp { b.setLocalDescription(it, answer) }
        setSdp { a.setRemoteDescription(it, answer) }
        return a to b
    }

    private fun createSdp(call: (SdpObserver) -> Unit): SessionDescription {
        val done = CountDownLatch(1)
        var created: SessionDescription? = null
        call(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                created = desc
                done.countDown()
            }
        })
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        return created!!
    }

    private fun setSdp(call: (SdpObserver) -> Unit) {
        val done = CountDownLatch(1)
        call(object : SdpAdapter() {
            override fun onSetSuccess() = done.countDown()
        })
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = throw AssertionError("create failed: $error")
        override fun onSetFailure(error: String?) = throw AssertionError("set failed: $error")
    }

    private class CandidateForwarder(private val forward: (IceCandidate) -> Unit) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = forward(candidate)
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private companion object {
        const val RATE = 48000
        const val SETTLE_MS = 4000L
        const val TOGGLE_MS = 2000L
        const val TIMEOUT_S = 10L
    }
}
