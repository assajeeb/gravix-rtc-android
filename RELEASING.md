# Releasing com.gravixcloud:gravix-rtc-android to Maven Central

Only the owner releases. A Maven Central release can **never be changed or deleted**: a mistake means a new
version. Nothing in this repository uploads unless `publishToMavenCentral` is typed by hand, and even then the
deployment waits in the Central Portal until the owner presses **Publish**.

Publishing is configured in `gravix-rtc-android-sdk/build.gradle` (vanniktech maven-publish 0.34.0, base plugin;
the same task and property names as `gravix-token-java`). Only `gravix-rtc-android` is published; the camerax,
track-processors and test modules are not (0.1.0).

## One-time setup (owner)

1. Central Portal account (central.sonatype.com), namespace `com.gravixcloud` verified by the DNS TXT record on
   gravixcloud.com.
2. Portal user token (central.sonatype.com/usertoken) in `~/.gradle/gradle.properties` -- never in this repository:
   ```
   mavenCentralUsername=<token user>
   mavenCentralPassword=<token password>
   ```
3. Release signing key in your own GnuPG (`~/.gnupg`), public key on keyserver.ubuntu.com, and in
   `~/.gradle/gradle.properties` (no private key and no passphrase in any file):
   ```
   signing.gnupg.executable=/opt/homebrew/bin/gpg
   signing.gnupg.useLegacyGpg=false
   signing.gnupg.keyName=<your key id>
   ```
   With `signing.gnupg.keyName` set the build signs with the gpg command (gpg-agent holds the passphrase).
   Otherwise it uses an in-memory key: `signingInMemoryKey` (ASCII-armored), `signingInMemoryKeyId` (last 8 hex
   digits), `signingInMemoryKeyPassword`, e.g. as `ORG_GRADLE_PROJECT_*` environment variables in CI.

Signing runs only when `publishToMavenCentral` or `centralBundle` is named on the command line, so everyday
builds and `publishToMavenLocal` never call gpg.

## Every release

1. **Public source repository.** Set `POM_SCM_URL`, `POM_SCM_CONNECTION`, `POM_SCM_DEV_CONNECTION` in
   `gradle.properties` to the public snapshot repository (made by `tools/release/public-snapshot.sh`; the private
   development repository must not appear in a published POM). An upload refuses to run while any of them still
   holds the `.invalid` placeholder.
2. **Version.** `VERSION_NAME` in `gradle.properties` (plain `x.y.z`; an upload refuses anything else),
   `CHANGELOG.md` section for it.
3. **Gates** (all must pass on a fresh clone of the release commit, `git submodule update --init` first):
   ```
   ./gradlew assembleRelease :gravix-rtc-android-test:testRelease :gravix-rtc-lint:test \
       :gravix-rtc-detekt-rules:test :gravix-rtc-android-sdk:apiCheck :sample-app-java:assembleDebug
   python3 tools/api-scan/scan.py
   tools/consumer-check/run.sh
   tools/rebrand/rebrand.sh && git diff --exit-code
   tools/release/public-snapshot.sh <empty dir>      # builds + tests the snapshot, gitleaks scan
   ```
4. **Dry run of the upload bundle** (local, no network; signs with your key, so unlock it first):
   ```
   export GPG_TTY=$(tty)
   echo unlock | gpg --clearsign --local-user <your key id> >/dev/null
   gpg --export <your key id> > /tmp/release-pub.gpg
   tools/release/central-bundle-check.sh /tmp/release-pub.gpg
   ```
   PASS = every artifact (aar, pom, module, sources, javadoc) has a verified `.asc` and md5/sha1/sha256/sha512,
   and the POM has every field Central requires. The zip is
   `gravix-rtc-android-sdk/build/central-bundle/gravix-rtc-android-<version>-bundle.zip`.
5. **Upload** (one deployment, USER_MANAGED):
   ```
   export GPG_TTY=$(tty)
   echo unlock | gpg --clearsign --local-user <your key id> >/dev/null
   ./gradlew --no-configuration-cache publishToMavenCentral
   ```
   `--no-configuration-cache` keeps the Portal token out of Gradle's configuration-cache store.
   Never `publishAndReleaseToMavenCentral` (the build refuses it): the release waits for step 6.
6. **Publish.** central.sonatype.com -> Publish -> Deployments: the deployment shows VALIDATED (or FAILED with
   the reasons; then Drop it, fix, and upload again). Check the file list, then press **Publish**. It appears on
   central.sonatype.com within minutes and on repo1.maven.org usually within an hour.
7. Tag the release commit (`v<version>`) and push the public snapshot.

## Guards in the build

- `checkReleaseSigning` runs before vanniktech's `prepareMavenCentralPublishing` (which registers the end-of-build
  upload for ANY `*ToMavenCentralRepository` publish, a plain `./gradlew publish` included) and before the local
  bundle. It fails unless a release task was named on the command line and a signing key is configured; it
  refuses `publishAndReleaseToMavenCentral`; for an upload it also refuses a version that is not `x.y.z` and a
  POM that still has the `.invalid` placeholders.
- `tools/api-scan/scan.py` + `apiCheck`: the public API is the reviewed dump
  (`gravix-rtc-android-sdk/api/gravix-rtc-android-sdk.api`) and names no implementation-only dependency.
- `tools/consumer-check/run.sh`: Kotlin, Kotlin SDK-only and Java SDK-only apps build against the published
  artifact with only Google + Maven Central (+ Maven Local for com.gravixcloud).
