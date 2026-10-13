# audioswitch (vendored)

- Source: https://github.com/davidliu/audioswitch at `039a35aefab7747c557242fa216c9ea11743b604`
  (a fork of https://github.com/twilio/audioswitch; version 1.2.0).
- License: Apache License 2.0, Copyright 2020 Twilio, inc. (`LICENSE.txt` here).
- Location in this repository: `gravix-rtc-android-sdk/src/main/java/com/gravixcloud/rtc/audioswitch/`.
- Changes: package relocated `com.twilio.audioswitch` -> `com.gravixcloud.rtc.audioswitch`;
  `BuildConfig.VERSION_NAME` replaced by the constant `"1.2.0-039a35a"`; `@VisibleForTesting(otherwise = PRIVATE)`
  spelled `VisibleForTesting.PRIVATE` (the static import does not resolve against androidx.annotation 1.7); a provenance header added to
  each file. Its manifest permissions (BLUETOOTH up to API 30, MODIFY_AUDIO_SETTINGS) and its keep rule
  are carried by the SDK module's manifest and consumer rules.
- Why: upstream depends on `com.github.davidliu:audioswitch`, which is published only on JitPack.
- Regenerate: `tools/vendor/vendor-audioswitch.sh <checkout at 039a35aefab7747c557242fa216c9ea11743b604>`.
