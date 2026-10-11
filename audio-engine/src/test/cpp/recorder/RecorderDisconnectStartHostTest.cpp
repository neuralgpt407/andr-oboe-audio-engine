#include "../../../main/cpp/recorder/RecorderMicSessionCoordinator.h"

#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <mutex>
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
    auto failureState = std::make_shared<RecorderFailureState>();
    auto callbackFence = std::make_shared<RecorderCallbackFence>();
    auto coordinator = std::make_shared<RecorderMicSessionCoordinator>(
        failureState,
        callbackFence
    );

    const auto firstSession = coordinator->beginOpening();
    require(coordinator->activate(firstSession), "first microphone session activates");

    // Coordinate the exact cross-thread interleaving: take initialization
    // observes an active stream, then an error callback closes that stream
    // before the take can reset failures or arm audio callbacks.
    std::mutex interleavingMutex;
    std::condition_variable interleavingChanged;
    bool takeObservedSession = false;
    bool disconnectPublished = false;
    RecorderMicSessionCoordinator::SessionToken observedSession =
        RecorderMicSessionCoordinator::kNoSession;
    bool closeAccepted = false;
    bool resetAccepted = true;
    bool armAccepted = true;

    std::thread takeInitializer([&]() {
        observedSession = coordinator->activeToken();
        {
            std::unique_lock<std::mutex> lock(interleavingMutex);
            takeObservedSession = true;
            interleavingChanged.notify_all();
            interleavingChanged.wait(lock, [&]() {
                return disconnectPublished;
            });
        }
        resetAccepted = coordinator->resetFailureForTake(observedSession);
        armAccepted = coordinator->armTakeIfActive(observedSession);
    });
    std::thread errorCallback([&]() {
        {
            std::unique_lock<std::mutex> lock(interleavingMutex);
            interleavingChanged.wait(lock, [&]() {
                return takeObservedSession;
            });
        }
        closeAccepted = coordinator->closeStream(
            firstSession,
            OboeRecorderErrorCode::StreamDisconnected,
            "recorder input stream disconnected"
        );
        {
            std::lock_guard<std::mutex> lock(interleavingMutex);
            disconnectPublished = true;
        }
        interleavingChanged.notify_all();
    });

    takeInitializer.join();
    errorCallback.join();

    require(observedSession == firstSession, "take observes the active microphone session");
    require(closeAccepted, "current stream disconnect is accepted");
    require(
        !resetAccepted,
        "take cannot reset failure after its observed stream disconnects"
    );
    require(
        !armAccepted,
        "take cannot arm callbacks after its observed stream disconnects"
    );
    const auto disconnected = failureState->snapshot();
    require(
        disconnected.errorCode == OboeRecorderErrorCode::StreamDisconnected,
        "typed disconnect remains observable"
    );

    // A callback retained by the replaced stream carries the old token and
    // must not poison or disarm the replacement session.
    failureState->reset();
    const auto replacementSession = coordinator->beginOpening();
    require(
        coordinator->activate(replacementSession),
        "replacement microphone session activates"
    );
    require(
        !coordinator->closeStream(
            firstSession,
            OboeRecorderErrorCode::StreamDisconnected,
            "stale disconnect"
        ),
        "stale stream disconnect is ignored"
    );
    require(!failureState->hasFailed(), "stale disconnect does not publish failure");
    require(
        coordinator->resetFailureForTake(replacementSession),
        "replacement take may reset older failure state"
    );
    require(
        coordinator->armTakeIfActive(replacementSession),
        "replacement take arms callbacks"
    );
    const uint64_t replacementCallbackEpoch = callbackFence->epoch();
    require(
        callbackFence->tryEnter(replacementCallbackEpoch),
        "replacement callback epoch accepts producers"
    );
    callbackFence->leave();

    require(
        coordinator->closeStream(
            replacementSession,
            OboeRecorderErrorCode::StreamDisconnected,
            "replacement disconnected"
        ),
        "replacement disconnect is accepted"
    );
    require(
        !callbackFence->tryEnter(replacementCallbackEpoch),
        "current disconnect disarms the writer callback epoch"
    );
    require(!coordinator->isActive(), "current disconnect marks the microphone inactive");

    std::cout << "PASS: Recorder disconnect/start transactions preserve current failures "
                 "and ignore stale callbacks\n";
    return 0;
}
