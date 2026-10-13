#!/usr/bin/env bash
# Copyright 2026 Gravity Compile
# SPDX-License-Identifier: Apache-2.0
#
# Release gate: publish the SDK to Maven Local and build a minimal customer app against it with only
# Google + Maven Central (+ Maven Local for com.gravixcloud). Proves that customers need no JitPack and
# that the published metadata carries no upstream coordinates.
#
# Phase 4 adds two consumers that depend on the SDK ALONE: kotlin-minimal (Kotlin) and java-app (Java only, no
# Kotlin plugin). Their compile classpath is checked to have no kotlinx-serialization first, so a public SDK
# signature naming an implementation-only dependency (the regionsMeasuredReport JsonObject leak) fails here.
#
#   tools/consumer-check/run.sh [extra gradle args]     exit 0 = PASS, 1 = FAIL
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
VERSION=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
fail() { echo "consumer-check: FAIL: $*" >&2; exit 1; }

./gradlew --console=plain -q "$@" :gravix-rtc-android-sdk:publishToMavenLocal

M2="${MAVEN_LOCAL_REPO:-$HOME/.m2/repository}/com/gravixcloud/gravix-rtc-android/$VERSION"
[ -d "$M2" ] || fail "nothing published at $M2"
echo "consumer-check: published files:"
ls -1 "$M2" | sed 's/^/  /'
POM="$M2/gravix-rtc-android-$VERSION.pom"
MODULE="$M2/gravix-rtc-android-$VERSION.module"
AAR="$M2/gravix-rtc-android-$VERSION.aar"
for f in "$POM" "$MODULE" "$AAR"; do [ -f "$f" ] || fail "missing $(basename "$f")"; done

# Published metadata: no repositories section, no JitPack, no upstream group, no prefixed WebRTC.
if grep -q '<repositories>' "$POM"; then fail "POM declares <repositories>"; fi
if grep -nEi 'jitpack|davidliu|audioswitch|io\.livekit|android-prefixed' "$POM" "$MODULE"; then
    fail "upstream/JitPack coordinates in published metadata"
fi
grep -q '<groupId>io.github.webrtc-sdk</groupId>' "$POM" || fail "WebRTC dependency missing from POM"
grep -q '<artifactId>android</artifactId>' "$POM" || fail "unprefixed WebRTC artifact missing from POM"

# White-label: no class in the AAR lives in an upstream-named package.
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$AAR" classes.jar -d "$TMP"
BAD=$(unzip -Z1 "$TMP/classes.jar" | grep -Ei 'livekit|/lk[A-Z]|^LK' || true)
[ -z "$BAD" ] || fail "upstream-named classes in the AAR: $(echo "$BAD" | head -5)"
echo "consumer-check: AAR classes: $(unzip -Z1 "$TMP/classes.jar" | grep -c '\.class$'), none upstream-named"

# The README's quickstart join() blocks are these consumers' sources verbatim (so the documented API is compiled).
python3 -I - <<'PY' || fail "README quickstart differs from tools/consumer-check sources"
import sys
readme = open("README.md").read()
for path, start, end in [
    ("tools/consumer-check/app/src/main/java/com/gravixcloud/consumercheck/CallActivity.kt",
     "    // Call after RECORD_AUDIO", "    override fun onDestroy"),
    ("tools/consumer-check/java-app/src/main/java/com/gravixcloud/consumercheck/java/JavaCallActivity.java",
     "    // Call after RECORD_AUDIO", "    @Override\n    protected void onDestroy"),
]:
    src = open(path).read()
    i = src.index(start)
    if src[i:src.index(end, i)] not in readme:
        print("consumer-check: README snippet differs from " + path, file=sys.stderr)
        sys.exit(1)
PY
echo "consumer-check: README Kotlin + Java quickstarts = the compiled consumer sources"

# Precondition for the leak check: the SDK-only consumers get no kotlinx-serialization (an `implementation`
# dependency of the SDK) at compile time, so they can only compile if no public signature they use names it.
for m in kotlin-minimal java-app; do
    DEPS=$(./gradlew --console=plain -q "$@" -p tools/consumer-check -PgravixSdkVersion="$VERSION" \
        ":$m:dependencies" --configuration debugCompileClasspath)
    echo "$DEPS" | grep -q 'com.gravixcloud:gravix-rtc-android' || fail "$m: SDK not on its compile classpath"
    if echo "$DEPS" | grep -q 'kotlinx-serialization'; then fail "$m: kotlinx-serialization on its compile classpath"; fi
done
echo "consumer-check: kotlin-minimal + java-app compile classpaths have no kotlinx-serialization"

# Build the customer apps. settings.gradle.kts has no JitPack repository.
./gradlew --console=plain -q "$@" -p tools/consumer-check -PgravixSdkVersion="$VERSION" \
    :app:assembleDebug :kotlin-minimal:assembleDebug :java-app:assembleDebug
for m in app kotlin-minimal java-app; do
    APK=tools/consumer-check/$m/build/outputs/apk/debug/$m-debug.apk
    [ -f "$APK" ] || fail "consumer APK not built: $m"
    echo "consumer-check: built $m ($(du -h "$APK" | cut -f1))"
done
echo "consumer-check: PASS (com.gravixcloud:gravix-rtc-android:$VERSION from Maven Local, everything else" \
     "from Google/Maven Central; Kotlin, Kotlin SDK-only and Java SDK-only consumers built)"
