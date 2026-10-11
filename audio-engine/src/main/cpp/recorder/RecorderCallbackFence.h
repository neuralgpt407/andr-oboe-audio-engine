#pragma once

#include <atomic>
#include <cstdint>
#include <thread>

class RecorderCallbackFence {
public:
    uint64_t arm() {
        const uint64_t nextEpoch = epoch_.fetch_add(1, std::memory_order_acq_rel) + 1;
        armed_.store(true, std::memory_order_release);
        return nextEpoch;
    }

    uint64_t epoch() const {
        return epoch_.load(std::memory_order_acquire);
    }

    bool tryEnter(uint64_t observedEpoch) {
        if (!armed_.load(std::memory_order_acquire) ||
            epoch_.load(std::memory_order_acquire) != observedEpoch) {
            return false;
        }
        producers_.fetch_add(1, std::memory_order_acq_rel);
        if (!armed_.load(std::memory_order_acquire) ||
            epoch_.load(std::memory_order_acquire) != observedEpoch) {
            producers_.fetch_sub(1, std::memory_order_acq_rel);
            return false;
        }
        return true;
    }

    void leave() {
        producers_.fetch_sub(1, std::memory_order_acq_rel);
    }

    void disarmAndAwait() {
        disarm();
        while (producers_.load(std::memory_order_acquire) != 0) {
            std::this_thread::yield();
        }
    }

    void disarm() {
        armed_.store(false, std::memory_order_release);
        epoch_.fetch_add(1, std::memory_order_acq_rel);
    }

private:
    std::atomic<bool> armed_{false};
    std::atomic<uint64_t> epoch_{0};
    std::atomic<int> producers_{0};
};
