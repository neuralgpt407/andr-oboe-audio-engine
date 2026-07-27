#pragma once

#include "DecodedAudioNormalizer.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <atomic>
#include <cstdint>
#include <vector>

enum class NativeAudioDecoderFailure : int32_t {
    None = 0,
    SourceUnavailable = 1,
    InvalidFormat = 2,
    DecoderFailure = 3,
    ResamplerFailure = 4,
};

struct NativeAudioFailureSnapshot {
    NativeAudioDecoderFailure kind = NativeAudioDecoderFailure::None;
    int32_t trackIndex = -1;

    constexpr int64_t encode() const {
        return static_cast<int64_t>(
            (static_cast<uint64_t>(static_cast<uint32_t>(kind)) << 32) |
            static_cast<uint32_t>(trackIndex)
        );
    }

    static constexpr NativeAudioFailureSnapshot decode(int64_t encoded) {
        return {
            static_cast<NativeAudioDecoderFailure>(
                static_cast<uint32_t>(static_cast<uint64_t>(encoded) >> 32)
            ),
            static_cast<int32_t>(static_cast<uint32_t>(encoded)),
        };
    }
};

class NativeAudioDecoder {
public:
    NativeAudioDecoder() = default;
    ~NativeAudioDecoder();

    NativeAudioDecoder(const NativeAudioDecoder&) = delete;
    NativeAudioDecoder& operator=(const NativeAudioDecoder&) = delete;

    bool initialize(int fd);
    int decode(int16_t* output, int maxSamples);
    bool seekTo(int64_t ms);
    bool seekToUs(int64_t us);
    void release();

    int getSampleRate() const {
        return neuralsound::audio::DecodedAudioNormalizer::kOutputSampleRate;
    }
    int getDurationMs() const { return durationMs_; }
    NativeAudioDecoderFailure getFailureKind() const {
        return failureKind_.load(std::memory_order_acquire);
    }

private:
    bool feedInput();
    int drainOutput(int16_t* output, int maxSamples);
    void clearPending();
    bool configureOutputFormat(int sampleRate, int channelCount);

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    int dupFd_ = -1;
    int sourceSampleRate_ = 0;
    int sourceChannelCount_ = 0;
    int64_t durationMs_ = 0;
    int64_t trimBeforeUs_ = -1;
    bool isEndOfStream_ = false;
    bool inputDone_ = false;
    std::atomic<NativeAudioDecoderFailure> failureKind_{
        NativeAudioDecoderFailure::None
    };
    neuralsound::audio::DecodedAudioNormalizer outputNormalizer_;
    std::vector<int16_t> pendingBuffer_;
    size_t pendingOffset_ = 0;
};
