#!/usr/bin/env bash

readonly GROUP="com.github.neuralgpt407.andr-oboe-audio-engine"
readonly GROUP_PATH="com/github/neuralgpt407/andr-oboe-audio-engine"
readonly ARTIFACT="audio-engine"
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

initialize_verification_context() {
    if [ "$#" -ne 2 ]; then
        fail "usage: verify-local-publication.sh <local-maven-repository> <candidate-version>"
    fi

    MAVEN_REPOSITORY=$1
    VERSION=$2

    [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
        fail "candidate version must be an exact immutable-tag coordinate"
    [ -d "$MAVEN_REPOSITORY" ] ||
        fail "local Maven repository does not exist: $MAVEN_REPOSITORY"

    CORE_DIRECTORY="$MAVEN_REPOSITORY/$GROUP_PATH/$ARTIFACT/$VERSION"
    CORE_AAR="$CORE_DIRECTORY/$ARTIFACT-$VERSION.aar"
    CORE_POM="$CORE_DIRECTORY/$ARTIFACT-$VERSION.pom"
    CORE_MODULE="$CORE_DIRECTORY/$ARTIFACT-$VERSION.module"
    CORE_SOURCES="$CORE_DIRECTORY/$ARTIFACT-$VERSION-sources.jar"
    CORE_SYMBOLS="$CORE_DIRECTORY/$ARTIFACT-$VERSION-native-symbols.zip"
}
