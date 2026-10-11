#pragma once

#include <atomic>

namespace neuralsound::audio::dsp {

class SourceBoost {
public:
    static constexpr int kMinDb = 0;
    static constexpr int kMaxDb = 18;
    static constexpr int kRampMilliseconds = 5;

    void prepare(int sampleRate);
    void reset(int sourceBoostDb = kMinDb);
    void setTargetDb(int sourceBoostDb);

    int targetDb() const;
    int rampFrames() const;
    float nextLinearGain();
    void advanceFrames(int frames);

    static int clampDb(int sourceBoostDb);
    static float dbToLinear(float sourceBoostDb);

private:
    std::atomic<int> targetDb_{kMinDb};
    int appliedTargetDb_ = kMinDb;
    int rampFrames_ = 1;
    int rampFramesRemaining_ = 0;
    float currentDb_ = 0.0f;
    float currentLinearGain_ = 1.0f;
    float rampStepDb_ = 0.0f;
    float rampLinearMultiplier_ = 1.0f;
};

} // namespace neuralsound::audio::dsp
