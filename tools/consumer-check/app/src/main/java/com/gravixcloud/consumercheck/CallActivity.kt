// Copyright 2026 Gravity Compile
// SPDX-License-Identifier: Apache-2.0
//
// The README's "Quickstart (Kotlin)" and "Region auto-pick" snippets, compiled against the published artifact so the
// documented API cannot silently drift from the real one.
package com.gravixcloud.consumercheck

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.regions.GravixRegionMeasureOptions
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegions
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer
import com.gravixcloud.rtc.room.track.VideoTrack
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {
    private val room by lazy { GravixRtc.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRegions(applicationContext)
        val renderer = SurfaceViewRenderer(this)
        setContentView(renderer)
        val url = intent.getStringExtra("url") ?: return
        val token = intent.getStringExtra("token") ?: return
        join(url, token, renderer)
    }

    private fun startRegions(context: Context) {
        GravixRegions.startMeasurement(
            context,
            GravixRegionMeasureOptions().apply { regionsUrl = "https://<your Gravix console host>/v1/regions" },
        )
    }

    // Call after RECORD_AUDIO and CAMERA permissions are granted.
    private fun join(url: String, token: String, renderer: SurfaceViewRenderer) {
        room.initVideoRenderer(renderer)
        lifecycleScope.launch {
            launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> (event.track as? VideoTrack)?.addRenderer(renderer)
                        is RoomEvent.Resuming, is RoomEvent.Reconnecting -> showReconnecting(true)
                        is RoomEvent.Resumed, is RoomEvent.Reconnected -> showReconnecting(false)
                        else -> Unit
                    }
                }
            }
            // e.g. wss://<region>.gravixcloud.com; the measured nearest region when one was measured
            room.connect(GravixRegionMeasurements.resolveConnectUrl(url), token)
            room.localParticipant.setMicrophoneEnabled(true)
            room.localParticipant.setCameraEnabled(true)
        }
    }

    private fun showReconnecting(on: Boolean) {
        title = if (on) "Reconnecting..." else "In call"
    }

    override fun onDestroy() {
        room.disconnect()
        super.onDestroy()
    }
}
