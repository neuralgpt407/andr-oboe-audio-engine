#pragma once

#include "RecorderCallbackFence.h"
#include "RecorderFailureState.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

class RecorderMicSessionCoordinator {
public:
    using SessionToken = uint64_t;
    static constexpr SessionToken kNoSession = 0;

    RecorderMicSessionCoordinator(
        std::shared_ptr<RecorderFailureState> failureState,
        std::shared_ptr<RecorderCallbackFence> callbackFence
    );

    SessionToken beginOpening();
    void abandonOpening(SessionToken sessionToken);
    bool activate(SessionToken sessionToken);
    SessionToken activeToken() const;
    bool resetFailureForTake(SessionToken sessionToken);
    bool armTakeIfActive(SessionToken sessionToken);
    bool closeStream(
        SessionToken sessionToken,
        OboeRecorderErrorCode errorCode,
        const std::string& message
    );
    void release();
    bool isActive() const;

private:
    enum class Phase {
        Inactive,
        Opening,
        Active,
    };

    SessionToken nextTokenLocked();

    const std::shared_ptr<RecorderFailureState> failureState_;
    const std::shared_ptr<RecorderCallbackFence> callbackFence_;
    mutable std::mutex mutex_;
    SessionToken currentToken_ = kNoSession;
    Phase phase_ = Phase::Inactive;
};
