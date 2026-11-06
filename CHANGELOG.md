# Changelog — Gravix Cloud Android SDK

Upstream (LiveKit Android SDK) history up to the fork point: `doc/upstream/CHANGELOG.md`.

## 0.4.0 (2026-11-05)

Audio-only regions, ported from the Flutter SDK 0.8.0 to 0.8.3. The default join changes: nothing declared now lands on
the fastest region, audio-only ones included. Additive API, but Kotlin callers of `GravixRecoveryOptions.copy` recompile
(new trailing properties; the Java constructors are kept).

### Fixed
- The microphone is no longer lost when a region move, or any full reconnect, starts just as it is published. A publish
  that a full reconnect interrupts, or that starts between two sessions, goes out on the new session, with no app action,
  and the call answers with that. A mute during the move wins. Before, the app could say the mic was on with no
  publication behind it. Rollback: `GravixRecoveryOptions.publishFollowsReconnect = false`.
- A track that reaches the new session before the reconnect's republish is kept. Before, the republish took that
  publication down and sent the same track again; the SFU doesn't answer that, so after the 20 s publish deadline the mic
  was stopped. Rollback: `GravixRecoveryOptions.republishKeepsNewSessionTracks = false`.
- What landed on a session that a later attempt of the same reconnect replaced is republished too.
- A session that sees video in the room while a reconnect holds the engine, or before it is connected, keeps trying to move
  for up to 60 s, with the region its first try chose. Before, it gave up and the viewer stayed on an audio-only region
  with no video.

### Changed: participants across a full reconnect
- Remote participants are kept across a full reconnect: the same objects and no `ParticipantDisconnected`. Before, everyone
  was reported as left on every full reconnect, and those back in the JoinResponse were not announced again. Tracks are
  unsubscribed and arrive again on the same publications.
- A participant the new JoinResponse doesn't name gets a grace before being reported as left: 5 s on the same server
  (`rejoinAbsentGraceMs`), 35 s after a move to another server (`rejoinAbsentMoveGraceMs`), where the room may be new. Media
  arriving proves they are there. One who was dropped and returns is subscribed to again.
- Rollback: `GravixRecoveryOptions.keepParticipantsOnRejoin = false`.

### Fixed: found by review before release
- A camera whose enable failed, or that was left on in an earlier session, is no longer turned on by a later move.
- A camera turned off while its move-first runs is not published, and the camera isn't opened until the move is done.
- A mute while the reconnect's republish is out now wins. An enable in that window waits for the republish instead of
  stopping the track it uses.
- The signal dropping during the republish no longer loses what it holds: the next full reconnect publishes it.
- What the app chose for a kept participant's tracks (enabled, quality, a subscription) goes to the new session.
- An unmute during a network outage reaches the publication (a regression in earlier 0.4.0 builds, never released).
- A rejoin within 35 s of a move keeps the long grace. A direct or server mute of the camera stops a later move turning it
  on. Reconnects that follow a signal drop during the republish stop after 5 in a row, and the room disconnects.
- An enable or disable still waiting when the session was left, or when a later call changed the answer, doesn't take effect.
  A camera or mic asked for with no session opens nothing.
- Off, on, off during a reconnect ends off; an enable waiting on a reconnect that ends in a leave opens nothing.
- A kept participant who arrives after the JoinResponse gets their subscriptions restored, and one dropped by the grace
  who comes back keeps what the app chose.
- `publication.muted = false` counts as turning the source on again for the next reconnect.
- A move with a measured target no longer needs the region list. A camera-on move no longer notes the region as refusing.
- Crash, from the upstream base: a backup codec's AddTrack ending with an error (its deadline, a closing socket) was an
  uncaught exception.

### Known limit
- A camera or mic turned off during a reconnect sends nothing, but its capture runs until the reconnect's republish closes
  it: the camera light stays on until the reconnect ends.

### Rollback: back to 0.3.x
`GravixRegionMedia.autoDetect = false` and `room.gravixRecovery = GravixRecoveryOptions.LIKE_0_3`. The preset turns off only
what 0.4.0 added; the recovery 0.3.x had stays on (unlike `UPSTREAM`). With `autoDetect` off nothing moves first and the
server is asked, as before. These stay in any case, with no switch: a failed or old camera enable isn't turned on later, the
backup codec crash fix, and republishing what landed on a replaced attempt.

