#include "../../../main/cpp/recorder/RecorderFailureState.h"

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

namespace {

void require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

void requireCoherentSnapshot(const OboeRecorderFailureSnapshot& snapshot) {
    const bool overflow =
        snapshot.errorCode == OboeRecorderErrorCode::WriterOverflow &&
        snapshot.message == "recorder ring buffer overflow";
    const bool fileFailure =
        snapshot.errorCode == OboeRecorderErrorCode::WriterFileError &&
        snapshot.message == "disk write failed";
    require(overflow || fileFailure, "failure code and message form one coherent pair");
}

}

int main() {
    RecorderFailureState state;
    require(!state.hasFailed(), "new failure state starts clear");

    require(
        state.publish(
            OboeRecorderErrorCode::WriterFileError,
            "disk write failed"
        ),
        "file failure is accepted"
    );
    require(state.hasFailed(), "accepted file failure becomes observable");
    requireCoherentSnapshot(state.snapshot());
    require(
        !state.publishRealtimeOverflow(),
        "later realtime overflow cannot overwrite the first failure"
    );
    requireCoherentSnapshot(state.snapshot());

    for (int iteration = 0; iteration < 2'000; ++iteration) {
        state.reset();
        std::atomic<bool> start{false};
        std::thread filePublisher([&]() {
            while (!start.load(std::memory_order_acquire)) {
                std::this_thread::yield();
            }
            state.publish(
                OboeRecorderErrorCode::WriterFileError,
                "disk write failed"
            );
        });
        std::thread overflowPublisher([&]() {
            while (!start.load(std::memory_order_acquire)) {
                std::this_thread::yield();
            }
            state.publishRealtimeOverflow();
        });
        start.store(true, std::memory_order_release);
        filePublisher.join();
        overflowPublisher.join();

        require(state.hasFailed(), "one racing publisher wins");
        requireCoherentSnapshot(state.snapshot());
    }

    state.reset();
    require(state.publishRealtimeOverflow(), "realtime overflow is accepted after reset");
    requireCoherentSnapshot(state.snapshot());

    std::cout << "PASS: RecorderFailureState coherent first-failure snapshots\n";
    return 0;
}
