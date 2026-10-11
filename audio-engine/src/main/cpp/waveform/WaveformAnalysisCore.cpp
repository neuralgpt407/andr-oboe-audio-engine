#include "WaveformAnalysisCore.h"

#include "WaveformRmsAccumulator.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <thread>
#include <utility>

namespace neuralsound::audio::waveform {
namespace {

// Roughly 93 ms of canonical 44.1 kHz stereo PCM16. The fixed buffer avoids
// decode-loop allocation and supplies a frequent cancellation checkpoint.
constexpr int kDecodeChunkSamples = 4096 * 2;
constexpr int kMaximumIdleIterations = 5000;

} // namespace

WaveformCoreResult analyzeWaveformChunks(
    int sampleRate,
    int maxOutputSamples,
    const std::atomic<bool>& cancelled,
    const DecodePcm16Chunk& decodeChunk,
    int64_t expectedDurationMs
) {
    if (sampleRate <= 0 || maxOutputSamples <= 0 || !decodeChunk) {
        return {WaveformCoreStatus::DecodeFailed, {}};
    }

    const int64_t framesPerWindow = std::max<int64_t>(
        1,
        (static_cast<int64_t>(sampleRate) *
         WaveformRmsAccumulator::kDefaultWindowMs) /
            1000
    );
    const int64_t expectedFrames = expectedDurationMs > 0
        ? (
              expectedDurationMs * static_cast<int64_t>(sampleRate) +
              999
          ) / 1000
        : 0;
    const size_t expectedWindowCount = expectedFrames > 0
        ? static_cast<size_t>(
              (expectedFrames + framesPerWindow - 1) / framesPerWindow
          )
        : 0;
    WaveformRmsAccumulator accumulator(
        sampleRate,
        static_cast<size_t>(maxOutputSamples),
        expectedWindowCount
    );
    std::array<int16_t, kDecodeChunkSamples> decodeBuffer{};
    size_t decodedFrames = 0;
    int idleIterations = 0;

    while (true) {
        if (cancelled.load(std::memory_order_acquire)) {
            return {WaveformCoreStatus::Cancelled, {}};
        }

        const DecodePcm16ChunkResult decoded = decodeChunk(
            decodeBuffer.data(),
            static_cast<int>(decodeBuffer.size())
        );
        if (const auto* samples = std::get_if<DecodedPcm16Samples>(&decoded)) {
            if (samples->sampleCount == 0 ||
                samples->sampleCount > decodeBuffer.size() ||
                (samples->sampleCount % 2) != 0) {
                return {WaveformCoreStatus::DecodeFailed, {}};
            }
            const size_t frames = samples->sampleCount / 2;
            accumulator.addStereoFrames(decodeBuffer.data(), frames);
            decodedFrames += frames;
            idleIterations = 0;
            continue;
        }

        if (std::holds_alternative<DecodePcm16EndOfStream>(decoded)) {
            break;
        }
        if (std::holds_alternative<DecodePcm16Failure>(decoded)) {
            return {WaveformCoreStatus::DecodeFailed, {}};
        }

        ++idleIterations;
        if (idleIterations >= kMaximumIdleIterations) {
            return {WaveformCoreStatus::DecodeFailed, {}};
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    if (cancelled.load(std::memory_order_acquire)) {
        return {WaveformCoreStatus::Cancelled, {}};
    }
    if (decodedFrames == 0) {
        return {WaveformCoreStatus::EmptyMedia, {}};
    }

    std::vector<float> levels = accumulator.finish();
    if (levels.empty()) {
        return {WaveformCoreStatus::EmptyMedia, {}};
    }
    return {WaveformCoreStatus::Success, std::move(levels)};
}

} // namespace neuralsound::audio::waveform
