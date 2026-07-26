#include "RecorderWaveformAccumulator.h"

#include <algorithm>
#include <cmath>

void RecorderWaveformAccumulator::reset(int sampleRate) {
    const int32_t previousCount = std::clamp(
        publishedBucketCount_.load(std::memory_order_acquire) + 1,
        0,
        kMaxBuckets
    );
    for (int32_t index = 0; index < previousCount; ++index) {
        sumSquares_[index].store(0, std::memory_order_release);
        frameCounts_[index].store(0, std::memory_order_release);
    }

    sampleRate_.store(std::max(sampleRate, 1), std::memory_order_release);
    currentBucketIndex_.store(0, std::memory_order_release);
    currentFrameInBucket_.store(0, std::memory_order_release);
    publishedBucketCount_.store(0, std::memory_order_release);
}

void RecorderWaveformAccumulator::appendPcm16Mono(const int16_t* samples, int32_t frameCount) {
    if (samples == nullptr || frameCount <= 0) return;

    int32_t bucketIndex = currentBucketIndex_.load(std::memory_order_relaxed);
    int32_t frameInBucket = currentFrameInBucket_.load(std::memory_order_relaxed);
    for (int32_t frame = 0; frame < frameCount && bucketIndex < kMaxBuckets; ++frame) {
        const int32_t sample = samples[frame];
        const uint64_t square = static_cast<uint64_t>(static_cast<int64_t>(sample) * sample);
        sumSquares_[bucketIndex].fetch_add(square, std::memory_order_relaxed);
        ++frameInBucket;
        frameCounts_[bucketIndex].store(frameInBucket, std::memory_order_release);

        if (frameInBucket == framesThroughBucket(bucketIndex)) {
            publishedBucketCount_.store(bucketIndex + 1, std::memory_order_release);
            ++bucketIndex;
            frameInBucket = 0;
        }
    }
    currentBucketIndex_.store(bucketIndex, std::memory_order_release);
    currentFrameInBucket_.store(frameInBucket, std::memory_order_release);
}

RecorderWaveformDelta RecorderWaveformAccumulator::readDelta(int32_t lastConsumedBucketIndex) const {
    const int32_t completedCount = std::clamp(
        publishedBucketCount_.load(std::memory_order_acquire),
        0,
        kMaxBuckets
    );
    const int32_t firstBucket = std::clamp(lastConsumedBucketIndex, 0, completedCount);
    RecorderWaveformDelta delta;
    delta.firstBucketIndex = firstBucket;
    delta.completedBucketRms.reserve(static_cast<size_t>(completedCount - firstBucket));
    for (int32_t index = firstBucket; index < completedCount; ++index) {
        delta.completedBucketRms.push_back(rmsFor(
            index,
            frameCounts_[index].load(std::memory_order_acquire)
        ));
    }

    const int32_t partialIndex = currentBucketIndex_.load(std::memory_order_acquire);
    if (partialIndex >= 0 && partialIndex < kMaxBuckets) {
        delta.partialBucketFrames = currentFrameInBucket_.load(std::memory_order_acquire);
        delta.partialBucketRms = rmsFor(partialIndex, delta.partialBucketFrames);
    }
    return delta;
}

int32_t RecorderWaveformAccumulator::framesThroughBucket(int32_t bucketIndex) const {
    const int64_t rate = sampleRate_.load(std::memory_order_relaxed);
    const int64_t end = ((static_cast<int64_t>(bucketIndex) + 1) * rate * kBucketDurationMs) / 1000;
    const int64_t start = (static_cast<int64_t>(bucketIndex) * rate * kBucketDurationMs) / 1000;
    return std::max<int32_t>(1, static_cast<int32_t>(end - start));
}

float RecorderWaveformAccumulator::rmsFor(int32_t bucketIndex, int32_t frames) const {
    if (frames <= 0 || bucketIndex < 0 || bucketIndex >= kMaxBuckets) return 0.0f;
    const long double mean = static_cast<long double>(
        sumSquares_[bucketIndex].load(std::memory_order_acquire)
    ) / frames;
    return static_cast<float>(std::sqrt(mean) / 32768.0L);
}
