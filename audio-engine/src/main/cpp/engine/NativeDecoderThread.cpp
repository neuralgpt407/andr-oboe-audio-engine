#include "NativeDecoderThread.h"
#include "TrackReadOffset.h"

#include <android/log.h>
#include <android/set_abort_message.h>
#include <sys/resource.h>
#include <unistd.h>

#include <array>
#include <chrono>

namespace {
constexpr int kAndroidPriorityAudio = -16;
}

NativeDecoderThread::~NativeDecoderThread() {
    stop();
}

bool NativeDecoderThread::initialize(int fd) {
    return decoder_.initialize(fd);
}

void NativeDecoderThread::start() {
    if (isRunning_.exchange(true)) return;
    isPaused_.store(true);
    thread_ = std::thread(&NativeDecoderThread::run, this);
}

void NativeDecoderThread::stop() {
    isRunning_.store(false);
    isPaused_.store(false);
    isSeekPending_.store(false, std::memory_order_release);
    if (thread_.joinable()) {
        thread_.join();
    }
    decoder_.release();
}

void NativeDecoderThread::pause() {
    isPaused_.store(true, std::memory_order_release);
}

void NativeDecoderThread::resume() {
    isPaused_.store(false, std::memory_order_release);
}

void NativeDecoderThread::seekTo(int64_t ms) {
    seekToUs(saturatedMillisecondsToMicroseconds(ms));
}

void NativeDecoderThread::seekToUs(int64_t us) {
    isSeekPending_.store(true, std::memory_order_release);
    pendingSeekUs_.store(us, std::memory_order_release);
    ringBuffer_.clear();
}

int NativeDecoderThread::read(int16_t* dst, int count) {
    if (isSeekPending_.load(std::memory_order_acquire)) {
        return 0;
    }
    const size_t readCount = ringBuffer_.read(dst, static_cast<size_t>(count));
    if (readCount > 0) return static_cast<int>(readCount);
    return isEndOfStream_.load(std::memory_order_acquire) ? -1 : 0;
}

size_t NativeDecoderThread::available() const {
    if (isSeekPending_.load(std::memory_order_acquire)) {
        return 0;
    }
    return ringBuffer_.availableToRead();
}

bool NativeDecoderThread::isEndOfStream() const {
    return isEndOfStream_.load(std::memory_order_acquire);
}

bool NativeDecoderThread::isSeekPending() const {
    return isSeekPending_.load(std::memory_order_acquire);
}

int NativeDecoderThread::getSampleRate() const {
    return decoder_.getSampleRate();
}

int NativeDecoderThread::getDurationMs() const {
    return decoder_.getDurationMs();
}

NativeAudioDecoderFailure NativeDecoderThread::getFailureKind() const {
    return decoder_.getFailureKind();
}

void NativeDecoderThread::run() {
    setpriority(PRIO_PROCESS, 0, kAndroidPriorityAudio);
    std::array<int16_t, kDecodeChunkSamples> decodeBuffer{};

    while (isRunning_.load(std::memory_order_acquire)) {
        const int64_t seekUs = pendingSeekUs_.exchange(-1, std::memory_order_acq_rel);
        if (seekUs >= 0) {
            ringBuffer_.clear();
            if (decoder_.seekToUs(seekUs)) {
                isEndOfStream_.store(false, std::memory_order_release);
            } else {
                isEndOfStream_.store(true, std::memory_order_release);
                isPaused_.store(true, std::memory_order_release);
            }
            isSeekPending_.store(false, std::memory_order_release);
        }

        if (isPaused_.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        if (ringBuffer_.availableToWrite() < kDecodeChunkSamples) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        const int samples = decoder_.decode(decodeBuffer.data(), kDecodeChunkSamples);
        if (samples > 0) {
            if (pendingSeekUs_.load(std::memory_order_acquire) < 0) {
                ringBuffer_.write(decodeBuffer.data(), static_cast<size_t>(samples));
            }
        } else if (samples == -1) {
            isEndOfStream_.store(true, std::memory_order_release);
            isPaused_.store(true, std::memory_order_release);
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }
}
