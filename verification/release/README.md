# Release-candidate verification

Ticket 05 qualifies local artifacts only. It does not create or push a tag,
request a JitPack build, or publish to an external repository.

Set the SDK path once, then use an empty Maven repository and the exact
candidate version:

```shell
export ANDROID_HOME=/absolute/path/to/Android/sdk
candidate_repository="$(mktemp -d)"

./gradlew \
  :oboe-engine:publishToMavenLocal \
  :oboe-media3:publishToMavenLocal \
  -Dmaven.repo.local="$candidate_repository" \
  -PVERSION_NAME=v0.2.0
```

Download the immutable `v0.1.0` AARs as the binary-compatibility baseline. The
verifier pins their SHA-256 digests, rejects mutable POM or Gradle metadata,
compares the exact approved additive Kotlin/JVM surface and root class set,
checks the exact runtime and symbol-classifier entries, and validates Build IDs
plus usable debug sections for every native library in both ABIs:

```shell
baseline_directory="$(mktemp -d)"
curl -fsSL \
  https://jitpack.io/com/github/neuralgpt407/andr-oboe-audio-engine/oboe-engine/v0.1.0/oboe-engine-v0.1.0.aar \
  -o "$baseline_directory/oboe-engine-v0.1.0.aar"
curl -fsSL \
  https://jitpack.io/com/github/neuralgpt407/andr-oboe-audio-engine/oboe-media3/v0.1.0/oboe-media3-v0.1.0.aar \
  -o "$baseline_directory/oboe-media3-v0.1.0.aar"

printf '%s  %s\n' \
  22c3d516b3ac0cfca1c540244fb6f3d179014887dad9ae548640ee6d4422a1ab \
  "$baseline_directory/oboe-engine-v0.1.0.aar" \
  21000548f55c275662e1eb85d7b9185dc3b2acc0daf5a05aeee4f465a379eb47 \
  "$baseline_directory/oboe-media3-v0.1.0.aar" |
  shasum -a 256 -c

verification/release/verify-local-publication.sh \
  "$candidate_repository" \
  v0.2.0 \
  "$baseline_directory/oboe-engine-v0.1.0.aar" \
  "$baseline_directory/oboe-media3-v0.1.0.aar"
```

Finally, resolve only `oboe-media3` from that repository. The Java/Kotlin tests
compile the v0.1 named-argument surface and construct or invoke core,
frame-precise Recorder, waveform analysis, and Media3 preparation, so they
prove source-compatible clean consumption and the transitive Media3-to-core
edge:

```shell
./gradlew \
  -p verification/consumer-smoke \
  clean \
  :consumer:testDebugUnitTest \
  -PengineVersion=v0.2.0 \
  -PlocalRepository="$candidate_repository" \
  --refresh-dependencies \
  --no-daemon
```
