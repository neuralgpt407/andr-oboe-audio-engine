#pragma once

#include "NativeAudioDecoder.h"
#include "SPSCRingBuffer.h"

#include <atomic>
#include <cstdint>
#include <thread>

class NativeDecoderThread {
public:
    static constexpr int kDecodeChunkSamples = 4096 * 2;

    NativeDecoderThread() = default;
    ~NativeDecoderThread();

    NativeDecoderThread(const NativeDecoderThread&) = delete;
    NativeDecoderThread& operator=(const NativeDecoderThread&) = delete;

    bool initialize(int fd);
    void start();
    void stop();
    void pause();
    void resume();
    void seekTo(int64_t ms);
    void seekToUs(int64_t us);
    int read(int16_t* dst, int count);
    size_t available() const;
    bool isEndOfStream() const;
    int getSampleRate() const;
    int getDurationMs() const;

private:
    void run();

    NativeAudioDecoder decoder_;
    SPSCRingBuffer ringBuffer_;
    std::thread thread_;
    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isPaused_{true};
    std::atomic<int64_t> pendingSeekUs_{-1};
    std::atomic<bool> isEndOfStream_{false};
};
