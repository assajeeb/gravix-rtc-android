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

package com.gravixcloud.rtc.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.gravixcloud.rtc.ConnectOptions;
import com.gravixcloud.rtc.GravixRtc;
import com.gravixcloud.rtc.RoomOptions;
import com.gravixcloud.rtc.events.DisconnectReason;
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer;
import com.gravixcloud.rtc.room.GravixIceTransport;
import com.gravixcloud.rtc.room.RetiredServerException;
import com.gravixcloud.rtc.room.Room;
import com.gravixcloud.rtc.room.track.LocalVideoTrack;
import com.gravixcloud.rtc.room.track.VideoTrack;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * GRAVIX(Phase 4): GravixRoomService as a Java app uses it. {@link #usage} is not run: it compiles = the API is usable
 * from Java (constructor overloads, futures, an anonymous listener overriding only what it needs, String uids, no
 * Kotlin types to satisfy). {@link RecordingListener} is driven end to end by GravixRoomServiceMockE2ETest.
 */
public class GravixRoomServiceJavaApiTest {

    /** Not run: proves the calls compile from Java. */
    @SuppressWarnings("unused")
    static void usage(Context context, SurfaceViewRenderer renderer) {
        GravixRoomService service = new GravixRoomService(context);
        GravixRoomService withOptions = new GravixRoomService(context, new RoomOptions());
        Room plainRoom = GravixRtc.create(context);
        service.initVideoRenderer(renderer);
        service.addListener(new GravixRoomServiceListener() {
            @Override
            public void onUserJoined(String uid) {
            }

            @Override
            public void onRemoteVideoTrack(String uid, VideoTrack track) {
                track.addRenderer(renderer);
            }

            @Override
            public void onReconnecting(boolean isResume) {
            }

            @Override
            public void onDisconnected(DisconnectReason reason, Throwable error) {
            }
        });
        CompletableFuture<Void> joined = service.connect("wss://sgp1.gravixcloud.com", "<token>");
        CompletableFuture<Void> joinedWithOptions = service.connect("wss://sgp1.gravixcloud.com", "<token>", new ConnectOptions());
        joined.thenCompose(v -> service.setMicrophoneEnabled(true))
            .thenCompose(micOn -> service.setCameraEnabled(true))
            .exceptionally(error -> false);
        // GRAVIX(retired servers): a retired Gravix server fails the join future with RetiredServerException
        service.connect("wss://<url from your token response>", "<token>").exceptionally(error -> {
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            if (cause instanceof RetiredServerException) {
                String retiredHost = ((RetiredServerException) cause).getHost();
            }
            return null;
        });
        CompletableFuture<Boolean> muted = service.muteLocalAudio(true);
        CompletableFuture<Boolean> remoteMuted = service.muteRemoteAudio("peer", true);
        CompletableFuture<Void> allMuted = service.muteAllRemoteAudio(false);
        CompletableFuture<Boolean> switched = service.switchCamera();
        LocalVideoTrack local = service.localVideoTrack();
        VideoTrack remote = service.remoteVideoTrack("peer");
        List<String> uids = service.getRemoteUids();
        String me = service.getLocalUid();
        Room.State state = service.getConnectionState();
        String url = service.getConnectedUrl();
        String region = service.getConnectedRegion();
        GravixIceTransport transport = service.getSelectedTransport();
        GravixIceTransport publisherTransport = service.getPublisherTransport();
        GravixIceTransport subscriberTransport = service.getSubscriberTransport();
        boolean onTcp = transport == GravixIceTransport.TCP;
        Room room = service.getRoom();
        CompletableFuture<Void> left = service.disconnect();
        service.release();
    }

    /** A listener written in Java; GravixRoomServiceMockE2ETest attaches it to a service on the mock engine. */
    public static class RecordingListener extends GravixRoomServiceListener {
        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onConnected() {
            calls.add("connected");
        }

        @Override
        public void onUserJoined(String uid) {
            calls.add("joined:" + uid);
        }

        @Override
        public void onReconnecting(boolean isResume) {
            calls.add("reconnecting:" + isResume);
        }

        @Override
        public void onReconnected(boolean isResume) {
            calls.add("reconnected:" + isResume);
        }

        @Override
        public void onDisconnected(DisconnectReason reason, Throwable error) {
            calls.add("disconnected");
        }

        @Override
        public void onReconnectAttempt(boolean isResume, String cause, int attempt, boolean afterNetworkChange) {
            calls.add("attempt:" + (isResume ? "resume" : "full") + ":" + cause + ":" + attempt + ":" + afterNetworkChange);
        }

        @Override
        public void onIceTransportChanged(GravixIceTransport transport, GravixIceTransport previous) {
            calls.add("transport:" + previous + "->" + transport);
        }

        @Override
        public void onSlowTcpTransport(long rttMs, long overMs) {
            calls.add("slowTcp:" + rttMs);
        }
    }

    /** GRAVIX(retired servers): the retired-server error from Java: its host, its message, an IllegalArgumentException. */
    @Test
    public void retiredServerExceptionIsUsableFromJava() {
        RetiredServerException error = new RetiredServerException("retired.example");
        assertEquals("retired.example", error.getHost());
        assertEquals("retired Gravix server (retired.example): use the url from your token response", error.getMessage());
        assertTrue(error instanceof IllegalArgumentException);
    }

    @Test
    public void unOverriddenCallbacksAreNoOps() {
        RecordingListener listener = new RecordingListener();
        GravixRoomServiceListener asBase = listener;
        asBase.onUserOffline("a");
        asBase.onActiveSpeakers(Collections.singletonList("a"));
        asBase.onConnectedUrlChanged("wss://x.example.test", null);
        asBase.onDataReceived(null, null, new byte[0]);
        asBase.onUserJoined("b");
        assertEquals(Collections.singletonList("joined:b"), listener.calls);
        assertTrue(listener.calls.size() == 1);
    }
}
