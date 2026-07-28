#include "SharedHandleRegistry.h"
#include "WaveformAnalysisCore.h"
#include "WaveformRmsAccumulator.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <thread>
#include <vector>

using neuralsound::audio::waveform::SharedHandleRegistry;
using neuralsound::audio::waveform::DecodePcm16ChunkResult;
using neuralsound::audio::waveform::DecodePcm16EndOfStream;
using neuralsound::audio::waveform::DecodePcm16Failure;
using neuralsound::audio::waveform::DecodedPcm16Samples;
using neuralsound::audio::waveform::WaveformCoreStatus;
using neuralsound::audio::waveform::WaveformRmsAccumulator;
using neuralsound::audio::waveform::analyzeWaveformChunks;

namespace {

int gFailures = 0;

void check(bool condition, const char* message) {
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++gFailures;
    }
}

bool nearlyEqual(float actual, float expected, float tolerance = 0.00001f) {
    return std::fabs(actual - expected) <= tolerance;
}

void testMagnitudeUsesTheLouderStereoChannel() {
    check(
        nearlyEqual(
            WaveformRmsAccumulator::stereoFrameMagnitude(
                std::numeric_limits<int16_t>::min(),
                0
            ),
            1.0f
        ),
        "magnitude: signed PCM16 minimum maps exactly to one"
    );
    check(
        nearlyEqual(
            WaveformRmsAccumulator::stereoFrameMagnitude(
                std::numeric_limits<int16_t>::max(),
                0
            ),
            32767.0f / 32768.0f
        ),
        "magnitude: positive PCM16 limit keeps the absolute scale"
    );
    check(
        nearlyEqual(
            WaveformRmsAccumulator::stereoFrameMagnitude(-1000, 12000),
            12000.0f / 32768.0f
        ),
        "magnitude: louder right channel wins"
    );
    check(
        nearlyEqual(
            WaveformRmsAccumulator::stereoFrameMagnitude(-16000, 2000),
            16000.0f / 32768.0f
        ),
        "magnitude: absolute left channel wins"
    );
}

void testRmsWindowsIncludeSilenceAndTheFinalPartialWindow() {
    WaveformRmsAccumulator accumulator(1000);
    check(
        accumulator.framesPerWindow() == 32,
        "window: 32 ms at 1 kHz is 32 frames"
    );

    std::vector<int16_t> mixedEnergy(32 * 2, 0);
    for (size_t frame = 0; frame < 16; ++frame) {
        mixedEnergy[frame * 2 + 1] = 16'384;
    }
    accumulator.addStereoFrames(mixedEnergy.data(), 32);

    std::vector<int16_t> silence(32 * 2, 0);
    accumulator.addStereoFrames(silence.data(), 32);

    const int16_t clippedPartial[] = {
        std::numeric_limits<int16_t>::min(),
        0,
    };
    accumulator.addStereoFrames(clippedPartial, 1);

    const std::vector<float> levels = accumulator.finish();
    check(levels.size() == 3, "window: final partial window is retained");
    check(
        nearlyEqual(levels[0], std::sqrt(0.125f)),
        "rms: a window uses mean-square energy rather than its peak"
    );
    check(nearlyEqual(levels[1], 0.0f), "silence: complete window stays zero");
    check(nearlyEqual(levels[2], 1.0f), "partial: clipped sample stays bounded");
}

void testStreamingReductionUsesRms() {
    WaveformRmsAccumulator accumulator(
        1000,
        4,
        8
    );
    std::vector<int16_t> window(32 * 2, 0);
    for (int index = 0; index < 8; ++index) {
        std::fill(
            window.begin(),
            window.end(),
            index % 2 == 0 ? 0 : std::numeric_limits<int16_t>::min()
        );
        accumulator.addStereoFrames(window.data(), 32);
    }

    const std::vector<float> reduced = accumulator.finish();
    check(reduced.size() == 4, "reduction: output respects the requested bound");
    check(
        std::all_of(reduced.begin(), reduced.end(), [](float value) {
            return nearlyEqual(value, std::sqrt(0.5f));
        }),
        "reduction: production buckets combine adjacent window levels by RMS"
    );
}

