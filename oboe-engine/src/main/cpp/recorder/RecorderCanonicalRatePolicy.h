#pragma once

constexpr int kRecorderCanonicalSampleRate = 44'100;

enum class RecorderCanonicalRateAction {
    Reject,
    Direct,
    Convert,
};

RecorderCanonicalRateAction recorderCanonicalRateAction(
    int deviceSampleRate,
    bool converterAvailable
);
