#include "RenderLifecycleCoordinator.h"

#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using neuralsound::audio::engine::RenderLifecycleCoordinator;

namespace {

void require(bool value, const char* message) {
    if (!value) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

template <typename TryAcquire>
bool canAcquireOnAnotherThread(TryAcquire tryAcquire) {
    bool acquired = true;
    std::thread contender([&]() {
        auto guard = tryAcquire();
        acquired = guard.owns_lock();
    });
    contender.join();
    return acquired;
}

void testAppendStopsPublishesAndRestartsOnlyForActiveRendering() {
    RenderLifecycleCoordinator coordinator;
    std::vector<std::string> events;

    const bool appended = coordinator.append(
        [&]() {
            events.emplace_back("sample");
            require(
                !canAcquireOnAnotherThread([&]() {
                    return coordinator.tryAcquireForPlay();
                }),
                "play cannot enter between append intent sampling and publication"
            );
            return true;
        },
        [&events]() { events.emplace_back("stop"); },
        [&]() {
            require(
                !canAcquireOnAnotherThread([&]() {
                    return coordinator.tryAcquireForPlay();
                }),
                "play remains excluded while append publishes the decoder"
            );
            events.emplace_back("publish");
            return true;
        },
        [&events]() { events.emplace_back("restart"); }
    );
    require(appended, "successful append result is preserved");
    require(events == std::vector<std::string>({"sample", "stop", "publish", "restart"}),
            "active rendering is stopped before publication and restarted after it");

    events.clear();
    const bool pausedAppend = coordinator.append(
        [&events]() {
            events.emplace_back("sample");
            return false;
        },
        [&events]() { events.emplace_back("stop"); },
        [&events]() {
            events.emplace_back("publish");
            return true;
        },
        [&events]() { events.emplace_back("restart"); }
    );
    require(pausedAppend && events == std::vector<std::string>({"sample", "publish"}),
            "paused append publishes without creating playback intent");

    events.clear();
    const bool failedAppend = coordinator.append(
        [&events]() {
            events.emplace_back("sample");
            return true;
        },
        [&events]() { events.emplace_back("stop"); },
        [&events]() {
            events.emplace_back("reject");
            return false;
        },
        [&events]() { events.emplace_back("restart"); }
    );
    require(!failedAppend, "failed publication remains a failed append");
    require(events == std::vector<std::string>({"sample", "stop", "reject", "restart"}),
            "failed append still restores the pre-existing render worker");
}

void testPlayPauseSeekAndRestartShareOneLifecycleGate() {
    RenderLifecycleCoordinator coordinator;
    auto restartGuard = coordinator.acquireForRestart();
    require(
        !canAcquireOnAnotherThread([&]() {
            return coordinator.tryAcquireForPause();
        }),
        "pause cannot enter while route restart owns the lifecycle gate"
    );
    require(
        !canAcquireOnAnotherThread([&]() {
            return coordinator.tryAcquireForSeek();
        }),
        "seek cannot enter while route restart owns the lifecycle gate"
    );
    require(
        !canAcquireOnAnotherThread([&]() {
            return coordinator.tryAcquireForPlay();
        }),
        "play cannot enter while route restart owns the lifecycle gate"
    );
    restartGuard.unlock();

    require(
        canAcquireOnAnotherThread([&]() {
            return coordinator.tryAcquireForPause();
        }),
        "pause proceeds once route restart releases the lifecycle gate"
    );
}

void testLatestPauseOrRestartOrderWinsDeterministically() {
    RenderLifecycleCoordinator coordinator;
    bool playbackIntentActive = true;
    bool renderWorkerRunning = false;
    bool streamStarted = false;

    {
        auto pauseGuard = coordinator.acquireForPause();
        playbackIntentActive = false;
        renderWorkerRunning = false;
        streamStarted = false;
    }
    {
        auto restartGuard = coordinator.acquireForRestart();
        if (playbackIntentActive) {
            renderWorkerRunning = true;
            streamStarted = true;
        }
    }
    require(!renderWorkerRunning && !streamStarted,
            "restart after pause observes paused intent and stays stopped");

    playbackIntentActive = true;
    {
        auto restartGuard = coordinator.acquireForRestart();
        renderWorkerRunning = playbackIntentActive;
        streamStarted = playbackIntentActive;
    }
    {
        auto pauseGuard = coordinator.acquireForPause();
        playbackIntentActive = false;
        renderWorkerRunning = false;
        streamStarted = false;
    }
    require(!playbackIntentActive && !renderWorkerRunning && !streamStarted,
            "pause after restart stops both worker and stream");
}

} // namespace

int main() {
    testAppendStopsPublishesAndRestartsOnlyForActiveRendering();
    testPlayPauseSeekAndRestartShareOneLifecycleGate();
    testLatestPauseOrRestartOrderWinsDeterministically();
    std::cout << "PASS: Render lifecycle coordinator host contract\n";
    return 0;
}
