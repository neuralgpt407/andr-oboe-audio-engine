#include "RecorderFailureState.h"

namespace {
constexpr const char* kOverflowMessage = "recorder ring buffer overflow";
}

void RecorderFailureState::reset() {
    std::lock_guard<std::mutex> lock(messageMutex_);
    message_.clear();
    errorCode_.store(OboeRecorderErrorCode::None, std::memory_order_release);
    failed_.store(false, std::memory_order_release);
}

bool RecorderFailureState::hasFailed() const {
    return failed_.load(std::memory_order_acquire);
}

bool RecorderFailureState::publish(
    OboeRecorderErrorCode errorCode,
    const std::string& message
) {
    if (errorCode == OboeRecorderErrorCode::None) {
        return false;
    }

    std::lock_guard<std::mutex> lock(messageMutex_);
    OboeRecorderErrorCode expected = OboeRecorderErrorCode::None;
    if (!errorCode_.compare_exchange_strong(
            expected,
            errorCode,
            std::memory_order_acq_rel,
            std::memory_order_acquire
        )) {
        return false;
    }
    message_ = message;
    failed_.store(true, std::memory_order_release);
    return true;
}

bool RecorderFailureState::publishRealtimeOverflow() {
    OboeRecorderErrorCode expected = OboeRecorderErrorCode::None;
    if (!errorCode_.compare_exchange_strong(
            expected,
            OboeRecorderErrorCode::WriterOverflow,
            std::memory_order_acq_rel,
            std::memory_order_acquire
        )) {
        return false;
    }
    failed_.store(true, std::memory_order_release);
    return true;
}

OboeRecorderFailureSnapshot RecorderFailureState::snapshot() const {
    std::lock_guard<std::mutex> lock(messageMutex_);
    const OboeRecorderErrorCode errorCode =
        errorCode_.load(std::memory_order_acquire);
    return OboeRecorderFailureSnapshot{
        errorCode,
        errorCode == OboeRecorderErrorCode::WriterOverflow
            ? kOverflowMessage
            : message_,
    };
}
