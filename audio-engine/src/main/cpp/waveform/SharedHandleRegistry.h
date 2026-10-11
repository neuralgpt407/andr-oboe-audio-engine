#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace neuralsound::audio::waveform {

// JNI handles are registry keys rather than object addresses. Operations borrow
// shared ownership so a release cannot destroy an in-flight analyzer call.
template <typename T>
class SharedHandleRegistry {
public:
    uint64_t insert(std::shared_ptr<T> value) {
        if (!value) {
            return 0;
        }
        const std::lock_guard<std::mutex> lock(mutex_);
        uint64_t handle = nextHandle_++;
        if (handle == 0) {
            handle = nextHandle_++;
        }
        values_[handle] = std::move(value);
        return handle;
    }

    std::shared_ptr<T> acquire(uint64_t handle) const {
        if (handle == 0) {
            return {};
        }
        const std::lock_guard<std::mutex> lock(mutex_);
        const auto found = values_.find(handle);
        return found == values_.end() ? std::shared_ptr<T>{} : found->second;
    }

    std::shared_ptr<T> remove(uint64_t handle) {
        if (handle == 0) {
            return {};
        }
        const std::lock_guard<std::mutex> lock(mutex_);
        const auto found = values_.find(handle);
        if (found == values_.end()) {
            return {};
        }
        std::shared_ptr<T> removed = std::move(found->second);
        values_.erase(found);
        return removed;
    }

private:
    mutable std::mutex mutex_;
    uint64_t nextHandle_ = 1;
    std::unordered_map<uint64_t, std::shared_ptr<T>> values_;
};

} // namespace neuralsound::audio::waveform
