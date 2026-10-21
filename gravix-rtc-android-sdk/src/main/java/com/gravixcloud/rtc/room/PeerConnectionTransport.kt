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

package com.gravixcloud.rtc.room

import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpFactory
import androidx.annotation.VisibleForTesting
import com.gravixcloud.rtc.dagger.InjectionNames
import com.gravixcloud.rtc.room.util.MediaConstraintKeys
import com.gravixcloud.rtc.room.util.createOffer
import com.gravixcloud.rtc.room.util.findConstraint
import com.gravixcloud.rtc.room.util.setLocalDescription
import com.gravixcloud.rtc.room.util.setRemoteDescription
import com.gravixcloud.rtc.util.Either
import com.gravixcloud.rtc.util.GravixPubLog
import com.gravixcloud.rtc.util.GravixStepLogs
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.LoggingLevel
import com.gravixcloud.rtc.util.debounce
import com.gravixcloud.rtc.webrtc.SdpExt
import com.gravixcloud.rtc.webrtc.SdpFmtp
import com.gravixcloud.rtc.webrtc.getExts
import com.gravixcloud.rtc.webrtc.getFmtps
import com.gravixcloud.rtc.webrtc.getMsid
import com.gravixcloud.rtc.webrtc.getRtps
import com.gravixcloud.rtc.webrtc.isConnected
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadToken
import com.gravixcloud.rtc.webrtc.peerconnection.executeBlockingOnRTCThread
import com.gravixcloud.rtc.webrtc.peerconnection.launchBlockingOnRTCThread
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Named
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.roundToLong

/**
 * @suppress
 */
