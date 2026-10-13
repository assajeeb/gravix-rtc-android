# Changelog — Gravix Cloud Android SDK

Upstream (LiveKit Android SDK) history up to the fork point: `doc/upstream/CHANGELOG.md`.

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