### Changed: which region a move goes to
- When the fastest measured region is audio-only, the other video regions are measured too, in the background: never on the
  join path, at most once per region-list lifetime and network, with the same budget. So a move to video really goes to the
  nearest video region.
- A move now goes to the region video joins use, with its hysteresis, so two near-equal regions don't flip between steps. A
  `[GravixRegion] mediaTarget` log line says why it was chosen.

### Changed: rejoining a room
- A room is remembered as having video only if it still had video when the session left (remote video or your own camera),
  and for 10 minutes at most. Rejoining a room with no video goes to the fastest region by the normal path; rejoining one
  that still has video goes straight to a video region.
- A muted camera no longer counts as video in the room: nobody moves for it until it is unmuted.

### Changed: turning video on from an audio-only region moves first
- Turning on the camera or a screen share on a region known not to carry video no longer tries the publish there. The
  session moves once to the fastest measured region that carries video (log `mediaFailover ... cause=camera-on` or
  `cause=screen-on`), then publishes. `setCameraEnabled` returns when that is done: true when the camera is live.
- With no region carrying video, a failed move, or `mediaRefusalFailover` off, it returns false at once.
- A server refusal (`media_not_allowed`, reached with a stale region list) now ends the publish at once, moves, and
  `setCameraEnabled` answers with the republish. Before, the first camera RTP came about 20.8 s after the call (the
  20 s `addTrack` deadline held the publish lock), and the call returned false although the camera came up.

### Changed: audio-only regions need no declaration (auto-detect)
Ported from the Flutter SDK 0.8.0.

- A join with no `media` now goes to the fastest region, audio-only ones included. Nothing is asked of the server
  first, so joins are not slower, and tokens are unchanged.
- If the room carries video, at join or later, the session moves once to the fastest measured region that carries
  video. Before, someone watching on such a region got no video and wasn't told.
- A room seen with video goes straight to a region that carries video for as long as the app process runs. Nothing is
  stored.
- `media` stays optional. `GravixRoomMedia.VIDEO` or `.AUDIO` decides where the join lands first; a session on a region
  without video still moves once if the room carries video.
- `GravixRegionMedia.joinMedia()` with nothing declared now returns `AUDIO`, with step line `media=audio cause=auto`.
- Rollback: set `GravixRegionMedia.autoDetect = false` before the first join for the old behaviour, where no `media`
  means video.

## 0.3.2 (2026-11-02)

Crash fix from the Flutter SDK 0.7.5. No API changes.

### Fixed: crash after a refused microphone start
- When Android refused to start the microphone, the next start aborted the app (SIGABRT at `jvm.cc:81` under
  `AudioRecordJni::StartRecording`). That next start could come from a mic toggle or from the same negotiation, within
  milliseconds, so the app could crash during a join.
- The next start now retries instead. The microphone stays silent until it succeeds. An error callback set through the
  audio device module customizer still receives each refusal. An audio device module the app supplies itself does not
  get this fix.

## 0.3.1 (2026-10-28)

Join fixes ported from the Flutter SDK 0.7.2. Additive API.

### Fixed: a join that hit a server restart failed for good
- A first connect whose last url answered the WebSocket with 502/503/504 and no reason text (the SFU restarting behind
  its proxy, e.g. during a drain roll) dials that url again, at most twice, after 0.5 s and 1.5 s
  (`GravixRecoveryOptions.retryServerRestart`, on by default; off in `UPSTREAM`). Field 2026-10-27 (dac1): such a join
  failed in 0.1 s while the new server process was up a second later. A region failover still comes first; refusals
  with a reason (401/403, "app user limit reached", "app plan limit reached") stay final. Step line
  `[GravixJoin] joinRetry n= in= status=`.
- Kotlin callers of `GravixRecoveryOptions.copy` recompile (a new trailing property); the Java constructors are kept.

### Added: `GravixJoin.Builder.setRetryCapacityRefusals(true)` (opt-in, off by default)
- A join the server refuses because the app owner is at its concurrent-user cap ("app user limit reached") is tried
  again silently on the same Room, waiting 1, 2, 3, 5, 8 s, then every 10 s (each +-20 %), until it connects, fails
  otherwise, or the join ends (left, abandoned, superseded, the attach timeout): the screen's `attach()` just waits
  longer. The cap stays enforced on the server; "app plan limit reached" (billing) and every other error still fail at
  once. `GravixJoin.isCapacityRefusal(e)`; a `connect` hook must keep the Room and let the SDK's `ConnectException`
  through (as the error or its cause) for it to be retried.
