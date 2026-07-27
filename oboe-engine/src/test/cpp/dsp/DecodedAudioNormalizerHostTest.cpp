#include "DecodedAudioNormalizer.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <string>
#include <vector>

using neuralsound::audio::DecodedAudioNormalizer;

namespace {

int gFailures = 0;
constexpr double kPi = 3.14159265358979323846;

void check(bool condition, const char* message) {
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++gFailures;
    }
}

void testCanonicalStereoChannelMapping() {
    DecodedAudioNormalizer mono;
    check(mono.configure(44'100, 1), "mono: valid metadata accepted");
    const std::vector<int16_t> monoInput = {12'000, -6'000};
    std::vector<int16_t> output;
    check(mono.process(monoInput.data(), 2, output) == 2, "mono: frame count preserved");
    check(
        output == std::vector<int16_t>({12'000, 12'000, -6'000, -6'000}),
        "mono: each sample duplicated to left and right"
    );

    DecodedAudioNormalizer stereo;
    check(stereo.configure(44'100, 2), "stereo: valid metadata accepted");
    const std::vector<int16_t> stereoInput = {12'000, -3'000, -6'000, 9'000};
    check(stereo.process(stereoInput.data(), 2, output) == 2, "stereo: frame count preserved");
    check(output == stereoInput, "stereo: left and right preserved");

    DecodedAudioNormalizer multichannel;
    check(multichannel.configure(44'100, 4), "multichannel: valid metadata accepted");
    const std::vector<int16_t> multichannelInput = {
        12'000, 4'000, 0, 0,
        -12'000, -4'000, 0, 0,
    };
    check(
        multichannel.process(multichannelInput.data(), 2, output) == 2,
        "multichannel: frame count preserved"
    );
    check(
        output == std::vector<int16_t>({4'000, 4'000, -4'000, -4'000}),
        "multichannel: every channel averaged and duplicated to stereo"
    );
}

void testMixedRatesShareCanonicalDurationAndChannels() {
    constexpr int32_t kSourceFrames = 48'000;
    DecodedAudioNormalizer normalizer;
    check(normalizer.configure(48'000, 2), "48k: valid metadata accepted");

    std::vector<int16_t> input(static_cast<size_t>(kSourceFrames) * 2, 0);
    for (int32_t frame = 0; frame < kSourceFrames; ++frame) {
        input[static_cast<size_t>(frame) * 2] = static_cast<int16_t>(
            std::sin(2.0 * kPi * 1'000.0 * frame / 48'000.0) * 12'000.0
        );
    }

    std::vector<int16_t> output;
    const int32_t outputFrames =
        normalizer.process(input.data(), kSourceFrames, output);
    check(
        std::abs(outputFrames - DecodedAudioNormalizer::kOutputSampleRate) <= 64,
        "48k: one source second becomes one canonical second"
    );
    check(
        output.size() == static_cast<size_t>(outputFrames) * 2,
        "48k: normalized output remains stereo"
    );

    int32_t leftPeak = 0;
    int32_t rightPeak = 0;
    for (int32_t frame = 0; frame < outputFrames; ++frame) {
        leftPeak = std::max(
            leftPeak,
            std::abs(static_cast<int32_t>(output[static_cast<size_t>(frame) * 2]))
        );
        rightPeak = std::max(
            rightPeak,
            std::abs(static_cast<int32_t>(output[static_cast<size_t>(frame) * 2 + 1]))
        );
    }
    check(leftPeak > 6'000, "48k: left signal survives conversion");
    check(rightPeak < 100, "48k: silent right channel remains silent");
}

void testBothRatesAcrossTheChannelMatrix() {
    for (const int sampleRate : {44'100, 48'000}) {
        for (const int channelCount : {1, 2, 4}) {
            const std::string scenario =
                std::to_string(sampleRate) + " Hz / " +
                std::to_string(channelCount) + " channels";
            DecodedAudioNormalizer normalizer;
            check(
                normalizer.configure(sampleRate, channelCount),
                (scenario + ": configuration succeeds").c_str()
            );

            std::vector<int16_t> input(
                static_cast<size_t>(sampleRate) * static_cast<size_t>(channelCount),
                0
            );
            for (int frame = 0; frame < sampleRate; ++frame) {
                const size_t base =
                    static_cast<size_t>(frame) * static_cast<size_t>(channelCount);
                input[base] = 8'000;
                if (channelCount >= 2) input[base + 1] = -4'000;
                if (channelCount >= 4) {
                    input[base + 1] = 4'000;
                    input[base + 2] = 0;
                    input[base + 3] = 0;
                }
            }

            std::vector<int16_t> output;
            const int32_t outputFrames =
                normalizer.process(input.data(), sampleRate, output);
            check(
                std::abs(outputFrames - DecodedAudioNormalizer::kOutputSampleRate) <= 64,
                (scenario + ": one second remains one canonical second").c_str()
            );
            check(
                output.size() == static_cast<size_t>(outputFrames) * 2,
                (scenario + ": output is stereo").c_str()
            );

            const int expectedLeft = channelCount == 2 ? 8'000 :
                (channelCount == 1 ? 8'000 : 3'000);
            const int expectedRight = channelCount == 2 ? -4'000 : expectedLeft;
            const size_t middle = outputFrames > 0
                ? static_cast<size_t>(outputFrames / 2) * 2
                : 0;
            check(
                middle + 1 < output.size() &&
                    std::abs(static_cast<int>(output[middle]) - expectedLeft) <= 100,
                (scenario + ": left mapping is preserved").c_str()
            );
            check(
                middle + 1 < output.size() &&
                    std::abs(static_cast<int>(output[middle + 1]) - expectedRight) <= 100,
                (scenario + ": right mapping is preserved").c_str()
            );
        }
    }
}

void testResetAndReplacementDiscardConversionState() {
    DecodedAudioNormalizer normalizer;
    check(normalizer.configure(48'000, 2), "reset: stateful converter configured");

    std::vector<int16_t> input(2'048, 0);
    for (size_t frame = 0; frame < input.size() / 2; ++frame) {
        const int16_t sample = static_cast<int16_t>(
            std::sin(2.0 * kPi * 500.0 * frame / 48'000.0) * 10'000.0
        );
        input[frame * 2] = sample;
        input[frame * 2 + 1] = sample;
    }

    std::vector<int16_t> first;
    std::vector<int16_t> continued;
    normalizer.process(input.data(), static_cast<int32_t>(input.size() / 2), first);
    normalizer.process(input.data(), static_cast<int32_t>(input.size() / 2), continued);
    check(first != continued, "reset: streaming filter carries history");

    check(normalizer.reset(), "reset: filter state reset succeeds");
    normalizer.process(input.data(), static_cast<int32_t>(input.size() / 2), continued);
    check(first == continued, "reset: seek restarts conversion deterministically");

    check(normalizer.configure(44'100, 1), "replacement: new source format configured");
    const std::vector<int16_t> replacement = {
        std::numeric_limits<int16_t>::min(),
        std::numeric_limits<int16_t>::max(),
    };
    normalizer.process(replacement.data(), 2, continued);
    check(
        continued == std::vector<int16_t>({
            std::numeric_limits<int16_t>::min(),
            std::numeric_limits<int16_t>::min(),
            std::numeric_limits<int16_t>::max(),
            std::numeric_limits<int16_t>::max(),
        }),
        "replacement: previous rate and channel state is discarded"
    );

    normalizer.release();
    continued = {1, 2};
    check(
        normalizer.process(replacement.data(), 2, continued) == 0 && continued.empty(),
        "release: conversion state and pending output are discarded"
    );
    check(
        normalizer.configure(44'100, 1),
        "release: normalizer can be configured for a new lifecycle"
    );
}

void testInvalidMetadataFailsBeforeOutput() {
    DecodedAudioNormalizer normalizer;
    std::vector<int16_t> output = {1, 2};
    const int16_t input = 1;

    check(!normalizer.configure(0, 2), "invalid metadata: zero rate rejected");
    check(
        normalizer.process(&input, 1, output) == 0 && output.empty(),
        "invalid metadata: no partial output after zero rate"
    );
    check(!normalizer.configure(44'100, 0), "invalid metadata: zero channels rejected");
    check(
        normalizer.process(&input, 1, output) == 0 && output.empty(),
        "invalid metadata: no partial output after zero channels"
    );
}

void testUnsafeConversionExpansionFailsWithoutPartialOutput() {
    DecodedAudioNormalizer normalizer;
    check(
        normalizer.configure(1, 2),
        "conversion failure: positive metadata reaches the normalizer"
    );
    const std::vector<int16_t> input(200, 1'000);
    std::vector<int16_t> output = {1, 2};
    check(
        normalizer.process(input.data(), 100, output) == -1,
        "conversion failure: unsafe expansion reports failure"
    );
    check(
        output.empty(),
        "conversion failure: unsafe expansion emits no partial output"
    );
}

void testStreamingOutputDoesNotDependOnDecoderChunkBoundaries() {
    constexpr int32_t kInputFrames = 48'000;
    constexpr int32_t kChunkFrames = 1'024;
    std::vector<int16_t> input(static_cast<size_t>(kInputFrames) * 2, 1'000);

    DecodedAudioNormalizer wholeNormalizer;
    check(wholeNormalizer.configure(48'000, 2), "stream: whole converter configured");
    std::vector<int16_t> wholeOutput;
    wholeNormalizer.process(input.data(), kInputFrames, wholeOutput);

    DecodedAudioNormalizer chunkedNormalizer;
    check(chunkedNormalizer.configure(48'000, 2), "stream: chunked converter configured");
    std::vector<int16_t> chunkOutput;
    std::vector<int16_t> chunkedOutput;
    for (int32_t offset = 0; offset < kInputFrames; offset += kChunkFrames) {
        const int32_t frames = std::min(kChunkFrames, kInputFrames - offset);
        chunkedNormalizer.process(
            input.data() + static_cast<size_t>(offset) * 2,
            frames,
            chunkOutput
        );
        chunkedOutput.insert(chunkedOutput.end(), chunkOutput.begin(), chunkOutput.end());
    }

    check(
        chunkedOutput == wholeOutput,
        "stream: canonical output is invariant to codec chunk boundaries"
    );
}

} // namespace

int main() {
    testCanonicalStereoChannelMapping();
    testMixedRatesShareCanonicalDurationAndChannels();
    testBothRatesAcrossTheChannelMatrix();
    testResetAndReplacementDiscardConversionState();
    testInvalidMetadataFailsBeforeOutput();
    testUnsafeConversionExpansionFailsWithoutPartialOutput();
    testStreamingOutputDoesNotDependOnDecoderChunkBoundaries();

    if (gFailures == 0) {
        std::printf("PASS: DecodedAudioNormalizer host contract\n");
        return 0;
    }
    std::printf("FAILED: %d DecodedAudioNormalizer checks failed\n", gFailures);
    return 1;
}
