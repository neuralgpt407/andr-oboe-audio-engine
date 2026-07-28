#pragma once

#include <oboe/Oboe.h>

#include <memory>

std::shared_ptr<oboe::AudioStream> openRecorderInputStream(
    oboe::SharingMode sharingMode,
    oboe::AudioStreamDataCallback* dataCallback,
    std::shared_ptr<oboe::AudioStreamErrorCallback> errorCallback,
    const char* logTag
);

bool recorderStreamClosedDuringStart(
    const std::shared_ptr<oboe::AudioStream>& stream,
    int timeoutMs
);
