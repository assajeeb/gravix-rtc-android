#!/usr/bin/env python3
# Copyright 2026 Gravity Compile
# SPDX-License-Identifier: Apache-2.0
"""Release gate: no public SDK signature may name a type from an `implementation`-only dependency.

Why: a type that appears in a public signature but comes from an `implementation` dependency is not on the
app's compile classpath (Gradle module metadata and the POM put it in runtime scope only), so apps cannot
compile a call that uses it. The first real-fleet run hit exactly this: regionsMeasuredReport() returned a
kotlinx-serialization JsonObject. Phase 4's scan of the whole dump also found coroutines and protobuf types
(both now `api`, they ARE the API) and jwtdecode's JWT (TokenPayload.jwt, now internal).

Reads the binary-compatibility-validator dump (gravix-rtc-android-sdk/api/gravix-rtc-android-sdk.api) and
reports every line naming a package of an implementation-only dependency, minus the ALLOWED list: dependency
injection plumbing (dagger factories, @Inject constructors) and kotlinx-serialization companions that apps
never call. Keep FORBIDDEN in step with the `implementation` lines of gravix-rtc-android-sdk/build.gradle.

    python3 tools/api-scan/scan.py [path/to/dump.api]     exit 0 = PASS, 1 = FAIL (leaks listed), 2 = usage
"""
import re
import sys

# Packages of the SDK's implementation-only dependencies (gravix-rtc-android-sdk/build.gradle).
FORBIDDEN = {
    "kotlinx/serialization/": "kotlinx-serialization-json",
    "com/auth0/android/jwt/": "jwtdecode",
    "dagger/": "dagger",
    "javax/inject/": "dagger (javax.inject)",
    "androidx/core/": "androidx.core",
    "javax/sip/": "android-jain-sip-ri",
    "gov/nist/": "android-jain-sip-ri",
}

# (class regex, member regex) pairs that may name a forbidden package. Each is plumbing an app never calls.
ALLOWED = [
    # dagger-generated factories / members injectors / components (public by codegen, not app API)
    (r"_(Factory|MembersInjector)(\$.*)?$|_Factory_Impl$|/Dagger[A-Z]", r".*"),
    # dagger modules' provider functions (JsonFormatModule.kotlinSerializationJson, ...)
    (r"^com/gravixcloud/rtc/dagger/", r".*"),
    # @Inject constructors of engine classes built by dagger (upstream signatures)
    (r"^com/gravixcloud/rtc/room/(SignalClient|RegionUrlProvider)$", r"^public fun <init> "),
    (r"^com/gravixcloud/rtc/room/provisions/GxObjects$", r"^public fun <init> "),
    # kotlinx-serialization companions of @Serializable types: serializer() is for apps that use the library
    (r"\$Companion$", r"^public final fun serializer \(\)Lkotlinx/serialization/KSerializer;$"),
    (r"\$\$serializer$", r".*"),
]


def scan(path):
    leaks = []
    cls = None
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        m = re.match(r"^(?:public|protected)[a-z ]* (?:class|interface) ([^ ]+)", line)
        if m:
            cls = m.group(1)
        if cls is None:
            continue
        for pkg, dep in FORBIDDEN.items():
            # in a type descriptor (Lpkg/...;) or a supertype list ("class X : pkg/Y")
            if ("L" + pkg) not in line and (" : " + pkg) not in line and (", " + pkg) not in line:
                continue
            if any(re.search(c, cls) and re.search(mm, line) for c, mm in ALLOWED):
                continue
            leaks.append((dep, cls, line))
    return leaks


def main(argv):
    path = argv[1] if len(argv) > 1 else "gravix-rtc-android-sdk/api/gravix-rtc-android-sdk.api"
    try:
        leaks = scan(path)
    except OSError as e:
        print(f"api-scan: cannot read {path}: {e}", file=sys.stderr)
        return 2
    if leaks:
        for dep, cls, line in leaks:
            print(f"api-scan: LEAK ({dep}) {cls}: {line}")
        print(f"api-scan: FAIL, {len(leaks)} public signature(s) name implementation-only types")
        return 1
    print(f"api-scan: PASS ({path}: no implementation-only types in public signatures outside the allowlist)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
