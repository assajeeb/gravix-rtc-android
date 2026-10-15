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
import com.gravixcloud.rtc.test.util.toPBByteString
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.GravixPubLog
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.PeerConnection

/**
 * GRAVIX (0.1.2, offer serialisation): the publisher's offers, one at a time.
 *
 * The Flutter SDK (0.4.19) had two offers in flight: a debounced negotiate and the answer's renegotiate replay each
 * built one while one was pending; libwebrtc gives an unmidded transceiver a new mid per createOffer, so the second
 * setLocalDescription was refused ("order of m-lines") and the session rejoined. This file checks the same paths on
 * Android (PeerConnectionTransport: the debounced [PeerConnectionTransport.negotiate] and the replay in
 * [PeerConnectionTransport.setRemoteDescription]).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixOfferSerialiseMockE2ETest : MockE2ETest() {

    private lateinit var rtcEngine: RTCEngine

    @Before
    fun setupRTCEngine() {
        rtcEngine = component.rtcEngine()
    }

    private fun sentOffers(): List<GravixcloudRtc.SessionDescription> =
        wsFactory.ws.sentRequests
            .map { GravixcloudRtc.SignalRequest.newBuilder().mergeFrom(it.toPBByteString()).build() }
            .filter { it.hasOffer() }
            .map { it.offer }

    private fun answer(id: Int) {
        val answer = with(GravixcloudRtc.SignalResponse.newBuilder()) {
            answer = with(GravixcloudRtc.SessionDescription.newBuilder()) {
                sdp = "remote_answer"
                type = "answer"
                this.id = id
                build()
            }
            build()
        }
        wsFactory.receiveMessage(answer)
    }

    /**
     * The Flutter race: an offer pending, then a negotiate request and the answer at once (the answer's replay and the
     * debounced negotiate both want an offer). Android: never a createOffer while an offer of ours is pending, and the
     * offers go out one at a time (each after the previous one's answer).
     */
    @Test
    fun negotiateAndAnswerReplayNeverOverlapOffers() = runTest {
        connect()
        val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection
        wsFactory.ws.clearRequests()

        publisher.observer?.onRenegotiationNeeded()
        testScheduler.advanceTimeBy(25)
        assertEquals(listOf(1), sentOffers().map { it.id })
        assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, publisher.signalingState())

        // a sender change while offer 1 is pending: deferred (no offer)
        publisher.observer?.onRenegotiationNeeded()
        testScheduler.advanceTimeBy(25)
        assertEquals(listOf(1), sentOffers().map { it.id })

        // another change, and the answer to offer 1 right behind it: the replay and the debounced negotiate
        publisher.observer?.onRenegotiationNeeded()
        answer(1)
        testScheduler.advanceTimeBy(25)
        assertEquals(listOf(1, 2), sentOffers().map { it.id })
        assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, publisher.signalingState())

        answer(2)
        testScheduler.advanceTimeBy(25)
        val ids = sentOffers().map { it.id }
        println("offers sent: $ids, createOffer calls ${publisher.createOfferCalls}")
        if (publisher.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) answer(ids.last())
        testScheduler.advanceTimeBy(25)

        assertEquals("createOffer while an offer was pending", 0, publisher.createOfferWhileLocalOfferPending)
        assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
        // ids strictly increasing, one per createOffer: no offer was dropped or duplicated
        val all = sentOffers().map { it.id }
        assertEquals(all.sorted().distinct(), all)
        assertEquals(publisher.createOfferCalls, all.size)
    }

    /** The [GravixPubLog] lines of an offer, a deferred request and its replay: one line per step, no SDP body. */
    @Test
    fun pubLogLinesForEachStep() = runTest {
        val lines = mutableListOf<String>()
        GravixPubLog.sink = GravixLogSink { _, line -> synchronized(lines) { lines.add(line) } }
        try {
            connect()
            val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection
            publisher.offerSdp = GravixLogSecretsMockE2ETest.sdp("zzufragzz", "zzpwdzz", "sendonly")
            val label = rtcEngine.publisher!!.logLabel
            wsFactory.ws.clearRequests()
            synchronized(lines) { lines.clear() }

            publisher.observer?.onRenegotiationNeeded()
            testScheduler.advanceTimeBy(25)
            publisher.observer?.onRenegotiationNeeded()
            testScheduler.advanceTimeBy(25)
            answer(1)
            testScheduler.advanceTimeBy(25)
            answer(2)
            testScheduler.advanceTimeBy(25)

            val got = synchronized(lines) { lines.toList() }
            println(got.joinToString("\n"))
            val mlines = "mlines=audio:0/sendonly,application:1"
            assertEquals(
                listOf(
                    "[GravixPub] offerCreate pc=$label id=1 restart=false $mlines",
                    "[GravixPub] setLocal pc=$label offer ok munged=true",
                    "[GravixPub] offerSend pc=$label id=1 $mlines",
                    "[GravixPub] offerDefer pc=$label have-local-offer: replayed after the answer",
                    "[GravixPub] remoteSet pc=$label answer ok id=1 replay=true mlines=-",
                    "[GravixPub] offerReplay pc=$label a sender change waited for this answer",
                    "[GravixPub] offerCreate pc=$label id=2 restart=false $mlines",
                    "[GravixPub] setLocal pc=$label offer ok munged=true",
                    "[GravixPub] offerSend pc=$label id=2 $mlines",
                    "[GravixPub] remoteSet pc=$label answer ok id=2 replay=false mlines=-",
                ),
                got.filter { it.contains("pc=$label") },
            )
            assertTrue(got.none { it.contains("zzpwdzz") || it.contains("zzufragzz") })
        } finally {
            GravixPubLog.sink = null
        }
    }

    /**
     * A negotiate request that arrives while the previous one's offer is being set locally (setLocalDescription takes
     * tens of ms on a phone): the debounce cancels the running job. The started offer must still be sent; otherwise the
     * publisher sits in have-local-offer with no offer at the server, and every later negotiate waits behind an answer
     * that never comes (tracks never negotiated until a reconnect).
     */
    @Test
    fun negotiateDuringSetLocalStillSendsTheOffer() = runTest {
        connect()
        val publisher = rtcEngine.getPublisherPeerConnection() as MockPeerConnection
        wsFactory.ws.clearRequests()

        publisher.holdSetLocal = true
        publisher.observer?.onRenegotiationNeeded()
        testScheduler.advanceTimeBy(25) // offer 1 created, its setLocalDescription parked
        assertEquals(1, publisher.createOfferCalls)
        assertEquals(0, sentOffers().size)

        publisher.observer?.onRenegotiationNeeded() // the next request, 30 ms after the first
        publisher.holdSetLocal = false
        assertEquals(1, publisher.releaseHeldSetLocal())
        testScheduler.advanceTimeBy(25)

        val ids = sentOffers().map { it.id }
        println("offers sent: $ids, state ${publisher.signalingState()}")
        assertEquals("the started offer was never sent", 1, ids.size)
        assertEquals(PeerConnection.SignalingState.HAVE_LOCAL_OFFER, publisher.signalingState())

        // its answer releases the deferred request: the next offer goes out
        answer(ids.single())
        testScheduler.advanceTimeBy(25)
        assertEquals(listOf(1, 2), sentOffers().map { it.id })
        answer(2)
        testScheduler.advanceTimeBy(25)
        assertEquals(PeerConnection.SignalingState.STABLE, publisher.signalingState())
        assertEquals(0, publisher.createOfferWhileLocalOfferPending)
    }
}
