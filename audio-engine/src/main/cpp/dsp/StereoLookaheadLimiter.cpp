#include "StereoLookaheadLimiter.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace neuralsound::audio::dsp {

void StereoLookaheadLimiter::prepare(int sampleRate) {
    const int safeSampleRate = std::max(1, sampleRate);
    latencyFrames_ = static_cast<int>(
        (static_cast<int64_t>(safeSampleRate) * kLookaheadMilliseconds) / 1000
    );
    releaseFrames_ = std::max(
        1,
        static_cast<int>(
            (static_cast<int64_t>(safeSampleRate) * kReleaseMilliseconds) / 1000
        )
    );
    capacityFrames_ = latencyFrames_ + 1;
    inverseLatencyFrames_ = latencyFrames_ > 0
        ? 1.0 / static_cast<double>(latencyFrames_)
        : 0.0;
    delayBuffer_.assign(
        static_cast<size_t>(capacityFrames_) * kChannelCount,
        0.0f
    );
    // The queue can briefly contain latency + 1 visible constraints plus
    // the previously emitted frame until expiry runs for the next output.
    gainConstraintBuffer_.assign(
        static_cast<size_t>(latencyFrames_) + 2,
        GainConstraint{}
    );
    reset();
}

void StereoLookaheadLimiter::reset() {
    readFrame_ = 0;
    writeFrame_ = 0;
    bufferedFrames_ = 0;
    gainConstraintHead_ = 0;
    gainConstraintCount_ = 0;
    inputFrameIndex_ = 0;
    outputFrameIndex_ = 0;
    zeroLookaheadTargetGain_ = 1.0f;
    currentGain_ = 1.0f;
    releaseIncrement_ = 0.0f;
}

int StereoLookaheadLimiter::latencyFrames() const {
    return latencyFrames_;
}

int StereoLookaheadLimiter::releaseFrames() const {
    return releaseFrames_;
}

bool StereoLookaheadLimiter::hasPendingOutput() const {
    return bufferedFrames_ > 0;
}

int StereoLookaheadLimiter::process(
    const float* inputInterleaved,
    int inputFrames,
    float* outputInterleaved
) {
    if (inputInterleaved == nullptr || inputFrames <= 0 ||
        outputInterleaved == nullptr || capacityFrames_ <= 0) {
        return 0;
    }

    int outputFrames = 0;
    for (int frame = 0; frame < inputFrames; ++frame) {
        const size_t inputOffset = static_cast<size_t>(frame) * kChannelCount;
        const float left = sanitize(inputInterleaved[inputOffset]);
        const float right = sanitize(inputInterleaved[inputOffset + 1]);
        const size_t writeOffset = static_cast<size_t>(writeFrame_) * kChannelCount;
        delayBuffer_[writeOffset] = left;
        delayBuffer_[writeOffset + 1] = right;

        const float frameRequiredGain = requiredGain(
            std::max(std::fabs(left), std::fabs(right))
        );
        if (latencyFrames_ == 0) {
            zeroLookaheadTargetGain_ = frameRequiredGain;
        } else {
            enqueueGainConstraint(inputFrameIndex_, frameRequiredGain);
        }
        ++inputFrameIndex_;

        ++writeFrame_;
        if (writeFrame_ == capacityFrames_) writeFrame_ = 0;
        ++bufferedFrames_;

        if (bufferedFrames_ > latencyFrames_) {
            outputFrames += produceOldestFrame(
                outputInterleaved + static_cast<size_t>(outputFrames) * kChannelCount
            );
        }
    }
    return outputFrames;
}

int StereoLookaheadLimiter::drain(
    float* outputInterleaved,
    int maxOutputFrames
) {
    if (outputInterleaved == nullptr || maxOutputFrames <= 0) return 0;

    const int framesToProduce = std::min(maxOutputFrames, bufferedFrames_);
    int outputFrames = 0;
    while (outputFrames < framesToProduce) {
        outputFrames += produceOldestFrame(
            outputInterleaved + static_cast<size_t>(outputFrames) * kChannelCount
        );
    }
    if (bufferedFrames_ == 0) {
        reset();
    }
    return outputFrames;
}

