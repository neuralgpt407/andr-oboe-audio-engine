# Release qualification (audio-engine)

Qualify a local publication before creating an immutable tag. Nothing here pushes, tags or
publishes. The release is qualified by the API dump, metadata, native and consumer checks
below. The v1.0.0 public API is new (FEAT-0001), so there is no binary-compatibility
baseline against v0.x; from v1.0.0 on, `apiCheck` must show additions only.

```shell
export ANDROID_HOME=/absolute/path/to/Android/sdk
candidate_repository="$(mktemp -d)"

# Start clean: stale CMake configurations leave duplicate libraries for the symbols zip.
./gradlew :audio-engine:clean
./gradlew :audio-engine:apiCheck
./gradlew :audio-engine:publishToMavenLocal \
  -Dmaven.repo.local="$candidate_repository" \
  -PVERSION_NAME=v1.0.0

verification/release/verify-local-publication.sh "$candidate_repository" v1.0.0
```

`verify-local-publication.sh` requires the AAR, POM, Gradle module metadata, sources and
`native-symbols` classifier, then runs:

- `verify-metadata.sh`: exact coordinates; `api` dependencies on coroutines and Media3
  ExoPlayer; sources contain every public package and no removed `oboe-*` module.
- `verify-native.sh`: exactly `arm64-v8a` and `armeabi-v7a`; the expected runtime libraries
  and symbol entries; Build IDs that match between runtime and symbol libraries; usable debug
  sections; and `JNI_OnLoad` as the only exported engine symbol.

Then compile and run a clean consumer against only that repository:

```shell
./gradlew -p verification/consumer-smoke :consumer:testDebugUnitTest \
  -PengineVersion=v1.0.0 \
  -PlocalRepository="$candidate_repository"
```
