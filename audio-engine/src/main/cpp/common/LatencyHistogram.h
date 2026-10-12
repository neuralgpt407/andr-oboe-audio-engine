#pragma once

#include <array>
#include <atomic>
#include <cstdint>

namespace neuralsound::audio {

/**
 * Lock-free, allocation-free histogram for real-time timing (FEAT-0001 NFR-1/NFR-2).
 * record() is safe on the audio thread; percentile() and reset() run on other threads.
 * Values are bucketed linearly by [bucketWidth] with a final overflow bucket.
 */
template <int kBuckets>
class LatencyHistogram {
public:
    explicit LatencyHistogram(int64_t bucketWidth) : bucketWidth_(bucketWidth > 0 ? bucketWidth : 1) {}

    void record(int64_t value) noexcept {
        if (value < 0) value = 0;
        int64_t index = value / bucketWidth_;
        if (index >= kBuckets) index = kBuckets - 1;
        counts_[static_cast<size_t>(index)].fetch_add(1, std::memory_order_relaxed);
        int64_t previous = max_.load(std::memory_order_relaxed);
        while (value > previous && !max_.compare_exchange_weak(previous, value, std::memory_order_relaxed)) {
        }
    }

    int64_t max() const noexcept { return max_.load(std::memory_order_relaxed); }

    int64_t count() const noexcept {
        int64_t total = 0;
        for (const auto& bucket : counts_) total += bucket.load(std::memory_order_relaxed);
        return total;
    }

    /** Upper edge of the bucket containing the [fraction] quantile (e.g. 0.99), or 0 when empty. */
    int64_t percentile(double fraction) const noexcept {
        const int64_t total = count();
        if (total == 0) return 0;
        const auto target = static_cast<int64_t>(fraction * static_cast<double>(total) + 0.5);
        int64_t seen = 0;
        for (int i = 0; i < kBuckets; ++i) {
            seen += counts_[static_cast<size_t>(i)].load(std::memory_order_relaxed);
            if (seen >= target) return (i + 1) * bucketWidth_;
        }
        return kBuckets * bucketWidth_;
    }

    void reset() noexcept {
        for (auto& bucket : counts_) bucket.store(0, std::memory_order_relaxed);
        max_.store(0, std::memory_order_relaxed);
    }

private:
    const int64_t bucketWidth_;
    std::array<std::atomic<int64_t>, kBuckets> counts_{};
    std::atomic<int64_t> max_{0};
};

} // namespace neuralsound::audio
