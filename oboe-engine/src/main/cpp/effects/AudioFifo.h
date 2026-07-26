#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

class AudioFifo {
public:
    void prepare(int channels, int capacityFrames);
    void clear();

    int availableFrames() const;
    int availableWriteFrames() const;
    int capacityFrames() const;

    int write(const float* inputInterleaved, int frames);
    int read(float* outputInterleaved, int frames);

private:
    int channels_ = 0;
    int capacityFrames_ = 0;
    std::atomic<int64_t> readFrame_{0};
    std::atomic<int64_t> writeFrame_{0};
    std::vector<float> buffer_;
};
