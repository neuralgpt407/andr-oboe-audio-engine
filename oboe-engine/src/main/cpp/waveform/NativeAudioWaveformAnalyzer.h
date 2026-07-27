#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace neuralsound::audio::waveform {

enum class NativeWaveformFailure : int32_t {
    None = 0,
    Unreadable = 1,
    Unsupported = 2,
    Corrupt = 3,
    EmptyMedia = 4,
    Cancelled = 5,
    InvalidArgument = 6,
};

class NativeAudioWaveformAnalyzer {
public:
    static constexpr int kMaximumOutputSamples = 1024;

    NativeAudioWaveformAnalyzer(int sourceFd, int maxOutputSamples);
    ~NativeAudioWaveformAnalyzer();

    NativeAudioWaveformAnalyzer(const NativeAudioWaveformAnalyzer&) = delete;
    NativeAudioWaveformAnalyzer& operator=(const NativeAudioWaveformAnalyzer&) = delete;

    std::vector<float> analyze();
    void cancel();
    NativeWaveformFailure getFailure() const;

private:
    void closeSourceFd();
    void setFailure(NativeWaveformFailure failure);

    int sourceFd_ = -1;
    int maxOutputSamples_;
    std::atomic<bool> cancelled_{false};
    std::atomic<NativeWaveformFailure> failure_{NativeWaveformFailure::None};
    std::mutex analysisMutex_;
};

} // namespace neuralsound::audio::waveform
