#pragma once

#include <algorithm>
#include <cstdint>

struct TrackReadWindow {
    int leadingSilentFrames;
    int sourceFrames;
};

inline TrackReadWindow trackReadWindow(
    int64_t timelineFrame,
    int requestedFrames,
    int64_t sourceFrameCount,
    int64_t offsetFrames
) {
    const int64_t sourceStartFrame = timelineFrame + offsetFrames;
    const int leadingSilentFrames = static_cast<int>(std::clamp<int64_t>(
        -sourceStartFrame,
        0,
        requestedFrames
    ));
    const int64_t readableSourceStart = std::max<int64_t>(0, sourceStartFrame);
    const int availableFrames = static_cast<int>(std::clamp<int64_t>(
        sourceFrameCount - readableSourceStart,
        0,
        requestedFrames - leadingSilentFrames
    ));
    return {leadingSilentFrames, availableFrames};
}
