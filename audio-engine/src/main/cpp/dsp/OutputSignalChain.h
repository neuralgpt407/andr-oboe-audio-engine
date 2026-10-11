#pragma once

#include "OutputAttenuationRamp.h"

#include <cstdint>

namespace neuralsound::audio::dsp {

enum class OutputSignalStage : uint8_t {
    SourceBoost,
    PitchTempo,
    Limiter,
    OutputAttenuation,
    SafetyClamp,
};

constexpr bool outputSignalStagePrecedes(
    OutputSignalStage first,
    OutputSignalStage second
) {
    return static_cast<uint8_t>(first) < static_cast<uint8_t>(second);
}

static_assert(outputSignalStagePrecedes(
    OutputSignalStage::Limiter,
    OutputSignalStage::OutputAttenuation
));

inline void applyPostLimiterOutputAttenuation(
    OutputAttenuationRamp& attenuation,
    float* postLimiterInterleaved,
    int frameCount
) noexcept {
    if (postLimiterInterleaved == nullptr || frameCount <= 0) return;
    for (int frame = 0; frame < frameCount; ++frame) {
        const float gain = attenuation.nextGain();
        postLimiterInterleaved[frame * 2] *= gain;
        postLimiterInterleaved[frame * 2 + 1] *= gain;
    }
}

} // namespace neuralsound::audio::dsp
