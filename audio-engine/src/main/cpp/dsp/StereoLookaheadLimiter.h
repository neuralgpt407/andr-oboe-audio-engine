#pragma once

#include <cstdint>
#include <vector>

namespace neuralsound::audio::dsp {

class StereoLookaheadLimiter {
public:
    static constexpr float kCeiling = 0.98f;
    static constexpr int kLookaheadMilliseconds = 5;
    static constexpr int kReleaseMilliseconds = 50;
    static constexpr int kChannelCount = 2;

    void prepare(int sampleRate);
    void reset();

    int latencyFrames() const;
    int releaseFrames() const;
    bool hasPendingOutput() const;

    int process(
        const float* inputInterleaved,
        int inputFrames,
        float* outputInterleaved
    );
    int drain(float* outputInterleaved, int maxOutputFrames);

private:
    friend class StereoLookaheadLimiterTestAccess;

    struct GainConstraint {
        int64_t frameIndex = 0;
        double requiredGain = 1.0;
    };

    int produceOldestFrame(float* outputInterleaved);
    void enqueueGainConstraint(int64_t frameIndex, float requiredGain);
    void expireGainConstraints(int64_t outputFrameIndex);
    float targetGainForOldestFrame() const;
    static float requiredGain(float framePeak);
    static float sanitize(float sample);

    std::vector<float> delayBuffer_;
    std::vector<GainConstraint> gainConstraintBuffer_;
    int capacityFrames_ = 0;
    int latencyFrames_ = 0;
    int releaseFrames_ = 1;
    int readFrame_ = 0;
    int writeFrame_ = 0;
    int bufferedFrames_ = 0;
    int gainConstraintHead_ = 0;
    int gainConstraintCount_ = 0;
    int64_t inputFrameIndex_ = 0;
    int64_t outputFrameIndex_ = 0;
    double inverseLatencyFrames_ = 0.0;
    float zeroLookaheadTargetGain_ = 1.0f;
    float currentGain_ = 1.0f;
    float releaseIncrement_ = 0.0f;
};

} // namespace neuralsound::audio::dsp
