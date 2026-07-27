#include "DecodedAudioNormalizer.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace neuralsound::audio {
namespace {

constexpr int32_t kMaxOutputFramesPerProcess = 1 << 20;

float pcm16ToFloat(int16_t sample) {
    return static_cast<float>(sample) / 32768.0f;
}

int16_t floatToPcm16(float sample) {
    constexpr float kMaxPcm16 = 32767.0f / 32768.0f;
    const float clamped = std::clamp(sample, -1.0f, kMaxPcm16);
    const int32_t scaled = static_cast<int32_t>(std::lrint(clamped * 32768.0f));
    return static_cast<int16_t>(std::clamp(
        scaled,
        static_cast<int32_t>(std::numeric_limits<int16_t>::min()),
        static_cast<int32_t>(std::numeric_limits<int16_t>::max())
    ));
}

struct StereoFrame {
    float left;
    float right;
};

StereoFrame pcm16FrameToStereo(const int16_t* input, int channelCount) {
    if (channelCount == 1) {
        const float mono = pcm16ToFloat(input[0]);
        return {mono, mono};
    }
    if (channelCount == 2) {
        return {pcm16ToFloat(input[0]), pcm16ToFloat(input[1])};
    }

    float sum = 0.0f;
    for (int channel = 0; channel < channelCount; ++channel) {
        sum += pcm16ToFloat(input[channel]);
    }
    const float mono = sum / static_cast<float>(channelCount);
    return {mono, mono};
}

} // namespace

bool DecodedAudioNormalizer::configure(int inputSampleRate, int inputChannelCount) {
    release();

    if (inputSampleRate <= 0 || inputChannelCount <= 0) {
        return false;
    }

    auto converter = SampleRateConverter::make(
        kOutputChannelCount,
        inputSampleRate,
        kOutputSampleRate,
        ResampleQuality::Medium
    );
    if (converter == nullptr) {
        return false;
    }

    inputSampleRate_ = inputSampleRate;
    inputChannelCount_ = inputChannelCount;
    sampleRateConverter_ = std::move(converter);
    return true;
}

int32_t DecodedAudioNormalizer::process(
    const int16_t* input,
    int32_t inputFrames,
    std::vector<int16_t>& output
) {
    output.clear();
    if (input == nullptr || inputFrames <= 0 || sampleRateConverter_ == nullptr) {
        return 0;
    }

    constexpr size_t kOutputChannels = static_cast<size_t>(kOutputChannelCount);
    const size_t sourceChannels = static_cast<size_t>(inputChannelCount_);
    const int32_t outputCapacityFrames =
        sampleRateConverter_->maxOutputFramesFor(inputFrames);
    if (outputCapacityFrames <= 0 ||
        outputCapacityFrames > kMaxOutputFramesPerProcess) {
        return -1;
    }

    if (sampleRateConverter_->isPassthrough() &&
        inputChannelCount_ <= kOutputChannelCount) {
        output.resize(static_cast<size_t>(inputFrames) * kOutputChannels);
        for (int32_t frame = 0; frame < inputFrames; ++frame) {
            const size_t source = static_cast<size_t>(frame) * sourceChannels;
            const size_t destination = static_cast<size_t>(frame) * kOutputChannels;
            output[destination] = input[source];
            output[destination + 1] =
                inputChannelCount_ == 1 ? input[source] : input[source + 1];
        }
        return inputFrames;
    }

    inputScratch_.resize(static_cast<size_t>(inputFrames) * kOutputChannels);
    for (int32_t frame = 0; frame < inputFrames; ++frame) {
        const size_t source = static_cast<size_t>(frame) * sourceChannels;
        const size_t destination = static_cast<size_t>(frame) * kOutputChannels;
        const StereoFrame stereo = pcm16FrameToStereo(
            input + source,
            inputChannelCount_
        );
        inputScratch_[destination] = stereo.left;
        inputScratch_[destination + 1] = stereo.right;
    }

    outputScratch_.resize(
        static_cast<size_t>(outputCapacityFrames) * kOutputChannels
    );
    const int32_t outputFrames = sampleRateConverter_->process(
        inputScratch_.data(),
        inputFrames,
        outputScratch_.data(),
        outputCapacityFrames
    );

    output.resize(static_cast<size_t>(outputFrames) * kOutputChannels);
    for (size_t sample = 0; sample < output.size(); ++sample) {
        output[sample] = floatToPcm16(outputScratch_[sample]);
    }
    return outputFrames;
}

bool DecodedAudioNormalizer::reset() {
    return sampleRateConverter_ != nullptr && sampleRateConverter_->reset();
}

void DecodedAudioNormalizer::release() {
    sampleRateConverter_.reset();
    inputScratch_.clear();
    outputScratch_.clear();
    inputSampleRate_ = 0;
    inputChannelCount_ = 0;
}

int DecodedAudioNormalizer::inputSampleRate() const {
    return inputSampleRate_;
}

int DecodedAudioNormalizer::inputChannelCount() const {
    return inputChannelCount_;
}

} // namespace neuralsound::audio
