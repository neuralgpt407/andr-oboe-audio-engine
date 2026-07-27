#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
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

// Returns a positive interleaved-stereo sample count, zero while temporarily
// idle, -1 at end of stream, or a value below -1 for a decoder failure.
using DecodePcm16Chunk = std::function<int(int16_t* output, int maxSamples)>;

WaveformCoreResult analyzeWaveformChunks(
    int sampleRate,
    int maxOutputSamples,
    const std::atomic<bool>& cancelled,
    const DecodePcm16Chunk& decodeChunk,
    int64_t expectedDurationMs = 0
);

} // namespace neuralsound::audio::waveform
