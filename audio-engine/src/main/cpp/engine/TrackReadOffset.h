#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>

struct TrackReadWindow {
    int leadingSilentFrames;
    int sourceFrames;
};

inline int64_t saturatedAdd(int64_t left, int64_t right) {
    if (right > 0 && left > std::numeric_limits<int64_t>::max() - right) {
        return std::numeric_limits<int64_t>::max();
    }
    if (right < 0 && left < std::numeric_limits<int64_t>::min() - right) {
        return std::numeric_limits<int64_t>::min();
    }
    return left + right;
}

inline int64_t saturatedMultiply(int64_t left, int64_t right) {
    if (left == 0 || right == 0) return 0;
    if (left == -1) {
        return right == std::numeric_limits<int64_t>::min()
            ? std::numeric_limits<int64_t>::max()
            : -right;
    }
    if (right == -1) {
        return left == std::numeric_limits<int64_t>::min()
            ? std::numeric_limits<int64_t>::max()
            : -left;
    }
    if (left > 0) {
        if (right > 0 && left > std::numeric_limits<int64_t>::max() / right) {
            return std::numeric_limits<int64_t>::max();
        }
        if (right < 0 && right < std::numeric_limits<int64_t>::min() / left) {
            return std::numeric_limits<int64_t>::min();
        }
    } else {
        if (right > 0 && left < std::numeric_limits<int64_t>::min() / right) {
            return std::numeric_limits<int64_t>::min();
        }
        if (right < 0 && left < std::numeric_limits<int64_t>::max() / right) {
            return std::numeric_limits<int64_t>::max();
        }
    }
    return left * right;
}

inline int64_t saturatedMillisecondsToMicroseconds(int64_t milliseconds) {
    return saturatedMultiply(milliseconds, 1000);
}

inline int64_t saturatedScaleDivide(
    int64_t value,
    int64_t multiplier,
    int64_t divisor
) {
    const int64_t whole = saturatedMultiply(value / divisor, multiplier);
    const int64_t remainder = saturatedMultiply(value % divisor, multiplier) / divisor;
    return saturatedAdd(whole, remainder);
}

inline int64_t trackSourceFrame(int64_t timelineFrame, int64_t offsetFrames) {
    return std::max<int64_t>(0, saturatedAdd(timelineFrame, offsetFrames));
}

inline TrackReadWindow trackReadWindow(
    int64_t timelineFrame,
    int requestedFrames,
    int64_t sourceFrameCount,
    int64_t offsetFrames
) {
    const int64_t sourceStartFrame = saturatedAdd(timelineFrame, offsetFrames);
    const int64_t framesBeforeSource = sourceStartFrame < 0
        ? saturatedMultiply(sourceStartFrame, -1)
        : 0;
    const int leadingSilentFrames = static_cast<int>(std::clamp<int64_t>(
        framesBeforeSource,
        0,
        requestedFrames
    ));
    const int64_t readableSourceStart = trackSourceFrame(timelineFrame, offsetFrames);
    const int availableFrames = static_cast<int>(std::clamp<int64_t>(
        sourceFrameCount - readableSourceStart,
        0,
        requestedFrames - leadingSilentFrames
    ));
    return {leadingSilentFrames, availableFrames};
}
