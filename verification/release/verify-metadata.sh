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

verify_pom_versions() {
    local pom=$1
    local dependency_count
    local versioned_dependency_count
    dependency_count=$(
        xml_value "$pom" \
            "count(/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'])"
    )
    versioned_dependency_count=$(
        xml_value "$pom" \
            "count(/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']/*[local-name()='version'])"
    )
    [ "$dependency_count" = "$versioned_dependency_count" ] ||
        fail "$pom contains a dependency without one explicit version"

    while IFS= read -r version; do
        [ -n "$version" ] || fail "$pom contains an empty version"
        if [[ "$version" =~ [\$\{\}\+\[\]\(\),] ]] ||
            [[ "$version" =~ [Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt] ]] ||
            [[ "$version" =~ [Ll][Aa][Tt][Ee][Ss][Tt] ]] ||
            [[ "$version" =~ [Rr][Ee][Ll][Ee][Aa][Ss][Ee] ]] ||
            [[ "$version" =~ [Uu][Nn][Ss][Pp][Ee][Cc][Ii][Ff][Ii][Ee][Dd] ]] ||
            [[ "$version" =~ [Pp][Rr][Oo][Jj][Ee][Cc][Tt] ]]; then
            fail "$pom contains mutable or project version: $version"
        fi
    done < <(
        grep -oE '<version>[^<]*</version>' "$pom" |
            sed -E 's#</?version>##g'
    )
}

verify_module_versions() {
    local module_file=$1
    jq -e '
        [.variants[].dependencies[]?] |
        all(.[];
            (.group | type == "string" and length > 0) and
            (.module | type == "string" and length > 0) and
            (.version | type == "object") and
            (.version | keys == ["requires"]) and
            (.version.requires | type == "string" and length > 0) and
            (
                .version.requires |
                test("(?i)SNAPSHOT|LATEST|RELEASE|unspecified|project|[+\\[\\](),${}]") |
                not
            )
        )
    ' "$module_file" > /dev/null ||
        fail "$module_file contains a mutable, project, or non-exact dependency"
}

verify_module_files() {
    local module_file=$1
    local artifact_directory=$2
    local expected_names=$3
    local declared_names
    declared_names=$(
        jq -r '.variants[].files[]?.name' "$module_file" |
            sort -u
    )
    [ "$declared_names" = "$expected_names" ] ||
        fail "$module_file declares an unexpected artifact set"

    while IFS=$'\t' read -r name expected_size expected_sha256; do
        local artifact="$artifact_directory/$name"
        local actual_size
        local actual_sha256
        require_file "$artifact"
        actual_size=$(wc -c < "$artifact" | tr -d ' ')
        actual_sha256=$(shasum -a 256 "$artifact" | awk '{ print $1 }')
        [ "$actual_size" = "$expected_size" ] ||
            fail "$artifact size does not match Gradle module metadata"
        [ "$actual_sha256" = "$expected_sha256" ] ||
            fail "$artifact SHA-256 does not match Gradle module metadata"
    done < <(
        jq -r \
            '.variants[].files[]? | [.name, (.size | tostring), .sha256] | @tsv' \
            "$module_file" |
            sort -u
    )
}

verify_pom_versions "$CORE_POM"
verify_pom_versions "$MEDIA3_POM"

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

core_internal_dependencies=$(
    xml_value "$CORE_POM" \
        "count(/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId']='$GROUP'])"
)
[ "$core_internal_dependencies" = "0" ] ||
    fail "core POM unexpectedly depends on another project publication"
media3_internal_dependencies=$(
    xml_value "$MEDIA3_POM" \
        "count(/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId']='$GROUP'])"
)
[ "$media3_internal_dependencies" = "1" ] ||
    fail "Media3 POM must contain exactly one internal core dependency"

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

verify_module_versions "$CORE_MODULE"
verify_module_versions "$MEDIA3_MODULE"
verify_module_files \
    "$CORE_MODULE" \
    "$CORE_DIRECTORY" \
    $'oboe-engine-v0.2.0-sources.jar\noboe-engine-v0.2.0.aar'
verify_module_files \
    "$MEDIA3_MODULE" \
    "$MEDIA3_DIRECTORY" \
    $'oboe-media3-v0.2.0-sources.jar\noboe-media3-v0.2.0.aar'

jq -e \
    --arg group "$GROUP" \
    '[.variants[].dependencies[]? | select(.group == $group)] | length == 0' \
    "$CORE_MODULE" > /dev/null ||
    fail "core Gradle metadata unexpectedly depends on another project publication"
jq -e \
    --arg group "$GROUP" \
    --arg version "$VERSION" \
    '[
        .variants[] |
        select(.attributes["org.gradle.category"] == "library") |
        [.dependencies[]? | select(.group == $group)] |
        (
            length == 1 and
            .[0].module == "oboe-engine" and
            (.[0].version | keys == ["requires"]) and
            .[0].version.requires == $version
        )
    ] | length > 0 and all' \
    "$MEDIA3_MODULE" > /dev/null ||
    fail "each Media3 library variant must expose exactly one exact core dependency"

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