internal class PeerConnectionTransport
@AssistedInject
constructor(
    @Assisted config: RTCConfiguration,
    @Assisted pcObserver: PeerConnection.Observer,
    @Assisted private val listener: Listener?,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    connectionFactory: PeerConnectionFactory,
    private val sdpFactory: SdpFactory,
    private val rtcThreadToken: RTCThreadToken,
) {
    private val coroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())

    @VisibleForTesting
    internal val peerConnection: PeerConnection = executeBlockingOnRTCThread(rtcThreadToken) {
        connectionFactory.createPeerConnection(
            config,
            pcObserver,
        ) ?: throw IllegalStateException("peer connection creation failed?")
    }!!
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var restartingIce: Boolean = false

    @Volatile
    private var renegotiate = false

    private val trackBitrates = mutableMapOf<TrackBitrateInfoKey, TrackBitrateInfo>()

    /** GRAVIX (2026-10-21): published opus sections whose answer is pinned to DTX / mono ([GravixOpusAnswer]). */
    private val opusAnswerPrefs = java.util.concurrent.ConcurrentHashMap<RtpTransceiver, GravixOpusAnswer.Prefs>()
    private var isClosed = AtomicBoolean(false)

    private val latestOfferId = AtomicInteger(0)

    /** GRAVIX (0.1.2): this transport's name in [GravixPubLog] lines (`pub#1`, `sub#2`). */
    internal val logLabel: String = (if (listener != null) "pub#" else "sub#") + transportCounter.incrementAndGet()

    private fun pubLog(step: String, detail: String? = null, at: LoggingLevel = LoggingLevel.INFO) =
        GravixPubLog.step(step, logLabel, detail, at)

    interface Listener {
        fun onOffer(sd: SessionDescription, offerId: Int)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executeRTCIfNotClosed {
            if (peerConnection.remoteDescription != null && !restartingIce) {
                peerConnection.addIceCandidate(candidate)
            } else {
                pendingCandidates.add(candidate)
            }
        }
    }

    suspend fun <T> withPeerConnection(action: suspend PeerConnection.() -> T): T? {
        return launchRTCIfNotClosed {
            action(peerConnection)
        }
    }

    suspend fun setRemoteDescription(sd: SessionDescription, offerId: Int): Either<Unit, String?> {
        val result = launchRTCIfNotClosed {
            val currentOfferId = latestOfferId.get()
            val type = sd.type.canonicalForm()
            if (sd.type == SessionDescription.Type.ANSWER && currentOfferId > 0 && offerId > 0 && currentOfferId > offerId) {
                pubLog("remoteSet", "$type ignored id=$offerId stale (current $currentOfferId)")
                return@launchRTCIfNotClosed Either.Right("Old offer, ignoring. Expected: $currentOfferId, actual: $offerId")
            }
            val answer = if (sd.type == SessionDescription.Type.ANSWER && opusAnswerPrefs.isNotEmpty()) {
                val byMid = opusAnswerPrefs.entries.mapNotNull { (t, p) -> runCatching { t.mid }.getOrNull()?.let { it to p } }.toMap()
                val munged = GravixOpusAnswer.munge(sd.description, byMid)
                if (munged != sd.description) {
                    pubLog("answerPin", "opus usedtx/stereo pinned mids=${byMid.keys.joinToString(",")}")
                    SessionDescription(sd.type, munged)
                } else {
                    sd
                }
            } else {
                sd
            }
            val result = peerConnection.setRemoteDescription(answer)
            if (result is Either.Left) {
                pubLog(
                    "remoteSet",
                    "$type ok id=$offerId replay=$renegotiate mlines=${GravixStepLogs.mlines(sd.description)}",
                )
                pendingCandidates.forEach { pending ->
                    peerConnection.addIceCandidate(pending)
                }
                pendingCandidates.clear()
                restartingIce = false
            } else if (result is Either.Right) {
                pubLog("remoteSet", "$type FAIL id=$offerId reason=${GravixStepLogs.reason(result.value)}")
            }
            return@launchRTCIfNotClosed result
        } ?: Either.Right("PCT is closed.")

        if (this.renegotiate) {
            this.renegotiate = false
            pubLog("offerReplay", "a sender change waited for this answer")
            this.createAndSendOffer()
        }

        return result
    }

    private val negotiateDebounced = debounce<MediaConstraints?, Unit>(20, coroutineScope) {
        if (it != null) {
            createAndSendOffer(it)
        } else {
            createAndSendOffer()
        }
    }

    /** Asks for an offer (debounced 20 ms; an offer already pending defers it until its answer). */
    val negotiate: (MediaConstraints?) -> Unit = { constraints ->
        pubLog("offerRequest", null, LoggingLevel.VERBOSE)
        negotiateDebounced(constraints)
    }

    private val offerLock = Mutex()
    private suspend fun createAndSendOffer(constraints: MediaConstraints = MediaConstraints()) {
        offerLock.withLock {
            // GRAVIX (0.1.2, offer serialisation): once an offer is started it is finished and sent. [negotiate]'s
            // debounce cancels the previous call's job; a job cancelled after setLocalDescription and before onOffer
            // left the publisher in have-local-offer with no offer at the server, and every later negotiate deferred
            // behind an answer that never came (GravixOfferSerialiseMockE2ETest).
            withContext(NonCancellable) {
                createAndSendOfferLocked(constraints)
            }
        }
    }

    private suspend fun createAndSendOfferLocked(constraints: MediaConstraints) {
        if (listener == null) {
            return
        }

        var offerId = -1
        var finalSdp: SessionDescription? = null

        // TODO: This is a potentially long lock hold. May need to break up.
        launchRTCIfNotClosed {
            val iceRestart =
                constraints.findConstraint(MediaConstraintKeys.ICE_RESTART) == MediaConstraintKeys.TRUE
            if (iceRestart) {
                pubLog("iceRestart")
                restartingIce = true
            }

            if (peerConnection.signalingState() == SignalingState.HAVE_LOCAL_OFFER) {
                // we're waiting for the peer to accept our offer, so we'll just wait
                // the only exception to this is when ICE restart is needed
                val curSd = peerConnection.remoteDescription
                if (iceRestart && curSd != null) {
                    // TODO: handle when ICE restart is needed but we don't have a remote description
                    // the best thing to do is to recreate the peerconnection
                    peerConnection.setRemoteDescription(curSd)
                } else {
                    renegotiate = true
                    pubLog("offerDefer", "have-local-offer: replayed after the answer")
                    return@launchRTCIfNotClosed
                }
            }

            // actually negotiate

            // increase the offer id at the start to ensure the offer is always > 0
            // so that we can use 0 as a default value for legacy behavior
            // this may skip some ids, but is not an issue.
            offerId = latestOfferId.incrementAndGet()

            val sdpOffer = when (val outcome = peerConnection.createOffer(constraints)) {
                is Either.Left -> outcome.value
                is Either.Right -> {
                    pubLog("offerCreate", "FAIL reason=${GravixStepLogs.reason(outcome.value)}")
                    return@launchRTCIfNotClosed
                }
            }
            pubLog("offerCreate", "id=$offerId restart=$iceRestart mlines=${GravixStepLogs.mlines(sdpOffer.description)}")

            if (isClosed()) {
                return@launchRTCIfNotClosed
            }
            // munge sdp
            val sdpDescription = sdpFactory.createSessionDescription(sdpOffer.description)

            val mediaDescs = sdpDescription.getMediaDescriptions(true)
            for (mediaDesc in mediaDescs) {
                if (mediaDesc !is MediaDescription) {
                    continue
                }
                if (mediaDesc.media.mediaType == "audio") {
                    // TODO
                } else if (mediaDesc.media.mediaType == "video") {
                    ensureVideoDDExtensionForSVC(mediaDesc)
                    ensureCodecBitrates(mediaDesc, trackBitrates = trackBitrates)
                }
            }
            finalSdp = setMungedSdp(sdpOffer, sdpDescription.toString())
        }

        finalSdp?.let { sdp ->
            val currentOfferId = latestOfferId.get()
            if (offerId < 0) {
                pubLog("offerDrop", "invalid offer id")
                return
            }
            if (currentOfferId > offerId) {
                pubLog("offerDrop", "id=$offerId stale (current $currentOfferId)")
                return
            }
            if (isClosed()) {
                // closed during the (non-cancellable) offer: a replaced transport's offer never reaches the server
                pubLog("offerDrop", "id=$offerId transport closed")
                return
            }
            pubLog("offerSend", "id=$offerId mlines=${GravixStepLogs.mlines(sdp.description)}")
            listener.onOffer(sdp, offerId)
        }
    }

    private suspend fun setMungedSdp(sdp: SessionDescription, mungedDescription: String, remote: Boolean = false): SessionDescription {
        val mungedSdp = SessionDescription(sdp.type, mungedDescription)

        // GRAVIX (0.1.2): never an SDP body in a log line (ice-pwd, ice-ufrag, fingerprints): type + m-lines only
        GxLog.v { "sdp: ${GravixStepLogs.describe(sdp.type.canonicalForm(), sdp.description)}, munged" }

        val mungedResult = launchRTCIfNotClosed {
            if (remote) {
                peerConnection.setRemoteDescription(mungedSdp)
            } else {
                peerConnection.setLocalDescription(mungedSdp)
            }
        } ?: Either.Right("PCT closed")

        val where = if (remote) "remoteSet" else "setLocal"
        val type = sdp.type.canonicalForm()
        val mungedErrorMessage = when (mungedResult) {
            is Either.Left -> {
                // munged sdp set successfully.
                pubLog(where, "$type ok munged=true")
                return mungedSdp
            }

            is Either.Right -> {
                if (mungedResult.value.isNullOrBlank()) {
                    "unknown sdp error"
                } else {
                    mungedResult.value
                }
            }
        }

        // munged sdp setting failed
        GxLog.w {
            "setting munged sdp for " +
                "${if (remote) "remote" else "local"} description, " +
                "${mungedSdp.type} type failed, falling back to unmodified."
        }
        GxLog.w { "error: ${GravixStepLogs.safeError(mungedErrorMessage)}" }
        pubLog(where, "$type FAIL munged=true reason=${GravixStepLogs.reason(mungedErrorMessage)} (retrying unmunged)")

        val result = launchRTCIfNotClosed {
            if (remote) {
                peerConnection.setRemoteDescription(sdp)
            } else {
                peerConnection.setLocalDescription(sdp)
            }
        } ?: Either.Right("PCT closed")

        if (result is Either.Right) {
            val errorMessage = if (result.value.isNullOrBlank()) {
                "unknown sdp error"
            } else {
                result.value
            }

            // sdp setting failed
            GxLog.w {
                "setting original sdp for " +
                    "${if (remote) "remote" else "local"} description, " +
                    "${sdp.type} type failed!"
            }
            GxLog.w { "error: ${GravixStepLogs.safeError(errorMessage)}" }
            pubLog(where, "$type FAIL munged=false reason=${GravixStepLogs.reason(errorMessage)}")
        } else {
            pubLog(where, "$type ok munged=false")
        }
        return sdp
    }

    fun prepareForIceRestart() {
        restartingIce = true
    }

    fun isClosed() = isClosed.get()

    fun closeBlocking() {
        runBlocking {
            close()
        }
    }

    suspend fun close() {
        launchRTCIfNotClosed {
            isClosed.set(true)
            peerConnection.dispose()
        }
        coroutineScope.cancel()
    }

    fun updateRTCConfig(config: RTCConfiguration) {
        executeRTCIfNotClosed {
            peerConnection.setConfiguration(config)
        }
    }

    fun registerTrackBitrateInfo(cid: String, trackBitrateInfo: TrackBitrateInfo) {
        trackBitrates[TrackBitrateInfoKey.Cid(cid)] = trackBitrateInfo
    }

    fun registerTrackBitrateInfo(transceiver: RtpTransceiver, trackBitrateInfo: TrackBitrateInfo) {
        trackBitrates[TrackBitrateInfoKey.Transceiver(transceiver)] = trackBitrateInfo
    }

    /** GRAVIX (2026-10-21): pin [transceiver]'s opus answer to [dtx] / [stereo] ([GravixOpusAnswer]). */
    internal fun registerOpusAnswerPrefs(transceiver: RtpTransceiver, dtx: Boolean, stereo: Boolean) {
        opusAnswerPrefs[transceiver] = GravixOpusAnswer.Prefs(dtx, stereo)
    }

    /** GRAVIX (2026-10-21): [transceiver] is no longer published (unpublish / rolled-back publish): no pin. */
    internal fun unregisterOpusAnswerPrefs(transceiver: RtpTransceiver) {
        opusAnswerPrefs.remove(transceiver)
    }

    internal val opusAnswerPrefCount: Int get() = opusAnswerPrefs.size

    suspend fun isConnected(): Boolean {
        return launchRTCIfNotClosed {
            peerConnection.isConnected()
        } ?: false
    }

    suspend fun iceConnectionState(): PeerConnection.IceConnectionState {
        return launchRTCIfNotClosed {
            peerConnection.iceConnectionState()
        } ?: PeerConnection.IceConnectionState.CLOSED
    }

    suspend fun connectionState(): PeerConnection.PeerConnectionState {
        return launchRTCIfNotClosed {
            peerConnection.connectionState()
        } ?: PeerConnection.PeerConnectionState.CLOSED
    }

    suspend fun signalingState(): SignalingState {
        return launchRTCIfNotClosed {
            peerConnection.signalingState()
        } ?: SignalingState.CLOSED
    }

    @OptIn(ExperimentalContracts::class)
    private suspend inline fun <T> launchRTCIfNotClosed(noinline action: suspend CoroutineScope.() -> T): T? {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        if (isClosed()) {
            return null
        }
        return launchBlockingOnRTCThread(rtcThreadToken) {
            return@launchBlockingOnRTCThread if (isClosed()) {
                null
            } else {
                action()
            }
        }
    }

    @OptIn(ExperimentalContracts::class)
    private fun <T> executeRTCIfNotClosed(action: () -> T): T? {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        if (isClosed()) {
            return null
        }
        return executeBlockingOnRTCThread(rtcThreadToken) {
            return@executeBlockingOnRTCThread if (isClosed()) {
                null
            } else {
                action()
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            config: RTCConfiguration,
            pcObserver: PeerConnection.Observer,
            listener: Listener?,
        ): PeerConnectionTransport
    }
}

