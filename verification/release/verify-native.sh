#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
source "$SCRIPT_DIRECTORY/lib/common.sh"
initialize_verification_context "$@"

readonly EXPECTED_ABIS=$'arm64-v8a\narmeabi-v7a'
readonly EXPECTED_RUNTIME_LIBRARIES=$'jni/arm64-v8a/libc++_shared.so\njni/arm64-v8a/libneuralsound_audio_engine.so\njni/arm64-v8a/liboboe.so\njni/armeabi-v7a/libc++_shared.so\njni/armeabi-v7a/libneuralsound_audio_engine.so\njni/armeabi-v7a/liboboe.so'
readonly EXPECTED_SYMBOL_ENTRIES=$'META-INF/LICENSE-APACHE-2.0.txt\nMETA-INF/LICENSE-LLVM-EXCEPTIONS.txt\nMETA-INF/LICENSE-SIGNALSMITH-LINEAR.txt\nMETA-INF/LICENSE-SIGNALSMITH-STRETCH.txt\nMETA-INF/THIRD_PARTY_NOTICES.md\narm64-v8a/libc++_shared.so\narm64-v8a/libneuralsound_audio_engine.so\narm64-v8a/liboboe.so\narmeabi-v7a/libc++_shared.so\narmeabi-v7a/libneuralsound_audio_engine.so\narmeabi-v7a/liboboe.so'
aar_abis=$(
    unzip -Z1 "$CORE_AAR" |
        awk -F/ '$1 == "jni" && NF >= 3 { print $2 }' |
        sort -u
)
[ "$aar_abis" = "$EXPECTED_ABIS" ] ||
    fail "core AAR ABI set is not exactly arm64-v8a and armeabi-v7a"
aar_runtime_libraries=$(
    unzip -Z1 "$CORE_AAR" |
        grep -E '^jni/[^/]+/[^/]+$' |
        sort -u
)
[ "$aar_runtime_libraries" = "$EXPECTED_RUNTIME_LIBRARIES" ] ||
    fail "core AAR runtime library set is not exact"
symbol_entries=$(
    unzip -Z1 "$CORE_SYMBOLS" |
        grep -v '/$' |
        sort -u
)
[ "$symbol_entries" = "$EXPECTED_SYMBOL_ENTRIES" ] ||
    fail "native-symbols classifier contains missing or unsupported entries"

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
readonly LLVM_READELF="$NDK_BIN/llvm-readelf"
require_file "$LLVM_NM"
require_file "$LLVM_OBJDUMP"
require_file "$LLVM_READELF"

readonly TEMP_DIRECTORY=$(mktemp -d)
trap 'rm -R "${TEMP_DIRECTORY:?}"' EXIT

for abi in arm64-v8a armeabi-v7a; do
    for library in libc++_shared.so libneuralsound_audio_engine.so liboboe.so; do
        runtime_library="$TEMP_DIRECTORY/$abi-runtime-$library"
        symbol_library="$TEMP_DIRECTORY/$abi-symbol-$library"
        unzip -p "$CORE_AAR" "jni/$abi/$library" > "$runtime_library"
        unzip -p "$CORE_SYMBOLS" "$abi/$library" > "$symbol_library"
        require_file "$runtime_library"
        require_file "$symbol_library"

        runtime_build_id=$(
            "$LLVM_READELF" -n "$runtime_library" |
                awk '/Build ID:/ { print $3; exit }'
        )
        symbol_build_id=$(
            "$LLVM_READELF" -n "$symbol_library" |
                awk '/Build ID:/ { print $3; exit }'
        )
        [ -n "$runtime_build_id" ] ||
            fail "$abi $library runtime does not contain a Build ID"
        [ "$runtime_build_id" = "$symbol_build_id" ] ||
            fail "$abi $library runtime and native symbols have different Build IDs"

        file "$symbol_library" | grep -F "not stripped" > /dev/null ||
            fail "$abi $library native symbols are stripped"
        "$LLVM_OBJDUMP" --section-headers "$symbol_library" |
            grep -F ".debug_info" > /dev/null ||
            fail "$abi $library native symbols do not contain DWARF debug information"
        "$LLVM_OBJDUMP" --section-headers "$symbol_library" |
            grep -F ".symtab" > /dev/null ||
            fail "$abi $library native symbols do not contain a symbol table"
        printf \
            'BUILD_ID %s %s runtime=%s symbols=%s\n' \
            "$abi" \
            "$library" \
            "$runtime_build_id" \
            "$symbol_build_id"
        printf \
            'DEBUG_SYMBOLS %s %s not_stripped=yes dwarf=yes symtab=yes\n' \
            "$abi" \
            "$library"
    done

    runtime_engine="$TEMP_DIRECTORY/$abi-runtime-libneuralsound_audio_engine.so"
    runtime_exports="$TEMP_DIRECTORY/$abi-runtime-exports.txt"
    "$LLVM_NM" \
        --dynamic \
        --defined-only \
        --extern-only \
        "$runtime_engine" |
        awk '{ print $NF }' |
        sort -u > "$runtime_exports"
    [ "$(cat "$runtime_exports")" = "JNI_OnLoad" ] ||
        fail "$abi runtime exports unsupported native symbols"
    printf 'RUNTIME_EXPORTS %s %s\n' "$abi" "$(cat "$runtime_exports")"

    symbol_engine="$TEMP_DIRECTORY/$abi-symbol-libneuralsound_audio_engine.so"
    "$LLVM_NM" --defined-only "$symbol_engine" |
        grep -F "JNI_OnLoad" > /dev/null ||
        fail "$abi native symbols do not contain JNI_OnLoad"

    for symbol in \
        "DecodedAudioNormalizer" \
        "NativeAudioWaveformAnalyzer" \
        "RecorderFailureState"; do
        "$LLVM_NM" --defined-only "$symbol_engine" |
            grep -F "$symbol" > /dev/null ||
            fail "$abi native symbols do not expose usable $symbol diagnostics"
    done
    printf 'ENGINE_DIAGNOSTICS %s usable=yes\n' "$abi"
done