- Each refused attempt still emits the Room's `RoomEvent.FailedToConnect` and `RoomEvent.Disconnected(JOIN_FAILURE)`
  to listeners bound in a `createRoom` hook.
- Step lines `capacityRetry n= in=` per wait, `capacityRetryOk after= waited=` when it connects.

### Added: subscriber answer timing lines
- `[GravixPub] subOffer pc=sub#N received id=` and `subAnswer pc=sub#N <step> +<ms>ms` (setRemoteDescriptionDone,
  createAnswerDone, setLocalDescriptionDone, answerSent, answerFailed), timed from the offer's arrival. Field
  2026-10-27 (dac1): the SFU had its offer out in 0.2 s and the phone's answer arrived seconds later; nothing on the
  phone said where.

### Not ported: the standby upgrade floor (Flutter 0.7.2)
- This SDK has no standby / pre-connect WebSocket (`GravixJoin.warm` fetches the token only), so there is no upgrade
  wait to raise.

## 0.3.0 (2026-10-27)

### Added: on-device join-token signing
- `GravixTokenBuilder.create(appId, appCertificate, GravixTokenOptions)` signs a join token (HS256 JWT) on the device, no
  network: byte-for-byte the token the npm `gravix-rtc-token` makes for the same inputs and clock (vectors generated
  from the JS build). Same validation as JS (empty / `_`-containing App ID, certificate under 32 characters, empty room
  or identity, ttl outside (0, 24 h], default 6 h); errors never contain the certificate. No new dependency.
- `GravixJoinTokenProvider.local(appId, appCertificate, url, ttlSeconds, options)`: a provider that signs per request
  and caches like any other (no token round trip). No region list: the join uses the start-up measurement as for a
  token response without one, and a video join whose token url is an audio-only region (e.g. doh1) moves to a region
  that allows video (the 4b5d7e0e media-pick fix).
- Security: the App Certificate is then on the device; anyone who extracts it can sign tokens for any identity and room
  of the app until it is rotated. Deliver it from the app's own server to signed-in users only.

### Fixed: a video join no longer lands on an audio-only region it never measured
- When the token's url is a region that allows audio only (doh1) and this device never measured it (no region list
  either), a video join goes to the best measured region allowing video, else the first shortlist region allowing it;
  an audio join stays. Before, the camera publish was refused (`media_not_allowed`). Lookup only, no network call.

## 0.2.0 (2026-10-22)

On-device noise suppression (on by default), an opt-in call foreground service, region-aware join, and DTX/stereo
publisher-answer fixes. Additive API; one behaviour change: the microphone is now noise-suppressed unless the app opts out.

