# Fast join with `GravixJoin` (0.1.2)

Joining a room fast takes more than `room.connect`: the token has to be fetched before the tap, the connect started
at the tap (not after the screen opened), handed to the room screen without a second connect, abandoned when the
user leaves before the room opens, and a switch from one room to another has to stop the old session at once and
start the new one the moment it closed. Apps used to write this by hand around `Room`. `GravixJoin`
(`com.gravixcloud.rtc.join`) is that code in the SDK. Everything in it is opt-in: `Room.connect` and
`GravixRoomService` behave as before unless you use it.

## Kotlin

```kotlin
// once per app (or per signed-in user)
val tokens = GravixJoinTokenProvider(
    GravixJoinTokenFetcher { r ->
        // your server mints the token (never the API secret in the app)
        GravixJoinCredentials("wss://sgp1.gravixcloud.com", myBackend.joinToken(r.room, r.identity, r.canPublish))
    },
    GravixTokenReuse.FAST_JOIN,
)
val join = GravixJoin.Builder(applicationContext, tokens)
    .setListener { event -> trace(event) }            // GravixJoinEvent per step, on the main thread
    .build()
GravixRegions.startMeasurement(applicationContext, measureOptions) // as before (README): joins dial the measured region

val request = GravixJoinRequest(roomId, uid, isHost)  // room + identity + publish right = one session

// a room list: every room on screen, the one under the finger
join.warmAsync(request)                               // the token into the cache, no connect

// the tap: the connect starts now, beside your own work and the navigation
join.prepare(request)

// the room screen (onCreate): the same connect, no second session
lifecycleScope.launch {
    val room = join.attach(request) ?: return@launch finish()   // null: failed or ended
    // read room.remoteParticipants and room.state now, then listen (events before attach are not replayed)
}

// a gate after the tap refused the room (password, payment, ban)
lifecycleScope.launch { join.abandon(request) }

// from one room to another (an in-room list, a banner)
join.switchTo(next, GravixSwitchBeforeConnect { closeOldScreen(); true })

// the real exit; another account signed in
lifecycleScope.launch { join.leave() }
join.forgetAll()
```

## Java

```java
GravixJoinTokenProvider tokens = new GravixJoinTokenProvider(
        GravixJoinTokenFetcher.fromFuture(request ->
                myBackend.joinToken(request.getRoom(), request.getIdentity(), request.getCanPublish())
                        .thenApply(token -> new GravixJoinCredentials(serverUrl, token))),
        GravixTokenReuse.FAST_JOIN);
GravixJoin join = new GravixJoin.Builder(getApplicationContext(), tokens)
        .setListener(event -> Log.d("join", event.toString()))
        .build();

GravixJoinRequest request = new GravixJoinRequest(roomId, uid);   // a viewer (canPublish false)
join.warmAsync(request);                                          // a card on screen
join.prepare(request);                                            // the tap

// the room screen's onCreate
join.prepare(request).attachAsync().thenAccept(room -> {          // completes on the main thread
    if (room == null) finish(); else show(room);
});

join.abandonAsync(request);                                       // a gate refused the room
join.switchTo(next, GravixSwitchBeforeConnect.fromFuture(() -> closeOldScreen()));
join.leaveAsync();                                                // the real exit
join.forgetAll();                                                 // another account
```

`sample-app-java/.../FastJoinExample.java` has every call (Go Live, adopt, rejoin) and compiles with the sample.

| Call | What it does |
|---|---|
| `warm(request)` / `warmAsync` | The token into the provider's cache. No connect. Never throws (null: no token). The engine has no standby pre-connect: warm is the token only. |
| `prepare(request[, hooks])` | Starts the join now and returns its `GravixPrejoin`; the same handle while that session (room + identity + publish right) is live. Another session is left first; the new connect starts after that leave returned. |
| `attach(request[, hooks])` / `attachAsync` | The connected `Room` of the session the tap started, or a cold join when there was none (with `hooks`). The screen claims the session at this call. Null when it failed or ended. |
| `switchTo(request, beforeConnect[, hooks])` | The old session stopped now, the token fetched and your `beforeConnect` run beside it, the new connect the moment both are done. `false` cancels. |
| `adopt(request, room[, leave])` | A Room you connected yourself (your own reconnect, e.g. a role change) becomes the current session, already attached. |
| `abandon` / `leave` / `forgetAll` | End a join nobody will use / the real exit / drop every cached token. |
| `current`, `pending(request)` | The live session / the live handle for a request. |
| Listener | `GravixJoinEvent`s on the tap's clock: `PREJOIN_START`, `TOKEN_READY` (`cached`/`fetched`), `URL_CHOSEN` (`token`/`measured`), `CONNECT_START`, `CONNECTED`, `ATTACHED`, `SHOWN`, `ABANDONED`, `LEFT`, `SWITCH_START`, `SWITCH_OLD_LEFT`, `SWITCH_CONNECTED`, `ADOPTED`, `CONNECT_FAILED`, `WARM`. |

Every Kotlin `suspend` call has a Java twin returning a `CompletableFuture` (`...Async`, `connectedFuture()`,
`endedFuture()`); hooks have `fromFuture(...)` adapters. Futures and listener calls complete on the main thread;
never block on them there.

## What is safe by default

