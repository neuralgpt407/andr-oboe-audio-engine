#include "RecorderStreamConfig.h"

#include <android/log.h>

#include <chrono>
#include <thread>
#include <utility>

std::shared_ptr<oboe::AudioStream> openRecorderInputStream(
    oboe::SharingMode sharingMode,
    oboe::AudioStreamDataCallback* dataCallback,
    std::shared_ptr<oboe::AudioStreamErrorCallback> errorCallback,
    const char* logTag
) {
    // Open at the device's native sample rate (Unspecified). Any conversion to
    // the canonical recording rate is done downstream by the SampleRateConverter,
    // so we accept whatever rate the device offers instead of rejecting it.
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(sharingMode)
        ->setFormat(oboe::AudioFormat::Unspecified)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(oboe::kUnspecified)
        ->setInputPreset(oboe::InputPreset::VoicePerformance)
        ->setDataCallback(dataCallback)
        ->setErrorCallback(std::move(errorCallback));

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK || stream == nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            logTag,
            "openInputStream failed: %s",
            oboe::convertToText(result)
        );
        return nullptr;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        logTag,
        "Recorder input stream opened at device sample rate=%d",
        stream->getSampleRate()
    );
    return stream;
}

bool recorderStreamClosedDuringStart(
    const std::shared_ptr<oboe::AudioStream>& stream,
    int timeoutMs
) {
    if (stream == nullptr) {
        return true;
    }

    const auto start = std::chrono::steady_clock::now();
    while (std::chrono::steady_clock::now() - start < std::chrono::milliseconds(timeoutMs)) {
        const auto state = stream->getState();
        if (state == oboe::StreamState::Started) {
            return false;
        }
        if (state == oboe::StreamState::Closed || state == oboe::StreamState::Disconnected) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}
