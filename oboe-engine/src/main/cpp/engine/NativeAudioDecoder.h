#pragma once

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <cstdint>
#include <vector>

class NativeAudioDecoder {
public:
    NativeAudioDecoder() = default;
    ~NativeAudioDecoder();

    NativeAudioDecoder(const NativeAudioDecoder&) = delete;
    NativeAudioDecoder& operator=(const NativeAudioDecoder&) = delete;

    bool initialize(int fd);
    int decode(int16_t* output, int maxSamples);
    bool seekTo(int64_t ms);
    bool seekToUs(int64_t us);
    void release();

    int getSampleRate() const { return sampleRate_; }
    int getDurationMs() const { return durationMs_; }

private:
    bool feedInput();
    int drainOutput(int16_t* output, int maxSamples);
    void clearPending();

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    int dupFd_ = -1;
    int sampleRate_ = 0;
    int channelCount_ = 0;
    int64_t durationMs_ = 0;
    int64_t trimBeforeUs_ = -1;
    bool isEndOfStream_ = false;
    bool inputDone_ = false;
    std::vector<int16_t> pendingBuffer_;
    size_t pendingOffset_ = 0;
};
