#include "SampleRateConverter.h"

#include <algorithm>
#include <cstring>
#include <limits>

#include "MultiChannelResampler.h"

namespace neuralsound::audio {

// RESAMPLER_OUTER_NAMESPACE is provided by the build (target compile definition)
// and, as a fallback, defaulted by ResamplerDefinitions.h (included transitively
// above). Referencing it through the macro keeps this wrapper independent of the
// concrete namespace the vendored resampler is compiled under.
using VendoredResampler = RESAMPLER_OUTER_NAMESPACE::resampler::MultiChannelResampler;

namespace {
VendoredResampler::Quality toVendoredQuality(ResampleQuality quality) {
    switch (quality) {
        case ResampleQuality::Fastest:
            return VendoredResampler::Quality::Fastest;
        case ResampleQuality::Low:
            return VendoredResampler::Quality::Low;
        case ResampleQuality::Medium:
            return VendoredResampler::Quality::Medium;
        case ResampleQuality::High:
            return VendoredResampler::Quality::High;
        case ResampleQuality::Best:
            return VendoredResampler::Quality::Best;
    }
    return VendoredResampler::Quality::Medium;
}
} // namespace

struct SampleRateConverter::Impl {
    int channelCount = 1;
    int inputRate = 0;
    int outputRate = 0;
    ResampleQuality quality = ResampleQuality::Medium;
    // Null when passthrough (input rate == output rate).
    std::unique_ptr<VendoredResampler> resampler;
};

SampleRateConverter::SampleRateConverter(std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

SampleRateConverter::~SampleRateConverter() = default;

std::unique_ptr<SampleRateConverter> SampleRateConverter::make(
    int channelCount,
    int inputSampleRate,
    int outputSampleRate,
    ResampleQuality quality
) {
    if (channelCount <= 0 || inputSampleRate <= 0 || outputSampleRate <= 0) {
        return nullptr;
    }

    auto impl = std::make_unique<Impl>();
    impl->channelCount = channelCount;
    impl->inputRate = inputSampleRate;
    impl->outputRate = outputSampleRate;
    impl->quality = quality;

    if (inputSampleRate != outputSampleRate) {
        impl->resampler.reset(VendoredResampler::make(
            channelCount,
            inputSampleRate,
            outputSampleRate,
            toVendoredQuality(quality)
        ));
        if (impl->resampler == nullptr) {
            return nullptr;
        }
    }

    return std::unique_ptr<SampleRateConverter>(new SampleRateConverter(std::move(impl)));
}

int32_t SampleRateConverter::process(
    const float* input,
    int32_t inputFrames,
    float* output,
    int32_t outputCapacityFrames
) {
    if (input == nullptr || output == nullptr || inputFrames <= 0 ||
        outputCapacityFrames <= 0) {
        return 0;
    }

    const int channels = impl_->channelCount;

    if (impl_->resampler == nullptr) {
        // Passthrough: copy as many frames as fit.
        const int32_t frames = std::min(inputFrames, outputCapacityFrames);
        std::memcpy(
            output,
            input,
            static_cast<size_t>(frames) * static_cast<size_t>(channels) * sizeof(float)
        );
        return frames;
    }

    VendoredResampler* resampler = impl_->resampler.get();
    const float* in = input;
    float* out = output;
    int32_t inputFramesLeft = inputFrames;
    int32_t outputFrames = 0;

    // Pull model: feed an input frame whenever the resampler asks for one,
    // otherwise read an output frame. Stop when the output buffer is full or
    // when more input is needed than remains (streaming state is preserved for
    // the next call).
    while (outputFrames < outputCapacityFrames) {
        if (resampler->isWriteNeeded()) {
            if (inputFramesLeft == 0) {
                break;
            }
            resampler->writeNextFrame(in);
            in += channels;
            --inputFramesLeft;
        } else {
            resampler->readNextFrame(out);
            out += channels;
            ++outputFrames;
        }
    }

    return outputFrames;
}

int32_t SampleRateConverter::maxOutputFramesFor(int32_t inputFrames) const {
    if (inputFrames <= 0) {
        return 0;
    }
    if (impl_->resampler == nullptr) {
        return inputFrames;
    }
    // ceil(inputFrames * outputRate / inputRate) plus a small guard for the
    // fractional phase carried between calls.
    const int64_t numerator =
        static_cast<int64_t>(inputFrames) * static_cast<int64_t>(impl_->outputRate);
    const int64_t frames = (numerator + impl_->inputRate - 1) / impl_->inputRate;
    if (frames > std::numeric_limits<int32_t>::max() - 2) {
        return -1;
    }
    return static_cast<int32_t>(frames) + 2;
}

bool SampleRateConverter::isPassthrough() const {
    return impl_->resampler == nullptr;
}

bool SampleRateConverter::reset() {
    if (impl_->resampler == nullptr) {
        return true;
    }
    // MultiChannelResampler has no public reset; rebuild to clear filter state.
    std::unique_ptr<VendoredResampler> replacement(VendoredResampler::make(
        impl_->channelCount,
        impl_->inputRate,
        impl_->outputRate,
        toVendoredQuality(impl_->quality)
    ));
    if (replacement == nullptr) {
        return false;
    }
    impl_->resampler = std::move(replacement);
    return true;
}

int SampleRateConverter::inputSampleRate() const {
    return impl_->inputRate;
}

int SampleRateConverter::outputSampleRate() const {
    return impl_->outputRate;
}

int SampleRateConverter::channelCount() const {
    return impl_->channelCount;
}

} // namespace neuralsound::audio
