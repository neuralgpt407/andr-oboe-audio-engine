#include "SourceBoost.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace neuralsound::audio::dsp {

void SourceBoost::prepare(int sampleRate) {
    const int safeSampleRate = std::max(1, sampleRate);
    rampFrames_ = std::max(
        1,
        static_cast<int>(
            (static_cast<int64_t>(safeSampleRate) * kRampMilliseconds) / 1000
        )
    );
    reset(kMinDb);
}

void SourceBoost::reset(int sourceBoostDb) {
    const int clampedDb = clampDb(sourceBoostDb);
    targetDb_.store(clampedDb, std::memory_order_release);
    appliedTargetDb_ = clampedDb;
    currentDb_ = static_cast<float>(clampedDb);
    currentLinearGain_ = dbToLinear(currentDb_);
    rampStepDb_ = 0.0f;
    rampLinearMultiplier_ = 1.0f;
    rampFramesRemaining_ = 0;
}

void SourceBoost::setTargetDb(int sourceBoostDb) {
    targetDb_.store(clampDb(sourceBoostDb), std::memory_order_release);
}

int SourceBoost::targetDb() const {
    return targetDb_.load(std::memory_order_acquire);
}

int SourceBoost::rampFrames() const {
    return rampFrames_;
}

float SourceBoost::nextLinearGain() {
    const int nextTargetDb = targetDb_.load(std::memory_order_acquire);
    if (nextTargetDb != appliedTargetDb_) {
        appliedTargetDb_ = nextTargetDb;
        rampFramesRemaining_ = rampFrames_;
        rampStepDb_ =
            (static_cast<float>(appliedTargetDb_) - currentDb_) /
            static_cast<float>(rampFrames_);
        rampLinearMultiplier_ = std::pow(10.0f, rampStepDb_ / 20.0f);
    }

    if (rampFramesRemaining_ > 0) {
        currentDb_ += rampStepDb_;
        currentLinearGain_ *= rampLinearMultiplier_;
        --rampFramesRemaining_;
        if (rampFramesRemaining_ == 0) {
            currentDb_ = static_cast<float>(appliedTargetDb_);
            currentLinearGain_ = dbToLinear(currentDb_);
        }
    }
    return currentLinearGain_;
}

void SourceBoost::advanceFrames(int frames) {
    for (int frame = 0; frame < frames; ++frame) {
        nextLinearGain();
    }
}

int SourceBoost::clampDb(int sourceBoostDb) {
    return std::clamp(sourceBoostDb, kMinDb, kMaxDb);
}

float SourceBoost::dbToLinear(float sourceBoostDb) {
    const float clampedDb = std::clamp(
        sourceBoostDb,
        static_cast<float>(kMinDb),
        static_cast<float>(kMaxDb)
    );
    return std::pow(10.0f, clampedDb / 20.0f);
}

} // namespace neuralsound::audio::dsp