### Added: noise suppression, on (auto) by default
- `RoomOptions.noiseSuppression` defaults to `GravixNoiseSuppressionOptions()` (mode `AUTO`: RNNoise everywhere,
  DeepFilterNet3 on arm64 devices that pass the static check and a first-run benchmark), as in the Flutter and JS SDKs.
  Opt out with `GravixNoiseSuppressionOptions.OFF` / `LEGACY` (mode `OFF`: WebRTC's own NS/AGC).
- A connect with the default options never undoes an earlier `configure` (the app's off, or a runtime `setMode`).
- Quiet-mic and DFN3 fixes (gxns resynced from the Flutter SDK): quiet microphones count as talk again, word onsets open
  the gate at once, the pause gate follows sure talk, and DFN3 no longer erases speech from a quiet mic.

### Added: call foreground service (opt-in)
- `RoomOptions(foregroundService = GravixForegroundServiceOptions(enabled = true))` keeps the microphone and playback
  alive in the background; the hold is kept through rejoins.
- `GravixForegroundService.hold()` for apps with their own rejoin logic. A start while a stop is pending starts again.
- README: Play Console foreground-service declarations apply even when unused, plus a `tools:node="remove"` manifest
  snippet for apps that do not use the service.

### Added: region-aware join (media)
- `GravixJoinRequest.media` / `ConnectOptions.media`: regions advertising a `media` list (e.g. audio-only) are
  skipped for joins they do not take, ordered last in failover (never dropped); a `media_not_allowed` refusal moves once
  and the wanted camera is published again.

### Fixed
- The publisher answer keeps the microphone on DTX (`usedtx=1`, mono); a silent mic no longer sends ~37 kbps.
  `AudioTrackPublishOptions.stereo` publishes explicit stereo (no DTX).
- A cancelled connect now settles `DISCONNECTED` and emits `Disconnected(CLIENT_INITIATED)`, releasing the call service;
  the room can connect again.
- The publisher answer pin goes with its publication (unpublish / rolled-back publish).

## 0.1.2 (2026-10-15)

Fast join in the SDK, a publisher negotiation fix, and secret-free step logs, ported from the Flutter SDK 0.4.19
where they fit this engine (tests first on the mock engine). Purely additive API; `Room.connect` and
`GravixRoomService` are unchanged for an app that does not use the new classes.

### Fixed: a negotiation request during an offer's setLocalDescription left the publisher stuck
- The publisher's negotiation is debounced (20 ms) and each new request cancelled the previous one's job. A request
  that came while the previous offer was being set locally (tens of ms on a phone) cancelled it after
  setLocalDescription and before the offer was sent: the publisher stayed in have-local-offer with no offer at the
  server, and every later negotiation waited behind an answer that never came (a track published then was never
  negotiated until a reconnect). A started offer is now always finished and sent; the newer request waits for its
  answer as before (`GravixOfferSerialiseMockE2ETest`).
- Checked and not present here: the Flutter SDK's two offers in flight (a debounced negotiation and the answer's
  replay each building an offer, refused as "order of m-lines"): this engine creates offers under a lock and defers
  while an offer is pending (the test passes on 0.1.1 code).

### Added: `GravixJoin` (`com.gravixcloud.rtc.join`, `doc/FAST_JOIN.md`)
- `GravixJoin` (Builder): `warm` / `warmAsync` (token only: the engine has no standby pre-connect), `prepare`,
  `attach` / `attachAsync`, `abandon`, `switchTo` (with `GravixSwitchBeforeConnect`), `adopt`, `leave`, `forgetAll`,
  `current`, `pending`; `GravixPrejoin` handles (`attach`, `markShown`, `abandon`, `leave`, `awaitConnected` /
  `connectedFuture`, `awaitEnded` / `endedFuture`, `state`, `endReason`, `error`); `GravixJoinEvent`s per step.
- One session at a time (the old leave before the new connect); the tap's connect handed to the room screen; a join
  abandoned when the app goes to the background before attach (`ProcessLifecycleOwner`; a cold start's first
  `onCreate` is not "background"), or before the attached screen was shown; attach timeout 20 s; a dead Room ends the join (`ROOM_GONE`) and is never left again; abandon
  cancels a running connect, then leaves. A join dials the measured nearest region when the token's url is one of
  the measured regions (`GravixRegionMeasurements.resolveConnectUrl`).
- `GravixJoinTokenProvider` (per-request cache of 16, in-flight sharing, failures not cached, 15 s timeout) with
  `GravixTokenReuse.FAST_JOIN` (younger than half its lifetime and 5 min, >= 90 s left); `GravixJoinTokenFetcher`
  (`fromFuture`, `fromTokenSource`).
- `GravixRejoinPolicy`: a viewer's rejoin when the live is back / after a session that ended by itself (1/2/4/8 s,
  reason filter, 3 per incident, 12 per live, entry check).
- Java: every suspend call has a `CompletableFuture` twin; hooks have `fromFuture` adapters; builders and plain
  classes only (no data classes with default arguments). `sample-app-java` has `FastJoinExample.java`.
- New `implementation` dependency: `androidx.lifecycle:lifecycle-process` (no lifecycle type in the public API).

