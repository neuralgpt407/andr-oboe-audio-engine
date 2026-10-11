#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace neuralsound::audio::waveform {

// Collects absolute PCM16 energy without per-source normalization. Each level
// is the RMS of the louder stereo-channel magnitude for a 32 ms window.
class WaveformRmsAccumulator {
public:
    static constexpr int kDefaultWindowMs = 32;

    explicit WaveformRmsAccumulator(
        int sampleRate,
        size_t maxSamples = 1024,
        size_t expectedWindowCount = 0,
        int windowMs = kDefaultWindowMs
    );

    void addStereoFrames(const int16_t* interleavedStereo, size_t frameCount);

    // Flushes a final partial window. Completed windows are retained as
    // bounded, RMS-reducible buckets throughout streaming analysis.
    std::vector<float> finish();

    size_t completedWindowCount() const;
    size_t retainedLevelCount() const;
    size_t framesPerWindow() const;

    static float stereoFrameMagnitude(int16_t left, int16_t right);

private:
    struct Bucket {
        double squareSum;
        size_t levelCount;
    };

    void completeWindow(double squareSum, size_t frameCount);
    void appendRmsLevel(float level);
    void compactAdjacentBuckets();

    size_t framesPerWindow_;
    size_t maxSamples_;
    size_t expectedWindowCount_;
    size_t completedWindowCount_ = 0;
    size_t framesInWindow_ = 0;
    double currentWindowSquareSum_ = 0.0;
    std::vector<Bucket> completedBuckets_;
};

} // namespace neuralsound::audio::waveform
