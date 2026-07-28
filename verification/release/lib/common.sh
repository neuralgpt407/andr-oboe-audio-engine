#!/usr/bin/env bash

readonly GROUP="com.github.neuralgpt407.andr-oboe-audio-engine"
readonly GROUP_PATH="com/github/neuralgpt407/andr-oboe-audio-engine"
readonly NDK_VERSION="29.0.14206865"
readonly BASELINE_CORE_SHA256="22c3d516b3ac0cfca1c540244fb6f3d179014887dad9ae548640ee6d4422a1ab"
readonly BASELINE_MEDIA3_SHA256="21000548f55c275662e1eb85d7b9185dc3b2acc0daf5a05aeee4f465a379eb47"

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

verify_sha256() {
    local file=$1
    local expected=$2
    local actual
    actual=$(shasum -a 256 "$file" | awk '{ print $1 }')
    [ "$actual" = "$expected" ] ||
        fail "SHA-256 mismatch for $file: expected $expected, got $actual"
}

xml_value() {
    local file=$1
    local xpath=$2
    xmllint --xpath "string($xpath)" "$file"
}

initialize_verification_context() {
    if [ "$#" -ne 4 ]; then
        fail "usage: verify-local-publication.sh <local-maven-repository> <candidate-version> <v0.1.0-core-aar> <v0.1.0-media3-aar>"
    fi

    MAVEN_REPOSITORY=$1
    VERSION=$2
    BASELINE_CORE_AAR=$3
    BASELINE_MEDIA3_AAR=$4

    [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
        fail "candidate version must be an exact immutable-tag coordinate"
    [ "$VERSION" = "v0.2.0" ] ||
        fail "this qualification contract is pinned to v0.2.0"
    [ -d "$MAVEN_REPOSITORY" ] ||
        fail "local Maven repository does not exist: $MAVEN_REPOSITORY"
    require_file "$BASELINE_CORE_AAR"
    require_file "$BASELINE_MEDIA3_AAR"
    verify_sha256 "$BASELINE_CORE_AAR" "$BASELINE_CORE_SHA256"
    verify_sha256 "$BASELINE_MEDIA3_AAR" "$BASELINE_MEDIA3_SHA256"
    printf 'BASELINE_SHA256 core=%s media3=%s\n' \
        "$BASELINE_CORE_SHA256" \
        "$BASELINE_MEDIA3_SHA256"

    CORE_DIRECTORY="$MAVEN_REPOSITORY/$GROUP_PATH/oboe-engine/$VERSION"
    MEDIA3_DIRECTORY="$MAVEN_REPOSITORY/$GROUP_PATH/oboe-media3/$VERSION"
    CORE_AAR="$CORE_DIRECTORY/oboe-engine-$VERSION.aar"
    CORE_POM="$CORE_DIRECTORY/oboe-engine-$VERSION.pom"
    CORE_MODULE="$CORE_DIRECTORY/oboe-engine-$VERSION.module"
    CORE_SOURCES="$CORE_DIRECTORY/oboe-engine-$VERSION-sources.jar"
    CORE_SYMBOLS="$CORE_DIRECTORY/oboe-engine-$VERSION-native-symbols.zip"
    MEDIA3_AAR="$MEDIA3_DIRECTORY/oboe-media3-$VERSION.aar"
    MEDIA3_POM="$MEDIA3_DIRECTORY/oboe-media3-$VERSION.pom"
    MEDIA3_MODULE="$MEDIA3_DIRECTORY/oboe-media3-$VERSION.module"
    MEDIA3_SOURCES="$MEDIA3_DIRECTORY/oboe-media3-$VERSION-sources.jar"
}