### Added: `[GravixJoin]` and `[GravixPub]` step logs
- One secret-free line per join step (`GravixJoin`, and `Room.connect`: `roomConnect`, `regionFailover`,
  `roomConnectOk`, `roomConnectFail`) and per publisher negotiation step (offer created / deferred / sent /
  replayed, local and remote descriptions set or refused with libwebrtc's reason, the m-line list).
- **On by default at INFO, also with `GravixRtc.loggingLevel` OFF** (a few lines per connect and publish), to
  `GravixRtc.logger` (logcat by default). `GravixJoinLog.level` / `GravixPubLog.level` = `LoggingLevel.OFF`
  silences them; `.sink` (`GravixLogSink`) sends them to your own logger.

### Changed: no secret in any log line
- With SDK logging on (VERBOSE), 0.1.1 logged whole signalling requests and responses (SDP offers and answers with
  ice-pwd / ice-ufrag / fingerprints, the TURN credential of the JoinResponse), ICE candidates with their ufrag, the
  connect url with its query, the E2EE ratchet key and full SDP bodies. Now: the message type only, candidates as
  mid / type / protocol, hosts only, libwebrtc errors cut before any SDP. `GxRedact` also redacts SDP secret
  attributes and a candidate's ufrag (`GravixLogSecretsMockE2ETest`).

## 0.1.1 (2026-10-15)

Recovery after a phone's network change, ported from the Flutter SDK 0.4.18 where the hazard exists in this
engine (found with tests on the mock engine first). The new switches are in `GravixRecoveryOptions`, on by
default; off = the 0.1.0 behaviour; `GravixRecoveryOptions.UPSTREAM` turns them off. The events and getters are
observation only.

### Fixed: a short network drop resumes the session instead of rejoining it
- `resumeAfterNetworkSocketError` (on): a resume whose dial failed with a socket error (no answer from the server:
  the interface going away under the dial) while the device's network changed -- within `networkChangeWindowMs`
  (30 s) before the reconnect began, or since -- or reads offline, makes the next attempt a resume again instead
  of a full rejoin; two such failed resumes in a row still become a full rejoin. In 0.1.0 every attempt after the
  first was full unless the network reading said offline at that moment: a dial made as the Wi-Fi went away (the
  reading still said Wi-Fi) made the next attempt a full rejoin, and the others saw the participant leave and
  join. A socket error on a steady network, a refusal and a timed-out resume are a full rejoin as before.
- A network change is a network lost, or one that became available after the connect: the networks that already
  existed when the room registered its network callback (Android announces each of them at once) are not a change.

### Fixed: a new connection's transient FAILED
- `freshPeerFailedGraceMs` (3 s): a peer connection that has never connected and reports FAILED gets the grace to
  connect before the engine acts on it. Over ICE/TCP a new connection's first checks can fail at once (checking ->
  failed -> connected within ~0.1-1.7 s on a device). In 0.1.0 such a FAILED of a full rejoin's new connection
  made the rejoin end with `RoomEvent.Connected` instead of `RoomEvent.Reconnected` (Java: `onConnected()`
  instead of `onReconnected(false)`, so an app's "reconnecting" banner stayed), and a publisher that failed once
  on its first negotiation (subscriber-primary sessions) started a reconnect at once. A connection that was
  connected and fails is acted on at once, as before. With the switch on, a rejoin's new primary connection that
  stays down longer than the grace and then connects within the rejoin's wait also ends with `Reconnected`.

### Added: `RoomEvent.ReconnectAttempt`
- One per reconnect attempt, before its dial: `mode` (`RoomEvent.ReconnectMode.RESUME` / `FULL`), `cause`
  (`peerConnectionFailed`, `peerConnectionClosed`, `peerConnectionDisconnected`, `signal`, `networkAvailable`,
  `requested`; `reconnectRetry` for every later attempt), `attempt` (1 for the first of an outage) and
  `afterNetworkChange`. Java: `GravixRoomServiceListener.onReconnectAttempt(isResume, cause, attempt,
  afterNetworkChange)`. Emitted with every switch off too.

### Added: the session's ICE transport
- `Room.selectedTransport` (`GravixIceTransport.UDP` / `TCP` / `RELAY`: TCP when either peer connection runs over
  ICE/TCP, RELAY when either is relayed), `Room.publisherTransport`, `Room.subscriberTransport`; null until the
  first reading and after the session ended. From libwebrtc's selected-candidate-pair callback (no polling).
- `RoomEvent.IceTransportChanged` (`transport`, `previous`, `publisher`, `subscriber`) when it changes.
- `RoomEvent.SlowTcpTransport` (`rttMs`, `overMs`) once when a TCP session's round trip stays above `slowTcpRttMs`
  (1 s) for `slowTcpForMs` (10 s), again after it recovered (a TCP episode ends when the session leaves TCP). The
  round trip is read (one RTC-thread hop and one getStats per connected peer connection, each bounded by the
  interval) every `iceTransportWatchMs` (5 s) only while either connection is on TCP AND the session is steady:
  never during a resume or a full rejoin. A full rejoin starts the transport afresh (null until the new connections
  select their pairs). `iceTransportWatchMs = null`: nothing is observed (the getters stay null).
- Java: `GravixRoomService.getSelectedTransport()` / `getPublisherTransport()` / `getSubscriberTransport()`,
  `onIceTransportChanged(transport, previous)`, `onSlowTcpTransport(rttMs, overMs)`.

### Not ported (no such hazard in this engine)
- Flutter `resumeReasonNetworkWindow` (a resume after a network change tells the server `RR_SIGNAL_DISCONNECTED`
  instead of `RR_*_FAILED`): this SDK sends no reconnect reason at all (the server reads `RR_UNKNOWN` and never
  takes a resume for "UDP blocked"); pinned by a test. `RoomEvent.ReconnectAttempt` has no `reconnectReason`.
- Flutter `resumeBeforeFullOnNetworkFailure` (a connected connection's FAILED during a network change resumes
  before a full rejoin): here a FAILED already starts with a resume (upstream); pinned by a test.

### API
- `GravixRecoveryOptions` gains six constructor parameters at the end. Source compatible, and Java code keeps
  working unchanged (every `@JvmOverloads` constructor of 0.1.0 is still there). Not binary compatible for compiled
  Kotlin callers: the Kotlin default-arguments constructor and `copy()` changed signature (a data class), so a
  library compiled against 0.1.0 that builds a `GravixRecoveryOptions(...)` with named or default arguments, or
  calls `.copy(...)` on one, must be recompiled. Java callers of the `@JvmOverloads` constructors link unchanged.
  Apps recompile anyway.
- Java code that turned everything off by passing all 17 constructor arguments (instead of
  `GravixRecoveryOptions.UPSTREAM`) now gets the three new switches ON (`resumeAfterNetworkSocketError`,
  `freshPeerFailedGraceMs`, `iceTransportWatchMs`, at their defaults). Use `UPSTREAM` (or pass the new arguments)
  for "all off".

## 0.1.0 (2026-10-13)

First release, `com.gravixcloud:gravix-rtc-android:0.1.0` on Maven Central. Only this artifact is published; the
camerax and track-processors modules are not (0.1.0).

- **Base:** LiveKit Android SDK v2.28.2 (Apache-2.0), upstream history kept so syncs are merges, plus two
  fixes cherry-picked from v2.29.0: #1010 (a duplicate `SubscribedQualityUpdate` no longer crashes with
  "VP8 already added!") and #1023 (R8 keep rules for JNA; a no-op here, kept so a later merge is clean).
  v2.29.0 itself was not taken: it adds a 3.3 MB Rust library used only for data tracks.
