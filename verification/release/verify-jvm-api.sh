#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
source "$SCRIPT_DIRECTORY/lib/common.sh"
initialize_verification_context "$@"

normalize_api() {
    awk '
        /^[[:space:]]+public .* access\$/ {
            skip_descriptor = 1
            next
        }
        skip_descriptor == 1 && /^[[:space:]]+descriptor:/ {
            skip_descriptor = 0
            next
        }
        { print }
    ' |
        sed \
        -e '/^Compiled from /d' \
        -e '/^[[:space:]]*$/d' \
        -e 's/[[:space:]]*$//'
}

verify_class_compatibility() {
    local baseline_jar=$1
    local candidate_jar=$2
    local class_name=$3
    local work_directory=$4
    local expected_additions=${5:-}
    local safe_name=${class_name//[^A-Za-z0-9]/_}
    local baseline_dump="$work_directory/$safe_name.baseline"
    local candidate_dump="$work_directory/$safe_name.candidate"
    local candidate_additions="$work_directory/$safe_name.additions"

    javap -classpath "$baseline_jar" -public -s "$class_name" |
        normalize_api > "$baseline_dump"
    javap -classpath "$candidate_jar" -public -s "$class_name" |
        normalize_api > "$candidate_dump"

    while IFS= read -r baseline_line; do
        grep -Fqx "$baseline_line" "$candidate_dump" ||
            fail "binary API removed from $class_name: $baseline_line"
    done < "$baseline_dump"

    grep -Fvx -f "$baseline_dump" "$candidate_dump" > "$candidate_additions" || true
    if [ -n "$expected_additions" ]; then
        diff -u "$expected_additions" "$candidate_additions" ||
            fail "candidate exposes an unsupported additive API on $class_name"
    elif [ -s "$candidate_additions" ]; then
        cat "$candidate_additions" >&2
        fail "candidate exposes an unsupported additive API on $class_name"
    fi
}

verify_supported_classes() {
    local class_list=$1
    local baseline_jar=$2
    local candidate_jar=$3
    local work_directory=$4
    local additive_class=${5:-}
    local additive_contract=${6:-}

    while IFS= read -r class_name; do
        [ -n "$class_name" ] || continue
        expected_additions=""
        if [ "$class_name" = "$additive_class" ]; then
            expected_additions=$additive_contract
        fi
        verify_class_compatibility \
            "$baseline_jar" \
            "$candidate_jar" \
            "$class_name" \
            "$work_directory" \
            "$expected_additions"
    done < "$class_list"
}

verify_root_class_additions() {
    local baseline_jar=$1
    local candidate_jar=$2
    local package_path=$3
    local expected_additions=$4
    local work_directory=$5
    local safe_package=${package_path//\//_}
    local baseline_classes="$work_directory/$safe_package.baseline-classes"
    local candidate_classes="$work_directory/$safe_package.candidate-classes"
    local removed_classes="$work_directory/$safe_package.removed-classes"
    local added_classes="$work_directory/$safe_package.added-classes"

    unzip -Z1 "$baseline_jar" |
        grep -E "^${package_path}/[^/]+\\.class$" |
        sort -u > "$baseline_classes"
    unzip -Z1 "$candidate_jar" |
        grep -E "^${package_path}/[^/]+\\.class$" |
        sort -u > "$candidate_classes"
    comm -23 "$baseline_classes" "$candidate_classes" > "$removed_classes"
    [ ! -s "$removed_classes" ] || {
        cat "$removed_classes" >&2
        fail "candidate removed root classes from $package_path"
    }
    comm -13 "$baseline_classes" "$candidate_classes" > "$added_classes"
    diff -u "$expected_additions" "$added_classes" ||
        fail "candidate root class additions do not match $expected_additions"
}

readonly TEMP_DIRECTORY=$(mktemp -d)
trap 'rm -R "${TEMP_DIRECTORY:?}"' EXIT

unzip -p "$CORE_AAR" classes.jar > "$TEMP_DIRECTORY/core-candidate.jar"
unzip -p "$MEDIA3_AAR" classes.jar > "$TEMP_DIRECTORY/media3-candidate.jar"
unzip -p "$BASELINE_CORE_AAR" classes.jar > "$TEMP_DIRECTORY/core-baseline.jar"
unzip -p "$BASELINE_MEDIA3_AAR" classes.jar > "$TEMP_DIRECTORY/media3-baseline.jar"
for artifact in \
    "$TEMP_DIRECTORY/core-candidate.jar" \
    "$TEMP_DIRECTORY/media3-candidate.jar" \
    "$TEMP_DIRECTORY/core-baseline.jar" \
    "$TEMP_DIRECTORY/media3-baseline.jar"; do
    require_file "$artifact"
done
touch "$TEMP_DIRECTORY/no-class-additions"
verify_root_class_additions \
    "$TEMP_DIRECTORY/core-baseline.jar" \
    "$TEMP_DIRECTORY/core-candidate.jar" \
    "com/neuralsound/audio" \
    "$SCRIPT_DIRECTORY/v0.2.0-core-root-class-additions.txt" \
    "$TEMP_DIRECTORY"
verify_root_class_additions \
    "$TEMP_DIRECTORY/media3-baseline.jar" \
    "$TEMP_DIRECTORY/media3-candidate.jar" \
    "com/neuralsound/audio/media3" \
    "$TEMP_DIRECTORY/no-class-additions" \
    "$TEMP_DIRECTORY"
require_archive_entry "$TEMP_DIRECTORY/core-candidate.jar" "META-INF/THIRD_PARTY_NOTICES.md"
unzip -p "$CORE_AAR" proguard.txt > "$TEMP_DIRECTORY/consumer-rules.pro"
require_file "$TEMP_DIRECTORY/consumer-rules.pro"
grep -F \
    "class com.neuralsound.audio.internal.JniNativeWaveformBridge" \
    "$TEMP_DIRECTORY/consumer-rules.pro" > /dev/null ||
    fail "consumer rules do not preserve the registered waveform JNI bridge"

verify_supported_classes \
    "$SCRIPT_DIRECTORY/v0.1.0-core-supported-classes.txt" \
    "$TEMP_DIRECTORY/core-baseline.jar" \
    "$TEMP_DIRECTORY/core-candidate.jar" \
    "$TEMP_DIRECTORY" \
    "com.neuralsound.audio.AudioEngine" \
    "$SCRIPT_DIRECTORY/v0.2.0-audio-engine-public-additions.txt"
verify_supported_classes \
    "$SCRIPT_DIRECTORY/v0.1.0-media3-supported-classes.txt" \
    "$TEMP_DIRECTORY/media3-baseline.jar" \
    "$TEMP_DIRECTORY/media3-candidate.jar" \
    "$TEMP_DIRECTORY"

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

while IFS= read -r class_name; do
    [ -n "$class_name" ] || continue
    class_entry=${class_name//.//}.class
    require_archive_entry "$TEMP_DIRECTORY/core-candidate.jar" "$class_entry"
done < "$SCRIPT_DIRECTORY/v0.2.0-core-public-additions.txt"

javap \
    -classpath "$TEMP_DIRECTORY/core-candidate.jar" \
    -public \
    -s \
    com.neuralsound.audio.AudioEngine \
    com.neuralsound.audio.FramePreciseRecorderSession \
    com.neuralsound.audio.FrameRecordingRequest \
    com.neuralsound.audio.NativeAudioWaveformAnalyzer \
    com.neuralsound.audio.NativeAudioWaveformAnalyzer\$Companion \
    com.neuralsound.audio.NativeWaveformAnalysisError \
    com.neuralsound.audio.NativeWaveformAnalysisResult \
    com.neuralsound.audio.NativeWaveformAnalysisResult\$Failure \
    com.neuralsound.audio.NativeWaveformAnalysisResult\$Success \
    com.neuralsound.audio.RecorderFailure \
    com.neuralsound.audio.RecorderState \
    |
    normalize_api > "$TEMP_DIRECTORY/v0.2.0-public-api.txt"

# AudioEngine is a v0.1 class whose one exact v0.2 addition is checked above.
# The remaining v0.2 classes must match the complete approved bytecode surface.
sed \
    '/^public final class com\.neuralsound\.audio\.AudioEngine {$/,/^}$/d' \
    "$TEMP_DIRECTORY/v0.2.0-public-api.txt" \
    > "$TEMP_DIRECTORY/v0.2.0-additive-public-api.txt"
diff -u \
    "$SCRIPT_DIRECTORY/v0.2.0-core-public-api.txt" \
    "$TEMP_DIRECTORY/v0.2.0-additive-public-api.txt" ||
    fail "candidate v0.2 public API differs from the exact approved contract"

cat "$TEMP_DIRECTORY/v0.2.0-additive-public-api.txt"
