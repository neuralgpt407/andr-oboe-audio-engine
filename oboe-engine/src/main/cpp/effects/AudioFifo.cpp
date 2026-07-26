#include "AudioFifo.h"

#include <algorithm>
#include <cstring>

void AudioFifo::prepare(int channels, int capacityFrames) {
    channels_ = std::max(1, channels);
    capacityFrames_ = std::max(1, capacityFrames);
    buffer_.assign(static_cast<size_t>(channels_) * capacityFrames_, 0.0f);
    readFrame_.store(0, std::memory_order_release);
    writeFrame_.store(0, std::memory_order_release);
}

void AudioFifo::clear() {
    const int64_t writeFrame = writeFrame_.load(std::memory_order_acquire);
    readFrame_.store(writeFrame, std::memory_order_release);
}

int AudioFifo::availableFrames() const {
    const int64_t writeFrame = writeFrame_.load(std::memory_order_acquire);
    const int64_t readFrame = readFrame_.load(std::memory_order_acquire);
    const int64_t available = writeFrame - readFrame;
    return static_cast<int>(std::clamp<int64_t>(available, 0, capacityFrames_));
}

int AudioFifo::availableWriteFrames() const {
    return capacityFrames_ - availableFrames();
}

int AudioFifo::capacityFrames() const {
    return capacityFrames_;
}

int AudioFifo::write(const float* inputInterleaved, int frames) {
    if (inputInterleaved == nullptr || frames <= 0 || channels_ <= 0 || capacityFrames_ <= 0) {
        return 0;
    }

    const int writableFrames = std::min(frames, availableWriteFrames());
    int64_t writeFrame = writeFrame_.load(std::memory_order_relaxed);
    int framesWritten = 0;
    while (framesWritten < writableFrames) {
        const int writeOffset = static_cast<int>(writeFrame % capacityFrames_);
        const int chunkFrames = std::min(writableFrames - framesWritten, capacityFrames_ - writeOffset);
        const int chunkSamples = chunkFrames * channels_;
        std::memcpy(
            buffer_.data() + static_cast<size_t>(writeOffset) * channels_,
            inputInterleaved + static_cast<size_t>(framesWritten) * channels_,
            static_cast<size_t>(chunkSamples) * sizeof(float)
        );
        writeFrame += chunkFrames;
        framesWritten += chunkFrames;
    }
    writeFrame_.store(writeFrame, std::memory_order_release);
    return framesWritten;
}

int AudioFifo::read(float* outputInterleaved, int frames) {
    if (outputInterleaved == nullptr || frames <= 0 || channels_ <= 0 || capacityFrames_ <= 0) {
        return 0;
    }

    const int readableFrames = std::min(frames, availableFrames());
    int64_t readFrame = readFrame_.load(std::memory_order_relaxed);
    int framesRead = 0;
    while (framesRead < readableFrames) {
        const int readOffset = static_cast<int>(readFrame % capacityFrames_);
        const int chunkFrames = std::min(readableFrames - framesRead, capacityFrames_ - readOffset);
        const int chunkSamples = chunkFrames * channels_;
        std::memcpy(
            outputInterleaved + static_cast<size_t>(framesRead) * channels_,
            buffer_.data() + static_cast<size_t>(readOffset) * channels_,
            static_cast<size_t>(chunkSamples) * sizeof(float)
        );
        readFrame += chunkFrames;
        framesRead += chunkFrames;
    }
    readFrame_.store(readFrame, std::memory_order_release);
    return framesRead;
}
