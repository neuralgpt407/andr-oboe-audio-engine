#include "RecorderErrorCallback.h"

#include <utility>

RecorderErrorCallback::RecorderErrorCallback(
    std::shared_ptr<RecorderMicSessionCoordinator> coordinator,
    RecorderMicSessionCoordinator::SessionToken sessionToken
)
    : coordinator_(std::move(coordinator)),
      sessionToken_(sessionToken) {}

void RecorderErrorCallback::onErrorAfterClose(
    oboe::AudioStream*,
    oboe::Result error
) {
    const bool disconnected = error == oboe::Result::ErrorDisconnected;
    coordinator_->closeStream(
        sessionToken_,
        disconnected
            ? OboeRecorderErrorCode::StreamDisconnected
            : OboeRecorderErrorCode::None,
        disconnected ? "recorder input stream disconnected" : ""
    );
}
