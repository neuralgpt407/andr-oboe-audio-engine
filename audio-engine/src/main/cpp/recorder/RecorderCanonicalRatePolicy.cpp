#include "RecorderCanonicalRatePolicy.h"

RecorderCanonicalRateAction recorderCanonicalRateAction(
    int deviceSampleRate,
    bool converterAvailable
) {
    if (deviceSampleRate <= 0) {
        return RecorderCanonicalRateAction::Reject;
    }
    if (deviceSampleRate == kRecorderCanonicalSampleRate) {
        return RecorderCanonicalRateAction::Direct;
    }
    return converterAvailable
        ? RecorderCanonicalRateAction::Convert
        : RecorderCanonicalRateAction::Reject;
}
