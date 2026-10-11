#pragma once

#include <atomic>

namespace neuralsound::audio::dsp {

class OutputAttenuationRamp {
public:
    static constexpr int kRampMilliseconds = 5;

    void prepare(int sampleRate);
    void reset();
    void setTarget(float attenuation);

    int rampFrames() const;
    float target() const;
    float nextGain();

private:
    std::atomic<float> target_{1.0f};
    int rampFrames_ = 1;
    int framesRemaining_ = 0;
    float current_ = 1.0f;
    float appliedTarget_ = 1.0f;
    float step_ = 0.0f;
};

} // namespace neuralsound::audio::dsp