- **White-label rename** by `tools/rebrand/rebrand.sh` (reproducible, idempotent): Maven
  `com.gravixcloud:gravix-rtc-android`, Kotlin package `com.gravixcloud.rtc`, entry point
  `GravixRtc.create(context)` (was `LiveKit.create`), `GravixRtcOverrides`, `GxLog`, generated protobuf
  classes `gravixcloud.Gravixcloud*`. Wire protocol unchanged: signalling protocol v13,
  `ClientInfo.SDK.ANDROID`, protobuf definitions, `lk.*` keys, E2EE constants.
- **WebRTC:** the unprefixed `io.github.webrtc-sdk:android:144.7559.14` (`org.webrtc.*`,
  `libjingle_peerconnection_so.so`), the same libwebrtc build upstream ships in its prefixed form.
- **No JitPack:** audioswitch 1.2.0 is vendored as `com.gravixcloud.rtc.audioswitch`; every SDK
  dependency resolves from Maven Central.
- **minSdk 24** (was 21).
- **Regions:** region discovery and failover (`/settings/regions`) run for `*.gravixcloud.com` and
  `*.gravixcloud.run` hosts (upstream: only its own cloud hosts).
- **Start-up region measurement** (`com.gravixcloud.rtc.regions`, ported from the Flutter SDK):
  `GravixRegions.startMeasurement(context, options)` measures the regions (gateway shortlist / all / est /
  rotating exploration; one pooled socket per region, cold sample dropped; parallel, budget 1.5 s Wi-Fi /
  3 s cellular, early exit; per-network answers with an anchor a rival must beat twice), refreshes every
  5 min and on a network change. `GravixRegionMeasurements.resolveConnectUrl(url)` / `pickRegion(...)` pick
  the url to connect to (pinned and home-region rules). Same shared test fixtures as the Flutter and React SDKs.