int StereoLookaheadLimiter::produceOldestFrame(float* outputInterleaved) {
    if (outputInterleaved == nullptr || bufferedFrames_ <= 0) return 0;

    if (latencyFrames_ > 0) {
        expireGainConstraints(outputFrameIndex_);
    }
    const float targetGain = targetGainForOldestFrame();
    if (targetGain < currentGain_) {
        currentGain_ = targetGain;
        releaseIncrement_ = (1.0f - currentGain_) / static_cast<float>(releaseFrames_);
    } else {
        currentGain_ = std::min(
            targetGain,
            std::min(1.0f, currentGain_ + releaseIncrement_)
        );
    }

    const size_t readOffset = static_cast<size_t>(readFrame_) * kChannelCount;
    for (int channel = 0; channel < kChannelCount; ++channel) {
        const float limited = sanitize(delayBuffer_[readOffset + channel] * currentGain_);
        outputInterleaved[channel] = std::clamp(limited, -kCeiling, kCeiling);
    }

    ++readFrame_;
    if (readFrame_ == capacityFrames_) readFrame_ = 0;
    --bufferedFrames_;
    ++outputFrameIndex_;
    return 1;
}

void StereoLookaheadLimiter::enqueueGainConstraint(
    int64_t frameIndex,
    float frameRequiredGain
) {
    if (frameRequiredGain >= 1.0f) return;

    const double newRequiredGain = static_cast<double>(frameRequiredGain);
    const int queueCapacity = static_cast<int>(gainConstraintBuffer_.size());

    // Compare logical keys using only the bounded frame-index difference.
    // This avoids subtracting two growing absolute keys during long sessions.
    while (gainConstraintCount_ > 0) {
        int tail = gainConstraintHead_ + gainConstraintCount_ - 1;
        if (tail >= queueCapacity) tail -= queueCapacity;
        const GainConstraint& tailConstraint = gainConstraintBuffer_[tail];
        const double newGainAtTail = newRequiredGain +
            static_cast<double>(frameIndex - tailConstraint.frameIndex) *
                inverseLatencyFrames_;
        if (tailConstraint.requiredGain < newGainAtTail) break;
        --gainConstraintCount_;
    }

    assert(gainConstraintCount_ < queueCapacity);
    int insertion = gainConstraintHead_ + gainConstraintCount_;
    if (insertion >= queueCapacity) insertion -= queueCapacity;
    gainConstraintBuffer_[insertion] = GainConstraint{
        frameIndex,
        newRequiredGain,
    };
    ++gainConstraintCount_;
}

void StereoLookaheadLimiter::expireGainConstraints(int64_t outputFrameIndex) {
    const int queueCapacity = static_cast<int>(gainConstraintBuffer_.size());
    while (gainConstraintCount_ > 0 &&
           gainConstraintBuffer_[gainConstraintHead_].frameIndex < outputFrameIndex) {
        ++gainConstraintHead_;
        if (gainConstraintHead_ == queueCapacity) gainConstraintHead_ = 0;
        --gainConstraintCount_;
    }
}

float StereoLookaheadLimiter::targetGainForOldestFrame() const {
    if (latencyFrames_ == 0) return zeroLookaheadTargetGain_;
    if (gainConstraintCount_ == 0) return 1.0f;

    const GainConstraint& constraint = gainConstraintBuffer_[gainConstraintHead_];
    const double target = constraint.requiredGain +
        static_cast<double>(constraint.frameIndex - outputFrameIndex_) *
            inverseLatencyFrames_;
    return static_cast<float>(std::clamp(target, 0.0, 1.0));
}

float StereoLookaheadLimiter::requiredGain(float framePeak) {
    return framePeak > kCeiling ? kCeiling / framePeak : 1.0f;
}

float StereoLookaheadLimiter::sanitize(float sample) {
    return std::isfinite(sample) ? sample : 0.0f;
}

} // namespace neuralsound::audio::dsp
