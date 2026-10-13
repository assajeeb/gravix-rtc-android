#!/usr/bin/env bash
# Copyright 2026 Gravity Compile
# SPDX-License-Identifier: Apache-2.0
#
# Release gate, local only (no network, no upload): build the signed Maven Central bundle with
# `./gradlew :gravix-rtc-android-sdk:centralBundle` and check it the way the Central Portal validates a deployment:
#   - every artifact (.aar, .pom, .module, -sources.jar, -javadoc.jar) has .asc + .md5 + .sha1 + .sha256 + .sha512,
#     and every checksum matches the file;
#   - every .asc verifies against the given public key (gpgv: no agent, no keyring of yours is touched);
#   - the POM has the required fields: groupId, artifactId, version (not -SNAPSHOT), packaging aar, name,
#     description, url, licenses/license (name AND url), developers/developer/name, scm (url, connection),
#     and no <repositories>;
#   - no unexpected files (maven-metadata, .asc checksums).
#
# Signing key: whatever the build is configured with (signing.gnupg.keyName -> gpg; else signingInMemoryKey*).
# For a dry run with a THROWAWAY key pass it as ORG_GRADLE_PROJECT_signingInMemoryKey/-KeyId/-KeyPassword in the
# environment of this script (never on the command line, never in a file in the repository).
#
#   tools/release/central-bundle-check.sh <public-key-file> [extra gradle args]
#       <public-key-file>: the signing key's public key (binary or armored export) for gpgv
#   exit 0 = PASS, 1 = FAIL, 2 = usage
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 <public-key-file> [extra gradle args]" >&2; exit 2; }
PUBKEY=$1; shift
[ -f "$PUBKEY" ] || { echo "central-bundle-check: no such public key file: $PUBKEY" >&2; exit 2; }
GPGV=${GPGV:-$(command -v gpgv || true)}
[ -n "$GPGV" ] || { echo "central-bundle-check: gpgv not found (GnuPG)" >&2; exit 2; }

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
VERSION=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
fail() { echo "central-bundle-check: FAIL: $*" >&2; exit 1; }

# --no-configuration-cache: the signing key is read at configuration time; keep it out of the cache entry.
./gradlew --console=plain -q --no-configuration-cache "$@" :gravix-rtc-android-sdk:centralBundle

ZIP="gravix-rtc-android-sdk/build/central-bundle/gravix-rtc-android-$VERSION-bundle.zip"
[ -f "$ZIP" ] || fail "bundle not built: $ZIP"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP/bundle"
# gpgv wants a keyring file: a binary export is used as is, an armored one is dearmored here (in Python, so no
# gpg homedir -- yours or anyone's -- is opened).
KEYRING="$TMP/key.gpg"
python3 -I - "$PUBKEY" "$KEYRING" <<'PY' || fail "cannot read the public key $PUBKEY"
import base64, sys
raw = open(sys.argv[1], "rb").read()
if b"-----BEGIN PGP PUBLIC KEY BLOCK-----" in raw:
    lines = raw.decode("ascii").splitlines()
    body = lines[lines.index("-----BEGIN PGP PUBLIC KEY BLOCK-----") + 1:]
    body = body[: next(i for i, l in enumerate(body) if l.startswith("-----END"))]
    while body and (":" in body[0] or body[0].strip() == ""):  # armor headers, then the blank line
        if body[0].strip() == "":
            body = body[1:]
            break
        body = body[1:]
    raw = base64.b64decode("".join(l for l in body if not l.startswith("=")))
open(sys.argv[2], "wb").write(raw)
PY

python3 -I - "$TMP/bundle" "$KEYRING" "$GPGV" "$VERSION" <<'PY'
import hashlib, os, subprocess, sys, xml.etree.ElementTree as ET
root, keyring, gpgv, version = sys.argv[1:5]
base = os.path.join(root, "com", "gravixcloud", "gravix-rtc-android", version)
problems = []
if not os.path.isdir(base):
    print("central-bundle-check: FAIL: no com/gravixcloud/gravix-rtc-android/%s in the bundle" % version); sys.exit(1)