- **Region failover** (`GravixRecoveryOptions`, `RoomOptions.gravixRecovery` / `Room.gravixRecovery`; each
  switch off = the upstream engine): `measuredRegionFailover` (nearest measured region first, near-only cap),
  `retrySameUrlFirst` + `sameUrlRetryDialTimeoutMs` (the first full reconnect returns to the session url
  with a 2.5 s dial before another region), `failoverNeedsEvidence` (leave the session url only when the
  device network was steady and the session url does not answer while the next region does),
  `lastGoodRegionList` (a failed region-list fetch uses the kept list; the host that just failed is not
  asked). `GravixRecoveryOptions.UPSTREAM` turns them all off. After a failover the next resume dials the
  region the session is on (upstream kept the original url).
- **Stale signal failures** (`GravixRecoveryOptions.ignoreStaleSignalFailures`, off = upstream): a WebSocket
  failure is reported only while that WebSocket is current. Upstream checked before its blocking validate
  request (up to 10 s to a silent host), so an earlier attempt's failure failed the next join or closed the
  session just joined in another region (found on the sim with a paused region). A WebSocket the same-url
  dial bound gave up on is not validated (a silent host).
- **Recovery (Phase 3, ported from the Flutter SDK where the hazard reproduced on this engine; each switch off =
  upstream, all off in `GravixRecoveryOptions.UPSTREAM`):** `resumeOnPeerDisconnected` (resume as soon as a
  peer connection reports DISCONNECTED: a paused/dropped region noticed at ~6.5 s instead of ~16.5 s; on the
  sim the session reached another region 22.5 s after the fault instead of 32.7 s), `resumePrimaryDeadlineMs`
  (3 s; a resume whose primary connection stays down becomes a full rejoin instead of waiting 20 s),
  `leaveAbortsResumeWait` / `restartAbortsOnSignalLoss` (a Leave or a lost signal socket ends the reconnect's
  20 s peer-connection wait at once), `waitForNetwork` + `networkWaitProbeIntervalMs` (no attempts while the
  device is offline; resume when the network is back: 5 s of airplane mode now resumes ~0.2 s after Android's
  network returns, upstream rejoined in full), `rejoinClearsFullRequest` (a server's full-reconnect request is
  satisfied by the rejoin it caused), `resumeWaitsForLiveState` (a resume waits for the live connection state; a
  stale observer made a resume after a short drop escalate to a full rejoin), `signalDialCapMs` (30 s signal deadline for a fresh join on a slow path)
  with `silentHostFailFast` (no `/rtc/validate` after a dial that got no answer), `refusalEndsConnect` (a 401/403
  first connect is not failed over: the token, not the region). The failover's "network available" evidence
  now means "an active network exists" (an unmapped transport no longer blocks failover); "the list host just
  failed" compares host and port. A timed-out region-list fetch falls back to the saved list (it failed as a
  cancellation).
- **Where the session is:** `Room.connectedUrl` / `Room.connectedRegion` (observable), the event
  `RoomEvent.ConnectedUrlChanged` (first connect, failover, rejoin to another url) and, for Java,
  `Room.addConnectedUrlListener`.
- **Version reporting:** `Version.CLIENT_VERSION` = `0.1.0`; the server receives the upstream
  engine version (`Version.ENGINE_VERSION`, 2.28.2) as the client SDK version, like the Flutter SDK.
