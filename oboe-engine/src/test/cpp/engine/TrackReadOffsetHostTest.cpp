#include "TrackReadOffset.h"

#include <cstdlib>
#include <iostream>
#include <limits>

namespace {
void require(bool value, const char* message) {
    if (!value) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}
}

int main() {
    require(
        trackSourceFrame(1000, 120) == 1120,
        "positive offset advances the appended decoder source anchor"
    );
    require(
        trackSourceFrame(50, -120) == 0,
        "negative offset clamps an appended decoder source anchor to zero"
    );
    require(
        saturatedAdd(std::numeric_limits<int64_t>::max() - 5, 10) ==
            std::numeric_limits<int64_t>::max(),
        "positive coordinate addition saturates instead of overflowing"
    );
    require(
        saturatedMultiply(std::numeric_limits<int64_t>::min(), 1000) ==
            std::numeric_limits<int64_t>::min(),
        "negative millisecond scaling saturates instead of overflowing"
    );
    require(
        saturatedScaleDivide(std::numeric_limits<int64_t>::max(), 48000, 1000) ==
            std::numeric_limits<int64_t>::max(),
        "positive millisecond-to-frame conversion saturates"
    );
    require(
        saturatedScaleDivide(std::numeric_limits<int64_t>::min(), 48000, 1000) ==
            std::numeric_limits<int64_t>::min(),
        "negative millisecond-to-frame conversion saturates"
    );
    require(
        trackSourceFrame(std::numeric_limits<int64_t>::max() - 5, 10) ==
            std::numeric_limits<int64_t>::max(),
        "extreme positive source coordinate clamps to the signed limit"
    );

    const auto advanced = trackReadWindow(1000, 100, 2000, 120);
    require(advanced.leadingSilentFrames == 0, "positive offset does not add leading silence");
    require(advanced.sourceFrames == 100, "positive offset reads advanced source frames");

    const auto beforeSource = trackReadWindow(50, 100, 2000, -120);
    require(beforeSource.leadingSilentFrames == 70, "negative offset fills before source zero");
    require(beforeSource.sourceFrames == 30, "negative offset reads only the in-range source tail");

    const auto afterSource = trackReadWindow(1950, 100, 2000, 120);
    require(afterSource.leadingSilentFrames == 0, "source tail has no leading silence");
    require(afterSource.sourceFrames == 0, "source tail is zero-filled");

    const auto extremeBeforeSource = trackReadWindow(
        0,
        100,
        2000,
        std::numeric_limits<int64_t>::min()
    );
    require(
        extremeBeforeSource.leadingSilentFrames == 100,
        "extreme negative offset fills the requested window with silence"
    );
    require(
        extremeBeforeSource.sourceFrames == 0,
        "extreme negative offset does not read before source zero"
    );

    std::cout << "PASS: Track read offsets advance source and zero-fill boundaries\n";
    return 0;
}
