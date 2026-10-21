#!/usr/bin/env bash
# Copyright 2026 Gravity Compile
# SPDX-License-Identifier: Apache-2.0
#
# Reproducible, idempotent white-label rename of the upstream LiveKit Android SDK tree into the
# Gravix Cloud Android SDK (com.gravixcloud:gravix-rtc-android, Kotlin package com.gravixcloud.rtc).
#
#   tools/rebrand/rebrand.sh            apply the rename to the working tree (moves are `git mv`)
#   tools/rebrand/rebrand.sh --report   only list the LiveKit references that remain afterwards
#
# Run it from anywhere inside the repository, on a tree whose tracked files are committed (so the
# result can be reviewed as one diff). Running it twice changes nothing. See tools/rebrand/README.md
# for what is renamed, what is deliberately kept, and the upstream-sync procedure.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
HERE=tools/rebrand
PL="$HERE/rebrand.pl"

die() { echo "rebrand.sh: $*" >&2; exit 1; }
# grep that tolerates "no match" (exit 1) under `set -o pipefail`; a re-run legitimately matches nothing.
grepq() { grep "$@" || [ $? -eq 1 ]; }

# Files the text pass never touches: legal texts (Apache-2.0 4(c)/(d)), upstream history and
# changesets, CI that is replaced by hand, this tool, lockfiles, the protocol submodule, binaries.
EXCLUDE_RE='^(LICENSE|NOTICE|CHANGELOG\.md|README\.md|LicenseHeaderFile\.txt|package\.json|pnpm-lock\.yaml|\.gitmodules|protocol|gradlew|gradlew\.bat)$|^(\.changeset|\.github|tools|doc/upstream|third_party|gravix-rtc-android-sdk/src/main/java/com/gravixcloud/rtc/audioswitch)/|^gradle/gravix-proto-names\.gradle$|^gravix-rtc-android-sdk/src/main/cpp/gxns/|\.(png|webp|jpg|jpeg|gif|ico|jar|so|aar|keystore|jks|ttf|otf|mp3|mp4|wav|y4m|pcm|bin|onnx|ort)$'
# File-name renames also cover binary resources (a renamed @drawable/livekit_x reference needs the file).
NAME_EXCLUDE_RE='^(LICENSE|NOTICE|CHANGELOG\.md|README\.md|LicenseHeaderFile\.txt|protocol)$|^(\.changeset|\.github|tools|doc/upstream|third_party|gravix-rtc-android-sdk/src/main/java/com/gravixcloud/rtc/audioswitch)/|^gradle/gravix-proto-names\.gradle$'

report() {
    echo "== remaining upstream-name references (outside excluded files) =="
    git ls-files -z | tr '\0' '\n' | grep -Ev "$EXCLUDE_RE" | tr '\n' '\0' \
        | xargs -0 grep -nIE 'LiveKit|Livekit|livekit|LIVEKIT|\bLK[A-Z]' 2>/dev/null \
        | grep -vE 'Copyright.*LiveKit, Inc\.' || true
}

if [ "${1:-}" = "--report" ]; then
    report
    exit 0
fi
[ $# -eq 0 ] || die "usage: rebrand.sh [--report]"
command -v perl >/dev/null || die "perl is required"

# Move every tracked file under $1 to the same relative path under $2 (works when $2 already
# exists, e.g. after an upstream merge added files under an old path).
move_tree() {
    local src=$1 dst=$2 f rel
    git ls-files -z -- "$src" | while IFS= read -r -d '' f; do
        rel=${f#"$src"/}
        mkdir -p "$(dirname "$dst/$rel")"
        git mv "$f" "$dst/$rel"
    done
    [ -d "$src" ] && find "$src" -type d -empty -delete 2>/dev/null || true
}

# --- 1. Gradle modules and shared build scripts
for pair in \
    livekit-android-sdk:gravix-rtc-android-sdk \
    livekit-android-test:gravix-rtc-android-test \
    livekit-android-camerax:gravix-rtc-android-camerax \
    livekit-android-track-processors:gravix-rtc-android-track-processors \
    livekit-lint:gravix-rtc-lint \
    livekit-detekt-rules:gravix-rtc-detekt-rules; do
    src=${pair%%:*}; dst=${pair##*:}
    if [ -n "$(git ls-files -- "$src" | head -1)" ]; then move_tree "$src" "$dst"; fi
done
if [ -e gradle/livekit-protobuf.gradle ]; then git mv gradle/livekit-protobuf.gradle gradle/gravix-protobuf.gradle; fi

# --- 2. Package directories (io/livekit/android -> com/gravixcloud/rtc, livekit/org/webrtc -> org/webrtc)
git ls-files | grepq -oE '^.*/java/(io/livekit/(android|lint|detekt)|livekit/org/(webrtc|jni_zero))/' | sort -u \
| while IFS= read -r d; do
    d=${d%/}
    case "$d" in
        */io/livekit/android) t="${d%/io/livekit/android}/com/gravixcloud/rtc" ;;
        */io/livekit/lint)    t="${d%/io/livekit/lint}/com/gravixcloud/rtc/lint" ;;
        */io/livekit/detekt)  t="${d%/io/livekit/detekt}/com/gravixcloud/rtc/detekt" ;;
        */livekit/org/webrtc) t="${d%/livekit/org/webrtc}/org/webrtc" ;;
        */livekit/org/jni_zero) t="${d%/livekit/org/jni_zero}/org/jni_zero" ;;
        *) die "unexpected package dir $d" ;;
    esac
    move_tree "$d" "$t"
done

