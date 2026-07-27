#include "WaveformRmsAccumulator.h"

#include <algorithm>
#include <cmath>
#include <iterator>

namespace neuralsound::audio::waveform {
namespace {

float boundedLevel(float level) {
    if (!std::isfinite(level)) {
        return 0.0f;
    }
    return std::clamp(level, 0.0f, 1.0f);
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
    completedBucketSquareSums_.reserve(reservedSize);
    completedBucketLevelCounts_.reserve(reservedSize);
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
    levels.reserve(completedBucketSquareSums_.size());
    for (size_t bucket = 0;
         bucket < completedBucketSquareSums_.size();
         ++bucket) {
        const size_t count = completedBucketLevelCounts_[bucket];
        const double meanSquare = count == 0
            ? 0.0
            : completedBucketSquareSums_[bucket] /
                  static_cast<double>(count);
        levels.push_back(boundedLevel(
            static_cast<float>(std::sqrt(std::max(0.0, meanSquare)))
        ));
    }
    return levels;
}

size_t WaveformRmsAccumulator::completedWindowCount() const {
    return completedWindowCount_;
}

size_t WaveformRmsAccumulator::retainedLevelCount() const {
    return completedBucketSquareSums_.size();
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
    const float level = boundedLevel(static_cast<float>(
        std::sqrt(std::max(
            0.0,
            squareSum / static_cast<double>(frameCount)
        ))
    ));
    appendRmsLevel(level);
}

void WaveformRmsAccumulator::appendRmsLevel(float level) {
    const double bounded = static_cast<double>(boundedLevel(level));
    const double levelSquare = bounded * bounded;
    if (expectedWindowCount_ > maxSamples_) {
        const size_t bucket = std::min(
            maxSamples_ - 1,
            (completedWindowCount_ * maxSamples_) / expectedWindowCount_
        );
        if (bucket == completedBucketSquareSums_.size()) {
            completedBucketSquareSums_.push_back(levelSquare);
            completedBucketLevelCounts_.push_back(1);
        } else {
            completedBucketSquareSums_[bucket] += levelSquare;
            ++completedBucketLevelCounts_[bucket];
        }
    } else {
        if (completedBucketSquareSums_.size() == maxSamples_) {
            compactAdjacentBuckets();
        }
        completedBucketSquareSums_.push_back(levelSquare);
        completedBucketLevelCounts_.push_back(1);
    }
    ++completedWindowCount_;
}

void WaveformRmsAccumulator::compactAdjacentBuckets() {
    size_t writeIndex = 0;
    for (size_t readIndex = 0;
         readIndex < completedBucketSquareSums_.size();
         readIndex += 2) {
        double squareSum = completedBucketSquareSums_[readIndex];
        size_t levelCount = completedBucketLevelCounts_[readIndex];
        if (readIndex + 1 < completedBucketSquareSums_.size()) {
            squareSum += completedBucketSquareSums_[readIndex + 1];
            levelCount += completedBucketLevelCounts_[readIndex + 1];
        }
        completedBucketSquareSums_[writeIndex] = squareSum;
        completedBucketLevelCounts_[writeIndex] = levelCount;
        ++writeIndex;
    }
    completedBucketSquareSums_.resize(writeIndex);
    completedBucketLevelCounts_.resize(writeIndex);
}

std::vector<float> WaveformRmsAccumulator::reduceRms(
    const std::vector<float>& input,
    size_t maxSamples
) {
    if (maxSamples == 0 || input.empty()) {
        return {};
    }
    if (input.size() <= maxSamples) {
        std::vector<float> bounded;
        bounded.reserve(input.size());
        std::transform(
            input.begin(),
            input.end(),
            std::back_inserter(bounded),
            boundedLevel
        );
        return bounded;
    }

    std::vector<float> reduced(maxSamples, 0.0f);
    for (size_t bucket = 0; bucket < maxSamples; ++bucket) {
        const size_t start = (bucket * input.size()) / maxSamples;
        const size_t end = ((bucket + 1) * input.size()) / maxSamples;
        double squareSum = 0.0;
        for (size_t index = start; index < end; ++index) {
            const double level = static_cast<double>(boundedLevel(input[index]));
            squareSum += level * level;
        }
        const size_t count = end - start;
        reduced[bucket] = count == 0
            ? 0.0f
            : static_cast<float>(std::sqrt(
                  squareSum / static_cast<double>(count)
              ));
    }
    return reduced;
}

} // namespace neuralsound::audio::waveform
