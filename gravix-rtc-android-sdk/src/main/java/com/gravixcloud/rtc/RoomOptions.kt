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
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.network.ReconnectPolicy
import com.gravixcloud.rtc.room.participant.AudioTrackPublishDefaults
import com.gravixcloud.rtc.room.participant.VideoTrackPublishDefaults
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import com.gravixcloud.rtc.room.track.LocalVideoTrackOptions

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
)
