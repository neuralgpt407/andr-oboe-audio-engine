#include "../../../main/cpp/recorder/RecorderCallbackFence.h"

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
}

int main() {
    RecorderCallbackFence fence;
    const uint64_t staleEpoch = fence.arm();
    std::atomic<bool> staleCallbackHasObservedEpoch{false};
    std::atomic<bool> letStaleCallbackEnter{false};
    std::atomic<int> writes{0};

    std::thread staleCallback([&] {
        staleCallbackHasObservedEpoch.store(true, std::memory_order_release);
        while (!letStaleCallbackEnter.load(std::memory_order_acquire)) {
            std::this_thread::yield();
        }
        if (fence.tryEnter(staleEpoch)) {
            ++writes;
            fence.leave();
        }
    });

    while (!staleCallbackHasObservedEpoch.load(std::memory_order_acquire)) {
        std::this_thread::yield();
    }
    fence.disarmAndAwait();
    const uint64_t freshEpoch = fence.arm();
    letStaleCallbackEnter.store(true, std::memory_order_release);
    staleCallback.join();

    require(writes.load() == 0, "stale onAudioReady epoch cannot write after take reset");
    require(fence.tryEnter(freshEpoch), "new callback epoch remains writable");
    fence.leave();
    std::cout << "PASS: Recorder stale-callback epoch fence race\n";
    return 0;
}
