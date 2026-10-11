#include "OutputAttenuationRamp.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace neuralsound::audio::dsp {

void OutputAttenuationRamp::prepare(int sampleRate) {
    const int safeSampleRate = std::max(1, sampleRate);
    rampFrames_ = std::max(
        1,
        static_cast<int>(
            (static_cast<int64_t>(safeSampleRate) * kRampMilliseconds) / 1000
        )
    );
    reset();
}

void OutputAttenuationRamp::reset() {
    target_.store(1.0f, std::memory_order_release);
    framesRemaining_ = 0;
    current_ = 1.0f;
    appliedTarget_ = 1.0f;
    step_ = 0.0f;
}

void OutputAttenuationRamp::setTarget(float attenuation) {
    const float safeAttenuation = std::isfinite(attenuation)
        ? std::clamp(attenuation, 0.0f, 1.0f)
        : 1.0f;
    target_.store(safeAttenuation, std::memory_order_release);
}

int OutputAttenuationRamp::rampFrames() const {
    return rampFrames_;
}

float OutputAttenuationRamp::target() const {
    return target_.load(std::memory_order_acquire);
}

float OutputAttenuationRamp::nextGain() {
    const float nextTarget = target_.load(std::memory_order_acquire);
    if (nextTarget != appliedTarget_) {
        appliedTarget_ = nextTarget;
        framesRemaining_ = rampFrames_;
        step_ = (appliedTarget_ - current_) / static_cast<float>(rampFrames_);
    }
    if (framesRemaining_ > 0) {
        current_ += step_;
        --framesRemaining_;
        if (framesRemaining_ == 0) {
            current_ = appliedTarget_;
        }
    }
    if (!std::isfinite(current_)) {
        current_ = 1.0f;
    }
    return std::clamp(current_, 0.0f, 1.0f);
}

} // namespace neuralsound::audio::dsp
