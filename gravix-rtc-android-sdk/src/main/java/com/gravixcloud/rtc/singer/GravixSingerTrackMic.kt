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

package com.gravixcloud.rtc.singer

import com.gravixcloud.rtc.room.participant.AudioTrackPublishOptions
import com.gravixcloud.rtc.room.participant.LocalParticipant
import com.gravixcloud.rtc.room.track.LocalAudioTrack
import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import com.gravixcloud.rtc.room.track.LocalTrackPublication
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.util.GxLog

/** The published microphone as a [GravixSingerMic]. */
internal class GravixSingerTrackMic(
    private val local: LocalParticipant,
    private val pub: LocalTrackPublication,
    private val track: LocalAudioTrack,
) : GravixSingerMic {

    override val processing: LocalAudioTrackOptions get() = track.options

    override fun setProcessing(o: LocalAudioTrackOptions): Boolean {
        val r = track.applyOptions(o)
        if (r.isFailure) GxLog.w { "singer mode: voice processing not changed: ${r.exceptionOrNull()?.message}" }
        return r.isSuccess
    }

    override val dtx: Boolean get() = (pub.options as? AudioTrackPublishOptions)?.dtx ?: true

    override val maxBitrate: Int? get() = track.gravixMaxBitrate()

    override fun setMaxBitrate(bps: Int?): Boolean = track.gravixSetMaxBitrate(bps)

    override suspend fun republish(dtx: Boolean, maxBitrate: Int?): GravixSingerMic? {
        if (!local.gravixRepublishMicrophone(dtx, maxBitrate)) return null
        return of(local)
    }

    companion object {
        fun of(local: LocalParticipant): GravixSingerMic? {
            val pub = local.getTrackPublication(Track.Source.MICROPHONE) ?: return null
            val track = pub.track as? LocalAudioTrack ?: return null
            return GravixSingerTrackMic(local, pub, track)
        }
    }
}
