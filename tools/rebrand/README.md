# tools/rebrand — the white-label rename

`rebrand.sh` turns an upstream `livekit/client-sdk-android` tree into the Gravix Cloud Android SDK.
It is **reproducible** (a pure function of the upstream tree) and **idempotent** (a second run changes
nothing), so the same rename is re-applied after every upstream sync instead of being redone by hand.

```sh
tools/rebrand/rebrand.sh            # apply (moves use `git mv`; review with `git status`, `git diff --stat`)
tools/rebrand/rebrand.sh --report   # list the upstream-name references that remain (expected ones only)
```

Requirements: bash, perl 5, git. Run on a committed tree.

## What it renames

| Upstream | Gravix | Rule |
|---|---|---|
| Kotlin package `io.livekit.android(.x)` | `com.gravixcloud.rtc(.x)` | packages, imports, directories |
| `io.livekit.lint` / `io.livekit.detekt` | `com.gravixcloud.rtc.lint` / `.detekt` | same |
| Maven group `io.livekit`, artifact `livekit-android` | `com.gravixcloud`, `gravix-rtc-android` | `gradle.properties` |
| Modules `livekit-android-{sdk,test,camerax,track-processors}`, `livekit-{lint,detekt-rules}` | `gravix-rtc-android-*`, `gravix-rtc-{lint,detekt-rules}` | directories + references |
| `LiveKit` entry object, `LiveKit*` types (`LiveKitOverrides`, `LiveKitComponent`, …) | `GravixRtc`, `GravixRtc*` (Flutter precedent: `LiveKitClient` → `GravixRtcClient`) | identifiers; prose in comments → "Gravix" |
| `LK*` helpers (`LKLog`, `LKObjects`, `LKDebugTree`, …) | `Gx*` (Flutter's Kotlin precedent: `GxAudioSwitchManager`) | identifiers, file names |
| `isLKCloud()` | `isGravixCloud()` (the host list itself is a hand edit) | identifier |
| Thread-name prefix `LK_RTC_THREAD`, screen-capture channel `livekit_screen_capture` | `GX_RTC_THREAD`, `gravix_screen_capture` | runtime strings |
| WebRTC `io.github.webrtc-sdk:android-prefixed` (`livekit.org.webrtc.*`, `liblkjingle_peerconnection_so.so`) | unprefixed `io.github.webrtc-sdk:android` (`org.webrtc.*`, `libjingle_peerconnection_so.so`), same version | dependency, imports, proguard rules, native library name |
| audioswitch `com.github.davidliu:audioswitch` (JitPack only), package `com.twilio.audioswitch` | vendored by `tools/vendor/vendor-audioswitch.sh` as `com.gravixcloud.rtc.audioswitch` (`third_party/audioswitch/`); JitPack confined to the sample app's groupie | imports, dependency, catalog, `settings.gradle` |
| Generated protobuf classes `livekit.LivekitModels`, `livekit.LivekitRtc`, … (proto `package livekit`) | `gravixcloud.GravixcloudModels`, `gravixcloud.GravixcloudRtc`, … (proto `package gravixcloud`, as in the Flutter SDK) | `gravix-proto-names.gradle` rewrites the staged copies protoc compiles (file names `gravixcloud_*.proto`, package, `java_package`, `java_outer_classname`, comments); the submodule is untouched |
| POM description/url/scm/developer | Gravix values (only while `gradle.properties` has no `GRAVIX(pom)` marker: since Phase 4 the POM is maintained by hand and a re-run keeps it) | `gradle.properties` |
| Modified source files | `Modifications Copyright 2026 Gravity Compile` under the upstream copyright line | Apache-2.0 §4(b) |

## What it deliberately keeps

- **Attribution:** upstream copyright lines, `LICENSE`, `NOTICE` (Apache-2.0 §4(c)/(d)), `CHANGELOG.md`
  history (moved by hand to `doc/upstream/`), `.changeset/`.
- **The wire protocol:** every protobuf message/field/enum name (the `protocol` submodule itself is
  untouched; only staged copies are renamed, and binary encoding carries no package or file name), `ClientInfo.SDK.ANDROID`,
  protocol version, `lk.*` attribute and topic keys (and the Kotlin properties that mirror them, e.g.
  `lkAgentState`), data-channel labels.
- **E2EE interop constants:** ratchet salt `"LKFrameEncryptionKey"` and magic bytes `"LK-ROCKS"`; every
  other SDK derives keys from these exact strings.
- **URLs and LiveKit host names** (`docs.livekit.io`, `github.com/livekit/…`, `*.livekit.cloud`):
  never rewritten blindly. Links in comments remain (listed by `--report`); behaviour that depends on a
  host (the region-failover host check) is a separate, reviewed hand edit.

## Upstream sync procedure

1. `git fetch upstream --tags` and merge the new tag into a sync branch (`git merge vX.Y.Z`). Git's rename
   detection maps most upstream changes onto the renamed paths.
2. Resolve conflicts, then run `tools/rebrand/rebrand.sh` again: it renames whatever the merge brought in
   under upstream names (new files, new imports) and is a no-op for the rest.
3. Re-check the hand edits (`git log --grep 'GRAVIX'`), especially the protobuf staging anchors (the
   script stops with a message if upstream changed them) and `.github/` (upstream workflows are removed
   by hand; delete any that come back).
4. `./gradlew assembleRelease :gravix-rtc-android-test:testRelease` and `rebrand.sh --report`.

## Known interactions

- **Spotless:** upstream's `spotless` config (`licenseHeaderFile` + `ratchetFrom 'origin/main'`) would
  replace the header of every file with `LicenseHeaderFile.txt` and so drop the "Modifications" line.
  Do not run `spotlessApply` until the config is adapted (not part of the build or the gates).
- **Remotes:** the sync procedure assumes `upstream` = github.com/livekit/client-sdk-android and
  `origin` = the private Gravix repository.
- **Gradle caching:** the proto filter is code; `gravix-proto-names.gradle` declares itself an input of
  the staging tasks so an edit re-runs them (without that, up-to-date checks reused old copies).
