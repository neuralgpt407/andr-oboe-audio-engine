#pragma once

#include <array>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstring>

class SPSCRingBuffer {
public:
    static constexpr size_t kCapacity = 262144;

    size_t write(const int16_t* src, size_t count) {
        const size_t writable = availableToWrite();
        const size_t toWrite = count < writable ? count : writable;
        if (toWrite == 0) return 0;

        const size_t writeIndex = writeIndex_.load(std::memory_order_relaxed);
        const size_t offset = writeIndex & kMask;
        const size_t first = std::min(toWrite, kCapacity - offset);
        std::memcpy(buffer_.data() + offset, src, first * sizeof(int16_t));
        if (toWrite > first) {
            std::memcpy(buffer_.data(), src + first, (toWrite - first) * sizeof(int16_t));
        }
        writeIndex_.store(writeIndex + toWrite, std::memory_order_release);
        return toWrite;
    }

    bool writeAllOrNothing(const int16_t* src, size_t count) {
        if (count > availableToWrite()) return false;
        return write(src, count) == count;
    }

    size_t read(int16_t* dst, size_t count) {
        const size_t readable = availableToRead();
        const size_t toRead = count < readable ? count : readable;
        if (toRead == 0) return 0;

        const size_t readIndex = readIndex_.load(std::memory_order_relaxed);
        const size_t offset = readIndex & kMask;
        const size_t first = std::min(toRead, kCapacity - offset);
        std::memcpy(dst, buffer_.data() + offset, first * sizeof(int16_t));
        if (toRead > first) {
            std::memcpy(dst + first, buffer_.data(), (toRead - first) * sizeof(int16_t));
        }
        readIndex_.store(readIndex + toRead, std::memory_order_release);
        return toRead;
    }

    size_t availableToRead() const {
        const size_t writeIndex = writeIndex_.load(std::memory_order_acquire);
        const size_t readIndex = readIndex_.load(std::memory_order_acquire);
        return writeIndex - readIndex;
    }

    size_t availableToWrite() const {
        return (kCapacity - 1) - availableToRead();
    }

    void clear() {
        const size_t writeIndex = writeIndex_.load(std::memory_order_acquire);
        readIndex_.store(writeIndex, std::memory_order_release);
    }

private:
    static constexpr size_t kMask = kCapacity - 1;
    std::array<int16_t, kCapacity> buffer_{};
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};
};
