#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
source "$SCRIPT_DIRECTORY/lib/common.sh"

initialize_verification_context "$@"

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

bash "$SCRIPT_DIRECTORY/verify-metadata.sh" "$@"
bash "$SCRIPT_DIRECTORY/verify-jvm-api.sh" "$@"
bash "$SCRIPT_DIRECTORY/verify-native.sh" "$@"

shasum -a 256 \
    "$CORE_AAR" \
    "$CORE_POM" \
    "$CORE_SOURCES" \
    "$CORE_SYMBOLS" \
    "$MEDIA3_AAR" \
    "$MEDIA3_POM" \
    "$MEDIA3_SOURCES"
printf \
    'PASS: local publication %s has compatible Kotlin/JVM APIs, exact metadata, both ABIs, notices, sources, matching symbols, and a minimal runtime export surface\n' \
    "$VERSION"
