// Host contract test for SampleRateConverter. Compiled and run on the host by
// SampleRateConverterHostTest.kt (c++ -std=c++17, plus the vendored resampler
// sources and -DRESAMPLER_OUTER_NAMESPACE=neuralsound_dsp).

#include "SampleRateConverter.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

using neuralsound::audio::ResampleQuality;
using neuralsound::audio::SampleRateConverter;

namespace {

constexpr double kPi = 3.14159265358979323846;
int g_failures = 0;

void check(bool condition, const char* message) {
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++g_failures;
    }
}

bool finiteAndBounded(const std::vector<float>& samples, int32_t count) {
    for (int32_t i = 0; i < count; ++i) {
        if (!std::isfinite(samples[static_cast<size_t>(i)])) {
            return false;
        }
        if (std::fabs(samples[static_cast<size_t>(i)]) > 1.5f) {
            return false;
        }
    }
    return true;
}

void testPassthrough() {
    auto converter = SampleRateConverter::make(1, 44'100, 44'100);
    check(converter != nullptr, "passthrough: converter created");
    if (converter == nullptr) {
        return;
    }
    check(converter->isPassthrough(), "passthrough: isPassthrough true");
    check(converter->maxOutputFramesFor(100) == 100, "passthrough: max output == input");

    const std::vector<float> input = {0.1f, 0.2f, -0.3f, 0.4f, -0.5f};
    std::vector<float> output(input.size(), 0.0f);
    const int32_t produced = converter->process(
        input.data(), static_cast<int32_t>(input.size()),
        output.data(), static_cast<int32_t>(output.size()));
    check(produced == static_cast<int32_t>(input.size()), "passthrough: produced count");
    bool identical = true;
    for (size_t i = 0; i < input.size(); ++i) {
        if (output[i] != input[i]) {
            identical = false;
        }
    }
    check(identical, "passthrough: samples copied unchanged");
}

void testRatio(int inputRate, int outputRate, const char* label) {
    auto converter = SampleRateConverter::make(1, inputRate, outputRate);
    check(converter != nullptr, label);
    if (converter == nullptr) {
        return;
    }
    check(!converter->isPassthrough(), "ratio: not passthrough");

    const int32_t inputFrames = inputRate; // one second of audio
    std::vector<float> input(static_cast<size_t>(inputFrames));
    for (int32_t k = 0; k < inputFrames; ++k) {
        input[static_cast<size_t>(k)] =
            0.5f * static_cast<float>(std::sin(2.0 * kPi * 1000.0 * k / inputRate));
    }

    const int32_t capacity = converter->maxOutputFramesFor(inputFrames);
    std::vector<float> output(static_cast<size_t>(capacity), 0.0f);
    const int32_t produced =
        converter->process(input.data(), inputFrames, output.data(), capacity);

    const int32_t expected =
        static_cast<int32_t>(static_cast<int64_t>(inputFrames) * outputRate / inputRate);
    check(std::abs(produced - expected) <= 64, "ratio: produced ~= inputFrames * out/in");
    check(produced <= capacity, "ratio: produced within capacity");
    check(finiteAndBounded(output, produced), "ratio: output finite and bounded");
}

void testFrequencyPreserved() {
    const int inputRate = 48'000;
    const int outputRate = 44'100;
    const double frequency = 1000.0;

    auto converter = SampleRateConverter::make(1, inputRate, outputRate);
    check(converter != nullptr, "frequency: converter created");
    if (converter == nullptr) {
        return;
    }

    const int32_t inputFrames = inputRate;
    std::vector<float> input(static_cast<size_t>(inputFrames));
    for (int32_t k = 0; k < inputFrames; ++k) {
        input[static_cast<size_t>(k)] =
            0.5f * static_cast<float>(std::sin(2.0 * kPi * frequency * k / inputRate));
    }

    const int32_t capacity = converter->maxOutputFramesFor(inputFrames);
    std::vector<float> output(static_cast<size_t>(capacity), 0.0f);
    const int32_t produced =
        converter->process(input.data(), inputFrames, output.data(), capacity);

    // Count zero crossings in the steady-state middle region and recover the
    // frequency; skipping the edges avoids filter warm-up transients.
    const int32_t start = 200;
    const int32_t end = produced - 200;
    check(end > start, "frequency: enough output for analysis");
    if (end <= start) {
        return;
    }
    int crossings = 0;
    float prev = output[static_cast<size_t>(start)];
    for (int32_t k = start + 1; k < end; ++k) {
        const float cur = output[static_cast<size_t>(k)];
        if ((prev < 0.0f && cur >= 0.0f) || (prev > 0.0f && cur <= 0.0f)) {
            ++crossings;
        }
        prev = cur;
    }
    const double regionSeconds = static_cast<double>(end - start) / outputRate;
    const double observedFrequency = crossings / (2.0 * regionSeconds);
    check(std::fabs(observedFrequency - frequency) < 30.0,
          "frequency: dominant frequency preserved after resampling");
}

