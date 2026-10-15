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

package com.gravixcloud.rtc.room

import com.gravixcloud.rtc.room.util.PeerConnectionStateObservable
import com.gravixcloud.rtc.util.FlowObservable
import com.gravixcloud.rtc.util.GravixStepLogs
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.flowDelegate
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadToken
import com.gravixcloud.rtc.webrtc.peerconnection.executeOnRTCThread
import gravixcloud.GravixcloudRtc
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription

internal class PublisherTransportObserver(
    private val engine: RTCEngine,
    private val client: SignalClient,
    private val rtcThreadToken: RTCThreadToken,
) : PeerConnection.Observer, PeerConnectionTransport.Listener, PeerConnectionStateObservable {

    var connectionChangeListener: PeerConnectionStateListener? = null

    @FlowObservable
    @get:FlowObservable
    override var connectionState by flowDelegate(PeerConnection.PeerConnectionState.NEW)
        private set

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        executeOnRTCThread(rtcThreadToken) {
            val candidate = iceCandidate ?: return@executeOnRTCThread
            GxLog.v { "onIceCandidate: ${GravixStepLogs.candidate(candidate)}" }
            client.sendCandidate(candidate, target = GravixcloudRtc.SignalTarget.PUBLISHER)
        }
    }

    override fun onRenegotiationNeeded() {
        executeOnRTCThread(rtcThreadToken) {
            engine.negotiatePublisher()
        }
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        GxLog.v { "onIceConnection new state: $newState" }
    }

    override fun onOffer(sd: SessionDescription, offerId: Int) {
        executeOnRTCThread(rtcThreadToken) {
            client.sendOffer(sd, offerId)
        }
    }

    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        executeOnRTCThread(rtcThreadToken) {
            GxLog.v { "onConnection new state: $newState" }
            connectionChangeListener?.invoke(newState)
            connectionState = newState
        }
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
        engine.gravixOnSelectedCandidatePair(isPublisher = true, local = event?.local) // GRAVIX (iceTransportWatchMs)
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
    }

    override fun onAddStream(p0: MediaStream?) {
    }

    override fun onRemoveStream(p0: MediaStream?) {
    }

    override fun onDataChannel(dataChannel: DataChannel?) {
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
    }
}
