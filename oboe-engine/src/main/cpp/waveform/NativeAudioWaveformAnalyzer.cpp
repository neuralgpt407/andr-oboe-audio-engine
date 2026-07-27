#include "NativeAudioWaveformAnalyzer.h"

#include "NativeAudioDecoder.h"
#include "WaveformAnalysisCore.h"

#include <unistd.h>

namespace neuralsound::audio::waveform {
namespace {

enum class DecoderPhase {
    Initialization,
    Decoding,
};

NativeWaveformFailure mapDecoderFailure(
    NativeAudioDecoderFailure failure,
    DecoderPhase phase
) {
    switch (failure) {
        case NativeAudioDecoderFailure::SourceUnavailable:
            // The analyzer already duplicated the caller's descriptor. A
            // later extractor failure therefore identifies invalid media.
            return NativeWaveformFailure::Corrupt;
        case NativeAudioDecoderFailure::InvalidFormat:
        case NativeAudioDecoderFailure::ResamplerFailure:
            return NativeWaveformFailure::Unsupported;
        case NativeAudioDecoderFailure::DecoderFailure:
            return phase == DecoderPhase::Initialization
                ? NativeWaveformFailure::Unsupported
                : NativeWaveformFailure::Corrupt;
        case NativeAudioDecoderFailure::None:
            return NativeWaveformFailure::Corrupt;
    }
    return NativeWaveformFailure::Corrupt;
}

} // namespace

NativeAudioWaveformAnalyzer::NativeAudioWaveformAnalyzer(
    int sourceFd,
    int maxOutputSamples
) : maxOutputSamples_(maxOutputSamples) {
    if (maxOutputSamples <= 0 || maxOutputSamples > kMaximumOutputSamples) {
        setFailure(NativeWaveformFailure::InvalidArgument);
        return;
    }

    sourceFd_ = dup(sourceFd);
    if (sourceFd_ < 0) {
        setFailure(NativeWaveformFailure::Unreadable);
    }
}

NativeAudioWaveformAnalyzer::~NativeAudioWaveformAnalyzer() {
    cancel();
    const std::lock_guard<std::mutex> lock(analysisMutex_);
    closeSourceFd();
}

std::vector<float> NativeAudioWaveformAnalyzer::analyze() {
    const std::lock_guard<std::mutex> lock(analysisMutex_);
    if (cancelled_.load(std::memory_order_acquire)) {
        setFailure(NativeWaveformFailure::Cancelled);
        closeSourceFd();
        return {};
    }
    if (getFailure() != NativeWaveformFailure::None) {
        closeSourceFd();
        return {};
    }
    if (sourceFd_ < 0) {
        setFailure(NativeWaveformFailure::Unreadable);
        return {};
    }

    NativeAudioDecoder decoder;
    const bool initialized = decoder.initialize(sourceFd_);
    closeSourceFd();
    if (!initialized) {
        setFailure(mapDecoderFailure(
            decoder.getFailureKind(),
            DecoderPhase::Initialization
        ));
        return {};
    }

    const WaveformCoreResult result = analyzeWaveformChunks(
        decoder.getSampleRate(),
        maxOutputSamples_,
        cancelled_,
        [&decoder](int16_t* output, int maxSamples) {
            const int decoded = decoder.decode(output, maxSamples);
            if (decoded > 0) {
                return DecodePcm16ChunkResult{
                    DecodedPcm16Samples{static_cast<size_t>(decoded)}
                };
            }
            if (decoded == 0) {
                return DecodePcm16ChunkResult{DecodePcm16Idle{}};
            }
            if (decoder.getFailureKind() != NativeAudioDecoderFailure::None) {
                return DecodePcm16ChunkResult{DecodePcm16Failure{}};
            }
            return DecodePcm16ChunkResult{DecodePcm16EndOfStream{}};
        },
        decoder.getDurationMs()
    );
    decoder.release();

    switch (result.status) {
        case WaveformCoreStatus::Success:
            setFailure(NativeWaveformFailure::None);
            return result.levels;
        case WaveformCoreStatus::Cancelled:
            setFailure(NativeWaveformFailure::Cancelled);
            return {};
        case WaveformCoreStatus::EmptyMedia:
            setFailure(NativeWaveformFailure::EmptyMedia);
            return {};
        case WaveformCoreStatus::DecodeFailed:
            setFailure(mapDecoderFailure(
                decoder.getFailureKind(),
                DecoderPhase::Decoding
            ));
            return {};
    }

    setFailure(NativeWaveformFailure::Corrupt);
    return {};
}

void NativeAudioWaveformAnalyzer::cancel() {
    cancelled_.store(true, std::memory_order_release);
}

NativeWaveformFailure NativeAudioWaveformAnalyzer::getFailure() const {
    return failure_.load(std::memory_order_acquire);
}

void NativeAudioWaveformAnalyzer::closeSourceFd() {
    if (sourceFd_ >= 0) {
        close(sourceFd_);
        sourceFd_ = -1;
    }
}

void NativeAudioWaveformAnalyzer::setFailure(NativeWaveformFailure failure) {
    failure_.store(failure, std::memory_order_release);
}

} // namespace neuralsound::audio::waveform