all_files = sorted(os.path.relpath(os.path.join(d, f), root) for d, _, fs in os.walk(root) for f in fs)
outside = [f for f in all_files if not f.startswith(os.path.relpath(base, root) + os.sep)]
if outside: problems.append("files outside the artifact directory: %s" % outside)
names = sorted(os.listdir(base))
sidecars = (".asc", ".md5", ".sha1", ".sha256", ".sha512")
artifacts = [n for n in names if not n.endswith(sidecars)]
stem = "gravix-rtc-android-%s" % version
for need in (stem + ".aar", stem + ".pom", stem + "-sources.jar", stem + "-javadoc.jar", stem + ".module"):
    if need not in artifacts: problems.append("missing artifact " + need)
for bad in [n for n in names if "maven-metadata" in n or any(n.endswith(".asc" + s) for s in sidecars[1:])]:
    problems.append("unexpected file " + bad)
algos = {".md5": "md5", ".sha1": "sha1", ".sha256": "sha256", ".sha512": "sha512"}
signed = 0
for a in artifacts:
    path = os.path.join(base, a)
    data = open(path, "rb").read()
    for ext, algo in algos.items():
        side = path + ext
        if not os.path.exists(side): problems.append("%s: no %s" % (a, ext)); continue
        want = open(side).read().split()[0].strip().lower()
        if want != hashlib.new(algo, data).hexdigest(): problems.append("%s: %s mismatch" % (a, ext))
    if not os.path.exists(path + ".asc"): problems.append("%s: no .asc" % a); continue
    r = subprocess.run([gpgv, "--keyring", keyring, path + ".asc", path], capture_output=True, text=True)
    if r.returncode != 0: problems.append("%s: signature does not verify: %s" % (a, r.stderr.strip().splitlines()[-1:]))
    else: signed += 1
# POM: Central's required elements
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(os.path.join(base, stem + ".pom")).getroot()
def text(xpath):
    e = pom.find(xpath, ns)
    return (e.text or "").strip() if e is not None else ""
for xp in ("m:groupId", "m:artifactId", "m:version", "m:name", "m:description", "m:url", "m:packaging",
           "m:licenses/m:license/m:name", "m:licenses/m:license/m:url", "m:developers/m:developer/m:name",
           "m:scm/m:url", "m:scm/m:connection"):
    if not text(xp): problems.append("POM: missing " + xp.replace("m:", ""))
if text("m:groupId") != "com.gravixcloud" or text("m:artifactId") != "gravix-rtc-android":
    problems.append("POM: coordinates %s:%s" % (text("m:groupId"), text("m:artifactId")))
if text("m:version") != version or version.endswith("-SNAPSHOT"): problems.append("POM: version " + text("m:version"))
if text("m:packaging") != "aar": problems.append("POM: packaging " + text("m:packaging"))
if pom.find("m:repositories", ns) is not None: problems.append("POM: declares <repositories>")
warnings = []
for xp in ("m:url", "m:scm/m:url", "m:scm/m:connection", "m:scm/m:developerConnection"):
    if ".invalid" in text(xp): warnings.append("POM %s is the .invalid placeholder (owner sets the public repo before release)" % xp.replace("m:", ""))
for f in all_files: print("  " + f)
for w in warnings: print("central-bundle-check: WARN: " + w)
if problems:
    for p in problems: print("central-bundle-check: FAIL: " + p)
    sys.exit(1)
print("central-bundle-check: PASS: %d artifacts, each with .asc (verified by gpgv) + md5/sha1/sha256/sha512 (recomputed); "
      "POM has every Central-required field" % signed)
PY
echo "central-bundle-check: bundle $ZIP ($(du -h "$ZIP" | cut -f1))"
