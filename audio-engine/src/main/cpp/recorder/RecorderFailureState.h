#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

enum class OboeRecorderErrorCode : int32_t {
    None = 0,
    MicSessionOpenFailed = 1,
    StreamDisconnected = 2,
    WriterOverflow = 3,
    WriterFileError = 4,
    InvalidOutput = 5,
};

struct OboeRecorderFailureSnapshot {
    OboeRecorderErrorCode errorCode = OboeRecorderErrorCode::None;
    std::string message;
};

class RecorderFailureState {
public:
    void reset();
    bool hasFailed() const;
    bool publish(OboeRecorderErrorCode errorCode, const std::string& message);
    bool publishRealtimeOverflow();
    OboeRecorderFailureSnapshot snapshot() const;

private:
    std::atomic<bool> failed_{false};
    std::atomic<OboeRecorderErrorCode> errorCode_{OboeRecorderErrorCode::None};
    mutable std::mutex messageMutex_;
    std::string message_;
};