- **Token:** `TokenSource.fromDevelopmentTokenServer` requires an explicit `baseUrl` (upstream defaulted
  to its vendor's hosted service). The Views and Compose sample apps ask for that base url ("Token Server
  URL", gradle property `gravixSampleTokenServerUrl`) and show a message instead of crashing without it.
- **Retired Gravix servers are refused:** the stock media servers and the token endpoint that Gravix ran before the
  Gravix Cloud fleet are retired. `Room.connect` and `Room.prepareConnection` throw
  `com.gravixcloud.rtc.room.RetiredServerException` (an `IllegalArgumentException` with the retired `host`; message
  "retired Gravix server (<host>): use the url from your token response") before any state change or request;
  `GravixRoomService.connect`'s future fails with it; `TokenSource.fromEndpoint` / `fromDevelopmentTokenServer`
  throw it for such an endpoint. Region lists (failover and the start-up measurement) leave such entries out.
  Exact hostnames only; `<region>.gravixcloud.com` fleet hosts are unaffected.
- **Logs never carry tokens:** every SDK log line passes `GxRedact` (`access_token=`/`token=` query
  values, `Bearer` values, JWT-shaped strings such as the server's refresh token in a logged
  SignalResponse). `GxLog.redactSecrets = false` restores upstream's verbatim logging.
- **Public API compiles in apps (Phase 4):** no public signature names a type from an `implementation`-only
  dependency any more. `GravixRegionMeasurements.regionsMeasuredReport()` returns the report as a JSON
  `String` (was a kotlinx-serialization `JsonObject` apps could not compile against; found in the first
  real-fleet run). kotlinx-coroutines and protobuf-javalite are `api` dependencies (Flow / suspend and the
  generated protobuf enums are part of the Room API). `TokenPayload.jwt` is internal (use its claim
  getters). `GravixRegionDirectory`/`GravixRegionChallenger` JSON helpers are internal;
  `GravixRegionDirectory.fromJson(String)` / `toJsonString()` serve custom directory fetchers. The public API
  is locked by `gravix-rtc-android-sdk/api/gravix-rtc-android-sdk.api` (`apiCheck`), `tools/api-scan/scan.py`
  rejects implementation-only types in it, and `tools/consumer-check` builds two SDK-only consumers (Kotlin,
  and Java with no Kotlin plugin) whose compile classpath has no kotlinx-serialization.
- **Resume events (Phase 4):** `RoomEvent.Resuming` / `RoomEvent.Resumed`. Upstream has no app event for a
  session resume (`Reconnecting`/`Reconnected` are full rejoins only), so during the most common recovery (a
  short network drop) an app could not show "reconnecting". `Resuming` is emitted as soon as a reconnect
  starts with a resume, also while it waits for the network to come back; then `Resumed`, or `Reconnecting` ->
  `Reconnected` when the resume escalates to a full rejoin, or `Disconnected`. `Room.state` stays `CONNECTED`
  during a resume (unchanged); the full-rejoin events are unchanged. Observation only: no recovery behaviour
  changed. Flutter SDK equivalents: `RoomResumingEvent`, `RoomReconnectedEvent`.
- **Java API (Phase 4):** `com.gravixcloud.rtc.service.GravixRoomService`, an Agora `RtcEngine`-style facade:
  `new GravixRoomService(context)`, `connect(url, token)` / `disconnect()` / `setMicrophoneEnabled` /
  `setCameraEnabled` / `muteLocalAudio` / `switchCamera` / `muteRemoteAudio(uid, mute)` / `muteAllRemoteAudio`
  returning `CompletableFuture`s, and `GravixRoomServiceListener` (no-op defaults; `onUserJoined` / `onUserOffline`,
  `onUserMuteAudio` / `onUserMuteVideo`, `onRemoteVideoTrack`, `onActiveSpeakers`, `onReconnecting(isResume)` /
  `onReconnected(isResume)`, `onConnectedUrlChanged`, `onDataReceived`, `onDisconnected`). Callbacks and future
  completions run on the main thread; uids are Strings (identities). Participants already in the room are announced
  with `onUserJoined`; a full rejoin reports only real participant changes; our own leave is one `onDisconnected`
  (no `onUserOffline` per participant). `getRoom()` gives the full Kotlin API. Not yet ported from the Flutter
  service: data saver, host camera guard, prewarm/standby, reports/analytics, uid mapping, audio routing helpers.
  `sample-app-java` (Java sources only) is built by `./gradlew check`.
- **`GravixRtc.create` is `@JvmStatic @JvmOverloads`:** Java calls `GravixRtc.create(context)` (was
  `GravixRtc.INSTANCE.create(context, options, overrides)`); Kotlin callers are unchanged.
- **Maven Central publishing (Phase 4):** vanniktech maven-publish 0.34.0 to the Central Portal, replacing upstream's
  OSSRH-compatibility setup; full POM, sources + javadoc jars, release-only signing (gpg command or in-memory key),
  `checkReleaseSigning` guard, local `centralBundle` + `tools/release/central-bundle-check.sh`. See `RELEASING.md`.
