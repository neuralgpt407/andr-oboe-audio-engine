#include "../../../main/cpp/common/SPSCRingBuffer.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {
void require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}
}

int main() {
    SPSCRingBuffer ring;
    const std::array<int16_t, 64> accepted{};
    require(ring.writeAllOrNothing(accepted.data(), accepted.size()), "accepted callback enters ring");
    int64_t acceptedFrames = static_cast<int64_t>(accepted.size());

    std::array<int16_t, 64> drained{};
    const size_t drainedFrames = ring.read(drained.data(), drained.size());
    require(drainedFrames == accepted.size(), "stop drains every accepted tail frame before close");
    require(acceptedFrames == static_cast<int64_t>(drainedFrames), "accepted and written frames agree");

    std::array<int16_t, SPSCRingBuffer::kCapacity> oversized{};
    require(!ring.writeAllOrNothing(oversized.data(), oversized.size()), "overflow is rejected atomically");
    require(ring.availableToRead() == 0, "rejected overflow publishes no disk or telemetry frames");

    std::cout << "PASS: Recorder ring drain/overflow integrity checks\n";
    return 0;
}
