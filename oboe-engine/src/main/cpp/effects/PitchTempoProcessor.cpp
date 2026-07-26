#include "PitchTempoProcessor.h"

#include <algorithm>
#include <cmath>
#include <cstring>

void PitchTempoProcessor::prepare(int sampleRate, int channels, int maxOutputFrames) {
    sampleRate_ = std::max(1, sampleRate);
    channels_ = std::max(1, channels);
    const int boundedMaxOutputFrames = std::max(1, maxOutputFrames);
    maxInputFrames_ = static_cast<int>(std::ceil(boundedMaxOutputFrames * kMaxTempo)) + 8;
    maxProcessedFrames_ = static_cast<int>(std::ceil(maxInputFrames_ / kMinTempo)) + 8;

    inputPlanar_.assign(
        static_cast<size_t>(channels_),
        std::vector<float>(static_cast<size_t>(maxInputFrames_), 0.0f)
    );
    outputPlanar_.assign(
        static_cast<size_t>(channels_),
        std::vector<float>(static_cast<size_t>(maxProcessedFrames_), 0.0f)
    );
    processedInterleaved_.assign(
        static_cast<size_t>(maxProcessedFrames_) * channels_,
        0.0f
    );
    fifo_.prepare(channels_, maxProcessedFrames_ * 2);

    stretch_.presetCheaper(channels_, static_cast<float>(sampleRate_), true);
    prepared_ = true;
    reset();
}

void PitchTempoProcessor::reset() {
    if (!prepared_) return;
    fifo_.clear();
    outputFrameRemainder_ = 0.0;
    appliedTempoSpeed_ = tempoSpeed_.load(std::memory_order_relaxed);
    appliedPitchSemitones_ = pitchSemitones_.load(std::memory_order_relaxed);
    stretch_.reset();
    stretch_.setTransposeSemitones(static_cast<float>(appliedPitchSemitones_));
}

void PitchTempoProcessor::setTempo(float tempoSpeed) {
    tempoSpeed_.store(
        std::clamp(tempoSpeed, kMinTempo, kMaxTempo),
        std::memory_order_release
    );
}

void PitchTempoProcessor::setPitchSemitones(int semitones) {
    pitchSemitones_.store(
        std::clamp(semitones, kMinPitch, kMaxPitch),
        std::memory_order_release
    );
}

bool PitchTempoProcessor::isBypassed() const {
    const float tempo = tempoSpeed_.load(std::memory_order_acquire);
    const int pitch = pitchSemitones_.load(std::memory_order_acquire);
    return nearlyDefaultTempo(tempo) && pitch == kDefaultPitch && fifo_.availableFrames() == 0;
}

bool PitchTempoProcessor::hasPendingOutput() const {
    return fifo_.availableFrames() > 0;
}

float PitchTempoProcessor::currentTempo() const {
    return tempoSpeed_.load(std::memory_order_acquire);
}

int PitchTempoProcessor::inputFramesNeeded(int outputFrames) {
    if (!prepared_ || outputFrames <= 0) return 0;
    applyPendingParameters();
    if (nearlyDefaultTempo(appliedTempoSpeed_) &&
        appliedPitchSemitones_ == kDefaultPitch &&
        fifo_.availableFrames() == 0) {
        return std::min(outputFrames, maxInputFrames_);
    }
    if (fifo_.availableFrames() >= outputFrames) return 0;

    const float requiredTempo = std::max(kDefaultTempo, appliedTempoSpeed_);
    const int needed = static_cast<int>(std::ceil(outputFrames * requiredTempo));
    return std::clamp(needed, 0, maxInputFrames_);
}

