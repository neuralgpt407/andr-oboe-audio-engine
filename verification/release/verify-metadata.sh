#!/usr/bin/env bash
# Publication metadata for the single audio-engine artifact: exact coordinates, the public
# runtime dependencies (coroutines + Media3 ExoPlayer for the built-in video), and sources
# containing every public API package and no legacy modules.

set -euo pipefail

readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
source "$SCRIPT_DIRECTORY/lib/common.sh"
initialize_verification_context "$@"

pom_value() {
    xml_value "$CORE_POM" "/*[local-name()='project']/*[local-name()='$1']"
}

[ "$(pom_value groupId)" = "$GROUP" ] || fail "POM groupId is not $GROUP"
[ "$(pom_value artifactId)" = "$ARTIFACT" ] || fail "POM artifactId is not $ARTIFACT"
[ "$(pom_value version)" = "$VERSION" ] || fail "POM version is not $VERSION"

dependency_scope() {
    xml_value "$CORE_POM" "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId']='$1' and *[local-name()='artifactId']='$2']/*[local-name()='scope']"
}
[ "$(dependency_scope org.jetbrains.kotlinx kotlinx-coroutines-core)" = "compile" ] ||
    fail "kotlinx-coroutines-core must be an api (compile) dependency"
[ "$(dependency_scope androidx.media3 media3-exoplayer)" = "compile" ] ||
    fail "media3-exoplayer must be an api (compile) dependency for VideoState.player"
for legacy in oboe-engine oboe-media3; do
    if xmllint --xpath "//*[local-name()='artifactId'][text()='$legacy']" "$CORE_POM" > /dev/null 2>&1; then
        fail "POM still references the removed $legacy module"
    fi
done

jq -e --arg artifact "$ARTIFACT" --arg version "$VERSION" '
    .component.module == $artifact and
    .component.version == $version and
    ([.variants[].files[]?.name] | index("\($artifact)-\($version).aar")) != null and
    ([.variants[].files[]?.name] | index("\($artifact)-\($version)-sources.jar")) != null
' "$CORE_MODULE" > /dev/null || fail "Gradle module metadata does not describe $ARTIFACT $VERSION with sources"

for source in \
    "com/neuralsound/audio/AudioEngine.kt" \
    "com/neuralsound/audio/AudioError.kt" \
    "com/neuralsound/audio/AudioResult.kt" \
    "com/neuralsound/audio/player/Player.kt" \
    "com/neuralsound/audio/recorder/Recorder.kt" \
    "com/neuralsound/audio/waveform/WaveformAnalyzer.kt"; do
    require_archive_entry "$CORE_SOURCES" "$source"
done
if unzip -Z1 "$CORE_SOURCES" | grep -E '^com/neuralsound/audio/media3/' > /dev/null; then
    fail "sources still contain the removed oboe-media3 package"
fi

printf 'PASS: %s %s metadata, dependencies and sources\n' "$ARTIFACT" "$VERSION"
