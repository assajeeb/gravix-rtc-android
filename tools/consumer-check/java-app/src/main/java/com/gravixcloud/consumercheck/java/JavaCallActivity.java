// Copyright 2026 Gravity Compile
// SPDX-License-Identifier: Apache-2.0
//
// The README's "Quickstart (Java)" and Java "Region auto-pick" snippets, compiled (Java only, no Kotlin plugin, the SDK
// alone on the classpath) against the published artifact so the documented Java API cannot silently drift.
package com.gravixcloud.consumercheck.java;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.gravixcloud.rtc.regions.GravixRegionMeasureOptions;
import com.gravixcloud.rtc.regions.GravixRegionMeasurementHandle;
import com.gravixcloud.rtc.regions.GravixRegionMeasurements;
import com.gravixcloud.rtc.regions.GravixRegions;
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer;
import com.gravixcloud.rtc.room.track.VideoTrack;
import com.gravixcloud.rtc.service.GravixRoomService;
import com.gravixcloud.rtc.service.GravixRoomServiceListener;

public class JavaCallActivity extends Activity {
    private static final String TAG = "GravixJava";
    private GravixRoomService service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startRegions(getApplicationContext());
        // The start-up region measurement as JSON, ready for the app's own analytics upload.
        String report = GravixRegionMeasurements.regionsMeasuredReport();
        Log.i(TAG, "regions report: " + (report == null ? 0 : report.length()) + " chars");
        SurfaceViewRenderer renderer = new SurfaceViewRenderer(this);
        setContentView(renderer);
        String url = getIntent().getStringExtra("url");
        String token = getIntent().getStringExtra("token");
        if (url != null && token != null) {
            join(url, token, renderer);
        }
    }

    private void startRegions(Context context) {
        GravixRegionMeasureOptions options = new GravixRegionMeasureOptions();
        options.setRegionsUrl("https://<your Gravix console host>/v1/regions");
        GravixRegionMeasurementHandle handle = GravixRegions.startMeasurement(context, options);
        handle.readyFuture().thenAccept(result -> { /* optional: result.getBest() */ });
    }

    // Call after RECORD_AUDIO and CAMERA permissions are granted.
    private void join(String url, String token, SurfaceViewRenderer renderer) {
        service = new GravixRoomService(this);
        service.initVideoRenderer(renderer);
        service.addListener(new GravixRoomServiceListener() {
            @Override
            public void onRemoteVideoTrack(String uid, VideoTrack track) {
                track.addRenderer(renderer);
            }

            @Override
            public void onReconnecting(boolean isResume) {
                setTitle("Reconnecting...");
            }

            @Override
            public void onReconnected(boolean isResume) {
                setTitle("In call");
            }
        });
        // e.g. wss://<region>.gravixcloud.com; the measured nearest region when one was measured
        service.connect(GravixRegionMeasurements.resolveConnectUrl(url), token)
            .thenCompose(v -> service.setMicrophoneEnabled(true))
            .thenCompose(micOn -> service.setCameraEnabled(true))
            .exceptionally(error -> {
                setTitle("Failed: " + error.getMessage());
                return null;
            });
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.release();
        }
        super.onDestroy();
    }
}
