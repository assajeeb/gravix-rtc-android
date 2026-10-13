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

package com.gravixcloud.rtc.sample.java;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gravixcloud.rtc.events.DisconnectReason;
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer;
import com.gravixcloud.rtc.room.Room;
import com.gravixcloud.rtc.room.track.LocalVideoTrack;
import com.gravixcloud.rtc.room.track.VideoTrack;
import com.gravixcloud.rtc.service.GravixRoomService;
import com.gravixcloud.rtc.service.GravixRoomServiceListener;

/**
 * Gravix Java sample: join a room with a server url and a token, publish microphone + camera, show the first remote
 * camera and the connection state ("Reconnecting..." during a resume or a rejoin, the region after a failover).
 * Everything goes through {@link GravixRoomService}: futures for actions, a listener for events, all on the main thread.
 */
public class MainActivity extends Activity {

    private static final int PERMISSIONS_REQUEST = 1;

    private GravixRoomService service;
    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;
    private TextView status;
    private EditText urlField;
    private EditText tokenField;
    private Button joinButton;
    private String remoteShown;
    private VideoTrack remoteTrack;

    private final GravixRoomServiceListener listener = new GravixRoomServiceListener() {
        @Override
        public void onConnectionStateChanged(Room.State state) {
            joinButton.setText(state == Room.State.DISCONNECTED ? "Join" : "Leave");
        }

        @Override
        public void onConnected() {
            showStatus("Connected");
        }

        @Override
        public void onReconnecting(boolean isResume) {
            showStatus(isResume ? "Reconnecting... (resuming)" : "Reconnecting... (rejoining)");
        }

        @Override
        public void onReconnected(boolean isResume) {
            showStatus("Connected");
        }

        @Override
        public void onConnectedUrlChanged(String url, String region) {
            showStatus("Connected to " + (region != null ? region : url));
        }

        @Override
        public void onDisconnected(DisconnectReason reason, Throwable error) {
            showStatus("Disconnected (" + reason + ")");
            clearRemote();
        }

        @Override
        public void onUserJoined(String uid) {
            showStatus(uid + " joined");
        }

        @Override
        public void onUserOffline(String uid) {
            showStatus(uid + " left");
        }

        @Override
        public void onRemoteVideoTrack(String uid, VideoTrack track) {
            if (remoteShown == null || remoteShown.equals(uid)) {
                clearRemote();
                remoteShown = uid;
                remoteTrack = track;
                track.addRenderer(remoteView);
            }
        }

        @Override
        public void onRemoteVideoTrackRemoved(String uid) {
            if (uid.equals(remoteShown)) {
                clearRemote();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = new GravixRoomService(this);
        service.addListener(listener);
        setContentView(buildUi());
        service.initVideoRenderer(localView);
        service.initVideoRenderer(remoteView);
    }

    private LinearLayout buildUi() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        urlField = new EditText(this);
        urlField.setHint("wss://<region>.gravixcloud.com");
        urlField.setText(BuildConfig.DEFAULT_URL);
        urlField.setSingleLine(true);
        tokenField = new EditText(this);
        tokenField.setHint("token (from your token server)");
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        joinButton = new Button(this);
        joinButton.setText("Join");
        joinButton.setOnClickListener(v -> {
            if (service.getConnectionState() == Room.State.DISCONNECTED) {
                joinWithPermissions();
            } else {
                service.disconnect();
            }
        });
        status = new TextView(this);
        status.setText("Disconnected");
        localView = new SurfaceViewRenderer(this);
        remoteView = new SurfaceViewRenderer(this);
        column.addView(urlField);
        column.addView(tokenField);
        column.addView(joinButton);
        column.addView(status);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(remoteView, half);
        column.addView(localView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return column;
    }

    private void joinWithPermissions() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (mic && camera) {
            join();
        } else {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, PERMISSIONS_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            join(); // publishing a source without its permission fails that source only (shown in the status)
        }
    }

    private void join() {
        String url = urlField.getText().toString().trim();
        String token = tokenField.getText().toString().trim();
        showStatus("Connecting...");
        service.connect(url, token)
            .thenCompose(v -> service.setMicrophoneEnabled(true))
            .thenCompose(micOn -> service.setCameraEnabled(true))
            .thenAccept(cameraOn -> {
                LocalVideoTrack local = service.localVideoTrack();
                if (local != null) {
                    local.addRenderer(localView);
                }
            })
            .exceptionally(error -> {
                showStatus("Failed: " + error.getMessage());
                return null;
            });
    }

    private void clearRemote() {
        if (remoteTrack != null) {
            remoteTrack.removeRenderer(remoteView);
        }
        remoteTrack = null;
        remoteShown = null;
    }

    private void showStatus(String text) {
        status.setText(text);
    }

    @Override
    protected void onDestroy() {
        service.release();
        localView.release();
        remoteView.release();
        super.onDestroy();
    }
}
