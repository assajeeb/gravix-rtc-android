# Gravix Cloud Android SDK

Native Android client SDK (Kotlin and Java) for **Gravix Cloud** real-time audio and video rooms: connect with a
Gravix token, publish the microphone and camera, subscribe to other participants, with start-up region measurement,
region failover and session recovery built in.

- Maven coordinates: `com.gravixcloud:gravix-rtc-android`
- Packages: `com.gravixcloud.rtc` (Kotlin `Room` API), `com.gravixcloud.rtc.service` (`GravixRoomService`, the
  Java-first API)
- minSdk 24, signalling protocol v13
- Status: **0.1.1**, on Maven Central (see `CHANGELOG.md`)

The engine is a white-labelled fork of the Apache-2.0 LiveKit Android SDK (v2.28.2); see `NOTICE` and
`tools/rebrand/README.md`.

## Install

Everything resolves from Google + Maven Central (no JitPack):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.gravixcloud:gravix-rtc-android:0.1.1")
}
```

The access token is minted by **your backend** with your Gravix API key and secret (the Gravix token libraries, e.g.
`com.gravixcloud:gravix-token-java`); never ship the secret in the app.

## Quickstart (Kotlin)

```kotlin
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer
import com.gravixcloud.rtc.room.track.VideoTrack

