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

Download the immutable `v0.1.0` AARs as the binary-compatibility baseline, then
verify POM and Gradle metadata, sources, notices, the supported Kotlin/JVM
surface, packaged ABIs, minimal runtime exports, and matching native-symbol
Build IDs:

```shell
baseline_directory="$(mktemp -d)"
curl -fsSL \
  https://jitpack.io/com/github/neuralgpt407/andr-oboe-audio-engine/oboe-engine/v0.1.0/oboe-engine-v0.1.0.aar \
  -o "$baseline_directory/oboe-engine-v0.1.0.aar"
curl -fsSL \
  https://jitpack.io/com/github/neuralgpt407/andr-oboe-audio-engine/oboe-media3/v0.1.0/oboe-media3-v0.1.0.aar \
  -o "$baseline_directory/oboe-media3-v0.1.0.aar"

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
