#include "RecorderMicSessionCoordinator.h"

#include <limits>
#include <utility>

RecorderMicSessionCoordinator::RecorderMicSessionCoordinator(
    std::shared_ptr<RecorderFailureState> failureState,
    std::shared_ptr<RecorderCallbackFence> callbackFence
)
    : failureState_(std::move(failureState)),
      callbackFence_(std::move(callbackFence)) {}

RecorderMicSessionCoordinator::SessionToken
RecorderMicSessionCoordinator::nextTokenLocked() {
    if (currentToken_ == std::numeric_limits<SessionToken>::max()) {
        currentToken_ = 1;
    } else {
        ++currentToken_;
        if (currentToken_ == kNoSession) {
            currentToken_ = 1;
        }
    }
    return currentToken_;
}

RecorderMicSessionCoordinator::SessionToken
RecorderMicSessionCoordinator::beginOpening() {
    std::lock_guard<std::mutex> lock(mutex_);
    phase_ = Phase::Opening;
    return nextTokenLocked();
}

void RecorderMicSessionCoordinator::abandonOpening(SessionToken sessionToken) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (currentToken_ == sessionToken && phase_ == Phase::Opening) {
        phase_ = Phase::Inactive;
    }
}

bool RecorderMicSessionCoordinator::activate(SessionToken sessionToken) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (currentToken_ != sessionToken || phase_ != Phase::Opening) {
        return false;
    }
    phase_ = Phase::Active;
    return true;
}

RecorderMicSessionCoordinator::SessionToken
RecorderMicSessionCoordinator::activeToken() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return phase_ == Phase::Active ? currentToken_ : kNoSession;
}

bool RecorderMicSessionCoordinator::resetFailureForTake(SessionToken sessionToken) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (currentToken_ != sessionToken || phase_ != Phase::Active) {
        return false;
    }
    failureState_->reset();
    return true;
}

bool RecorderMicSessionCoordinator::armTakeIfActive(SessionToken sessionToken) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (currentToken_ != sessionToken ||
        phase_ != Phase::Active ||
        failureState_->hasFailed()) {
        return false;
    }
    callbackFence_->arm();
    return true;
}

bool RecorderMicSessionCoordinator::closeStream(
    SessionToken sessionToken,
    OboeRecorderErrorCode errorCode,
    const std::string& message
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (currentToken_ != sessionToken || phase_ == Phase::Inactive) {
        return false;
    }
    phase_ = Phase::Inactive;
    callbackFence_->disarm();
    if (errorCode != OboeRecorderErrorCode::None) {
        failureState_->publish(errorCode, message);
    }
    return true;
}

void RecorderMicSessionCoordinator::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    nextTokenLocked();
    phase_ = Phase::Inactive;
    callbackFence_->disarm();
}

bool RecorderMicSessionCoordinator::isActive() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return phase_ == Phase::Active;
}
