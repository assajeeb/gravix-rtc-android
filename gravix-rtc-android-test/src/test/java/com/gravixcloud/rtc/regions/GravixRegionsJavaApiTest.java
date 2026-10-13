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

package com.gravixcloud.rtc.regions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.content.Context;

import com.gravixcloud.rtc.GravixRecoveryOptions;
import com.gravixcloud.rtc.events.RoomEvent;
import com.gravixcloud.rtc.room.GravixConnectedUrlListener;
import com.gravixcloud.rtc.room.Room;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GRAVIX: the region API as a Java app sees it (compiles = usable from Java: static entry points, setters,
 * futures, a functional listener).
 */
public class GravixRegionsJavaApiTest {

    /** Not run: proves the calls compile from Java. */
    @SuppressWarnings("unused")
    static void usage(Context context, Room room) throws Exception {
        GravixRegionMeasureOptions options = new GravixRegionMeasureOptions();
        options.setRegionsUrl("https://console.example.test/v1/regions");
        GravixRegionMeasurementHandle handle = GravixRegions.startMeasurement(context, options);
        CompletableFuture<GravixRegionMeasurementResult> ready = handle.readyFuture();
        GravixRegionMeasurementResult result = ready.get();
        String url = GravixRegionMeasurements.resolveConnectUrl("wss://sgp1.gravixcloud.com");
        room.setGravixRecovery(GravixRecoveryOptions.UPSTREAM);
        GravixConnectedUrlListener listener = (RoomEvent.ConnectedUrlChanged event) -> {
            String now = event.getUrl();
            String region = event.getRegion();
        };
        room.addConnectedUrlListener(listener);
        String connected = room.getConnectedUrl();
        String connectedRegion = room.getConnectedRegion();
        room.removeConnectedUrlListener(listener);
        handle.stop();
    }

    @Test
    public void pureCallsWorkFromJava() {
        GravixRegionMeasurements.clear();
        List<GravixRegionUrl> candidates = Arrays.asList(new GravixRegionUrl("sgp1", "wss://sgp1.example.test"));
        assertNull(GravixRegionMeasurements.pickRegion("wss://sgp1.example.test", candidates));
        assertEquals("wss://x.example.test", GravixRegionMeasurements.resolveConnectUrl("wss://x.example.test"));
        assertEquals("ws://a.example.test", GravixRegionFailoverOrder.normalizeUrl("HTTP://A.example.test/"));
        assertEquals(161L, GravixRegionFailoverOrder.nearOnlyCapMs(61));
        assertFalse(GravixRecoveryOptions.UPSTREAM.getRegionStrategyEnabled());
        assertEquals(new GravixRecoveryOptions(), new GravixRecoveryOptions(true));
    }
}
