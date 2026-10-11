#include "SourceBoost.h"
#include "StereoLookaheadLimiter.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <iostream>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace neuralsound::audio::dsp {

class StereoLookaheadLimiterTestAccess {
public:
    static void setEmptyFrameIndex(
        StereoLookaheadLimiter& limiter,
        int64_t frameIndex
    ) {
        limiter.inputFrameIndex_ = frameIndex;
        limiter.outputFrameIndex_ = frameIndex;
    }
};

} // namespace neuralsound::audio::dsp

using neuralsound::audio::dsp::SourceBoost;
using neuralsound::audio::dsp::StereoLookaheadLimiter;
using neuralsound::audio::dsp::StereoLookaheadLimiterTestAccess;

namespace {

constexpr float kTolerance = 1.0e-5f;
// Covers float envelope rounding and algebraically equivalent bounded-distance
// multiplication in production versus direct division in the scalar oracle.
constexpr float kOracleTolerance = 3.0e-6f;

void require(bool value, const char* message) {
    if (!value) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

bool near(float actual, float expected, float tolerance = kTolerance) {
    return std::fabs(actual - expected) <= tolerance;
}

float linearToDb(float gain) {
    return 20.0f * std::log10(gain);
}

// Deliberately simple and independent: this scans every still-buffered frame
// for every output frame instead of reproducing the production monotonic queue.
class ScalarLookaheadLimiterOracle {
public:
    void prepare(int sampleRate) {
        const int safeSampleRate = std::max(1, sampleRate);
        latencyFrames_ = static_cast<int>(
            (static_cast<int64_t>(safeSampleRate) *
             StereoLookaheadLimiter::kLookaheadMilliseconds) / 1000
        );
        releaseFrames_ = std::max(
            1,
            static_cast<int>(
                (static_cast<int64_t>(safeSampleRate) *
                 StereoLookaheadLimiter::kReleaseMilliseconds) / 1000
            )
        );
        prepared_ = true;
        reset();
    }

    void reset() {
        bufferedFrames_.clear();
        inputFrameIndex_ = 0;
        outputFrameIndex_ = 0;
        currentGain_ = 1.0f;
        releaseIncrement_ = 0.0f;
    }

    int latencyFrames() const {
        return latencyFrames_;
    }

    int releaseFrames() const {
        return releaseFrames_;
    }

    bool hasPendingOutput() const {
        return !bufferedFrames_.empty();
    }

    int process(
        const float* inputInterleaved,
        int inputFrames,
        float* outputInterleaved
    ) {
        if (!prepared_ || inputInterleaved == nullptr || inputFrames <= 0 ||
            outputInterleaved == nullptr) {
            return 0;
        }

        int outputFrames = 0;
        for (int frame = 0; frame < inputFrames; ++frame) {
            const size_t offset = static_cast<size_t>(frame) * 2;
            bufferedFrames_.push_back(BufferedFrame{
                inputFrameIndex_,
                sanitize(inputInterleaved[offset]),
                sanitize(inputInterleaved[offset + 1]),
            });
            ++inputFrameIndex_;
            if (static_cast<int>(bufferedFrames_.size()) > latencyFrames_) {
                outputFrames += produceOldestFrame(
                    outputInterleaved + static_cast<size_t>(outputFrames) * 2
                );
            }
        }
        return outputFrames;
    }

    int drain(float* outputInterleaved, int maxOutputFrames) {
        if (outputInterleaved == nullptr || maxOutputFrames <= 0) return 0;

        const int framesToProduce = std::min(
            maxOutputFrames,
            static_cast<int>(bufferedFrames_.size())
        );
        int outputFrames = 0;
        while (outputFrames < framesToProduce) {
            outputFrames += produceOldestFrame(
                outputInterleaved + static_cast<size_t>(outputFrames) * 2
            );
        }
        if (bufferedFrames_.empty()) reset();
        return outputFrames;
    }

private:
    struct BufferedFrame {
        int64_t frameIndex;
        float left;
        float right;
    };

    int produceOldestFrame(float* outputInterleaved) {
        if (outputInterleaved == nullptr || bufferedFrames_.empty()) return 0;

        float targetGain = 1.0f;
        if (latencyFrames_ == 0) {
            targetGain = requiredGain(bufferedFrames_.front());
        } else {
            double slowMinimum = 1.0;
            for (const BufferedFrame& frame : bufferedFrames_) {
                const double candidate = std::min(
                    1.0,
                    static_cast<double>(requiredGain(frame)) +
                        static_cast<double>(frame.frameIndex - outputFrameIndex_) /
                            static_cast<double>(latencyFrames_)
                );
                slowMinimum = std::min(slowMinimum, candidate);
            }
            targetGain = static_cast<float>(std::clamp(slowMinimum, 0.0, 1.0));
        }

        if (targetGain < currentGain_) {
            currentGain_ = targetGain;
            releaseIncrement_ =
                (1.0f - currentGain_) / static_cast<float>(releaseFrames_);
        } else {
            currentGain_ = std::min(
                targetGain,
                std::min(1.0f, currentGain_ + releaseIncrement_)
            );
        }

        const BufferedFrame& frame = bufferedFrames_.front();
        outputInterleaved[0] = std::clamp(
            sanitize(frame.left * currentGain_),
            -StereoLookaheadLimiter::kCeiling,
            StereoLookaheadLimiter::kCeiling
        );
        outputInterleaved[1] = std::clamp(
            sanitize(frame.right * currentGain_),
            -StereoLookaheadLimiter::kCeiling,
            StereoLookaheadLimiter::kCeiling
        );
        bufferedFrames_.pop_front();
        ++outputFrameIndex_;
        return 1;
    }

    static float requiredGain(const BufferedFrame& frame) {
        const float peak = std::max(std::fabs(frame.left), std::fabs(frame.right));
        return peak > StereoLookaheadLimiter::kCeiling
            ? StereoLookaheadLimiter::kCeiling / peak
            : 1.0f;
    }

    static float sanitize(float sample) {
        return std::isfinite(sample) ? sample : 0.0f;
    }

    std::deque<BufferedFrame> bufferedFrames_;
    bool prepared_ = false;
    int latencyFrames_ = 0;
    int releaseFrames_ = 1;
    int64_t inputFrameIndex_ = 0;
    int64_t outputFrameIndex_ = 0;
    float currentGain_ = 1.0f;
    float releaseIncrement_ = 0.0f;
};

template <typename Limiter>
std::vector<float> render(
    Limiter& limiter,
    const std::vector<float>& input,
    const std::vector<int>& chunkPattern,
    int drainChunkFrames
) {
    require(!chunkPattern.empty(), "render requires an input chunk pattern");
    require(drainChunkFrames > 0, "render requires a positive drain chunk size");

    const int inputFrames = static_cast<int>(input.size() / 2);
    std::vector<float> output;
    output.reserve(input.size());
    int inputOffset = 0;
    size_t chunkIndex = 0;

    while (inputOffset < inputFrames) {
        const int requested = chunkPattern[chunkIndex % chunkPattern.size()];
        require(requested > 0, "input chunk sizes must be positive");
        const int frames = std::min(requested, inputFrames - inputOffset);
        std::vector<float> block(static_cast<size_t>(frames) * 2, 0.0f);
        const int produced = limiter.process(
            input.data() + static_cast<size_t>(inputOffset) * 2,
            frames,
            block.data()
        );
        require(produced >= 0 && produced <= frames, "process returns a valid frame count");
        output.insert(output.end(), block.begin(), block.begin() + produced * 2);
        inputOffset += frames;
        ++chunkIndex;
    }

    while (limiter.hasPendingOutput()) {
        std::vector<float> block(static_cast<size_t>(drainChunkFrames) * 2, 0.0f);
        const int produced = limiter.drain(block.data(), drainChunkFrames);
        require(produced > 0, "drain makes progress while output is pending");
        output.insert(output.end(), block.begin(), block.begin() + produced * 2);
    }
    return output;
}

void requireVectorsNear(
    const std::vector<float>& actual,
    const std::vector<float>& expected,
    float tolerance,
    const std::string& scenario
) {
    if (actual.size() != expected.size()) {
        std::cerr << "FAIL: " << scenario << " frame count differs: actual samples="
                  << actual.size() << " expected samples=" << expected.size() << '\n';
        std::exit(1);
    }
    for (size_t sample = 0; sample < actual.size(); ++sample) {
        if (!near(actual[sample], expected[sample], tolerance)) {
            std::cerr << "FAIL: " << scenario << " differs at sample " << sample
                      << ": actual=" << actual[sample]
                      << " expected=" << expected[sample] << '\n';
            std::exit(1);
        }
    }
}

std::vector<float> renderAndCompareWithOracle(
    int sampleRate,
    const std::vector<float>& input,
    const std::vector<int>& chunkPattern,
    int drainChunkFrames,
    const std::string& scenario
) {
    StereoLookaheadLimiter limiter;
    ScalarLookaheadLimiterOracle oracle;
    limiter.prepare(sampleRate);
    oracle.prepare(sampleRate);
    require(limiter.latencyFrames() == oracle.latencyFrames(),
            "oracle and limiter latency agree");
    require(limiter.releaseFrames() == oracle.releaseFrames(),
            "oracle and limiter release agree");

    const auto actual = render(limiter, input, chunkPattern, drainChunkFrames);
    const auto expected = render(oracle, input, chunkPattern, drainChunkFrames);
    require(actual.size() == input.size(), "process plus drain preserves every input frame");
    requireVectorsNear(actual, expected, kOracleTolerance, scenario);
    for (float sample : actual) {
        require(std::isfinite(sample), "oracle-compared limiter output remains finite");
        require(std::fabs(sample) <= StereoLookaheadLimiter::kCeiling + 1.0e-7f,
                "oracle-compared limiter output respects the ceiling");
    }
    return actual;
}

void testDbConversionClampingAndSmoothing() {
    require(SourceBoost::kMinDb == 0, "native source boost minimum is zero dB");
    require(SourceBoost::kMaxDb == 18, "native source boost maximum is eighteen dB");
    require(SourceBoost::clampDb(-4) == 0, "negative source boost clamps to zero");
    require(SourceBoost::clampDb(19) == 18, "source boost clamps to 18 dB");
    require(near(SourceBoost::dbToLinear(0.0f), 1.0f), "zero dB is unity gain");
    require(near(SourceBoost::dbToLinear(6.0f), 1.9952623f), "six dB uses amplitude conversion");
    require(near(SourceBoost::dbToLinear(18.0f), 7.9432823f), "18 dB uses amplitude conversion");

    SourceBoost boost;
    boost.prepare(1'000);
    require(boost.rampFrames() == 5, "source boost ramp is five milliseconds");
    boost.setTargetDb(10);
    for (int frame = 1; frame <= 5; ++frame) {
        const float db = linearToDb(boost.nextLinearGain());
        require(near(db, static_cast<float>(frame * 2), 2.0e-4f),
                "source boost interpolates linearly in dB space");
    }

    boost.reset(0);
    boost.setTargetDb(10);
    boost.nextLinearGain();
    boost.nextLinearGain();
    boost.setTargetDb(-20);
    const float expectedDb[] = {3.2f, 2.4f, 1.6f, 0.8f, 0.0f};
    for (float expected : expectedDb) {
        require(near(linearToDb(boost.nextLinearGain()), expected, 2.0e-4f),
                "latest source boost target replaces the active ramp");
    }
    boost.setTargetDb(99);
    require(boost.targetDb() == 18, "stored native source boost target is clamped");

    SourceBoost independentBoost;
    independentBoost.prepare(1'000);
    independentBoost.setTargetDb(5);
    require(
        independentBoost.targetDb() == 5 && boost.targetDb() == 18,
        "native tracks retain independent source boost targets"
    );
}

void testLimiterTimingContract() {
    StereoLookaheadLimiter limiter;
    const std::vector<std::pair<int, int>> latencyCases = {
        {1, 0},
        {1'000, 5},
        {8'000, 40},
        {44'100, 220},
        {48'000, 240},
        {96'000, 480},
    };
    for (const auto& [sampleRate, expectedLatency] : latencyCases) {
        limiter.prepare(sampleRate);
        require(limiter.latencyFrames() == expectedLatency,
                "lookahead is floor(sample rate times five milliseconds)");
        require(limiter.releaseFrames() == std::max(1, sampleRate / 20),
                "release is floor(sample rate times fifty milliseconds)");
    }

    limiter.prepare(1'000);
    std::vector<float> input(6 * 2, 0.1f);
    std::vector<float> output(input.size(), 0.0f);
    require(limiter.process(input.data(), 5, output.data()) == 0,
            "no frame emerges before the fixed lookahead is filled");
    require(limiter.process(input.data() + 10, 1, output.data()) == 1,
            "one frame emerges immediately after the fixed lookahead");
}

void testZeroLookaheadAndInvalidArguments() {
    StereoLookaheadLimiter unprepared;
    float inputFrame[] = {0.25f, -0.5f};
    float outputFrames[8] = {};
    require(unprepared.process(inputFrame, 1, outputFrames) == 0,
            "process before prepare accepts no input");
    require(unprepared.drain(outputFrames, 1) == 0,
            "drain before prepare has no output");

    StereoLookaheadLimiter limiter;
    limiter.prepare(1);
    require(limiter.latencyFrames() == 0, "one hertz explicitly uses zero lookahead");
    require(limiter.releaseFrames() == 1, "one hertz retains a bounded release");
    const std::vector<float> zeroLookaheadInput = {
        2.0f, 0.25f,
        0.2f, -0.4f,
        std::numeric_limits<float>::quiet_NaN(),
        std::numeric_limits<float>::infinity(),
    };
    std::vector<float> zeroLookaheadOutput(zeroLookaheadInput.size(), -1.0f);
    require(limiter.process(
                zeroLookaheadInput.data(),
                static_cast<int>(zeroLookaheadInput.size() / 2),
                zeroLookaheadOutput.data()
            ) == 3,
            "zero lookahead emits every accepted frame immediately");
    require(!limiter.hasPendingOutput(), "zero lookahead leaves no delayed frames");
    require(near(zeroLookaheadOutput[0], StereoLookaheadLimiter::kCeiling),
            "zero-lookahead overage is limited on its own frame");
    require(near(zeroLookaheadOutput[1], 0.25f * 0.49f),
            "zero-lookahead limiting uses one linked stereo gain");
    require(near(zeroLookaheadOutput[2], 0.2f) && near(zeroLookaheadOutput[3], -0.4f),
            "zero-lookahead release returns to unity in its single release frame");
    require(zeroLookaheadOutput[4] == 0.0f && zeroLookaheadOutput[5] == 0.0f,
            "zero-lookahead path sanitizes non-finite samples");

    limiter.prepare(100);
    const float loudZeroLookahead[] = {2.0f, 0.5f};
    float loudZeroLookaheadOutput[2] = {};
    require(limiter.process(loudZeroLookahead, 1, loudZeroLookaheadOutput) == 1,
            "zero lookahead limits a loud single-frame segment");
    require(limiter.drain(outputFrames, 1) == 0,
            "completed zero-lookahead drain has no delayed frames");
    const float quietZeroLookahead[] = {0.2f, -0.3f};
    float quietZeroLookaheadOutput[2] = {};
    require(limiter.process(quietZeroLookahead, 1, quietZeroLookaheadOutput) == 1,
            "zero lookahead starts a new segment immediately");
    require(near(quietZeroLookaheadOutput[0], quietZeroLookahead[0], 1.0e-7f) &&
                near(quietZeroLookaheadOutput[1], quietZeroLookahead[1], 1.0e-7f),
            "empty completed drain resets the zero-lookahead release envelope");

    limiter.prepare(0);
    require(limiter.latencyFrames() == 0 && limiter.releaseFrames() == 1,
            "zero sample rate is sanitized to one hertz");
    limiter.prepare(-48'000);
    require(limiter.latencyFrames() == 0 && limiter.releaseFrames() == 1,
            "negative sample rate is sanitized to one hertz");

    limiter.prepare(1'000);
    const float pendingInput[] = {0.1f, -0.1f, 0.2f, -0.2f};
    require(limiter.process(pendingInput, 2, outputFrames) == 0,
            "valid short input remains pending for invalid-argument checks");
    require(limiter.process(nullptr, 1, outputFrames) == 0,
            "null process input is rejected");
    require(limiter.process(pendingInput, 0, outputFrames) == 0,
            "zero process frame count is rejected");
    require(limiter.process(pendingInput, -7, outputFrames) == 0,
            "negative process frame count is rejected");
    require(limiter.process(pendingInput, 1, nullptr) == 0,
            "null process output is rejected");
    require(limiter.drain(nullptr, 1) == 0, "null drain output is rejected");
    require(limiter.drain(outputFrames, 0) == 0, "zero drain size is rejected");
    require(limiter.drain(outputFrames, -3) == 0, "negative drain size is rejected");
    require(limiter.hasPendingOutput(),
            "invalid process and drain arguments do not discard pending audio");
    require(limiter.drain(outputFrames, 8) == 2,
            "valid drain emits all frames retained across invalid calls");
    require(near(outputFrames[0], 0.1f) && near(outputFrames[1], -0.1f) &&
                near(outputFrames[2], 0.2f) && near(outputFrames[3], -0.2f),
            "invalid calls do not mutate retained quiet audio");
}

void testRepeatedPrepareClearsAllStreamingState() {
    StereoLookaheadLimiter limiter;
    limiter.prepare(48'000);
    std::vector<float> loudInput(300 * 2, 2.0f);
    std::vector<float> scratch(loudInput.size(), 0.0f);
    require(limiter.process(loudInput.data(), 300, scratch.data()) == 60,
            "first prepared configuration enters active limiting");
    require(limiter.hasPendingOutput(), "first prepared configuration has delayed output");

    limiter.prepare(8'000);
    require(limiter.latencyFrames() == 40 && limiter.releaseFrames() == 400,
            "repeated prepare installs new timing");
    require(!limiter.hasPendingOutput(),
            "repeated prepare discards old delayed audio and constraints");
    std::vector<float> quiet(93 * 2, 0.15f);
    const auto afterPrepare = render(limiter, quiet, {1, 13, 41}, 7);
    require(afterPrepare == quiet,
            "repeated prepare restores a clean unity envelope");

    require(limiter.process(quiet.data(), 20, scratch.data()) == 0,
            "second configuration can hold a partial lookahead");
    limiter.prepare(8'000);
    require(!limiter.hasPendingOutput(),
            "preparing the same rate again also clears partial state");
    const auto afterSameRatePrepare = render(limiter, quiet, {93}, 200);
    require(afterSameRatePrepare == quiet,
            "same-rate repeated prepare is equivalent to a fresh limiter");
}

void testQuietPassthroughAndExactEosFrameCount() {
    for (const int frameCount : {0, 1, 5, 6, 37}) {
        StereoLookaheadLimiter limiter;
        limiter.prepare(1'000);
        std::vector<float> input(static_cast<size_t>(frameCount) * 2, 0.0f);
        for (int frame = 0; frame < frameCount; ++frame) {
            input[static_cast<size_t>(frame) * 2] =
                static_cast<float>((frame % 7) - 3) * 0.05f;
            input[static_cast<size_t>(frame) * 2 + 1] =
                static_cast<float>((frame % 5) - 2) * 0.04f;
        }
        const auto output = render(limiter, input, {1, 4, 2}, 3);
        require(output.size() == input.size(), "EOS drain preserves exact frame count");
        for (size_t sample = 0; sample < input.size(); ++sample) {
            require(near(output[sample], input[sample], 1.0e-7f),
                    "quiet material passes unchanged after limiter drain");
        }
    }
}

void testCeilingStereoLinkAndRelease() {
    StereoLookaheadLimiter limiter;
    limiter.prepare(1'000);
    const int impulseFrame = limiter.latencyFrames() + 3;
    const int frameCount = impulseFrame + limiter.releaseFrames() + 8;
    std::vector<float> input(static_cast<size_t>(frameCount) * 2, 0.1f);
    input[static_cast<size_t>(impulseFrame) * 2] = 2.0f;
    input[static_cast<size_t>(impulseFrame) * 2 + 1] = 0.25f;

    const auto output = render(limiter, input, {frameCount}, frameCount);
    require(output.size() == input.size(), "limited stream keeps its frame count");
    for (float sample : output) {
        require(std::isfinite(sample), "limited output stays finite");
        require(std::fabs(sample) <= StereoLookaheadLimiter::kCeiling,
                "limited output stays at or below the sample ceiling");
    }

    const float left = output[static_cast<size_t>(impulseFrame) * 2];
    const float right = output[static_cast<size_t>(impulseFrame) * 2 + 1];
    require(near(left, 0.98f), "over-ceiling left sample reaches the limiter ceiling");
    require(near(left / 2.0f, right / 0.25f), "one linked gain is applied to left and right");
    require(near(
                output[static_cast<size_t>(impulseFrame - limiter.latencyFrames()) * 2],
                0.1f
            ),
            "lookahead attack starts from unity when a peak enters the window");
    for (int frame = impulseFrame - limiter.latencyFrames() + 1;
         frame < impulseFrame;
         ++frame) {
        require(
            output[static_cast<size_t>(frame) * 2] <=
                output[static_cast<size_t>(frame - 1) * 2],
            "lookahead attack reduces linked gain smoothly toward the peak"
        );
    }
    require(output[static_cast<size_t>(impulseFrame + 1) * 2] < 0.1f,
            "gain reduction releases rather than snapping back");
    require(near(
                output[static_cast<size_t>(impulseFrame + limiter.releaseFrames()) * 2],
                0.1f,
                2.0e-5f
            ),
            "gain reduction returns to unity in 50 milliseconds");
}


std::vector<float> makeConstraintSignal(
    int frameCount,
    const std::vector<std::pair<int, float>>& peaks
) {
    std::vector<float> input(static_cast<size_t>(frameCount) * 2, 0.0f);
    for (int frame = 0; frame < frameCount; ++frame) {
        input[static_cast<size_t>(frame) * 2] = 0.2f;
        input[static_cast<size_t>(frame) * 2 + 1] = -0.1f;
    }
    for (size_t index = 0; index < peaks.size(); ++index) {
        const int frame = peaks[index].first;
        const float peak = peaks[index].second;
        require(frame >= 0 && frame < frameCount, "test peak is inside its signal");
        if (index % 2 == 0) {
            input[static_cast<size_t>(frame) * 2] = peak;
            input[static_cast<size_t>(frame) * 2 + 1] = -0.25f * peak;
        } else {
            input[static_cast<size_t>(frame) * 2] = 0.2f * peak;
            input[static_cast<size_t>(frame) * 2 + 1] = -peak;
        }
    }
    return input;
}

void testDeterministicFixedSlopeConstraintOrderingAndBoundaries() {
    constexpr int sampleRate = 1'000;
    constexpr int lookahead = 5;

    const auto overlapping = makeConstraintSignal(
        48,
        {{12, 1.3f}, {13, 3.5f}, {14, 1.7f}, {16, 4.0f}}
    );
    renderAndCompareWithOracle(
        sampleRate,
        overlapping,
        {1, 2, 7, 3},
        2,
        "overlapping and adjacent constraints"
    );

    const auto increasinglyRestrictive = makeConstraintSignal(
        52,
        {{12, 1.1f}, {14, 1.8f}, {16, 3.2f}, {17, 7.9432823f}}
    );
    renderAndCompareWithOracle(
        sampleRate,
        increasinglyRestrictive,
        {11, 1, 5},
        1,
        "increasing peak strength and tail domination"
    );

    const auto decreasinglyRestrictive = makeConstraintSignal(
        52,
        {{12, 7.9432823f}, {13, 3.2f}, {15, 1.8f}, {17, 1.1f}}
    );
    renderAndCompareWithOracle(
        sampleRate,
        decreasinglyRestrictive,
        {52},
        17,
        "decreasing peak strength and head expiry"
    );

    const auto windowBoundaries = makeConstraintSignal(
        55,
        {{12, 5.0f}, {12 + lookahead, 2.0f}, {12 + 2 * lookahead + 1, 6.0f}}
    );
    renderAndCompareWithOracle(
        sampleRate,
        windowBoundaries,
        {4, 5, 6},
        lookahead - 1,
        "constraints exactly one lookahead and one-lookahead-plus-one apart"
    );

    const auto equalLogicalKeys = makeConstraintSignal(
        40,
        {
            {12, StereoLookaheadLimiter::kCeiling / 0.8f},
            {14, StereoLookaheadLimiter::kCeiling / 0.4f},
        }
    );
    renderAndCompareWithOracle(
        sampleRate,
        equalLogicalKeys,
        {1, 11, 2},
        1,
        "equal logical keys prefer the later longer-lived constraint"
    );

    const float oneUlpOverCeiling = std::nextafter(
        StereoLookaheadLimiter::kCeiling,
        std::numeric_limits<float>::infinity()
    );
    std::vector<float> fullConstraintQueue(200 * 2, oneUlpOverCeiling);
    for (size_t frame = 0; frame < fullConstraintQueue.size() / 2; ++frame) {
        fullConstraintQueue[frame * 2 + 1] = -0.25f;
    }
    renderAndCompareWithOracle(
        sampleRate,
        fullConstraintQueue,
        {1},
        1,
        "increasing keys saturate the fixed queue capacity without overwrite"
    );

    std::vector<float> loneBoundaryPeak(30 * 2, 0.0f);
    for (int frame = 0; frame < 30; ++frame) {
        loneBoundaryPeak[static_cast<size_t>(frame) * 2] = 0.25f;
        loneBoundaryPeak[static_cast<size_t>(frame) * 2 + 1] = -0.125f;
    }
    constexpr int peakFrame = 12;
    loneBoundaryPeak[peakFrame * 2] = 7.9432823f;
    loneBoundaryPeak[peakFrame * 2 + 1] = 0.4f;
    const auto boundaryOutput = renderAndCompareWithOracle(
        sampleRate,
        loneBoundaryPeak,
        {3, 1, 8},
        3,
        "single peak attack at the exact lookahead boundary"
    );
    require(near(boundaryOutput[(peakFrame - lookahead) * 2], 0.25f, 1.0e-7f),
            "a constraint at distance L has a unity fixed-slope bound");
    require(boundaryOutput[(peakFrame - lookahead + 1) * 2] < 0.25f,
            "the fixed-slope attack begins after the window boundary");
    require(near(boundaryOutput[peakFrame * 2], StereoLookaheadLimiter::kCeiling),
            "the fixed-slope bound reaches required gain on the peak frame");
}

void testCeilingLevelsAndStereoConfigurationsAgainstOracle() {
    constexpr int frameCount = 180;
    std::vector<float> input(frameCount * 2, 0.0f);
    for (int frame = 0; frame < frameCount; ++frame) {
        input[static_cast<size_t>(frame) * 2] = 0.12f;
        input[static_cast<size_t>(frame) * 2 + 1] = -0.06f;
    }

    input[10 * 2] = StereoLookaheadLimiter::kCeiling;
    input[10 * 2 + 1] = -StereoLookaheadLimiter::kCeiling;
    input[20 * 2] = std::nextafter(
        StereoLookaheadLimiter::kCeiling,
        std::numeric_limits<float>::infinity()
    );
    input[20 * 2 + 1] = 0.3f;
    input[40 * 2] = 2.0f;
    input[40 * 2 + 1] = 0.0f;
    input[65 * 2] = 0.0f;
    input[65 * 2 + 1] = -2.5f;
    input[90 * 2] = 3.0f;
    input[90 * 2 + 1] = -3.0f;
    input[115 * 2] = -7.9432823f;
    input[115 * 2 + 1] = 1.0f;

    const auto output = renderAndCompareWithOracle(
        1'000,
        input,
        {1, 19, 4, 31},
        6,
        "ceiling levels and stereo linking matrix"
    );
    require(output[10 * 2] == StereoLookaheadLimiter::kCeiling &&
                output[10 * 2 + 1] == -StereoLookaheadLimiter::kCeiling,
            "samples exactly at the ceiling pass without gain reduction");
    require(std::fabs(output[20 * 2]) <= StereoLookaheadLimiter::kCeiling,
            "one ULP above the ceiling is constrained");
    require(output[40 * 2 + 1] == 0.0f,
            "left-only peak does not create a right-channel signal");
    require(output[65 * 2] == 0.0f,
            "right-only peak does not create a left-channel signal");
    require(near(output[90 * 2], -output[90 * 2 + 1], 2.0e-6f),
            "opposite-polarity equal peaks retain their stereo relationship");
    require(near(
                output[115 * 2] * input[115 * 2 + 1],
                output[115 * 2 + 1] * input[115 * 2],
                2.0e-5f
            ),
            "unequal boosted channels receive one linked gain rather than independent clipping");
}

class FixedSeedRandom {
public:
    explicit FixedSeedRandom(uint64_t seed) : state_(seed == 0 ? 1 : seed) {}

    uint32_t next() {
        state_ ^= state_ >> 12;
        state_ ^= state_ << 25;
        state_ ^= state_ >> 27;
        return static_cast<uint32_t>((state_ * UINT64_C(2685821657736338717)) >> 32);
    }

    float bipolar() {
        const double unit = static_cast<double>(next()) /
            static_cast<double>(std::numeric_limits<uint32_t>::max());
        return static_cast<float>(unit * 2.0 - 1.0);
    }

private:
    uint64_t state_;
};

std::vector<float> makeRandomizedSignal(int frameCount, uint64_t seed) {
    FixedSeedRandom random(seed);
    std::vector<float> input(static_cast<size_t>(frameCount) * 2, 0.0f);
    const float oneUlpOverCeiling = std::nextafter(
        StereoLookaheadLimiter::kCeiling,
        std::numeric_limits<float>::infinity()
    );
    const float amplitudes[] = {
        0.0f,
        0.35f,
        StereoLookaheadLimiter::kCeiling,
        oneUlpOverCeiling,
        1.25f,
        2.0f,
        7.9432823f,
    };
    for (int frame = 0; frame < frameCount; ++frame) {
        const float amplitude = amplitudes[random.next() % 7];
        input[static_cast<size_t>(frame) * 2] = amplitude * random.bipolar();
        input[static_cast<size_t>(frame) * 2 + 1] = amplitude * random.bipolar();
        if (frame % 97 == 43) {
            input[static_cast<size_t>(frame) * 2] = 7.9432823f;
            input[static_cast<size_t>(frame) * 2 + 1] = -0.37f;
        }
        if (frame % 131 == 71) {
            input[static_cast<size_t>(frame) * 2] = 0.18f;
            input[static_cast<size_t>(frame) * 2 + 1] = -2.4f;
        }
    }

    if (frameCount > 200) {
        input[123 * 2] = std::numeric_limits<float>::quiet_NaN();
        input[157 * 2 + 1] = std::numeric_limits<float>::infinity();
        input[199 * 2] = -std::numeric_limits<float>::infinity();
    }
    return input;
}

void testFixedSeedRandomizedStreamsAgainstScalarOracle() {
    for (const int sampleRate : {1, 1'000, 8'000, 44'100, 48'000, 96'000}) {
        const int latency = static_cast<int>(
            (static_cast<int64_t>(sampleRate) *
             StereoLookaheadLimiter::kLookaheadMilliseconds) / 1000
        );
        const int frameCount = std::max(521, latency * 6 + 137);
        const auto input = makeRandomizedSignal(
            frameCount,
            UINT64_C(0xD1B54A32D192ED03) ^ static_cast<uint64_t>(sampleRate)
        );
        const std::string prefix = "fixed-seed randomized rate " +
            std::to_string(sampleRate) + " Hz, ";

        renderAndCompareWithOracle(
            sampleRate,
            input,
            {frameCount},
            frameCount + latency + 1,
            prefix + "whole input and oversized drain"
        );
        renderAndCompareWithOracle(
            sampleRate,
            input,
            {1},
            1,
            prefix + "one-frame input and drain"
        );
        renderAndCompareWithOracle(
            sampleRate,
            input,
            {2, 17, 31, 3, 127, 5},
            std::max(1, latency / 3 + 1),
            prefix + "alternating prime input chunks and partial drain"
        );
    }
}

void testLongAbsoluteFrameIndexPrecisionAgainstScalarOracle() {
    constexpr int sampleRate = 48'000;
    constexpr int frameCount = 4'321;
    constexpr int64_t startingFrameIndex = INT64_C(4'000'000'000'000'000);

    StereoLookaheadLimiter limiter;
    ScalarLookaheadLimiterOracle oracle;
    limiter.prepare(sampleRate);
    oracle.prepare(sampleRate);
    StereoLookaheadLimiterTestAccess::setEmptyFrameIndex(
        limiter,
        startingFrameIndex
    );

    const auto input = makeRandomizedSignal(
        frameCount,
        UINT64_C(0xA0761D6478BD642F)
    );
    const auto actual = render(limiter, input, {1, 239, 2, 241, 17}, 31);
    const auto expected = render(oracle, input, {1, 239, 2, 241, 17}, 31);
    requireVectorsNear(
        actual,
        expected,
        kOracleTolerance,
        "large absolute frame indices preserve bounded-distance precision"
    );
}

void testChunkInvariance() {
    std::vector<float> input(4'003 * 2, 0.0f);
    for (int frame = 0; frame < 4'003; ++frame) {
        input[static_cast<size_t>(frame) * 2] =
            0.7f * std::sin(static_cast<float>(frame) * 0.071f);
        input[static_cast<size_t>(frame) * 2 + 1] =
            0.6f * std::cos(static_cast<float>(frame) * 0.053f);
    }
    input[777 * 2] = 1.8f;
    input[2'301 * 2 + 1] = -2.4f;

    StereoLookaheadLimiter wholeLimiter;
    wholeLimiter.prepare(48'000);
    const auto whole = render(wholeLimiter, input, {4'003}, 4'003);

    StereoLookaheadLimiter chunkedLimiter;
    chunkedLimiter.prepare(48'000);
    const auto chunked = render(chunkedLimiter, input, {1, 17, 239, 64, 511}, 37);

    require(whole.size() == chunked.size(), "chunking does not change output frame count");
    for (size_t sample = 0; sample < whole.size(); ++sample) {
        require(near(whole[sample], chunked[sample], 1.0e-7f),
                "limiter output is invariant to input and drain chunking");
    }
}

void testResetAndFiniteSanitization() {
    StereoLookaheadLimiter limiter;
    limiter.prepare(1'000);

    const std::vector<float> abandoned = {
        0.1f, 0.1f,
        0.2f, 0.2f,
        0.3f, 0.3f,
        0.4f, 0.4f,
    };
    std::vector<float> scratch(abandoned.size(), 0.0f);
    require(limiter.process(abandoned.data(), 4, scratch.data()) == 0,
            "sub-lookahead input remains pending");
    require(limiter.hasPendingOutput(), "limiter reports pending delayed frames");
    limiter.reset();
    require(!limiter.hasPendingOutput(), "reset discards delayed frames");

    std::vector<float> input(20 * 2, 0.2f);
    input[2] = std::numeric_limits<float>::quiet_NaN();
    input[7] = std::numeric_limits<float>::infinity();
    input[18] = -std::numeric_limits<float>::infinity();
    const auto output = render(limiter, input, {3, 7, 10}, 2);
    require(output.size() == input.size(), "sanitized stream keeps exact frame count");
    for (float sample : output) {
        require(std::isfinite(sample), "NaN and infinity never reach limiter output");
        require(std::fabs(sample) <= StereoLookaheadLimiter::kCeiling,
                "sanitized output respects the sample ceiling");
    }
    require(output[2] == 0.0f && output[7] == 0.0f && output[18] == 0.0f,
            "non-finite input samples are replaced with silence");

    limiter.reset();
    const std::vector<float> quiet = {0.25f, -0.25f, 0.5f, -0.5f};
    const auto afterReset = render(limiter, quiet, {2}, 8);
    require(afterReset == quiet, "reset restores unity gain and an empty delay");
}

void testPartialDrainResetAndCompletedDrainLifecycle() {
    StereoLookaheadLimiter limiter;
    limiter.prepare(1'000);

    std::vector<float> transient(12 * 2, 0.1f);
    transient[5 * 2] = 2.0f;
    transient[5 * 2 + 1] = 2.0f;
    std::vector<float> processed(transient.size(), 0.0f);
    const int processedFrames = limiter.process(transient.data(), 12, processed.data());
    require(processedFrames == 7,
            "processing emits exactly the frames beyond its five-frame delay");

    std::vector<float> partialDrain(2, 0.0f);
    int drainedFrames = limiter.drain(partialDrain.data(), 1);
    require(drainedFrames == 1,
            "partial EOS drain emits only the requested frame");
    require(limiter.hasPendingOutput(),
            "partial EOS drain retains delayed frames and the active envelope");
    require(partialDrain[0] < 0.1f,
            "partial EOS drain retains transient attenuation");

    std::vector<float> drainBuffer(12 * 2, 0.0f);
    while (limiter.hasPendingOutput()) {
        const int produced = limiter.drain(drainBuffer.data(), 12);
        require(produced > 0, "final EOS drain completes");
        drainedFrames += produced;
    }
    require(processedFrames + drainedFrames == 12,
            "partial followed by full drain emits every accepted frame exactly once");

    const std::vector<float> nextSegment(12 * 2, 0.1f);
    const auto nextOutput = render(limiter, nextSegment, {12}, 12);
    require(nextOutput == nextSegment,
            "completed EOS drain restores unity before a post-EOS segment");

    require(limiter.process(transient.data(), 12, processed.data()) == 7,
            "limiter can begin another reduced segment after completed drain");
    require(limiter.drain(partialDrain.data(), 1) == 1,
            "second segment can also be partially drained");
    require(limiter.hasPendingOutput(),
            "second partial drain retains delayed audio before reset");
    limiter.reset();
    require(!limiter.hasPendingOutput(),
            "reset after partial drain discards all remaining delayed audio");
    const auto afterPartialReset = render(limiter, nextSegment, {2, 5}, 1);
    require(afterPartialReset == nextSegment,
            "reset after partial drain also clears the active release envelope");
}

} // namespace

int main() {
    testDbConversionClampingAndSmoothing();
    testLimiterTimingContract();
    testZeroLookaheadAndInvalidArguments();
    testRepeatedPrepareClearsAllStreamingState();
    testQuietPassthroughAndExactEosFrameCount();
    testCeilingStereoLinkAndRelease();
    testDeterministicFixedSlopeConstraintOrderingAndBoundaries();
    testCeilingLevelsAndStereoConfigurationsAgainstOracle();
    testFixedSeedRandomizedStreamsAgainstScalarOracle();
    testLongAbsoluteFrameIndexPrecisionAgainstScalarOracle();
    testChunkInvariance();
    testResetAndFiniteSanitization();
    testPartialDrainResetAndCompletedDrainLifecycle();
    std::cout << "PASS: Source boost and limiter DSP host contract\n";
    return 0;
}
