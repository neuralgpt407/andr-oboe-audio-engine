#include "TrackReadOffset.h"

#include <cstdlib>
#include <iostream>

namespace {
void require(bool value, const char* message) {
    if (!value) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}
}

int main() {
    const auto advanced = trackReadWindow(1000, 100, 2000, 120);
    require(advanced.leadingSilentFrames == 0, "positive offset does not add leading silence");
    require(advanced.sourceFrames == 100, "positive offset reads advanced source frames");

    const auto beforeSource = trackReadWindow(50, 100, 2000, -120);
    require(beforeSource.leadingSilentFrames == 70, "negative offset fills before source zero");
    require(beforeSource.sourceFrames == 30, "negative offset reads only the in-range source tail");

    const auto afterSource = trackReadWindow(1950, 100, 2000, 120);
    require(afterSource.leadingSilentFrames == 0, "source tail has no leading silence");
    require(afterSource.sourceFrames == 0, "source tail is zero-filled");

    std::cout << "PASS: Track read offsets advance source and zero-fill boundaries\n";
    return 0;
}
