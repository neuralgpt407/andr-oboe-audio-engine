#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <vector>

struct RecorderWaveformDelta {
    int32_t firstBucketIndex = 0;
    std::vector<float> completedBucketRms;
    float partialBucketRms = 0.0f;
    int32_t partialBucketFrames = 0;
};

class RecorderWaveformAccumulator {
public:
    static constexpr int64_t kBucketDurationMs = 32;
    static constexpr int kMaxBuckets = 131072;

    void reset(int sampleRate);
    void appendPcm16Mono(const int16_t* samples, int32_t frameCount);
    RecorderWaveformDelta readDelta(int32_t lastConsumedBucketIndex) const;

private:
    int32_t framesThroughBucket(int32_t bucketIndex) const;
    float rmsFor(int32_t bucketIndex, int32_t frames) const;

    std::array<std::atomic<uint64_t>, kMaxBuckets> sumSquares_{};
    std::array<std::atomic<int32_t>, kMaxBuckets> frameCounts_{};
    std::atomic<int32_t> sampleRate_{1};
    std::atomic<int32_t> currentBucketIndex_{0};
    std::atomic<int32_t> currentFrameInBucket_{0};
    std::atomic<int32_t> publishedBucketCount_{0};
};
