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

import android.content.Context;
import android.util.Log;

import com.gravixcloud.rtc.ConnectOptions;
import com.gravixcloud.rtc.events.DisconnectReason;
import com.gravixcloud.rtc.join.GravixJoin;
import com.gravixcloud.rtc.join.GravixJoinCredentials;
import com.gravixcloud.rtc.join.GravixJoinHooks;
import com.gravixcloud.rtc.join.GravixJoinLeave;
import com.gravixcloud.rtc.join.GravixJoinLog;
import com.gravixcloud.rtc.join.GravixJoinRequest;
import com.gravixcloud.rtc.join.GravixJoinTokenFetcher;
import com.gravixcloud.rtc.join.GravixJoinTokenProvider;
import com.gravixcloud.rtc.join.GravixPrejoin;
import com.gravixcloud.rtc.join.GravixPrejoinEndReason;
import com.gravixcloud.rtc.join.GravixRejoinAction;
import com.gravixcloud.rtc.join.GravixRejoinPolicy;
import com.gravixcloud.rtc.join.GravixSwitchBeforeConnect;
import com.gravixcloud.rtc.join.GravixTokenReuse;
import com.gravixcloud.rtc.room.Room;
import com.gravixcloud.rtc.util.GravixPubLog;
import com.gravixcloud.rtc.util.LoggingLevel;

import java.util.concurrent.CompletableFuture;

/**
 * The fast-join API from Java (doc/FAST_JOIN.md): a room list warms tokens, the tap starts the connect, the room
 * screen attaches to it, a switch goes from room to room, the exit leaves. Compiled with the sample (the Java
 * surface stays Java-friendly); not wired to the sample's screen.
 */
public final class FastJoinExample {

    /** Your server's token call (here a placeholder that fails). */
    public interface Backend {
        CompletableFuture<String> joinToken(String room, String identity, boolean canPublish);
    }

    private final GravixJoin join;
    private GravixRejoinPolicy rejoin;

    public FastJoinExample(Context context, String serverUrl, Backend backend) {
        // the step lines into your own logger (default: logcat via GravixRtc.logger); LoggingLevel.OFF silences them
        GravixJoinLog.setSink((level, line) -> Log.i("GravixJoin", line));
        GravixPubLog.setLevel(LoggingLevel.INFO);

        GravixJoinTokenFetcher fetcher = GravixJoinTokenFetcher.fromFuture(request ->
                backend.joinToken(request.getRoom(), request.getIdentity(), request.getCanPublish())
                        .thenApply(token -> new GravixJoinCredentials(serverUrl, token)));
        GravixJoinTokenProvider tokens = new GravixJoinTokenProvider(fetcher, GravixTokenReuse.FAST_JOIN);

        join = new GravixJoin.Builder(context, tokens)
                .setDefaults(new GravixJoinHooks.Builder()
                        .setConnectOptions(new ConnectOptions())
                        .build())
                .setListener(event -> Log.d("GravixJoin", event.toString()))
                .build();
    }

    /** A room card on screen, or under the finger: the token now, no connect. */
    public void onCardVisible(String roomId, String uid) {
        join.warmAsync(new GravixJoinRequest(roomId, uid));
    }

    /** The tap: the connect starts now, beside the navigation. */
    public GravixPrejoin onTap(String roomId, String uid) {
        return join.prepare(new GravixJoinRequest(roomId, uid));
    }

    /** A host's own Go Live: kept when Home is pressed before the room screen opens. */
    public GravixPrejoin onGoLive(String roomId, String uid) {
        GravixJoinHooks hooks = new GravixJoinHooks.Builder()
                .setAbandonInBackground(false)
                .build();
        return join.prepare(new GravixJoinRequest(roomId, uid, true), hooks);
    }

    /** The room screen's onCreate: the same connect; null = failed or ended (show the failure, close). */
    public void onRoomScreenCreated(String roomId, String uid, RoomScreen screen) {
        GravixPrejoin handle = join.prepare(new GravixJoinRequest(roomId, uid)); // the live one, or a cold join
        handle.attachAsync().thenAccept(room -> {
            if (room == null) {
                screen.close();
            } else {
                screen.show(room); // read room.getRemoteParticipants() now, then listen
            }
        });
        handle.endedFuture().thenAccept(reason -> {
            if (reason == GravixPrejoinEndReason.NEVER_SHOWN || reason == GravixPrejoinEndReason.ROOM_GONE) screen.close();
        });
        rejoin = new GravixRejoinPolicy.Builder(
                GravixRejoinAction.fromFuture(() -> join.prepare(new GravixJoinRequest(roomId, uid))
                        .attachAsync()
                        .thenApply(room -> room != null)),
                screen::isLiveOn)
                .setOnGaveUp(screen::showRetry)
                .build();
    }

    /** The room screen's onResume (e.g. a screen under an overlay): from now on Home never leaves the room. */
    public void onRoomScreenResumed(String roomId, String uid) {
        GravixPrejoin handle = join.pending(new GravixJoinRequest(roomId, uid));
        if (handle != null) handle.markShown();
    }

    /** The Room disconnected by itself while the live is on. */
    public void onRoomDisconnected(DisconnectReason reason) {
        if (rejoin != null) rejoin.sessionEnded(reason);
    }

    /** A gate after the tap refused the room (password, payment, ban). */
    public void onRefused(String roomId, String uid) {
        join.abandonAsync(new GravixJoinRequest(roomId, uid));
    }

    /** Room to room: the old session stops now, the new one connects once your screen closed. */
    public GravixPrejoin onSwitch(String roomId, String uid, RoomScreen oldScreen) {
        return join.switchTo(
                new GravixJoinRequest(roomId, uid),
                GravixSwitchBeforeConnect.fromFuture(() -> CompletableFuture.supplyAsync(() -> {
                    oldScreen.close();
                    return true;
                })));
    }

    /** Your own reconnect (viewer to guest with a new token): tell the join about the new Room. */
    public void onRoleChanged(String roomId, String uid, Room newRoom) {
        join.adopt(new GravixJoinRequest(roomId, uid, true), newRoom,
                GravixJoinLeave.fromFuture(room -> {
                    room.release();
                    return CompletableFuture.completedFuture(null);
                }));
    }

    /** The real exit; another account signed in. */
    public void onExit(boolean signedOut) {
        if (rejoin != null) rejoin.release();
        join.leaveAsync();
        if (signedOut) join.forgetAll();
    }

    /** The app's room screen, as this example needs it. */
    public interface RoomScreen {
        void show(Room room);

        void close();

        boolean isLiveOn();

        void showRetry();
    }
}
