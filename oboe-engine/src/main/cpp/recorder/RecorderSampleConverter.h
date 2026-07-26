#pragma once

#include <oboe/Oboe.h>

#include <cstddef>
#include <cstdint>

void convertInputSliceToPcm16Mono(
    const void* audioData,
    oboe::AudioFormat format,
    int channelCount,
    int32_t sourceFrameOffset,
    int32_t frameCount,
    int16_t* output,
    float* peak
);

// Downmix an input slice to mono float in [-1, 1]. Used as the resampler's
// input when the device sample rate differs from the canonical recording rate.
void convertInputSliceToFloatMono(
    const void* audioData,
    oboe::AudioFormat format,
    int channelCount,
    int32_t sourceFrameOffset,
    int32_t frameCount,
    float* output,
    float* peak
);

// Convert mono float samples in [-1, 1] to PCM16, updating the running peak.
void floatMonoToPcm16(
    const float* input,
    int32_t frameCount,
    int16_t* output,
    float* peak
);
