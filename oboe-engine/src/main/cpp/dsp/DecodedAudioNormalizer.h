#pragma once

#include "SampleRateConverter.h"

#include <cstdint>
#include <memory>
#include <vector>

namespace neuralsound::audio {

// Converts decoded interleaved PCM16 into the canonical format consumed by the
// mixer. Every file-backed track crosses this boundary before entering the
// shared processing timeline.
class DecodedAudioNormalizer {
public:
    static constexpr int kOutputSampleRate = 44'100;
    static constexpr int kOutputChannelCount = 2;

    bool configure(int inputSampleRate, int inputChannelCount);

    // Replaces `output` with canonical 44.1 kHz stereo PCM16. Returns its
    // frame count, or -1 when the conversion cannot be completed safely.
    int32_t process(
        const int16_t* input,
        int32_t inputFrames,
        std::vector<int16_t>& output
    );

    // Clears resampler history when the decoder timeline is repositioned.
    bool reset();
    void release();

    int inputSampleRate() const;
    int inputChannelCount() const;

private:
    int inputSampleRate_ = 0;
    int inputChannelCount_ = 0;
    std::unique_ptr<SampleRateConverter> sampleRateConverter_;
    std::vector<float> inputScratch_;
    std::vector<float> outputScratch_;
};

} // namespace neuralsound::audio