# Old package roots left empty by the moves (git does not track directories; this is local tidiness).
find . -path ./.git -prune -o -type d \( -path "*/java/io" -o -path "*/java/livekit" \) -print \
    | while IFS= read -r d; do find "$d" -depth -type d -empty -delete; done

# --- 3. File names carrying the upstream name (LiveKit.kt -> GravixRtc.kt, LKLog.kt -> GxLog.kt, ...)
git ls-files | grepq -Ev "$NAME_EXCLUDE_RE" | grepq -E '(LiveKit|Livekit|livekit|LK[A-Z])[^/]*$' \
| while IFS= read -r f; do
    b=$(basename "$f")
    nb=$(perl "$PL" --name "$b")
    if [ "$nb" != "$b" ]; then git mv "$f" "$(dirname "$f")/$nb"; fi
done

# --- 4. Text: packages, imports, identifiers, Gradle names, log tags, resources
git ls-files -z | tr '\0' '\n' | grepq -Ev "$EXCLUDE_RE" | tr '\n' '\0' | xargs -0 perl "$PL"

# --- 5. Protobuf Java names (the .proto files and the wire stay upstream; see gravix-proto-names.gradle)
cp "$HERE/gravix-proto-names.gradle" gradle/gravix-proto-names.gradle
PG=gradle/gravix-protobuf.gradle
if ! grep -q 'GRAVIX(rebrand)' "$PG"; then
    perl -0pi -e '
        my $n = 0;
        $n += s/^(def stagedProtoSrcDir)/apply from: rootProject.file("gradle\/gravix-proto-names.gradle") \/\/ GRAVIX(rebrand)\n\n$1/m;
        $n += s/^(\s*)into stagedProtoSrcDir\n\}/$1into stagedProtoSrcDir\n$1eachFile(gravixProtoJavaNames) \/\/ GRAVIX(rebrand)\n}/m;
        $n += s/^(\s*)task\.addIncludeDir files\(generated\.protoSrc\)/$1task.dependsOn gravixStageProtoImports \/\/ GRAVIX(rebrand)\n$1task.addIncludeDir files(gravixStagedProtoImportDir)/m;
        die "rebrand.sh: protobuf gradle anchors changed upstream ($n/3 matched); update rebrand.sh\n" unless $n == 3;
    ' "$PG"
fi

# --- 6. Submodule URL: upstream uses a relative URL (../protocol.git) that only resolves on github.com/livekit
git config -f .gitmodules submodule.protocol.url https://github.com/livekit/protocol.git
git submodule sync -q -- protocol 2>/dev/null || true

# --- 7. Maven POM metadata (group/artifact ids are handled by the text pass)
setprop() { # key value file
    if grep -q "^$1=" "$3"; then
        KEY="$1" VAL="$2" perl -pi -e 's/^\Q$ENV{KEY}\E=.*$/$ENV{KEY}=$ENV{VAL}/' "$3"
    else
        die "$1 missing from $3"
    fi
}
# GRAVIX(Phase 4): once gradle.properties carries the GRAVIX(pom) marker the POM keys are maintained by hand (release
# metadata: public scm repo, url, licence); a re-run must not reset them. A pristine upstream tree has no marker, so
# the rebrand output is unchanged.
if ! grep -q 'GRAVIX(pom)' gradle.properties; then
    setprop POM_DESCRIPTION 'Gravix Cloud Android SDK: real-time audio and video rooms' gradle.properties
    setprop POM_URL 'https://www.gravixcloud.com' gradle.properties
    setprop POM_SCM_URL 'https://github.com/assajeeb/gravix-rtc-android' gradle.properties
    setprop POM_SCM_CONNECTION 'scm:git:https://github.com/assajeeb/gravix-rtc-android.git' gradle.properties
    setprop POM_SCM_DEV_CONNECTION 'scm:git:ssh://git@github.com/assajeeb/gravix-rtc-android.git' gradle.properties
    setprop POM_DEVELOPER_ID 'gravitycompile' gradle.properties
    setprop POM_DEVELOPER_NAME 'Gravity Compile' gradle.properties
fi

# --- 8. audioswitch is vendored (tools/vendor/vendor-audioswitch.sh, relocated to com.gravixcloud.rtc.audioswitch;
#        imports are rewritten by the text pass). Drop the JitPack-only dependency and its catalog entries,
#        and confine JitPack to the one sample-app library that still needs it, so an SDK module can
#        never silently start resolving from JitPack again.
[ -d gravix-rtc-android-sdk/src/main/java/com/gravixcloud/rtc/audioswitch ] \
    || die "vendored audioswitch missing: run tools/vendor/vendor-audioswitch.sh <checkout>"
git ls-files | grepq -E '(^|/)build\.gradle$' | while IFS= read -r f; do
    perl -ni -e 'print unless /^\s*(api|implementation|compileOnly)\s+libs\.audioswitch\s*$/' "$f"
done
perl -ni -e 'print unless /^audioswitch\s*=/' gradle/libs.versions.toml
if ! grep -q 'GRAVIX(rebrand): JitPack' settings.gradle; then
    perl -0pi -e '
        my $n = s{^(\s*)maven \{ url \x27https://jitpack\.io\x27 \}\n}{$1maven {\n$1    // GRAVIX(rebrand): JitPack serves only the sample app\x27s groupie; SDK modules resolve from Maven Central.\n$1    url \x27https://jitpack.io\x27\n$1    content { includeGroup \x27com.github.lisawray.groupie\x27 }\n$1}\n}m;
        die "rebrand.sh: JitPack repository anchor not found in settings.gradle\n" unless $n == 1;
    ' settings.gradle
fi

echo "rebrand.sh: done. Review with: git status && git diff --stat; remaining references: $0 --report"
