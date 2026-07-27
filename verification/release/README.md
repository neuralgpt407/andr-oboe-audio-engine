# Release-candidate verification

Ticket 05 qualifies local artifacts only. It does not create or push a tag,
request a JitPack build, or publish to an external repository.

Use an empty Maven repository and the exact candidate version:

```shell
candidate_repository="$(mktemp -d)"

ANDROID_HOME="$ANDROID_SDK_ROOT" ./gradlew \
  :oboe-engine:publishToMavenLocal \
  :oboe-media3:publishToMavenLocal \
  -Dmaven.repo.local="$candidate_repository" \
  -PVERSION_NAME=v0.2.0
```

Download the immutable `v0.1.0` AARs as the binary-compatibility baseline, then
verify POM and Gradle metadata, sources, notices, the additive JVM surface,
packaged ABIs, and native symbols:

```shell
baseline_directory="$(mktemp -d)"
curl -fsSL \
  https://jitpack.io/com/github/neuralgpt407/andr-oboe-audio-engine/oboe-engine/v0.1.0/oboe-engine-v0.1.0.aar \
  -o "$baseline_directory/oboe-engine-v0.1.0.aar"
curl -fsSL \
  https://jitpack.io/com/github/neuralgpt407/andr-oboe-audio-engine/oboe-media3/v0.1.0/oboe-media3-v0.1.0.aar \
  -o "$baseline_directory/oboe-media3-v0.1.0.aar"

ANDROID_HOME="$ANDROID_SDK_ROOT" \
  verification/release/verify-local-publication.sh \
  "$candidate_repository" \
  v0.2.0 \
  "$baseline_directory/oboe-engine-v0.1.0.aar" \
  "$baseline_directory/oboe-media3-v0.1.0.aar"
```

Finally, resolve only `oboe-media3` from that repository. The test imports and
constructs core, frame-precise Recorder, waveform, and Media3 entry points, so
it proves both clean consumption and the transitive Media3-to-core edge:

```shell
ANDROID_HOME="$ANDROID_SDK_ROOT" ./gradlew \
  -p verification/consumer-smoke \
  clean \
  :consumer:testDebugUnitTest \
  -PengineVersion=v0.2.0 \
  -PlocalRepository="$candidate_repository" \
  --refresh-dependencies \
  --no-daemon
```
