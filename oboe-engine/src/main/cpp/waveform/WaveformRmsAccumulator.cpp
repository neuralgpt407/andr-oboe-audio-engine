#include "WaveformRmsAccumulator.h"

#include <algorithm>
#include <cmath>

namespace neuralsound::audio::waveform {
namespace {

float boundedLevel(float level) {
    if (!std::isfinite(level)) {
        return 0.0f;
    }
    return std::clamp(level, 0.0f, 1.0f);
}

float boundedRms(double squareSum, size_t count) {
    if (count == 0) {
        return 0.0f;
    }
    return boundedLevel(static_cast<float>(
        std::sqrt(std::max(
            0.0,
            squareSum / static_cast<double>(count)
        ))
    ));
}

} // namespace

WaveformRmsAccumulator::WaveformRmsAccumulator(
    int sampleRate,
    size_t maxSamples,
    size_t expectedWindowCount,
    int windowMs
)
    : framesPerWindow_(static_cast<size_t>(std::max<int64_t>(
          1,
          (static_cast<int64_t>(sampleRate) * windowMs) / 1000
      ))),
      maxSamples_(std::max<size_t>(1, maxSamples)),
      expectedWindowCount_(expectedWindowCount) {
    const size_t reservedSize = std::min(
        maxSamples_,
        expectedWindowCount_ == 0 ? maxSamples_ : expectedWindowCount_
    );
    completedBuckets_.reserve(reservedSize);
}

void WaveformRmsAccumulator::addStereoFrames(
    const int16_t* interleavedStereo,
    size_t frameCount
) {
    if (interleavedStereo == nullptr || frameCount == 0) {
        return;
    }

    for (size_t frame = 0; frame < frameCount; ++frame) {
        const size_t sample = frame * 2;
        const double magnitude = static_cast<double>(
            stereoFrameMagnitude(
                interleavedStereo[sample],
                interleavedStereo[sample + 1]
            )
        );
        currentWindowSquareSum_ += magnitude * magnitude;
        ++framesInWindow_;
        if (framesInWindow_ == framesPerWindow_) {
            completeWindow(currentWindowSquareSum_, framesInWindow_);
            framesInWindow_ = 0;
            currentWindowSquareSum_ = 0.0;
        }
    }
}

std::vector<float> WaveformRmsAccumulator::finish() {
    if (framesInWindow_ > 0) {
        completeWindow(currentWindowSquareSum_, framesInWindow_);
        framesInWindow_ = 0;
        currentWindowSquareSum_ = 0.0;
    }

    std::vector<float> levels;
    levels.reserve(completedBuckets_.size());
    for (const Bucket& bucket : completedBuckets_) {
        levels.push_back(boundedRms(bucket.squareSum, bucket.levelCount));
    }
    return levels;
}

size_t WaveformRmsAccumulator::completedWindowCount() const {
    return completedWindowCount_;
}

size_t WaveformRmsAccumulator::retainedLevelCount() const {
    return completedBuckets_.size();
}

size_t WaveformRmsAccumulator::framesPerWindow() const {
    return framesPerWindow_;
}

float WaveformRmsAccumulator::stereoFrameMagnitude(
    int16_t left,
    int16_t right
) {
    const int32_t leftMagnitude = std::abs(static_cast<int32_t>(left));
    const int32_t rightMagnitude = std::abs(static_cast<int32_t>(right));
    const int32_t magnitude = std::max(leftMagnitude, rightMagnitude);
    return boundedLevel(static_cast<float>(magnitude) / 32768.0f);
}

void WaveformRmsAccumulator::completeWindow(
    double squareSum,
    size_t frameCount
) {
    if (frameCount == 0) {
        return;
    }
    appendRmsLevel(boundedRms(squareSum, frameCount));
}

void WaveformRmsAccumulator::appendRmsLevel(float level) {
    const double bounded = static_cast<double>(boundedLevel(level));
    const double levelSquare = bounded * bounded;
    if (maxSamples_ == 1) {
        if (completedBuckets_.empty()) {
            completedBuckets_.push_back({levelSquare, 1});
        } else {
            completedBuckets_.front().squareSum += levelSquare;
            ++completedBuckets_.front().levelCount;
        }
    } else if (expectedWindowCount_ > maxSamples_) {
        const size_t bucket = std::min(
            maxSamples_ - 1,
            (completedWindowCount_ * maxSamples_) / expectedWindowCount_
        );
        if (bucket == completedBuckets_.size()) {
            completedBuckets_.push_back({levelSquare, 1});
        } else {
            completedBuckets_[bucket].squareSum += levelSquare;
            ++completedBuckets_[bucket].levelCount;
        }
    } else {
        if (completedBuckets_.size() == maxSamples_) {
            compactAdjacentBuckets();
        }
        completedBuckets_.push_back({levelSquare, 1});
    }
    ++completedWindowCount_;
}

void WaveformRmsAccumulator::compactAdjacentBuckets() {
    size_t writeIndex = 0;
    for (size_t readIndex = 0;
         readIndex < completedBuckets_.size();
         readIndex += 2) {
        Bucket combined = completedBuckets_[readIndex];
        if (readIndex + 1 < completedBuckets_.size()) {
            combined.squareSum += completedBuckets_[readIndex + 1].squareSum;
            combined.levelCount += completedBuckets_[readIndex + 1].levelCount;
        }
        completedBuckets_[writeIndex] = combined;
        ++writeIndex;
    }
    completedBuckets_.resize(writeIndex);
}

} // namespace neuralsound::audio::waveform
