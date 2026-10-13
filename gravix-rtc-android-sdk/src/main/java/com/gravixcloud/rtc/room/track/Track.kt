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

package com.gravixcloud.rtc.room.track

import com.gravixcloud.rtc.events.BroadcastEventBus
import com.gravixcloud.rtc.events.TrackEvent
import com.gravixcloud.rtc.util.flowDelegate
import com.gravixcloud.rtc.webrtc.RTCStatsGetter
import com.gravixcloud.rtc.webrtc.getStats
import com.gravixcloud.rtc.webrtc.peerconnection.RTCThreadToken
import com.gravixcloud.rtc.webrtc.peerconnection.executeBlockingOnRTCThread
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import org.webrtc.MediaStreamTrack
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

abstract class Track(
    name: String,
    kind: Kind,
    open val rtcTrack: MediaStreamTrack,
    val rtcThreadToken: RTCThreadToken,
) {
    protected val eventBus = BroadcastEventBus<TrackEvent>()
    val events = eventBus.readOnly()
    var name = name
        internal set

    var kind = kind
        internal set
    var sid: String? = null
        internal set
    var streamState: StreamState by flowDelegate(StreamState.PAUSED) { newValue, oldValue ->
        if (newValue != oldValue) {
            eventBus.tryPostEvent(TrackEvent.StreamStateChanged(this, newValue))
        }
    }
        internal set

    var enabled: Boolean
        get() = withRTCTrack(defaultValue = false) { rtcTrack.enabled() }
        set(value) = withRTCTrack { rtcTrack.setEnabled(value) }

    var statsGetter: RTCStatsGetter? = null

    internal val isDisposed
        get() = rtcTrack.isDisposed

    /**
     * Return the [RTCStatsReport] for this track, or null if none is available.
     */
    suspend fun getRTCStats(): RTCStatsReport? = statsGetter?.getStats()

    /**
     * Calls the [callback] with the [RTCStatsReport] for this track, or null if none is available.
     */
    fun getRTCStats(callback: RTCStatsCollectorCallback) {
        statsGetter?.invoke(callback) ?: callback.onStatsDelivered(null)
    }

    enum class Kind(val value: String) {
        AUDIO("audio"),
        VIDEO("video"),

        // unknown
        UNRECOGNIZED("unrecognized"),
        ;

        fun toProto(): GravixcloudModels.TrackType {
            return when (this) {
                AUDIO -> GravixcloudModels.TrackType.AUDIO
                VIDEO -> GravixcloudModels.TrackType.VIDEO
                UNRECOGNIZED -> GravixcloudModels.TrackType.UNRECOGNIZED
            }
        }

        override fun toString(): String {
            return value
        }

        companion object {
            fun fromProto(tt: GravixcloudModels.TrackType): Kind {
                return when (tt) {
                    GravixcloudModels.TrackType.AUDIO -> AUDIO
                    GravixcloudModels.TrackType.VIDEO -> VIDEO
                    GravixcloudModels.TrackType.DATA, // TODO: does this need to be handled?
                    GravixcloudModels.TrackType.UNRECOGNIZED,
                    -> UNRECOGNIZED
                }
            }
        }
    }

    enum class Source {
        UNKNOWN,
        CAMERA,
        MICROPHONE,
        SCREEN_SHARE,
        SCREEN_SHARE_AUDIO,
        ;

        fun toProto(): GravixcloudModels.TrackSource {
            return when (this) {
                UNKNOWN -> GravixcloudModels.TrackSource.UNKNOWN
                CAMERA -> GravixcloudModels.TrackSource.CAMERA
                MICROPHONE -> GravixcloudModels.TrackSource.MICROPHONE
                SCREEN_SHARE -> GravixcloudModels.TrackSource.SCREEN_SHARE
                SCREEN_SHARE_AUDIO -> GravixcloudModels.TrackSource.SCREEN_SHARE_AUDIO
            }
        }

        companion object {
            fun fromProto(source: GravixcloudModels.TrackSource): Source {
                return when (source) {
                    GravixcloudModels.TrackSource.CAMERA -> CAMERA
                    GravixcloudModels.TrackSource.MICROPHONE -> MICROPHONE
                    GravixcloudModels.TrackSource.SCREEN_SHARE -> SCREEN_SHARE
                    GravixcloudModels.TrackSource.SCREEN_SHARE_AUDIO -> SCREEN_SHARE_AUDIO
                    GravixcloudModels.TrackSource.UNKNOWN,
                    GravixcloudModels.TrackSource.UNRECOGNIZED,
                    -> UNKNOWN
                }
            }
        }
    }

    enum class StreamState {
        ACTIVE,
        PAUSED,
        UNKNOWN,
        ;

        fun toProto(): GravixcloudRtc.StreamState {
            return when (this) {
                ACTIVE -> GravixcloudRtc.StreamState.ACTIVE
                PAUSED -> GravixcloudRtc.StreamState.PAUSED
                UNKNOWN -> GravixcloudRtc.StreamState.UNRECOGNIZED
            }
        }

        companion object {
            fun fromProto(state: GravixcloudRtc.StreamState): StreamState {
                return when (state) {
                    GravixcloudRtc.StreamState.ACTIVE -> ACTIVE
                    GravixcloudRtc.StreamState.PAUSED -> PAUSED
                    GravixcloudRtc.StreamState.UNRECOGNIZED -> UNKNOWN
                }
            }
        }
    }

    data class Dimensions(val width: Int, val height: Int)

    /**
     * Starts the track.
     */
    open fun start() {
        enabled = true
    }

    /**
     * Stops the track.
     */
    open fun stop() {
        enabled = false
    }

    /**
     * Disposes the track. Gravix will generally take care of disposing tracks for you.
     */
    open fun dispose() {
        withRTCTrack {
            rtcTrack.dispose()
        }
    }

    /**
     * Ensures the track is valid before attempting to run [action].
     */
    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withRTCTrack(crossinline action: MediaStreamTrack.() -> T) {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        withRTCTrack(Unit, action)
    }

    /**
     * Ensures the track is valid before attempting to run [action].
     *
     * @return the return value of [action], or [defaultValue] if the track is disposed.
     */
    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withRTCTrack(defaultValue: T, crossinline action: MediaStreamTrack.() -> T): T {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        if (isDisposed) {
            return defaultValue
        }
        return executeBlockingOnRTCThread(rtcThreadToken) {
            return@executeBlockingOnRTCThread if (isDisposed) {
                defaultValue
            } else {
                action(rtcTrack)
            }
        } ?: defaultValue
    }
}

sealed class TrackException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class InvalidTrackTypeException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class DuplicateTrackException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class InvalidTrackStateException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class MediaException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class PublishException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)
}

const val KIND_AUDIO = "audio"
const val KIND_VIDEO = "video"
