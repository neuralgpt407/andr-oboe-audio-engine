#pragma once

#include "RecorderMicSessionCoordinator.h"

#include <oboe/Oboe.h>

#include <memory>

class RecorderErrorCallback final : public oboe::AudioStreamErrorCallback {
public:
    RecorderErrorCallback(
        std::shared_ptr<RecorderMicSessionCoordinator> coordinator,
        RecorderMicSessionCoordinator::SessionToken sessionToken
    );

    void onErrorAfterClose(
        oboe::AudioStream* audioStream,
        oboe::Result error
    ) override;

private:
    const std::shared_ptr<RecorderMicSessionCoordinator> coordinator_;
    const RecorderMicSessionCoordinator::SessionToken sessionToken_;
};
