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

import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockPeerConnection
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.LoggingLevel
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GRAVIX (0.1.2, security): with SDK logging at VERBOSE, no SDP body (ice-pwd, ice-ufrag, fingerprint), no ICE
 * candidate ufrag, no TURN credential and no url query reaches a log line. Markers are obviously fake.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixLogSecretsMockE2ETest : MockE2ETest() {

    private val lines = mutableListOf<String>()
    private var oldLevel = LoggingLevel.OFF
    private var oldLogger: GxLog.Logger? = null

    @Before
    fun captureLogs() {
        oldLevel = GravixRtc.loggingLevel
        oldLogger = GravixRtc.logger
        GravixRtc.loggingLevel = LoggingLevel.VERBOSE
        GravixRtc.logger = object : GxLog.Logger {
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                synchronized(lines) { lines.add(message + (t?.let { "\n" + it.stackTraceToString() } ?: "")) }
            }
        }
    }

    @After
    fun restoreLogs() {
        GravixRtc.loggingLevel = oldLevel
        GravixRtc.logger = oldLogger
    }

    private fun logged(): String = synchronized(lines) { lines.joinToString("\n") }

    @Test
    fun noSdpCandidateOrTurnSecretInTheLog() = runTest {
        val join = TestData.JOIN.toBuilder().apply {
            join = join.toBuilder().setIceServers(0, join.getIceServers(0).toBuilder().setCredential(TURN_SECRET)).build()
        }.build()
        val job = coroutineRule.scope.launch {
            room.connect(url = "${TestData.EXAMPLE_URL}?access_token=opaque&room=$QUERY_MARK", token = "token")
        }
        prepareSignal(join)
        job.join()
        // the server's subscriber offer, with its secrets
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setOffer(
                GravixcloudRtc.SessionDescription.newBuilder().setType("offer").setSdp(sdp("zzsrvufragzz", "zzsrvpwdzz", "recvonly")).setId(7),
            ).build(),
        )
        getSubscriberPeerConnection().moveToIceConnectionState(org.webrtc.PeerConnection.IceConnectionState.CONNECTED)
        // a trickled candidate carrying its ufrag
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setTrickle(
                GravixcloudRtc.TrickleRequest.newBuilder()
                    .setTarget(GravixcloudRtc.SignalTarget.SUBSCRIBER)
                    .setCandidateInit(
                        """{"candidate":"candidate:1 1 udp 2122260223 192.168.1.20 54321 typ host generation 0 ufrag $CAND_UFRAG","sdpMid":"0","sdpMLineIndex":0}""",
                    ),
            ).build(),
        )
        // our own offer (publisher) and the server's answer, with their secrets
        val publisher = component.rtcEngine().getPublisherPeerConnection() as MockPeerConnection
        publisher.offerSdp = sdp("zzlocalufragzz", "zzlocalpwdzz", "sendonly")
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasOffer()) {
                wsFactory.receiveMessage(
                    GravixcloudRtc.SignalResponse.newBuilder().setAnswer(
                        GravixcloudRtc.SessionDescription.newBuilder().setType("answer").setSdp(sdp("zzansufragzz", "zzanspwdzz", "recvonly"))
                            .setId(request.offer.id),
                    ).build(),
                )
                true
            } else {
                false
            }
        }
        publisher.observer?.onRenegotiationNeeded()
        testScheduler.advanceTimeBy(50)

        val log = logged()
        for (secret in listOf(
            TURN_SECRET, "zzsrvufragzz", "zzsrvpwdzz", "zzlocalufragzz", "zzlocalpwdzz", "zzansufragzz", "zzanspwdzz",
            CAND_UFRAG, FINGERPRINT, QUERY_MARK,
        )) {
            assertFalse("$secret logged:\n$log", log.contains(secret))
        }
        assertTrue("the connect line is still logged:\n$log", log.contains("connecting to ws://www.example.com"))
    }

    companion object {
        const val TURN_SECRET = "zzturnpasszz"
        const val CAND_UFRAG = "zzcandufragzz"
        const val QUERY_MARK = "zzquerymarkzz"
        const val FINGERPRINT = "AA:BB:AA:BB:AA:BB:AA:BB:AA:BB:AA:BB:AA:BB:AA:BB"

        fun sdp(ufrag: String, pwd: String, direction: String) = listOf(
            "v=0",
            "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=group:BUNDLE 0 1",
            "a=fingerprint:sha-256 $FINGERPRINT",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "c=IN IP4 0.0.0.0",
            "a=mid:0",
            "a=$direction",
            "a=ice-ufrag:$ufrag",
            "a=ice-pwd:$pwd",
            "a=rtpmap:111 opus/48000/2",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "c=IN IP4 0.0.0.0",
            "a=mid:1",
            "a=sctp-port:5000",
            "",
        ).joinToString("\r\n")
    }
}