private val transportCounter = AtomicInteger(0)

private const val DD_EXTENSION_URI = "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension"

/**
 * @suppress
 */
@VisibleForTesting
fun ensureVideoDDExtensionForSVC(mediaDesc: MediaDescription) {
    val codec = mediaDesc.getRtps()
        .firstOrNull()
        ?.second
        ?.codec ?: return
    if (!isSVCCodec(codec)) {
        return
    }

    var maxId = 0L

    val ddFound = mediaDesc.getExts().any { (_, ext) ->
        if (ext.uri == DD_EXTENSION_URI) {
            return@any true
        }
        if (ext.value > maxId) {
            maxId = ext.value
        }
        false
    }

    // Not found, add manually
    if (!ddFound) {
        mediaDesc.addAttribute(
            SdpExt(
                value = maxId + 1,
                uri = DD_EXTENSION_URI,
                config = null,
                direction = null,
                encryptUri = null,
            ).toAttributeField(),
        )
    }
}

/* The svc codec (av1/vp9) would use a very low bitrate at the beginning and
increase slowly by the bandwidth estimator until it reach the target bitrate. The
process commonly cost more than 10 seconds cause subscriber will get blur video at
the first few seconds. So we use a 70% of target bitrate here as the start bitrate to
eliminate this issue.
*/
private const val startBitrateForSVC = 0.7

