/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package com.gravixcloud.rtc.composesample

import androidx.compose.foundation.layout.Box
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.gravixcloud.rtc.composesample.ui.VideoRenderer
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.participant.Participant
import com.gravixcloud.rtc.room.track.CameraPosition
import com.gravixcloud.rtc.room.track.LocalVideoTrack
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.room.track.VideoTrack
import com.gravixcloud.rtc.sample.common.R
import com.gravixcloud.rtc.util.flow

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTrackPublications].
 */
@Composable
fun VideoItemTrackSelector(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
) {
    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    // Find the most appropriate video stream to show
    // Prioritize screen share, then camera, then any video stream.
    val videoPub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }
        ?: videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }
        ?: videoPubs.firstOrNull()

    val videoTrack = videoPub?.track as? VideoTrack
    var videoMuted by remember { mutableStateOf(false) }
    var cameraFacingFront by remember { mutableStateOf(false) }

    // monitor muted state
    LaunchedEffect(videoPub) {
        if (videoPub != null) {
            videoPub::muted.flow.collect { muted -> videoMuted = muted }
        }
    }

    // monitor camera facing for local participant
    LaunchedEffect(participant, videoTrack) {
        if (room.localParticipant == participant && videoTrack as? LocalVideoTrack != null) {
            videoTrack::options.flow.collect { options ->
                cameraFacingFront = options.position == CameraPosition.FRONT
            }
        }
    }

    if (videoTrack != null && !videoMuted) {
        VideoRenderer(
            room = room,
            videoTrack = videoTrack,
            mirror = room.localParticipant == participant && cameraFacingFront,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier) {
            Icon(
                painter = painterResource(id = R.drawable.outline_videocam_off_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
