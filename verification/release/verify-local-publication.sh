#!/usr/bin/env bash

set -euo pipefail

readonly GROUP="com.github.neuralgpt407.andr-oboe-audio-engine"
readonly GROUP_PATH="com/github/neuralgpt407/andr-oboe-audio-engine"
readonly NDK_VERSION="29.0.14206865"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

require_file() {
    [ -s "$1" ] || fail "missing or empty artifact: $1"
}

require_archive_entry() {
    local archive=$1
    local entry=$2
    unzip -Z1 "$archive" | grep -Fx "$entry" > /dev/null ||
        fail "$archive is missing $entry"
}

xml_value() {
    local file=$1
    local xpath=$2
    xmllint --xpath "string($xpath)" "$file"
}

normalize_api() {
    sed \
        -e '/^Compiled from /d' \
        -e '/^[[:space:]]*$/d' \
        -e 's/[[:space:]]*$//'
}

verify_class_compatibility() {
    local baseline_jar=$1
    local candidate_jar=$2
    local class_name=$3
    local work_dir=$4
    local safe_name=${class_name//[^A-Za-z0-9]/_}
    local baseline_dump="$work_dir/$safe_name.baseline"
    local candidate_dump="$work_dir/$safe_name.candidate"

    javap -classpath "$baseline_jar" -public -s "$class_name" |
        normalize_api > "$baseline_dump"
    javap -classpath "$candidate_jar" -public -s "$class_name" |
        normalize_api > "$candidate_dump"

    while IFS= read -r baseline_line; do
        grep -Fqx "$baseline_line" "$candidate_dump" ||
            fail "binary API removed from $class_name: $baseline_line"
    done < "$baseline_dump"
}

if [ "$#" -ne 4 ]; then
    fail "usage: $0 <local-maven-repository> <candidate-version> <v0.1.0-core-aar> <v0.1.0-media3-aar>"
fi

readonly MAVEN_REPOSITORY=$1
readonly VERSION=$2
readonly BASELINE_CORE_AAR=$3
readonly BASELINE_MEDIA3_AAR=$4

[[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "candidate version must be an exact immutable-tag coordinate"
[ "$VERSION" = "v0.2.0" ] ||
    fail "this qualification contract is pinned to v0.2.0"
[ -d "$MAVEN_REPOSITORY" ] ||
    fail "local Maven repository does not exist: $MAVEN_REPOSITORY"
require_file "$BASELINE_CORE_AAR"
require_file "$BASELINE_MEDIA3_AAR"

readonly CORE_DIRECTORY="$MAVEN_REPOSITORY/$GROUP_PATH/oboe-engine/$VERSION"
readonly MEDIA3_DIRECTORY="$MAVEN_REPOSITORY/$GROUP_PATH/oboe-media3/$VERSION"
readonly CORE_AAR="$CORE_DIRECTORY/oboe-engine-$VERSION.aar"
readonly CORE_POM="$CORE_DIRECTORY/oboe-engine-$VERSION.pom"
readonly CORE_MODULE="$CORE_DIRECTORY/oboe-engine-$VERSION.module"
readonly CORE_SOURCES="$CORE_DIRECTORY/oboe-engine-$VERSION-sources.jar"
readonly CORE_SYMBOLS="$CORE_DIRECTORY/oboe-engine-$VERSION-native-symbols.zip"
readonly MEDIA3_AAR="$MEDIA3_DIRECTORY/oboe-media3-$VERSION.aar"
readonly MEDIA3_POM="$MEDIA3_DIRECTORY/oboe-media3-$VERSION.pom"
readonly MEDIA3_MODULE="$MEDIA3_DIRECTORY/oboe-media3-$VERSION.module"
readonly MEDIA3_SOURCES="$MEDIA3_DIRECTORY/oboe-media3-$VERSION-sources.jar"

for artifact in \
    "$CORE_AAR" \
    "$CORE_POM" \
    "$CORE_MODULE" \
    "$CORE_SOURCES" \
    "$CORE_SYMBOLS" \
    "$MEDIA3_AAR" \
    "$MEDIA3_POM" \
    "$MEDIA3_MODULE" \
    "$MEDIA3_SOURCES"; do
    require_file "$artifact"
done

xmllint --noout "$CORE_POM"
xmllint --noout "$MEDIA3_POM"

[ "$(xml_value "$CORE_POM" "/*[local-name()='project']/*[local-name()='groupId']")" = "$GROUP" ] ||
    fail "core POM group does not match"
[ "$(xml_value "$CORE_POM" "/*[local-name()='project']/*[local-name()='artifactId']")" = "oboe-engine" ] ||
    fail "core POM artifact does not match"
[ "$(xml_value "$CORE_POM" "/*[local-name()='project']/*[local-name()='version']")" = "$VERSION" ] ||
    fail "core POM version does not match"
[ "$(xml_value "$MEDIA3_POM" "/*[local-name()='project']/*[local-name()='groupId']")" = "$GROUP" ] ||
    fail "Media3 POM group does not match"
[ "$(xml_value "$MEDIA3_POM" "/*[local-name()='project']/*[local-name()='artifactId']")" = "oboe-media3" ] ||
    fail "Media3 POM artifact does not match"
[ "$(xml_value "$MEDIA3_POM" "/*[local-name()='project']/*[local-name()='version']")" = "$VERSION" ] ||
    fail "Media3 POM version does not match"

readonly MEDIA3_CORE_VERSION=$(
    xml_value "$MEDIA3_POM" \
        "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId']='$GROUP' and *[local-name()='artifactId']='oboe-engine']/*[local-name()='version']"
)
[ "$MEDIA3_CORE_VERSION" = "$VERSION" ] ||
    fail "Media3 POM does not expose the matching core version transitively"

if grep -Eq 'unspecified|SNAPSHOT|project\(' "$CORE_POM" "$MEDIA3_POM"; then
    fail "POM contains an unpublished or mutable project dependency"
fi

jq -e \
    --arg group "$GROUP" \
    --arg module "oboe-engine" \
    --arg version "$VERSION" \
    '.component.group == $group and .component.module == $module and .component.version == $version' \
    "$CORE_MODULE" > /dev/null ||
    fail "core Gradle module metadata does not identify the candidate"
jq -e \
    --arg group "$GROUP" \
    --arg module "oboe-media3" \
    --arg version "$VERSION" \
    '.component.group == $group and .component.module == $module and .component.version == $version' \
    "$MEDIA3_MODULE" > /dev/null ||
    fail "Media3 Gradle module metadata does not identify the candidate"
jq -e \
    --arg group "$GROUP" \
    --arg version "$VERSION" \
    '[.variants[].dependencies[]? |
        select(
            .group == $group and
            .module == "oboe-engine" and
            .version.requires == $version
        )] | length > 0' \
    "$MEDIA3_MODULE" > /dev/null ||
    fail "Media3 Gradle metadata lacks an exact transitive core dependency"

for entry in \
    "com/neuralsound/audio/AudioEngine.kt" \
    "com/neuralsound/audio/FramePreciseRecorderSession.kt" \
    "com/neuralsound/audio/NativeAudioWaveformAnalyzer.kt" \
    "com/neuralsound/audio/RecorderModels.kt" \
    "META-INF/THIRD_PARTY_NOTICES.md" \
    "META-INF/LICENSE-APACHE-2.0.txt" \
    "META-INF/LICENSE-LLVM-EXCEPTIONS.txt" \
    "META-INF/LICENSE-SIGNALSMITH-LINEAR.txt" \
    "META-INF/LICENSE-SIGNALSMITH-STRETCH.txt"; do
    require_archive_entry "$CORE_SOURCES" "$entry"
done

for entry in \
    "com/neuralsound/audio/media3/Media3AudioEngine.kt" \
    "com/neuralsound/audio/media3/Media3MixerSession.kt" \
    "com/neuralsound/audio/media3/Media3Models.kt" \
    "com/neuralsound/audio/media3/Media3SyncPolicy.kt"; do
    require_archive_entry "$MEDIA3_SOURCES" "$entry"
done

readonly TEMP_DIRECTORY=$(mktemp -d)
trap 'rm -R "${TEMP_DIRECTORY:?}"' EXIT

unzip -p "$CORE_AAR" classes.jar > "$TEMP_DIRECTORY/core-candidate.jar"
unzip -p "$MEDIA3_AAR" classes.jar > "$TEMP_DIRECTORY/media3-candidate.jar"
unzip -p "$BASELINE_CORE_AAR" classes.jar > "$TEMP_DIRECTORY/core-baseline.jar"
unzip -p "$BASELINE_MEDIA3_AAR" classes.jar > "$TEMP_DIRECTORY/media3-baseline.jar"
require_file "$TEMP_DIRECTORY/core-candidate.jar"
require_file "$TEMP_DIRECTORY/media3-candidate.jar"
require_file "$TEMP_DIRECTORY/core-baseline.jar"
require_file "$TEMP_DIRECTORY/media3-baseline.jar"
require_archive_entry "$TEMP_DIRECTORY/core-candidate.jar" "META-INF/THIRD_PARTY_NOTICES.md"

jar tf "$TEMP_DIRECTORY/core-baseline.jar" |
    grep -E '^com/neuralsound/audio/[^/]+\.class$' |
    sort > "$TEMP_DIRECTORY/core-baseline-classes.txt"
jar tf "$TEMP_DIRECTORY/core-candidate.jar" |
    grep -E '^com/neuralsound/audio/[^/]+\.class$' |
    sort > "$TEMP_DIRECTORY/core-candidate-classes.txt"
jar tf "$TEMP_DIRECTORY/media3-baseline.jar" |
    grep -E '^com/neuralsound/audio/media3/[^/$]+\.class$' |
    sort > "$TEMP_DIRECTORY/media3-baseline-classes.txt"
jar tf "$TEMP_DIRECTORY/media3-candidate.jar" |
    grep -E '^com/neuralsound/audio/media3/[^/$]+\.class$' |
    sort > "$TEMP_DIRECTORY/media3-candidate-classes.txt"

if [ -n "$(comm -23 "$TEMP_DIRECTORY/core-baseline-classes.txt" "$TEMP_DIRECTORY/core-candidate-classes.txt")" ]; then
    comm -23 "$TEMP_DIRECTORY/core-baseline-classes.txt" "$TEMP_DIRECTORY/core-candidate-classes.txt" >&2
    fail "candidate removed v0.1.0 core classes"
fi
if ! diff -u \
    "$TEMP_DIRECTORY/media3-baseline-classes.txt" \
    "$TEMP_DIRECTORY/media3-candidate-classes.txt"; then
    fail "Media3 top-level class surface changed unexpectedly"
fi

comm -13 \
    "$TEMP_DIRECTORY/core-baseline-classes.txt" \
    "$TEMP_DIRECTORY/core-candidate-classes.txt" \
    > "$TEMP_DIRECTORY/core-class-additions.txt"
if ! diff -u \
    "$(dirname "$0")/expected-v0.2.0-core-class-additions.txt" \
    "$TEMP_DIRECTORY/core-class-additions.txt"; then
    fail "core class additions differ from the reviewed v0.2.0 surface"
fi

while IFS= read -r class_entry; do
    class_name=${class_entry%.class}
    class_name=${class_name//\//.}
    verify_class_compatibility \
        "$TEMP_DIRECTORY/core-baseline.jar" \
        "$TEMP_DIRECTORY/core-candidate.jar" \
        "$class_name" \
        "$TEMP_DIRECTORY"
done < "$TEMP_DIRECTORY/core-baseline-classes.txt"

while IFS= read -r class_entry; do
    class_name=${class_entry%.class}
    class_name=${class_name//\//.}
    verify_class_compatibility \
        "$TEMP_DIRECTORY/media3-baseline.jar" \
        "$TEMP_DIRECTORY/media3-candidate.jar" \
        "$class_name" \
        "$TEMP_DIRECTORY"
done < "$TEMP_DIRECTORY/media3-baseline-classes.txt"

javap \
    -classpath "$TEMP_DIRECTORY/core-candidate.jar" \
    -public \
    com.neuralsound.audio.AudioEngine \
    com.neuralsound.audio.FramePreciseRecorderSession \
    com.neuralsound.audio.NativeAudioWaveformAnalyzer \
    com.neuralsound.audio.NativeWaveformAnalysisResult \
    com.neuralsound.audio.NativeWaveformAnalysisResult\$Success \
    com.neuralsound.audio.NativeWaveformAnalysisResult\$Failure \
    com.neuralsound.audio.RecorderFailure \
    com.neuralsound.audio.RecorderState \
    > "$TEMP_DIRECTORY/v0.2.0-public-api.txt"

for expected_api in \
    "createFramePreciseRecorderSession()" \
    "startWriting(com.neuralsound.audio.FrameRecordingRequest)" \
    "ALGORITHM_VERSION" \
    "MAX_OUTPUT_SAMPLES" \
    "analyze(android.os.ParcelFileDescriptor" \
    "getCurrentFailure()" \
    "getLevels()" \
    "getError()"; do
    grep -Fq "$expected_api" "$TEMP_DIRECTORY/v0.2.0-public-api.txt" ||
        fail "candidate public API is missing $expected_api"
done

javap \
    -classpath "$TEMP_DIRECTORY/core-baseline.jar" \
    -public \
    -s \
    com.neuralsound.audio.RecorderSession \
    com.neuralsound.audio.RecordingRequest |
    normalize_api > "$TEMP_DIRECTORY/legacy-recorder-baseline.txt"
javap \
    -classpath "$TEMP_DIRECTORY/core-candidate.jar" \
    -public \
    -s \
    com.neuralsound.audio.RecorderSession \
    com.neuralsound.audio.RecordingRequest |
    normalize_api > "$TEMP_DIRECTORY/legacy-recorder-candidate.txt"
diff -u \
    "$TEMP_DIRECTORY/legacy-recorder-baseline.txt" \
    "$TEMP_DIRECTORY/legacy-recorder-candidate.txt" ||
    fail "legacy RecorderSession or RecordingRequest ABI changed"

readonly EXPECTED_ABIS=$'arm64-v8a\narmeabi-v7a'
readonly AAR_ABIS=$(
    unzip -Z1 "$CORE_AAR" |
        awk -F/ '$1 == "jni" && NF >= 3 { print $2 }' |
        sort -u
)
[ "$AAR_ABIS" = "$EXPECTED_ABIS" ] ||
    fail "core AAR ABI set is not exactly arm64-v8a and armeabi-v7a"

for abi in arm64-v8a armeabi-v7a; do
    for library in \
        "libc++_shared.so" \
        "libneuralsound_audio_engine.so" \
        "liboboe.so"; do
        require_archive_entry "$CORE_AAR" "jni/$abi/$library"
        require_archive_entry "$CORE_SYMBOLS" "$abi/$library"
    done
done
require_archive_entry "$CORE_SYMBOLS" "META-INF/THIRD_PARTY_NOTICES.md"

readonly NDK_ROOT=${ANDROID_NDK_HOME:-"${ANDROID_HOME:?ANDROID_HOME is required}/ndk/$NDK_VERSION"}
NDK_BIN=""
for prebuilt_directory in "$NDK_ROOT"/toolchains/llvm/prebuilt/*; do
    if [ -x "$prebuilt_directory/bin/llvm-nm" ]; then
        NDK_BIN="$prebuilt_directory/bin"
        break
    fi
done
[ -n "$NDK_BIN" ] || fail "NDK LLVM tools were not found under $NDK_ROOT"
readonly NDK_BIN
readonly LLVM_NM="$NDK_BIN/llvm-nm"
readonly LLVM_OBJDUMP="$NDK_BIN/llvm-objdump"
require_file "$LLVM_NM"
require_file "$LLVM_OBJDUMP"

for abi in arm64-v8a armeabi-v7a; do
    symbol_library="$TEMP_DIRECTORY/$abi-libneuralsound_audio_engine.so"
    unzip -p "$CORE_SYMBOLS" "$abi/libneuralsound_audio_engine.so" > "$symbol_library"
    require_file "$symbol_library"

    file "$symbol_library" | grep -F "not stripped" > /dev/null ||
        fail "$abi native symbols are stripped"
    "$LLVM_OBJDUMP" --section-headers "$symbol_library" |
        grep -F ".debug_info" > /dev/null ||
        fail "$abi native symbols do not contain DWARF debug information"
    "$LLVM_NM" --defined-only --extern-only "$symbol_library" |
        grep -F "JNI_OnLoad" > /dev/null ||
        fail "$abi native library does not export JNI_OnLoad"

    for symbol in \
        "DecodedAudioNormalizer" \
        "NativeAudioWaveformAnalyzer" \
        "RecorderFailureState"; do
        "$LLVM_NM" --defined-only --extern-only "$symbol_library" |
            grep -F "$symbol" > /dev/null ||
            fail "$abi native symbols do not expose usable $symbol diagnostics"
    done
done

shasum -a 256 \
    "$CORE_AAR" \
    "$CORE_POM" \
    "$CORE_SOURCES" \
    "$CORE_SYMBOLS" \
    "$MEDIA3_AAR" \
    "$MEDIA3_POM" \
    "$MEDIA3_SOURCES"
printf 'PASS: local publication %s has compatible APIs, exact metadata, both ABIs, notices, sources, and usable native symbols\n' "$VERSION"
