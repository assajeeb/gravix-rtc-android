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

package com.gravixcloud.rtc

import com.gravixcloud.rtc.e2ee.E2EEOptions
import com.gravixcloud.rtc.noise.GravixNoiseSuppression
import com.gravixcloud.rtc.noise.GravixNoiseSuppressionOptions
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.network.ReconnectPolicy
import com.gravixcloud.rtc.room.participant.AudioTrackPublishDefaults
import com.gravixcloud.rtc.room.participant.VideoTrackPublishDefaults
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import com.gravixcloud.rtc.room.track.LocalVideoTrackOptions
import com.gravixcloud.rtc.service.GravixForegroundService
import com.gravixcloud.rtc.service.GravixForegroundServiceOptions

data class RoomOptions(
    /**
     * @see [Room.adaptiveStream]
     */
    val adaptiveStream: Boolean = false,

    /**
     * @see [Room.dynacast]
     */
    val dynacast: Boolean = false,

    /**
     * @see [Room.e2eeOptions]
     */
    val e2eeOptions: E2EEOptions? = null,

    val audioTrackCaptureDefaults: LocalAudioTrackOptions? = null,
    val videoTrackCaptureDefaults: LocalVideoTrackOptions? = null,
    val audioTrackPublishDefaults: AudioTrackPublishDefaults? = null,
    val videoTrackPublishDefaults: VideoTrackPublishDefaults? = null,
    val screenShareTrackCaptureDefaults: LocalVideoTrackOptions? = null,
    val screenShareTrackPublishDefaults: VideoTrackPublishDefaults? = null,

    /**
     * @see [Room.reconnectPolicy]
     */
    val reconnectPolicy: ReconnectPolicy? = null,

    /**
     * GRAVIX: @see [Room.gravixRecovery]. Null leaves the room's value (default: every switch on).
     */
    val gravixRecovery: GravixRecoveryOptions? = null,

    /**
     * GRAVIX (2026-10-21): on-device noise suppression of the microphone (RNNoise; DeepFilterNet3 "studio" on arm64
     * devices that pass the benchmark), with its pre-gain and AGC (quiet microphones reach a normal speech level).
     * ON by default (mode auto, owner decision 2026-10-21, as in the Flutter and JS SDKs). Applied by [Room.connect]
     * before the microphone is created; overwrites [Room.noiseSuppression]. Off:
     * `GravixNoiseSuppressionOptions(mode = GravixNoiseSuppressionMode.OFF)` (or [GravixNoiseSuppressionOptions.LEGACY]).
     * Null leaves [Room.noiseSuppression] as it is. Runtime mode changes: [GravixNoiseSuppression.setMode]; live
     * state: [GravixNoiseSuppression.state].
     */
    val noiseSuppression: GravixNoiseSuppressionOptions? = GravixNoiseSuppressionOptions(),

    /**
     * GRAVIX (2026-10-21): the Android call foreground service for this room (keeps the microphone and playback alive
     * in the background). Null (the default) = [GravixForegroundService.defaults], which is OFF unless the app turned
     * it on. `GravixForegroundServiceOptions(enabled = true)` turns it on for this room. See [GravixForegroundService].
     */
    val foregroundService: GravixForegroundServiceOptions? = null,
) {
    /** The options before [foregroundService] (2026-10-21): keeps that JVM constructor for Java and binary callers. */
    constructor(
        adaptiveStream: Boolean,
        dynacast: Boolean,
        e2eeOptions: E2EEOptions?,
        audioTrackCaptureDefaults: LocalAudioTrackOptions?,
        videoTrackCaptureDefaults: LocalVideoTrackOptions?,
        audioTrackPublishDefaults: AudioTrackPublishDefaults?,
        videoTrackPublishDefaults: VideoTrackPublishDefaults?,
        screenShareTrackCaptureDefaults: LocalVideoTrackOptions?,
        screenShareTrackPublishDefaults: VideoTrackPublishDefaults?,
        reconnectPolicy: ReconnectPolicy?,
        gravixRecovery: GravixRecoveryOptions?,
        noiseSuppression: GravixNoiseSuppressionOptions?,
    ) : this(
        adaptiveStream, dynacast, e2eeOptions, audioTrackCaptureDefaults, videoTrackCaptureDefaults,
        audioTrackPublishDefaults, videoTrackPublishDefaults, screenShareTrackCaptureDefaults,
        screenShareTrackPublishDefaults, reconnectPolicy, gravixRecovery, noiseSuppression, null,
    )

    /**
     * The options before [noiseSuppression] (2026-10-21): keeps the earlier JVM constructor for Java and binary
     * callers. Noise suppression is on (auto), as with the primary constructor's default.
     */
    constructor(
        adaptiveStream: Boolean,
        dynacast: Boolean,
        e2eeOptions: E2EEOptions?,
        audioTrackCaptureDefaults: LocalAudioTrackOptions?,
        videoTrackCaptureDefaults: LocalVideoTrackOptions?,
        audioTrackPublishDefaults: AudioTrackPublishDefaults?,
        videoTrackPublishDefaults: VideoTrackPublishDefaults?,
        screenShareTrackCaptureDefaults: LocalVideoTrackOptions?,
        screenShareTrackPublishDefaults: VideoTrackPublishDefaults?,
        reconnectPolicy: ReconnectPolicy?,
        gravixRecovery: GravixRecoveryOptions?,
    ) : this(
        adaptiveStream, dynacast, e2eeOptions, audioTrackCaptureDefaults, videoTrackCaptureDefaults,
        audioTrackPublishDefaults, videoTrackPublishDefaults, screenShareTrackCaptureDefaults,
        screenShareTrackPublishDefaults, reconnectPolicy, gravixRecovery, GravixNoiseSuppressionOptions(), null,
    )
}
