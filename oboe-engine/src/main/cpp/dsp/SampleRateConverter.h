#pragma once

#include <cstdint>
#include <memory>

namespace neuralsound::audio {

// Higher quality sounds better but costs more CPU. Mirrors the quality levels
// of the underlying resampler.
enum class ResampleQuality {
    Fastest,
    Low,
    Medium,
    High,
    Best,
};

// Streaming interleaved-float sample-rate converter.
//
// Reusable, dependency-free component: no Oboe, Android, or JNI types leak
// through this interface, so it can be lifted into a standalone package. The
// underlying resampler is a private implementation detail.
//
// Not thread-safe: a single instance is meant to be driven by one thread
// (e.g. the audio callback). Create it off the realtime thread; process() is
// allocation-free.
class SampleRateConverter {
public:
    // Returns nullptr for invalid arguments (channelCount <= 0 or a
    // non-positive sample rate).
    static std::unique_ptr<SampleRateConverter> make(
        int channelCount,
        int inputSampleRate,
        int outputSampleRate,
        ResampleQuality quality = ResampleQuality::Medium
    );

    ~SampleRateConverter();

    SampleRateConverter(const SampleRateConverter&) = delete;
    SampleRateConverter& operator=(const SampleRateConverter&) = delete;

    // Consume up to `inputFrames` interleaved input frames and produce up to
    // `outputCapacityFrames` interleaved output frames into `output`. Returns
    // the number of output frames produced. Sizing `output` with
    // maxOutputFramesFor(inputFrames) guarantees all input is consumed.
    int32_t process(
        const float* input,
        int32_t inputFrames,
        float* output,
        int32_t outputCapacityFrames
    );

    // Upper bound on the output frames produced from `inputFrames` input
    // frames, for buffer sizing. Includes a small guard for phase remainder.
    int32_t maxOutputFramesFor(int32_t inputFrames) const;

    // True when input and output rates match; process() is then a plain copy.
    bool isPassthrough() const;

    // Discard any buffered filter state, e.g. between takes.
    void reset();

    int inputSampleRate() const;
    int outputSampleRate() const;
    int channelCount() const;

private:
    struct Impl;
    explicit SampleRateConverter(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

} // namespace neuralsound::audio