- **The app leaves before the room opens.** A join is abandoned (its Room left) when the app goes to the background
  (`ProcessLifecycleOwner` ON_STOP, about 0.7 s after the last activity stopped) before `attach()`, or was started
  while the app was in the background after it had been visible. A join started before any activity of the process
  was ever visible (a cold start's first `onCreate`, a background push) is not abandoned for that: the attach timeout
  covers one nobody opens. An app that removed the `androidx.startup` initializer of `ProcessLifecycleOwner` has no
  background guard. `Builder.setAbandonInBackground(false)` for an app that joins in the background on purpose (a call
  answered from a notification); `GravixJoinHooks.Builder().setAbandonInBackground(false)` for one join, e.g. a
  host's own Go Live join started at the tap whose room screen opens only after your Go Live flow.
- **The room screen was built but never shown.** The screen claims the session when it *calls* `attach()` (call it
  in `onCreate`: the screen is up while the connect runs). From that call the join waits for a frame drawn with the
  app in the foreground; going to the background before that leaves the Room (`awaitEnded()` /
  `endedFuture()` give `NEVER_SHOWN`: close the screen; a pending `attach()` returns null). Once shown, Home never
  leaves, also while the connect still runs. `GravixJoinHooks.Builder().setLeaveIfNeverShown(false)` turns the watch
  off; `handle.markShown()` (e.g. from the room screen's `onResume`) says the screen is shown when you know better.
- **Nobody attaches.** The join leaves after the attach timeout (20 s, `Builder.setAttachTimeoutMs`, 0 = never). A
  screen that called `attach()` stops it.
- **A dead Room is an ended session.** A connected or attached join whose Room was disconnected after the connect
  (the server ended it, a kick, your own disconnect: `RoomEvent.Disconnected`) ends with `ROOM_GONE`, is not handed
  out again (`pending` / `current` return null, `prepare` / `attach` start a fresh join) and is **never left again**:
  your leave hook does not run (your own disconnect handling owns that cleanup). The SDK releases a dead Room only
  when it built it with the default factory and the default leave. A resume or a full reconnect is not a dead Room.
- **A failed join drops out** (`FAILED`; `error` holds the cause, e.g. a `GravixJoinTokenException`); keep the handle
  `prepare` returned to read it.
- **Abandon while joining** cancels the running connect, then leaves (no waiting for a join nobody wants).
- **One session at a time** inside one `GravixJoin`, one handle per session.

## Keeping your own connect

An app that builds its Room and runs its own steps around the connect (audio session, listeners, the microphone in
the first offer) keeps them:

```kotlin
join.prepare(request, GravixJoinHooks.Builder()
    .setCreateRoom { myService.createRoom() }                         // listeners bound
    .setConnect(GravixJoinConnect { room, c -> myService.connect(room, c.url, c.token) })
    .setLeave(GravixJoinLeave { room -> myService.teardown(room) })
    .build())
```

Java: `GravixJoinConnect.fromFuture((room, c) -> myService.connect(room, c.getUrl(), c.getToken()))`,
`GravixJoinLeave.fromFuture(room -> myService.teardown(room))`. The same hooks as `Builder.setDefaults(...)` apply to
every join. The default connect is `room.connect(url, token, connectOptions)`, the default leave
`room.disconnect()` then `room.release()`.

### Your own reconnect (role change)

A viewer becoming a guest usually reconnects with a new token whose `canPublish` differs: tell `GravixJoin` about the
new Room, or its current session stays the old one:

```kotlin
val room = myService.reconnectWithToken(newToken)
join.adopt(GravixJoinRequest(roomId, uid, true), room, GravixJoinLeave { myService.teardown(it) })
```

The previous session ends with `REPLACED` without its leave hook (you tore it down); one still joining is abandoned
normally.

## The pieces, usable on their own

| API | |
|---|---|
| `GravixJoinTokenProvider` | Per-request cache (16, least recently used out), one fetch per request at a time, failures not cached, 15 s timeout, `invalidate` / `invalidateAll`. |
| `GravixTokenReuse.FAST_JOIN` | A cached token is presented only while younger than half its lifetime and 5 min, with >= 90 s left (`DEFAULT`: >= 60 s left). |
| `GravixRejoinPolicy` | A viewer joins again when the live is back (`liveBack`) and after a session that ended by itself while the live is on (`sessionEnded(reason)`): 1/2/4/8 s, never after a kick, another device, a deleted room or a refused join; 3 per incident, 12 per live; an optional entry check. The app supplies `GravixLiveCheck` and the `GravixRejoinAction`. |

## Logging: `[GravixJoin]` and `[GravixPub]`

One secret-free line per step, on by default at INFO (independent of `GravixRtc.loggingLevel`), to
`GravixRtc.logger` (logcat by default) or your own sink:

```
[GravixJoin] prepare key=r1|u5c1e2a07|pub=false +0ms new
[GravixJoin] tokenOk key=r1|u5c1e2a07|pub=false +2ms via=cached
[GravixJoin] connectOk key=r1|u5c1e2a07|pub=false +905ms in=590ms
[GravixJoin] roomConnectOk key=r1|u5c1e2a07|pub=false in=588ms host=sgp1.gravixcloud.com region=sgp1
[GravixPub] offerCreate pc=pub#1 id=1 restart=false mlines=audio:0/sendonly,application:1
[GravixPub] offerDefer pc=pub#1 have-local-offer: replayed after the answer
[GravixPub] remoteSet pc=pub#1 answer ok id=1 replay=true mlines=audio:0/recvonly,application:1
```

Never in a line: a token, a JWT, an SDP body (ice-pwd, ice-ufrag, fingerprints), an ICE candidate, a url's path or
query (hosts only), an error message (types only), a full identity (`u` + a short stable hash, the same as the
Flutter SDK's). `GravixJoinLog.level` / `GravixPubLog.level` (`OFF`, `INFO`, `VERBOSE`) and `.sink`
(`GravixLogSink`); `GravixJoin.Builder.setLogSink` for one join object.

## Not in the SDK

Your backend's token call, your room rules (password, payment, bans, seats), screens and navigation, the switch's
visual cover and the server-side exit of the old room stay in the app.