void testStereoChannelsIndependent() {
    const int inputRate = 48'000;
    const int outputRate = 44'100;
    auto converter = SampleRateConverter::make(2, inputRate, outputRate);
    check(converter != nullptr, "stereo: converter created");
    if (converter == nullptr) {
        return;
    }
    check(converter->channelCount() == 2, "stereo: channel count");

    const int32_t inputFrames = inputRate;
    std::vector<float> input(static_cast<size_t>(inputFrames) * 2, 0.0f);
    for (int32_t k = 0; k < inputFrames; ++k) {
        // Left carries a tone, right is silent.
        input[static_cast<size_t>(k) * 2] =
            0.5f * static_cast<float>(std::sin(2.0 * kPi * 1000.0 * k / inputRate));
        input[static_cast<size_t>(k) * 2 + 1] = 0.0f;
    }

    const int32_t capacity = converter->maxOutputFramesFor(inputFrames);
    std::vector<float> output(static_cast<size_t>(capacity) * 2, 0.0f);
    const int32_t produced =
        converter->process(input.data(), inputFrames, output.data(), capacity);
    check(produced > 0, "stereo: produced frames");

    float leftMax = 0.0f;
    float rightMax = 0.0f;
    for (int32_t k = 0; k < produced; ++k) {
        leftMax = std::fmax(leftMax, std::fabs(output[static_cast<size_t>(k) * 2]));
        rightMax = std::fmax(rightMax, std::fabs(output[static_cast<size_t>(k) * 2 + 1]));
    }
    check(leftMax > 0.2f, "stereo: left channel carries signal");
    check(rightMax < 0.05f, "stereo: right channel stays silent");
}

void testEdgeCases() {
    check(SampleRateConverter::make(0, 48'000, 44'100) == nullptr,
          "edge: zero channels rejected");
    check(SampleRateConverter::make(1, 0, 44'100) == nullptr,
          "edge: zero input rate rejected");
    check(SampleRateConverter::make(1, 48'000, 0) == nullptr,
          "edge: zero output rate rejected");

    auto converter = SampleRateConverter::make(1, 48'000, 44'100);
    check(converter != nullptr, "edge: valid converter created");
    if (converter == nullptr) {
        return;
    }
    std::vector<float> buffer(16, 0.0f);
    check(converter->process(buffer.data(), 0, buffer.data(), 16) == 0,
          "edge: zero input frames produces nothing");
    check(converter->process(nullptr, 8, buffer.data(), 16) == 0,
          "edge: null input produces nothing");
    check(converter->process(buffer.data(), 8, nullptr, 16) == 0,
          "edge: null output produces nothing");
}

} // namespace

int main() {
    testPassthrough();
    testRatio(48'000, 44'100, "ratio: 48000 -> 44100 converter created");
    testRatio(16'000, 44'100, "ratio: 16000 -> 44100 converter created");
    testFrequencyPreserved();
    testStereoChannelsIndependent();
    testEdgeCases();

    if (g_failures == 0) {
        std::printf("PASS: SampleRateConverter host contract\n");
        return 0;
    }
    std::printf("FAILED: %d SampleRateConverter checks failed\n", g_failures);
    return 1;
}
