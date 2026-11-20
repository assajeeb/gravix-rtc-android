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

package com.gravixcloud.rtc.voicefx

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.room.participant.LocalParticipant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every effect loads, is timed, and runs in the real WebRTC capture path (two local peer connections, no server). */
@RunWith(AndroidJUnit4::class)
class VoiceEffectsDeviceTest {
    private val tag = "FXTEST"

    private open class Obs(val peer: () -> PeerConnection) : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) { peer().addIceCandidate(c) }
        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(s: MediaStream) = Unit
        override fun onRemoveStream(s: MediaStream) = Unit
        override fun onDataChannel(d: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun sdp(run: (SdpObserver) -> Unit): SessionDescription? {
        val done = CountDownLatch(1)
        var out: SessionDescription? = null
        run(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { out = d; done.countDown() }
            override fun onSetSuccess() { done.countDown() }
            override fun onCreateFailure(e: String?) { error("create: $e") }
            override fun onSetFailure(e: String?) { error("set: $e") }
        })
        assertTrue("sdp timeout", done.await(10, TimeUnit.SECONDS))
        return out
    }

    @Test
    fun everyEffectRunsOnTheCapturePath() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        val fx = GravixVoiceEffects
        val effects = GravixVoiceEffect.entries.filter { it != GravixVoiceEffect.NONE }

        for (e in effects) {
            val b = fx.benchmark(e, seconds = 2)
            assertNotNull("benchmark failed: $e", b)
            Log.i(tag, "bench ${e.wireName} p50=${b!!.p50Us} p95=${b.p95Us} max=${b.maxUs} latency=${b.latencyMs}")
            assertTrue("over the 6 ms budget: $e", b.p95Us < 6000)
        }

        val room = GravixRtc.create(ctx)
        assertTrue(fx.set(GravixVoiceEffect.ROBOT))
        val track = room.localParticipant.createAudioTrack("mic")
        track.start()

        val f = LocalParticipant::class.java.getDeclaredField("peerConnectionFactory").apply { isAccessible = true }
        val factory = f.get(room.localParticipant) as PeerConnectionFactory
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        lateinit var a: PeerConnection
        lateinit var b: PeerConnection
        a = factory.createPeerConnection(cfg, Obs { b })!!
        b = factory.createPeerConnection(cfg, Obs { a })!!
        a.addTrack(track.rtcTrack, listOf("s"))
        val offer = sdp { a.createOffer(it, MediaConstraints()) }!!
        sdp { a.setLocalDescription(it, offer) }
        sdp { b.setRemoteDescription(it, offer) }
        val answer = sdp { b.createAnswer(it, MediaConstraints()) }!!
        sdp { b.setLocalDescription(it, answer) }
        sdp { a.setRemoteDescription(it, answer) }
        var i = 0
        while (i++ < 60 && a.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) Thread.sleep(250)
        Log.i(tag, "peers state=${a.connectionState()}")
        Thread.sleep(2000)

        for (e in effects) {
            assertTrue(fx.set(e))
            Thread.sleep(500)
            val before = fx.currentState().frames
            Thread.sleep(1500)
            val s = fx.currentState()
            val frames = s.frames - before
            Log.i(tag, "live ${e.wireName} active=${s.active.wireName} rate=${s.sampleRate} frames=$frames latency=${s.latencyMs} over=${s.overBudget} maxUs=${s.maxBlockUs} watchdog=${s.watchdog}")
            assertEquals(e, s.active)
            assertTrue("capture path did not run $e (frames=$frames)", frames > 100)
            assertFalse(s.watchdog)
        }

        assertTrue(fx.set(GravixVoiceEffect.NONE))
        Thread.sleep(500)
        val off = fx.currentState()
        Log.i(tag, "off active=${off.active.wireName} latency=${off.latencyMs}")
        assertEquals(GravixVoiceEffect.NONE, off.active)
        assertEquals(0, off.latencyMs)

        a.close()
        b.close()
        track.stop()
        room.release()
    }
}
