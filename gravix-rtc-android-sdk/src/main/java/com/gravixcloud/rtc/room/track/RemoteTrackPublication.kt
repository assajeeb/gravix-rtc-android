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

package com.gravixcloud.rtc.room.track

import com.gravixcloud.rtc.dagger.InjectionNames
import com.gravixcloud.rtc.events.TrackEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.room.participant.RemoteParticipant
import com.gravixcloud.rtc.util.debounce
import com.gravixcloud.rtc.util.invoke
import gravixcloud.GravixcloudModels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Named

@Suppress("TooManyFunctions") // the kept-publication steps of a full rejoin belong here
class RemoteTrackPublication(
    info: GravixcloudModels.TrackInfo,
    track: Track? = null,
    participant: RemoteParticipant,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    private val autoSubscribe: Boolean,
) : TrackPublication(info, track, participant) {

    override var track: Track?
        get() = super.track
        set(value) {
            if (value != super.track) {
                trackJob?.cancel()
                trackJob = null
            }

            super.track = value

            if (value != null) {
                trackJob = CoroutineScope(ioDispatcher).launch {
                    value.events.collect {
                        when (it) {
                            is TrackEvent.VisibilityChanged -> handleVisibilityChanged(it.isVisible)
                            is TrackEvent.VideoDimensionsChanged -> handleVideoDimensionsChanged(it.newDimensions)
                            is TrackEvent.StreamStateChanged -> handleStreamStateChanged(it)
                        }
                    }
                }

                if (value is RemoteVideoTrack && value.autoManageVideo) {
                    handleVideoDimensionsChanged(value.lastDimensions)
                    handleVisibilityChanged(value.lastVisibility)
                }
            }
        }

    private var trackJob: Job? = null

    var isDesired: Boolean = autoSubscribe
        private set
    private var disabled: Boolean = false
    private var videoQuality: VideoQuality? = VideoQuality.HIGH
    private var videoDimensions: Track.Dimensions? = null
    private var fps: Int? = null

    var subscriptionAllowed: Boolean = true
        internal set

    val isAutoManaged: Boolean
        get() = (track as? RemoteVideoTrack)?.autoManageVideo ?: false

    /**
     * Returns true if track is subscribed, and ready for playback
     *
     * @see [subscriptionStatus]
     */
    override val subscribed: Boolean
        get() {
            if (!isDesired || !subscriptionAllowed) {
                return false
            }
            return super.subscribed
        }

    val subscriptionStatus: SubscriptionStatus
        get() {
            return if (!isDesired || track == null) {
                SubscriptionStatus.UNSUBSCRIBED
            } else if (!subscriptionAllowed) {
                SubscriptionStatus.SUBSCRIBED_AND_NOT_ALLOWED
            } else {
                SubscriptionStatus.SUBSCRIBED
            }
        }

    override var muted: Boolean
        get() = super.muted
        set(v) {
            if (super.muted == v) {
                return
            }
            super.muted = v
            val participant = this.participant.get() as? RemoteParticipant ?: return
            if (v) {
                participant.onTrackMuted(this)
            } else {
                participant.onTrackUnmuted(this)
            }
        }

    /**
     * Subscribe or unsubscribe from this track
     */
    fun setSubscribed(subscribed: Boolean) {
        isDesired = subscribed
        val participant = this.participant.get() as? RemoteParticipant ?: return
        val participantTracks = with(GravixcloudModels.ParticipantTracks.newBuilder()) {
            participantSid = participant.sid.value
            addTrackSids(sid)
            build()
        }
        participant.signalClient.sendUpdateSubscription(isDesired, participantTracks)
    }

    /** GRAVIX (2026-11-05): this publication was kept across a full rejoin: its settings go to the new session once it is subscribed there. */
    internal var gravixSettingsToResend = false

    /**
     * GRAVIX (2026-11-05): a full rejoin: the new session knows nothing of what the app chose. Its subscription, when it
     * differs from what the connect's autoSubscribe gives by default.
     */
    internal fun gravixResendSubscription() {
        if (isDesired != autoSubscribe) setSubscribed(isDesired)
    }

    internal val gravixDisabled: Boolean
        get() = disabled

    /** GRAVIX (2026-11-05): this participant was dropped by the rejoin's grace and is back: what the app chose stays. */
    internal fun gravixRestoreChoice(wasDesired: Boolean?, wasDisabled: Boolean) {
        if (wasDesired != null && wasDesired != isDesired) setSubscribed(wasDesired)
        if (wasDisabled) {
            disabled = true
            gravixSettingsToResend = true
        }
    }

    /** GRAVIX (2026-11-05): the new session's track arrived: what the app chose for it goes out again. */
    internal fun gravixResendSettingsIfKept() {
        if (!gravixSettingsToResend) return
        gravixSettingsToResend = false
        sendUpdateTrackSettings.invoke()
    }

    /**
     * GRAVIX (2026-11-05): the server thinks this track is delivered, this side lost it: unsubscribes, then subscribes a
     * moment later, so the server really drops the old one and sends the track anew. A track the app does not want stays
     * as it is.
     */
    internal fun gravixPullAgain(scope: CoroutineScope, gapMs: Long = 500L) {
        if (!isDesired || !subscriptionAllowed) return
        val participant = this.participant.get() as? RemoteParticipant ?: return
        val participantTracks = with(GravixcloudModels.ParticipantTracks.newBuilder()) {
            participantSid = participant.sid.value
            addTrackSids(sid)
            build()
        }
        participant.signalClient.sendUpdateSubscription(false, participantTracks)
        scope.launch {
            delay(gapMs)
            if (track == null && isDesired) participant.signalClient.sendUpdateSubscription(true, participantTracks)
        }
    }

    /**
     * disable server from sending down data for this track
     *
     * this is useful when the participant is off screen, you may disable streaming down their
     * video to reduce bandwidth requirements
     */
    fun setEnabled(enabled: Boolean) {
        if (isAutoManaged || !subscribed || enabled == !disabled) {
            return
        }
        disabled = !enabled
        sendUpdateTrackSettings.invoke()
    }

    /**
     * For tracks that support simulcasting, directly adjust subscribed quality
     *
     * This indicates the highest quality the client can accept. If network bandwidth does not
     * allow, server will automatically reduce quality to optimize for uninterrupted video.
     *
     * Will override previous calls to [setVideoDimensions].
     */
    fun setVideoQuality(quality: VideoQuality) {
        if (isAutoManaged ||
            !subscribed ||
            quality == videoQuality ||
            track !is VideoTrack
        ) {
            return
        }
        videoQuality = quality
        videoDimensions = null
        sendUpdateTrackSettings.invoke()
    }

    /**
     * Update the dimensions that the server will use for determining the video quality to send down.
     *
     * Will override previous calls to [setVideoQuality].
     */
    fun setVideoDimensions(dimensions: Track.Dimensions) {
        if (isAutoManaged ||
            !subscribed ||
            videoDimensions == dimensions ||
            track !is VideoTrack
        ) {
            return
        }

        videoQuality = null
        videoDimensions = dimensions
        sendUpdateTrackSettings.invoke()
    }

    /**
     * Update the fps that the server will use for determining the video quality to send down.
     */
    fun setVideoFps(fps: Int?) {
        if (isAutoManaged ||
            !subscribed ||
            this.fps == fps ||
            track !is VideoTrack
        ) {
            return
        }

        this.fps = fps
        sendUpdateTrackSettings.invoke()
    }

    private fun handleVisibilityChanged(isVisible: Boolean) {
        disabled = !isVisible
        sendUpdateTrackSettings.invoke()
    }

    private fun handleVideoDimensionsChanged(newDimensions: Track.Dimensions) {
        videoDimensions = newDimensions
        sendUpdateTrackSettings.invoke()
    }

    private fun handleStreamStateChanged(trackEvent: TrackEvent.StreamStateChanged) {
        participant.get()?.onTrackStreamStateChanged(trackEvent)
    }

    // Debounce just in case multiple settings get changed at once.
    internal val sendUpdateTrackSettings = debounce<Unit, Unit>(100L, CoroutineScope(ioDispatcher)) {
        sendUpdateTrackSettingsImpl()
    }

    private fun sendUpdateTrackSettingsImpl() {
        val participant = this.participant.get() as? RemoteParticipant ?: return

        val rtcTrack = track?.rtcTrack
        if (rtcTrack is org.webrtc.VideoTrack) {
            rtcTrack.setShouldReceive(!disabled)
        }

        participant.signalClient.sendUpdateTrackSettings(
            sid,
            disabled,
            videoDimensions,
            videoQuality?.toProto(),
            fps,
        )
    }

    enum class SubscriptionStatus {
        /**
         * Has a valid track, receiving data.
         */
        SUBSCRIBED,

        /**
         * Has a track, but no data will be received due to permissions.
         */
        SUBSCRIBED_AND_NOT_ALLOWED,

        /**
         * Not subscribed.
         */
        UNSUBSCRIBED,
    }
}
