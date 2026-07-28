#include "OboeRecorderEngine.h"

#include <chrono>
#include <future>
#include <thread>

namespace {
constexpr int kWriterIdleSleepMs = 2;
}

bool OboeRecorderEngine::startWriting(const std::string& outputPath, int64_t startOffsetMs) {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    return startWritingLocked(
        outputPath,
        startOffsetMs,
        StartOffsetUnit::Milliseconds
    );
}

bool OboeRecorderEngine::startWritingAtFrame(
    const std::string& outputPath,
    int64_t startOffsetFrames
) {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    return startWritingLocked(
        outputPath,
        startOffsetFrames,
        StartOffsetUnit::PcmFrames
    );
}

bool OboeRecorderEngine::startWritingLocked(
    const std::string& outputPath,
    int64_t startOffset,
    StartOffsetUnit startOffsetUnit
) {
    const bool invalidRequest =
        outputPath.empty() ||
        startOffset < 0 ||
        (
            startOffsetUnit == StartOffsetUnit::PcmFrames &&
            startOffset > Pcm16WavWriter::kMaxFrameCount
        );
    if (invalidRequest) {
        if (!writerRunning_.load(std::memory_order_acquire)) {
            failureState_->reset();
        }
        failActiveTake(
            "invalid recorder output request",
            OboeRecorderErrorCode::InvalidOutput
        );
        return false;
    }
    if (!startMicSessionLocked()) {
        return false;
    }
    const auto sessionToken = micSessionCoordinator_->activeToken();
    if (sessionToken == RecorderMicSessionCoordinator::kNoSession) {
        return false;
    }

    pauseWritingLocked();
    if (!micSessionCoordinator_->resetFailureForTake(sessionToken)) {
        return false;
    }
    ringBuffer_.clear();
    framesWritten_.store(0, std::memory_order_release);
    acceptedFrames_.store(0, std::memory_order_release);
    takeId_.fetch_add(1, std::memory_order_acq_rel);
    waveformAccumulator_.reset(sampleRate_.load(std::memory_order_acquire));
    writerStopRequested_.store(false, std::memory_order_release);
    writerRunning_.store(true, std::memory_order_release);

    std::promise<bool> startPromise;
    std::future<bool> started = startPromise.get_future();
    writerThread_ = std::thread(
        [
            this,
            outputPath,
            startOffset,
            startOffsetUnit,
            sessionToken,
            promise = std::move(startPromise)
        ]() mutable {
            Pcm16WavWriter writer;
            const int rate = sampleRate_.load(std::memory_order_acquire);
            const bool opened = startOffsetUnit == StartOffsetUnit::PcmFrames
                ? writer.openAtFrame(outputPath, rate, 1, startOffset)
                : writer.open(outputPath, rate, 1, startOffset);
            if (!opened) {
                failureState_->publish(
                    OboeRecorderErrorCode::WriterFileError,
                    writer.lastError()
                );
                writerRunning_.store(false, std::memory_order_release);
                promise.set_value(false);
                return;
            }

            if (!micSessionCoordinator_->armTakeIfActive(sessionToken)) {
                writer.close();
                writerRunning_.store(false, std::memory_order_release);
                promise.set_value(false);
                return;
            }
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
                failureState_->publish(
                    OboeRecorderErrorCode::WriterFileError,
                    writer.lastError()
                );
                callbackFence_->disarm();
                break;
            }
            framesWritten_.store(writer.framesWritten(), std::memory_order_release);
            continue;
        }

        if (failureState_->hasFailed()) {
            writerStopRequested_.store(true, std::memory_order_release);
        }
        if (writerStopRequested_.load(std::memory_order_acquire) &&
            ringBuffer_.availableToRead() == 0) {
            break;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(kWriterIdleSleepMs));
    }

    callbackFence_->disarm();
    if (!writer.close()) {
        failureState_->publish(
            OboeRecorderErrorCode::WriterFileError,
            writer.lastError()
        );
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
    const bool nativeFailed = failureState_->hasFailed();
    const bool failed = nativeFailed || accepted != written;
    if (accepted != written && !nativeFailed) {
        failureState_->publish(
            OboeRecorderErrorCode::WriterFileError,
            "recorder accepted/written frame mismatch"
        );
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
    micSessionCoordinator_->release();
    closeInputStream();
    errorCallback_.reset();
}
