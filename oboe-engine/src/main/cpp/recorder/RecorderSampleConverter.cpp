#include "RecorderSampleConverter.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace {
int16_t clampToPcm16(int32_t sample) {
    return static_cast<int16_t>(std::clamp(
        sample,
        static_cast<int32_t>(std::numeric_limits<int16_t>::min()),
        static_cast<int32_t>(std::numeric_limits<int16_t>::max())
    ));
}

int16_t convertFloatToPcm16(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 1.0f);
    return clampToPcm16(static_cast<int32_t>(std::lrint(clamped * 32767.0f)));
}

float normalizedPeak(int16_t sample) {
    const int32_t absSample = sample == std::numeric_limits<int16_t>::min()
        ? std::numeric_limits<int16_t>::max()
        : std::abs(static_cast<int32_t>(sample));
    return static_cast<float>(absSample) / 32767.0f;
}
}

void convertInputSliceToPcm16Mono(
    const void* audioData,
    oboe::AudioFormat format,
    int channelCount,
    int32_t sourceFrameOffset,
    int32_t frameCount,
    int16_t* output,
    float* peak
) {
    if (audioData == nullptr || output == nullptr || peak == nullptr || frameCount <= 0) {
        return;
    }

    const int channels = std::max(1, channelCount);
    if (format == oboe::AudioFormat::Float) {
        auto* source = static_cast<const float*>(audioData) + sourceFrameOffset * channels;
        for (int32_t i = 0; i < frameCount; ++i) {
            const float sample = std::clamp(source[i * channels], -1.0f, 1.0f);
            *peak = std::max(*peak, std::abs(sample));
            output[static_cast<size_t>(i)] = convertFloatToPcm16(sample);
        }
        return;
    }

    auto* source = static_cast<const int16_t*>(audioData) + sourceFrameOffset * channels;
    for (int32_t i = 0; i < frameCount; ++i) {
        const int16_t sample = source[i * channels];
        *peak = std::max(*peak, normalizedPeak(sample));
        output[static_cast<size_t>(i)] = sample;
    }
}

void convertInputSliceToFloatMono(
    const void* audioData,
    oboe::AudioFormat format,
    int channelCount,
    int32_t sourceFrameOffset,
    int32_t frameCount,
    float* output,
    float* peak
) {
    if (audioData == nullptr || output == nullptr || peak == nullptr || frameCount <= 0) {
        return;
    }

    const int channels = std::max(1, channelCount);
    if (format == oboe::AudioFormat::Float) {
        auto* source = static_cast<const float*>(audioData) + sourceFrameOffset * channels;
        for (int32_t i = 0; i < frameCount; ++i) {
            const float sample = std::clamp(source[i * channels], -1.0f, 1.0f);
            *peak = std::max(*peak, std::abs(sample));
            output[static_cast<size_t>(i)] = sample;
        }
        return;
    }

    auto* source = static_cast<const int16_t*>(audioData) + sourceFrameOffset * channels;
    for (int32_t i = 0; i < frameCount; ++i) {
        const int16_t sample = source[i * channels];
        *peak = std::max(*peak, normalizedPeak(sample));
        output[static_cast<size_t>(i)] = static_cast<float>(sample) / 32768.0f;
    }
}

void floatMonoToPcm16(
    const float* input,
    int32_t frameCount,
    int16_t* output,
    float* peak
) {
    if (input == nullptr || output == nullptr || peak == nullptr || frameCount <= 0) {
        return;
    }

    for (int32_t i = 0; i < frameCount; ++i) {
        const float sample = std::clamp(input[static_cast<size_t>(i)], -1.0f, 1.0f);
        *peak = std::max(*peak, std::abs(sample));
        output[static_cast<size_t>(i)] = convertFloatToPcm16(sample);
    }
}