int PitchTempoProcessor::process(
    const float* inputInterleaved,
    int inputFrames,
    float* outputInterleaved,
    int outputFrames
) {
    if (outputInterleaved == nullptr || outputFrames <= 0 || channels_ <= 0) return 0;

    const int outputSamples = outputFrames * channels_;
    std::memset(outputInterleaved, 0, static_cast<size_t>(outputSamples) * sizeof(float));

    if (!prepared_) {
        return 0;
    }

    applyPendingParameters();

    const bool bypass = nearlyDefaultTempo(appliedTempoSpeed_) &&
        appliedPitchSemitones_ == kDefaultPitch &&
        fifo_.availableFrames() == 0;
    if (bypass) {
        if (inputInterleaved == nullptr || inputFrames <= 0) return 0;
        const int copyFrames = std::min(inputFrames, outputFrames);
        std::memcpy(
            outputInterleaved,
            inputInterleaved,
            static_cast<size_t>(copyFrames) * channels_ * sizeof(float)
        );
        return copyFrames;
    }

    const int boundedInputFrames = std::clamp(inputFrames, 0, maxInputFrames_);
    if (inputInterleaved != nullptr && boundedInputFrames > 0) {
        const int processedFrames = calculateOutputFramesForInput(boundedInputFrames);
        if (processedFrames > 0) {
            deinterleave(inputInterleaved, boundedInputFrames);
            stretch_.process(inputPlanar_, boundedInputFrames, outputPlanar_, processedFrames);
            interleave(processedInterleaved_.data(), processedFrames);
            fifo_.write(processedInterleaved_.data(), processedFrames);
        }
    }

    const int framesRead = fifo_.read(outputInterleaved, outputFrames);
    if (framesRead < outputFrames) {
        const int remainingSamples = (outputFrames - framesRead) * channels_;
        std::memset(
            outputInterleaved + static_cast<size_t>(framesRead) * channels_,
            0,
            static_cast<size_t>(remainingSamples) * sizeof(float)
        );
    }
    return framesRead;
}

void PitchTempoProcessor::applyPendingParameters() {
    const float nextTempo = tempoSpeed_.load(std::memory_order_acquire);
    const int nextPitch = pitchSemitones_.load(std::memory_order_acquire);
    if (std::abs(nextTempo - appliedTempoSpeed_) < 0.0001f &&
        nextPitch == appliedPitchSemitones_) {
        return;
    }

    const bool tempoChanged = std::abs(nextTempo - appliedTempoSpeed_) >= 0.0001f;
    appliedTempoSpeed_ = nextTempo;
    appliedPitchSemitones_ = nextPitch;
    if (tempoChanged) {
        fifo_.clear();
        outputFrameRemainder_ = 0.0;
    }
    stretch_.setTransposeSemitones(static_cast<float>(appliedPitchSemitones_));
}

int PitchTempoProcessor::calculateOutputFramesForInput(int inputFrames) {
    const double exactFrames =
        static_cast<double>(inputFrames) / static_cast<double>(std::max(kMinTempo, appliedTempoSpeed_)) +
        outputFrameRemainder_;
    const int outputFrames = std::clamp(
        static_cast<int>(std::floor(exactFrames)),
        0,
        maxProcessedFrames_
    );
    outputFrameRemainder_ = exactFrames - outputFrames;
    return outputFrames;
}

void PitchTempoProcessor::deinterleave(const float* inputInterleaved, int frames) {
    const int boundedFrames = std::min(frames, maxInputFrames_);
    for (int channel = 0; channel < channels_; ++channel) {
        auto& channelBuffer = inputPlanar_[static_cast<size_t>(channel)];
        for (int frame = 0; frame < boundedFrames; ++frame) {
            channelBuffer[static_cast<size_t>(frame)] =
                inputInterleaved[static_cast<size_t>(frame) * channels_ + channel];
        }
    }
}

void PitchTempoProcessor::interleave(float* outputInterleaved, int frames) {
    const int boundedFrames = std::min(frames, maxProcessedFrames_);
    for (int frame = 0; frame < boundedFrames; ++frame) {
        for (int channel = 0; channel < channels_; ++channel) {
            outputInterleaved[static_cast<size_t>(frame) * channels_ + channel] =
                outputPlanar_[static_cast<size_t>(channel)][static_cast<size_t>(frame)];
        }
    }
}

bool PitchTempoProcessor::nearlyDefaultTempo(float value) {
    return std::abs(value - kDefaultTempo) < 0.0001f;
}
