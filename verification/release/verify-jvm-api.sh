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
    local safe_name=${class_name//[^A-Za-z0-9]/_}
    local baseline_dump="$work_directory/$safe_name.baseline"
    local candidate_dump="$work_directory/$safe_name.candidate"

    javap -classpath "$baseline_jar" -public -s "$class_name" |
        normalize_api > "$baseline_dump"
    javap -classpath "$candidate_jar" -public -s "$class_name" |
        normalize_api > "$candidate_dump"

    while IFS= read -r baseline_line; do
        grep -Fqx "$baseline_line" "$candidate_dump" ||
            fail "binary API removed from $class_name: $baseline_line"
    done < "$baseline_dump"
}

verify_supported_classes() {
    local class_list=$1
    local baseline_jar=$2
    local candidate_jar=$3
    local work_directory=$4

    while IFS= read -r class_name; do
        [ -n "$class_name" ] || continue
        verify_class_compatibility \
            "$baseline_jar" \
            "$candidate_jar" \
            "$class_name" \
            "$work_directory"
    done < "$class_list"
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
    "$TEMP_DIRECTORY"
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
    com.neuralsound.audio.AudioEngine \
    com.neuralsound.audio.FramePreciseRecorderSession \
    com.neuralsound.audio.FrameRecordingRequest \
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
    "FrameRecordingRequest(java.io.File, long)" \
    "public com.neuralsound.audio.NativeAudioWaveformAnalyzer()" \
    "ALGORITHM_VERSION" \
    "MAX_OUTPUT_SAMPLES" \
    "analyze(android.os.ParcelFileDescriptor" \
    "getCurrentFailure()" \
    "getLevels()" \
    "getError()"; do
    grep -Fq "$expected_api" "$TEMP_DIRECTORY/v0.2.0-public-api.txt" ||
        fail "candidate public API is missing $expected_api"
done

if grep -Eq \
    'NativeWaveformBridge|CoroutineDispatcher|Function0|analyzeFileDescriptor|access\$native' \
    "$TEMP_DIRECTORY/v0.2.0-public-api.txt"; then
    fail "public waveform API exposes an internal test or JNI bridge"
fi

for forbidden_entry in \
    "com/neuralsound/audio/NativeWaveformFailure.class" \
    "com/neuralsound/audio/NativeAudioWaveformAnalyzer\$NativeWaveformBridge.class" \
    "com/neuralsound/audio/NativeAudioWaveformAnalyzer\$JniNativeWaveformBridge.class"; do
    if unzip -Z1 "$TEMP_DIRECTORY/core-candidate.jar" |
        grep -Fx "$forbidden_entry" > /dev/null; then
        fail "candidate exposes unsupported root API class $forbidden_entry"
    fi
done

cat "$TEMP_DIRECTORY/v0.2.0-public-api.txt"