class CallActivity : AppCompatActivity() {
    private val room by lazy { GravixRtc.create(applicationContext) }

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
```

`sample-app-basic` is a runnable Kotlin version.

## Quickstart (Java)

`GravixRoomService` is the Java-first API (the Agora `RtcEngine` shape): `CompletableFuture`s instead of `suspend`
functions, a listener with no-op defaults instead of event Flows, String uids (the participants' identities).
Callbacks and future completions run on the main thread; never `get()`/`join()` a future on the main thread.

```java
import com.gravixcloud.rtc.regions.GravixRegionMeasurements;
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer;
import com.gravixcloud.rtc.room.track.VideoTrack;
import com.gravixcloud.rtc.service.GravixRoomService;
import com.gravixcloud.rtc.service.GravixRoomServiceListener;

public class JavaCallActivity extends Activity {
    private GravixRoomService service;

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
```

Other calls: `disconnect()`, `muteLocalAudio(mute)`, `setCameraEnabled`, `switchCamera()`, `muteRemoteAudio(uid,
mute)`, `muteAllRemoteAudio`, `remoteVideoTrack(uid)`, `localVideoTrack()`, `getRemoteUids()`,
`getConnectedRegion()`, `getSelectedTransport()`, and `getRoom()` for anything else. Listener callbacks: `onConnected`, `onUserJoined` /
`onUserOffline` (only real changes, also across a full rejoin), `onUserMuteAudio` / `onUserMuteVideo`,
`onRemoteVideoTrack` / `onRemoteVideoTrackRemoved`, `onActiveSpeakers`, `onReconnecting(isResume)` /
`onReconnected(isResume)`, `onReconnectAttempt`, `onConnectedUrlChanged(url, region)`, `onIceTransportChanged` /
`onSlowTcpTransport`, `onDataReceived`, `onDisconnected(reason, error)`.
`sample-app-java` is a runnable Java app (Java sources only).

## Reconnecting

| What happened | Kotlin `RoomEvent` | Java listener | `Room.state` |
|---|---|---|---|
| Short drop, session resumed (the usual case) | `Resuming` -> `Resumed` | `onReconnecting(true)` -> `onReconnected(true)` | stays `CONNECTED` |
| Resume failed, full rejoin (tracks republished) | `Resuming` -> `Reconnecting` -> `Reconnected` | `onReconnecting(true)` -> `onReconnecting(false)` -> `onReconnected(false)` | `RECONNECTING` -> `CONNECTED` |
| Recovery gave up | ... -> `Disconnected` | ... -> `onDisconnected` | `DISCONNECTED` |

`Resuming` is emitted as soon as the drop is detected, also while the SDK waits for the device's network to return.
Every attempt is `RoomEvent.ReconnectAttempt` (Java `onReconnectAttempt(isResume, cause, attempt,
afterNetworkChange)`): resume or full, what started the recovery, the attempt number, and whether the device's
network had just changed. How the media travels is `Room.selectedTransport` (UDP / TCP / RELAY;
`RoomEvent.IceTransportChanged`, Java `onIceTransportChanged`); a session stuck on a slow ICE/TCP path is
`RoomEvent.SlowTcpTransport` (Java `onSlowTcpTransport`).

## Region auto-pick

Measure the regions once at app start (e.g. in `Application.onCreate`); the SDK re-measures every 5 minutes and
when the network changes, and keeps a per-network answer:

```kotlin
GravixRegions.startMeasurement(
    context,
    GravixRegionMeasureOptions().apply { regionsUrl = "https://<your Gravix console host>/v1/regions" },
)
```

```java
GravixRegionMeasureOptions options = new GravixRegionMeasureOptions();
options.setRegionsUrl("https://<your Gravix console host>/v1/regions");
GravixRegionMeasurementHandle handle = GravixRegions.startMeasurement(context, options);
handle.readyFuture().thenAccept(result -> { /* optional: result.getBest() */ });
```

Then connect to `GravixRegionMeasurements.resolveConnectUrl(url)` (as in the quickstarts): the measured nearest
region when `url` is one of the measured regions, else `url` unchanged. `pickRegion(...)` takes the token's region
list and a home region. During a call the session fails over to the next-nearest measured region when its region
stops answering; `Room.connectedUrl` / `connectedRegion` and `RoomEvent.ConnectedUrlChanged` (Java:
`onConnectedUrlChanged`) say where it is. `GravixRegionMeasurements.regionsMeasuredReport()` is the measurement
as a JSON string for your analytics.

## Recovery switches

`GravixRecoveryOptions` (`RoomOptions(gravixRecovery = ...)` or `room.gravixRecovery = ...`). Every switch is on by
default; off = the upstream engine's behaviour; `GravixRecoveryOptions.UPSTREAM` turns all of them off at once.

| Switch | Default | What it does |
|---|---|---|
| `measuredRegionFailover` | on | fail over to the nearest measured region first |
| `retrySameUrlFirst` + `sameUrlRetryDialTimeoutMs` | on, 2.5 s | the first full reconnect retries the session's url (short dial) before another region |
| `failoverNeedsEvidence` | on | leave the session url only when the device network is up and that url does not answer while the next one does |
| `lastGoodRegionList` | on | a failed region-list fetch uses the last good list (never the host that just failed) |
| `ignoreStaleSignalFailures` | on | an old WebSocket's failure cannot fail a newer join |
| `refusalEndsConnect` | on | a first connect refused with 401/403 is not failed over (the token is the problem) |
| `resumeOnPeerDisconnected` | on | resume as soon as a media connection reports DISCONNECTED (~6.5 s instead of ~16.5 s) |
| `resumePrimaryDeadlineMs` | 3 s | a resume whose media connection stays down becomes a full rejoin |
| `leaveAbortsResumeWait` | on | a server Leave ends the reconnect's peer-connection wait at once |
| `restartAbortsOnSignalLoss` | on | a lost signal socket ends that wait at once |
| `waitForNetwork` + `networkWaitProbeIntervalMs` | on, 2 s | no attempts while the device is offline; resume when the network returns |
| `rejoinClearsFullRequest` | on | a server's full-reconnect request is satisfied by the rejoin it caused |
| `signalDialCapMs` | 30 s | signal deadline for a fresh join on a slow path |
| `silentHostFailFast` | on | no `/rtc/validate` after a dial that got no answer |
| `resumeWaitsForLiveState` | on | a resume waits for the live connection state, not a stale observer |
| `resumeAfterNetworkSocketError` + `networkChangeWindowMs` | on, 30 s | a resume lost to the device's network changing is resumed again (two in a row: full rejoin) |
| `freshPeerFailedGraceMs` | 3 s | a new media connection whose first checks fail gets this long to connect before it counts as failed |
| `iceTransportWatchMs` + `slowTcpRttMs` / `slowTcpForMs` | 5 s, 1 s / 10 s | `Room.selectedTransport` and the slow-TCP event (round trip read only while on TCP) |

Logs never carry tokens (`GxLog.redactSecrets`, on by default).

## Repository layout

| Path | What |
|---|---|
| `gravix-rtc-android-sdk/` | the SDK (`com.gravixcloud:gravix-rtc-android`); `api/` = the locked public API dump |
| `gravix-rtc-android-camerax/`, `gravix-rtc-android-track-processors/` | optional add-on modules (not published as of 0.1.1) |
| `gravix-rtc-android-test/` | engine test harness and the unit tests (`./gradlew :gravix-rtc-android-test:testRelease`) |
| `sample-app-basic/`, `sample-app-java/`, other `sample-app*/`, `examples/` | sample apps (Kotlin; `sample-app-java` is Java only) |
| `protocol/` | upstream protobuf definitions (git submodule, unmodified; vendored into the public snapshot) |
| `tools/rebrand/` | the reproducible white-label rename, re-applied on every upstream sync |
| `tools/vendor/`, `third_party/` | vendored audioswitch (no JitPack dependency) |
| `tools/consumer-check/` | Kotlin, Kotlin SDK-only and Java SDK-only apps built against Maven Local with only Google + Maven Central |
| `tools/api-scan/` | rejects public signatures that name implementation-only dependencies |
| `tools/release/` | public snapshot script, local Maven Central bundle check (`RELEASING.md`) |
| `doc/upstream/` | upstream README and CHANGELOG, kept for fork maintenance |

Releases: `RELEASING.md`. Upstream's release tooling (`.changeset/`, `ci/`, `package.json`,
`release-instructions.md`) is kept unchanged to keep merges clean; it is not used for Gravix releases.

## License

Apache License 2.0 (`LICENSE`). Attribution and third-party notices: `NOTICE`.
