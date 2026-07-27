#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
source "$SCRIPT_DIRECTORY/lib/common.sh"
initialize_verification_context "$@"

for artifact in \
    "$CORE_POM" \
    "$CORE_MODULE" \
    "$CORE_SOURCES" \
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

media3_core_version=$(
    xml_value "$MEDIA3_POM" \
        "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId']='$GROUP' and *[local-name()='artifactId']='oboe-engine']/*[local-name()='version']"
)
[ "$media3_core_version" = "$VERSION" ] ||
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