void testSingleSampleBoundHandlesUnknownOrUnderreportedDuration() {
    const auto analyze = [](int64_t expectedDurationMs) {
        std::atomic<bool> cancelled{false};
        int calls = 0;
        return analyzeWaveformChunks(
            1000,
            1,
            cancelled,
            [&calls](int16_t* output, int) {
                if (calls++ > 0) {
                    return DecodePcm16ChunkResult{
                        DecodePcm16EndOfStream{}
                    };
                }
                constexpr size_t kWindowFrames = 32;
                constexpr size_t kWindowCount = 3;
                for (size_t window = 0; window < kWindowCount; ++window) {
                    const int16_t amplitude = window == 1
                        ? std::numeric_limits<int16_t>::min()
                        : 0;
                    for (size_t frame = 0; frame < kWindowFrames; ++frame) {
                        const size_t sample = (window * kWindowFrames + frame) * 2;
                        output[sample] = amplitude;
                        output[sample + 1] = amplitude;
                    }
                }
                return DecodePcm16ChunkResult{
                    DecodedPcm16Samples{kWindowFrames * kWindowCount * 2}
                };
            },
            expectedDurationMs
        );
    };

    for (const int64_t expectedDurationMs : {int64_t{0}, int64_t{32}}) {
        const auto result = analyze(expectedDurationMs);
        check(
            result.status == WaveformCoreStatus::Success,
            "single bound: analysis succeeds"
        );
        check(
            result.levels.size() == 1,
            "single bound: unknown or low duration stays within one sample"
        );
        if (result.levels.size() == 1) {
            check(
                nearlyEqual(result.levels.front(), std::sqrt(1.0f / 3.0f)),
                "single bound: all decoded windows contribute by RMS"
            );
        }
    }
}

