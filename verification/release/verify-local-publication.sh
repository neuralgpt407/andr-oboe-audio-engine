#!/usr/bin/env bash
# Qualifies a local publication of audio-engine before tagging. The public Kotlin API is
# gated separately by `./gradlew :audio-engine:apiCheck` against audio-engine/api/audio-engine.api.

set -euo pipefail

readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
source "$SCRIPT_DIRECTORY/lib/common.sh"

initialize_verification_context "$@"

for artifact in "$CORE_AAR" "$CORE_POM" "$CORE_MODULE" "$CORE_SOURCES" "$CORE_SYMBOLS"; do
    require_file "$artifact"
done

bash "$SCRIPT_DIRECTORY/verify-metadata.sh" "$@"
bash "$SCRIPT_DIRECTORY/verify-native.sh" "$@"

shasum -a 256 "$CORE_AAR" "$CORE_POM" "$CORE_SOURCES" "$CORE_SYMBOLS"
printf 'PASS: local publication %s %s has exact metadata, both ABIs, notices, sources, matching symbols and a minimal native export surface\n' \
    "$ARTIFACT" "$VERSION"
