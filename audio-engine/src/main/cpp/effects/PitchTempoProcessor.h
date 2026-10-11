#pragma once

#include "AudioFifo.h"
#include "signalsmith-stretch.h"

#include <atomic>
#include <vector>

class PitchTempoProcessor {
public:
    void prepare(int sampleRate, int channels, int maxOutputFrames);
    void reset();

    void setTempo(float tempoSpeed);
    void setPitchSemitones(int semitones);

    bool isBypassed() const;
    bool hasPendingOutput() const;
    float currentTempo() const;
    int inputFramesNeeded(int outputFrames);

    int process(
        const float* inputInterleaved,
        int inputFrames,
        float* outputInterleaved,
        int outputFrames
    );

private:
    static constexpr float kDefaultTempo = 1.0f;
    static constexpr float kMinTempo = 0.5f;
    static constexpr float kMaxTempo = 1.5f;
    static constexpr int kDefaultPitch = 0;
    static constexpr int kMinPitch = -10;
    static constexpr int kMaxPitch = 10;

    void applyPendingParameters();
    int calculateOutputFramesForInput(int inputFrames);
    void deinterleave(const float* inputInterleaved, int frames);
    void interleave(float* outputInterleaved, int frames);
    static bool nearlyDefaultTempo(float value);

    signalsmith::stretch::SignalsmithStretch<float> stretch_{0};
    AudioFifo fifo_;

    std::atomic<float> tempoSpeed_{kDefaultTempo};
    std::atomic<int> pitchSemitones_{kDefaultPitch};

    float appliedTempoSpeed_ = kDefaultTempo;
    int appliedPitchSemitones_ = kDefaultPitch;
    double outputFrameRemainder_ = 0.0;

    int sampleRate_ = 44100;
    int channels_ = 2;
    int maxInputFrames_ = 0;
    int maxProcessedFrames_ = 0;
    bool prepared_ = false;

    std::vector<std::vector<float>> inputPlanar_;
    std::vector<std::vector<float>> outputPlanar_;
    std::vector<float> processedInterleaved_;
};