/**
 * @suppress
 */
@VisibleForTesting
fun ensureCodecBitrates(
    media: MediaDescription,
    trackBitrates: Map<TrackBitrateInfoKey, TrackBitrateInfo>,
) {
    val msid = media.getMsid()?.value ?: return
    for ((key, trackBr) in trackBitrates) {
        if (key !is TrackBitrateInfoKey.Cid) {
            continue
        }

        val (cid) = key
        if (!msid.contains(cid)) {
            continue
        }

        val (_, rtp) = media.getRtps()
            .firstOrNull { (_, rtp) -> rtp.codec.equals(trackBr.codec, ignoreCase = true) }
            ?: continue
        val codecPayload = rtp.payload

        val fmtps = media.getFmtps()
        var fmtpFound = false
        for ((attribute, fmtp) in fmtps) {
            if (fmtp.payload == codecPayload) {
                fmtpFound = true
                var newFmtpConfig = fmtp.config
                if (!fmtp.config.contains("x-google-start-bitrate")) {
                    newFmtpConfig = "$newFmtpConfig;x-google-start-bitrate=${(trackBr.maxBitrate * startBitrateForSVC).roundToLong()}"
                }
                if (!fmtp.config.contains("x-google-max-bitrate")) {
                    newFmtpConfig = "$newFmtpConfig;x-google-max-bitrate=${trackBr.maxBitrate}"
                }
                if (fmtp.config != newFmtpConfig) {
                    attribute.value = "${fmtp.payload} $newFmtpConfig"
                    break
                }
            }
        }

        if (!fmtpFound) {
            media.addAttribute(
                SdpFmtp(
                    payload = codecPayload,
                    config = "x-google-start-bitrate=${trackBr.maxBitrate * startBitrateForSVC};" +
                        "x-google-max-bitrate=${trackBr.maxBitrate}",
                ).toAttributeField(),
            )
        }
    }
}

internal fun isSVCCodec(codec: String?): Boolean {
    return codec != null &&
        ("av1".equals(codec, ignoreCase = true) ||
            "vp9".equals(codec, ignoreCase = true))
}

/**
 * @suppress
 */
data class TrackBitrateInfo(
    val codec: String,
    val maxBitrate: Long,
)

/**
 * @suppress
 */
sealed class TrackBitrateInfoKey {
    data class Cid(val value: String) : TrackBitrateInfoKey()
    data class Transceiver(val value: RtpTransceiver) : TrackBitrateInfoKey()
}
