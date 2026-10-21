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

import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockPeerConnection
import com.gravixcloud.rtc.test.mock.room.track.createMockLocalAudioTrack
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.webrtc.RtpTransceiver

/** GRAVIX (2026-10-21): the publisher answer keeps our mic sections on DTX (mono), as the JS SDK's fba6034. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GravixOpusAnswerTest : MockE2ETest() {
    private val dtxMono = GravixOpusAnswer.Prefs(dtx = true, stereo = false)

    @Test
    fun fmtpCases() {
        // mono mic with DTX: usedtx=1 added, stereo=1 removed, the rest kept
        assertEquals("minptime=10;useinbandfec=1;usedtx=1", GravixOpusAnswer.fmtp("minptime=10;useinbandfec=1;stereo=1", dtxMono))
        assertEquals("minptime=10;useinbandfec=1;usedtx=1", GravixOpusAnswer.fmtp("minptime=10;useinbandfec=1;usedtx=1", dtxMono))
        // dtx off removes usedtx; a stereo publication gets stereo=1
        assertEquals("minptime=10;stereo=1", GravixOpusAnswer.fmtp("minptime=10;usedtx=1", GravixOpusAnswer.Prefs(dtx = false, stereo = true)))
        assertEquals("usedtx=1", GravixOpusAnswer.fmtp("", dtxMono))
    }

    @Test
    fun onlyTheRegisteredSectionIsPinned() {
        val out = GravixOpusAnswer.munge(ANSWER_SDP, mapOf("0" to dtxMono)).split("\r\n").filter { it.startsWith("a=fmtp:111") }
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1", out[0])
        assertEquals("other sections as answered", "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1", out[1])
        assertEquals(ANSWER_SDP, GravixOpusAnswer.munge(ANSWER_SDP, emptyMap()))
        // a section without fmtp gets one after its rtpmap
        val bare = ANSWER_SDP.replace("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1\r\nm=audio", "m=audio")
        val munged = GravixOpusAnswer.munge(bare, mapOf("0" to dtxMono))
        assertEquals(true, munged.contains("a=rtpmap:111 opus/48000/2\r\na=fmtp:111 usedtx=1\r\n"))
    }

    @Test
    fun aPublisherAnswerIsPinnedBeforeSetRemoteDescription() = runTest {
        connect()
        val publisher = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        val mic = Mockito.mock(RtpTransceiver::class.java)
        Mockito.`when`(mic.mid).thenReturn("0")
        component.rtcEngine().registerOpusAnswerPrefs(mic, dtx = true, stereo = false)
        publisher.offerSdp = ANSWER_SDP.replace("a=recvonly", "a=X").replace("a=sendonly", "a=recvonly").replace("a=X", "a=sendonly")
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasOffer()) {
                wsFactory.receiveMessage(
                    GravixcloudRtc.SignalResponse.newBuilder().setAnswer(
                        GravixcloudRtc.SessionDescription.newBuilder().setType("answer").setSdp(ANSWER_SDP).setId(request.offer.id),
                    ).build(),
                )
                true
            } else {
                false
            }
        }
        publisher.observer?.onRenegotiationNeeded()
        testScheduler.advanceTimeBy(50)
        val fmtps = publisher.remoteDesc!!.description.split("\r\n").filter { it.startsWith("a=fmtp:111") }
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1", fmtps[0])
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1", fmtps[1])
    }

    @Test
    fun anUnpublishedMicIsNoLongerPinned() = runTest {
        connect()
        val track = createMockLocalAudioTrack()
        room.localParticipant.publishAudioTrack(track)
        val engine = component.rtcEngine()
        assertEquals("the published mic is pinned", 1, engine.opusAnswerPrefCount)
        room.localParticipant.unpublishTrack(track)
        assertEquals("its entry goes with it", 0, engine.opusAnswerPrefCount)
    }

    @Test
    fun stereoIsAnExplicitPublishOption() {
        val mono = com.gravixcloud.rtc.room.participant.AudioTrackPublishOptions()
        assertEquals(GravixOpusAnswer.Prefs(dtx = true, stereo = false), mono.opusAnswerPrefs())
        assertEquals(false, mono.getFeaturesList().contains(gravixcloud.GravixcloudModels.AudioTrackFeature.TF_STEREO))
        val stereo = com.gravixcloud.rtc.room.participant.AudioTrackPublishOptions(stereo = true)
        assertEquals(GravixOpusAnswer.Prefs(dtx = false, stereo = true), stereo.opusAnswerPrefs())
        assertEquals(
            setOf(gravixcloud.GravixcloudModels.AudioTrackFeature.TF_NO_DTX, gravixcloud.GravixcloudModels.AudioTrackFeature.TF_STEREO),
            stereo.getFeaturesList(),
        )
    }

    companion object {
        /** The SFU answer that mirrored the offer's defaults for our mic (mid 0). */
        val ANSWER_SDP = listOf(
            "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "c=IN IP4 0.0.0.0", "a=mid:0", "a=recvonly", "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "c=IN IP4 0.0.0.0", "a=mid:1", "a=sendonly", "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1",
            "",
        ).joinToString("\r\n")
    }
}
