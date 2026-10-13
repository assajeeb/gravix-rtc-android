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

import com.gravixcloud.rtc.room.participant.AudioTrackPublishDefaults
import com.gravixcloud.rtc.room.participant.VideoTrackPublishDefaults
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import com.gravixcloud.rtc.room.track.LocalVideoTrackOptions
import com.gravixcloud.rtc.room.track.ScreenSharePresets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @suppress
 */
@Singleton
class DefaultsManager
@Inject
constructor() {
    var audioTrackCaptureDefaults: LocalAudioTrackOptions = LocalAudioTrackOptions()
    var audioTrackPublishDefaults: AudioTrackPublishDefaults = AudioTrackPublishDefaults()
    var videoTrackCaptureDefaults: LocalVideoTrackOptions = LocalVideoTrackOptions()
    var videoTrackPublishDefaults: VideoTrackPublishDefaults = VideoTrackPublishDefaults()
    var screenShareTrackCaptureDefaults: LocalVideoTrackOptions = LocalVideoTrackOptions(isScreencast = true, captureParams = ScreenSharePresets.ORIGINAL.capture)
    var screenShareTrackPublishDefaults: VideoTrackPublishDefaults = VideoTrackPublishDefaults(videoEncoding = ScreenSharePresets.ORIGINAL.encoding)

    var isPrerecording: Boolean = false
}
