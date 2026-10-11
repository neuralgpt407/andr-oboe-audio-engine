#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <variant>
#include <vector>

namespace neuralsound::audio::waveform {

enum class WaveformCoreStatus {
    Success,
    Cancelled,
    DecodeFailed,
    EmptyMedia,
};

struct WaveformCoreResult {
    WaveformCoreStatus status = WaveformCoreStatus::DecodeFailed;
    std::vector<float> levels;
};

struct DecodedPcm16Samples {
    size_t sampleCount;
};

struct DecodePcm16Idle {};
struct DecodePcm16EndOfStream {};
struct DecodePcm16Failure {};

using DecodePcm16ChunkResult = std::variant<
    DecodedPcm16Samples,
    DecodePcm16Idle,
    DecodePcm16EndOfStream,
    DecodePcm16Failure
>;
using DecodePcm16Chunk = std::function<
    DecodePcm16ChunkResult(int16_t* output, int maxSamples)
>;

WaveformCoreResult analyzeWaveformChunks(
    int sampleRate,
    int maxOutputSamples,
    const std::atomic<bool>& cancelled,
    const DecodePcm16Chunk& decodeChunk,
    int64_t expectedDurationMs = 0
);

} // namespace neuralsound::audio::waveform