void testLongInputRetainsAtMost1024StreamingBuckets() {
    constexpr size_t kWindowCount = 100'000;
    constexpr size_t kMaximumSamples = 1024;
    WaveformRmsAccumulator accumulator(1000, kMaximumSamples, kWindowCount);
    std::vector<int16_t> window(32 * 2, 16'384);

    for (size_t index = 0; index < kWindowCount; ++index) {
        if (index + 1 == kWindowCount) {
            std::fill(
                window.begin(),
                window.end(),
                std::numeric_limits<int16_t>::min()
            );
        }
        accumulator.addStereoFrames(window.data(), 32);
        check(
            accumulator.retainedLevelCount() <= kMaximumSamples,
            "streaming: retained storage never exceeds 1024 levels"
        );
    }

    const std::vector<float> levels = accumulator.finish();
    check(levels.size() == kMaximumSamples, "streaming: output is bounded to 1024");
    check(nearlyEqual(levels[512], 0.5f), "streaming: constant absolute energy is retained");

    const size_t finalBucketStart = (
        (kMaximumSamples - 1) * kWindowCount +
        kMaximumSamples - 1
    ) / kMaximumSamples;
    const size_t finalBucketCount = kWindowCount - finalBucketStart;
    const float expectedFinalRms = std::sqrt(
        (
            static_cast<float>(finalBucketCount - 1) * 0.25f +
            1.0f
        ) / static_cast<float>(finalBucketCount)
    );
    check(
        nearlyEqual(levels.back(), expectedFinalRms),
        "streaming: the last clipped window participates in RMS reduction"
    );
    check(
        accumulator.completedWindowCount() == kWindowCount,
        "streaming: every source window is accounted for"
    );
}

void testIndependentTracksKeepAbsoluteAmplitude() {
    const auto analyzeConstant = [](int16_t amplitude) {
        std::atomic<bool> cancelled{false};
        int calls = 0;
        return analyzeWaveformChunks(
            1000,
            1024,
            cancelled,
            [amplitude, &calls](int16_t* output, int) {
                if (calls++ > 0) {
                    return DecodePcm16ChunkResult{
                        DecodePcm16EndOfStream{}
                    };
                }
                for (int frame = 0; frame < 32; ++frame) {
                    output[frame * 2] = amplitude;
                    output[frame * 2 + 1] = amplitude;
                }
                return DecodePcm16ChunkResult{
                    DecodedPcm16Samples{64}
                };
            }
        );
    };

    const auto quiet = analyzeConstant(4096);
    const auto loud = analyzeConstant(16'384);

    check(quiet.status == WaveformCoreStatus::Success, "amplitude: quiet analysis succeeds");
    check(loud.status == WaveformCoreStatus::Success, "amplitude: loud analysis succeeds");
    check(nearlyEqual(quiet.levels.front(), 0.125f), "amplitude: quiet track stays at 0.125");
    check(nearlyEqual(loud.levels.front(), 0.5f), "amplitude: loud track stays at 0.5");
    check(
        nearlyEqual(loud.levels.front() / quiet.levels.front(), 4.0f),
        "amplitude: tracks are not normalized against their own maxima"
    );
}

void testCancellationStopsBeforeAnotherDecodeChunk() {
    std::atomic<bool> cancelled{false};
    int calls = 0;
    const auto result = analyzeWaveformChunks(
        44'100,
        1024,
        cancelled,
        [&cancelled, &calls](int16_t* output, int) {
            ++calls;
            output[0] = 1000;
            output[1] = 2000;
            cancelled.store(true, std::memory_order_release);
            return DecodePcm16ChunkResult{
                DecodedPcm16Samples{2}
            };
        }
    );

    check(calls == 1, "cancellation: no second decode chunk is requested");
    check(result.status == WaveformCoreStatus::Cancelled, "cancellation: result is typed");
    check(result.levels.empty(), "cancellation: partial levels are not published");
}

void testEmptyAndInvalidDecoderResultsAreTyped() {
    std::atomic<bool> cancelled{false};
    const auto empty = analyzeWaveformChunks(
        44'100,
        1024,
        cancelled,
        [](int16_t*, int) {
            return DecodePcm16ChunkResult{
                DecodePcm16EndOfStream{}
            };
        }
    );
    check(empty.status == WaveformCoreStatus::EmptyMedia, "empty: result is typed");

    const auto failed = analyzeWaveformChunks(
        44'100,
        1024,
        cancelled,
        [](int16_t*, int) {
            return DecodePcm16ChunkResult{
                DecodePcm16Failure{}
            };
        }
    );
    check(failed.status == WaveformCoreStatus::DecodeFailed, "decoder failure: result is typed");

    const auto oddStereo = analyzeWaveformChunks(
        44'100,
        1024,
        cancelled,
        [](int16_t* output, int) {
            output[0] = 1;
            return DecodePcm16ChunkResult{
                DecodedPcm16Samples{1}
            };
        }
    );
    check(
        oddStereo.status == WaveformCoreStatus::DecodeFailed,
        "decoder failure: incomplete stereo frame is rejected"
    );
}

void testRepeatedHandleReleaseCleansUpOnceAfterInFlightWork() {
    struct LifetimeProbe {
        explicit LifetimeProbe(std::atomic<int>& destroyed) : destroyed_(destroyed) {}
        ~LifetimeProbe() {
            destroyed_.fetch_add(1, std::memory_order_release);
        }
        std::atomic<int>& destroyed_;
    };

    SharedHandleRegistry<LifetimeProbe> registry;
    std::atomic<int> destroyed{0};
    const uint64_t handle = registry.insert(
        std::make_shared<LifetimeProbe>(destroyed)
    );
    std::shared_ptr<LifetimeProbe> borrowed = registry.acquire(handle);
    check(handle != 0 && borrowed != nullptr, "registry: live handle can be borrowed");

    std::thread releaseThread([&registry, handle]() {
        registry.remove(handle);
        registry.remove(handle);
    });
    releaseThread.join();

    check(
        registry.acquire(handle) == nullptr,
        "registry: release prevents new work from borrowing the handle"
    );
    check(
        destroyed.load(std::memory_order_acquire) == 0,
        "registry: release preserves an in-flight borrow"
    );
    borrowed.reset();
    check(
        destroyed.load(std::memory_order_acquire) == 1,
        "registry: repeated release destroys the resource exactly once"
    );
}

} // namespace

int main() {
    testMagnitudeUsesTheLouderStereoChannel();
    testRmsWindowsIncludeSilenceAndTheFinalPartialWindow();
    testStreamingReductionUsesRms();
    testSingleSampleBoundHandlesUnknownOrUnderreportedDuration();
    testLongInputRetainsAtMost1024StreamingBuckets();
    testIndependentTracksKeepAbsoluteAmplitude();
    testCancellationStopsBeforeAnotherDecodeChunk();
    testEmptyAndInvalidDecoderResultsAreTyped();
    testRepeatedHandleReleaseCleansUpOnceAfterInFlightWork();

    if (gFailures == 0) {
        std::printf("PASS: WaveformAnalysis\n");
        return 0;
    }
    std::printf("FAIL: WaveformAnalysis (%d failures)\n", gFailures);
    return 1;
}
