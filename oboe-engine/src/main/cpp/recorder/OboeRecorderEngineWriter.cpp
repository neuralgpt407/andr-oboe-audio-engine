#include "OboeRecorderEngine.h"

#include <chrono>
#include <future>
#include <thread>

namespace {
constexpr int kWriterIdleSleepMs = 2;
}

bool OboeRecorderEngine::startWriting(const std::string& outputPath, int64_t startOffsetMs) {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    if (outputPath.empty() || startOffsetMs < 0) {
        failActiveTake("invalid recorder output request");
        return false;
    }
    if (!startMicSessionLocked()) {
        return false;
    }

    pauseWritingLocked();
    ringBuffer_.clear();
    framesWritten_.store(0, std::memory_order_release);
    acceptedFrames_.store(0, std::memory_order_release);
    takeId_.fetch_add(1, std::memory_order_acq_rel);
    waveformAccumulator_.reset(sampleRate_.load(std::memory_order_acquire));
    failed_.store(false, std::memory_order_release);
    writerStopRequested_.store(false, std::memory_order_release);
    writerRunning_.store(true, std::memory_order_release);

    std::promise<bool> startPromise;
    std::future<bool> started = startPromise.get_future();
    writerThread_ = std::thread(
        [this, outputPath, startOffsetMs, promise = std::move(startPromise)]() mutable {
            Pcm16WavWriter writer;
            const int rate = sampleRate_.load(std::memory_order_acquire);
            if (!writer.open(outputPath, rate, 1, startOffsetMs)) {
                {
                    std::lock_guard<std::mutex> lock(errorMutex_);
                    lastError_ = writer.lastError();
                }
                failed_.store(true, std::memory_order_release);
                writerRunning_.store(false, std::memory_order_release);
                promise.set_value(false);
                return;
            }

            callbackFence_.arm();
            promise.set_value(true);
            runWriterLoop(writer);
        }
    );

    if (!started.get()) {
        if (writerThread_.joinable()) {
            writerThread_.join();
        }
        return false;
    }
    return true;
}

void OboeRecorderEngine::runWriterLoop(Pcm16WavWriter& writer) {
    while (true) {
        const size_t read = ringBuffer_.read(writerBuffer_.data(), writerBuffer_.size());
        if (read > 0) {
            if (!writer.write(writerBuffer_.data(), read)) {
                std::lock_guard<std::mutex> lock(errorMutex_);
                lastError_ = writer.lastError();
                failed_.store(true, std::memory_order_release);
                callbackFence_.disarm();
                break;
            }
            framesWritten_.store(writer.framesWritten(), std::memory_order_release);
            continue;
        }

        if (failed_.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> lock(errorMutex_);
            if (lastError_.empty()) lastError_ = "recorder ring buffer overflow";
            writerStopRequested_.store(true, std::memory_order_release);
        }
        if (writerStopRequested_.load(std::memory_order_acquire) &&
            ringBuffer_.availableToRead() == 0) {
            break;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(kWriterIdleSleepMs));
    }

    callbackFence_.disarm();
    if (!writer.close()) {
        std::lock_guard<std::mutex> lock(errorMutex_);
        lastError_ = writer.lastError();
        failed_.store(true, std::memory_order_release);
    }
    framesWritten_.store(writer.framesWritten(), std::memory_order_release);
    writerRunning_.store(false, std::memory_order_release);
}

void OboeRecorderEngine::pauseWriting() {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    pauseWritingLocked();
}

void OboeRecorderEngine::pauseWritingLocked() {
    disarmAndAwaitProducers();
    writerStopRequested_.store(true, std::memory_order_release);
    if (writerThread_.joinable()) {
        writerThread_.join();
    }
    writerRunning_.store(false, std::memory_order_release);
}

OboeRecordingResult OboeRecorderEngine::stopWriting() {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    pauseWritingLocked();
    const int rate = sampleRate_.load(std::memory_order_acquire);
    const int64_t accepted = acceptedFrames_.load(std::memory_order_acquire);
    const int64_t written = framesWritten_.load(std::memory_order_acquire);
    const bool failed = failed_.load(std::memory_order_acquire) || accepted != written;
    if (accepted != written && !failed_.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(errorMutex_);
        lastError_ = "recorder accepted/written frame mismatch";
    }
    return OboeRecordingResult{
        rate > 0 ? (written * 1000) / rate : 0,
        accepted,
        written,
        rate,
        failed
    };
}

void OboeRecorderEngine::releaseMicSession() {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    pauseWritingLocked();
    closeInputStream();
    micSessionActive_.store(false, std::memory_order_release);
}
