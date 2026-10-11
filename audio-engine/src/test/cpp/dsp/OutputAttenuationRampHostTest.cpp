#include "OutputAttenuationRamp.h"
#include "OutputSignalChain.h"
#include "SourceBoost.h"
#include "StereoLookaheadLimiter.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <vector>

using neuralsound::audio::dsp::OutputAttenuationRamp;
using neuralsound::audio::dsp::OutputSignalStage;
using neuralsound::audio::dsp::SourceBoost;
using neuralsound::audio::dsp::StereoLookaheadLimiter;
using neuralsound::audio::dsp::applyPostLimiterOutputAttenuation;
using neuralsound::audio::dsp::outputSignalStagePrecedes;

namespace {

void require(bool value, const char* message) {
    if (!value) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

bool near(float actual, float expected, float tolerance = 1.0e-5f) {
    return std::fabs(actual - expected) <= tolerance;
}

void testUnityDuckUnityTransitionAndBounds() {
    OutputAttenuationRamp ramp;
    ramp.prepare(1'000);
    require(ramp.rampFrames() == 5, "attenuation ramp lasts five milliseconds");
    require(near(ramp.nextGain(), 1.0f), "attenuation starts at unity");

    ramp.setTarget(0.2f);
    const float duckExpected[] = {0.84f, 0.68f, 0.52f, 0.36f, 0.2f};
    float previous = 1.0f;
    for (float expected : duckExpected) {
        const float gain = ramp.nextGain();
        require(near(gain, expected), "duck reaches its target with a linear frame ramp");
        require(gain >= 0.2f && gain <= previous, "duck ramp stays within its endpoints");
        previous = gain;
    }
    require(near(ramp.target(), 0.2f), "duck target remains observable");

    ramp.setTarget(1.0f);
    const float restoreExpected[] = {0.36f, 0.52f, 0.68f, 0.84f, 1.0f};
    previous = 0.2f;
    for (float expected : restoreExpected) {
        const float gain = ramp.nextGain();
        require(near(gain, expected), "restoration reaches unity with the same bounded ramp");
        require(gain >= previous && gain <= 1.0f, "restore ramp stays within its endpoints");
        previous = gain;
    }

    ramp.setTarget(-4.0f);
    require(near(ramp.target(), 0.0f), "negative attenuation clamps to silence");
    ramp.setTarget(std::numeric_limits<float>::quiet_NaN());
    require(near(ramp.target(), 1.0f), "non-finite attenuation restores a safe unity target");
}

void testHighSourceBoostIsLimitedBeforeDuckAttenuation() {
    require(
        outputSignalStagePrecedes(
            OutputSignalStage::Limiter,
            OutputSignalStage::OutputAttenuation
        ),
        "production signal order keeps attenuation after the limiter"
    );

    SourceBoost boost;
    boost.prepare(1'000);
    boost.setTargetDb(SourceBoost::kMaxDb);
    boost.advanceFrames(boost.rampFrames());

    std::vector<float> boosted(20 * 2, 0.0f);
    for (int frame = 0; frame < 20; ++frame) {
        const float sample = 0.5f * boost.nextLinearGain();
        boosted[static_cast<size_t>(frame) * 2] = sample;
        boosted[static_cast<size_t>(frame) * 2 + 1] = sample;
    }

    StereoLookaheadLimiter limiter;
    limiter.prepare(1'000);
    std::vector<float> block(boosted.size(), 0.0f);
    std::vector<float> limited;
    const int processed = limiter.process(boosted.data(), 20, block.data());
    limited.insert(limited.end(), block.begin(), block.begin() + processed * 2);
    while (limiter.hasPendingOutput()) {
        const int drained = limiter.drain(block.data(), 20);
        limited.insert(limited.end(), block.begin(), block.begin() + drained * 2);
    }

    const float limitedPeak = *std::max_element(limited.begin(), limited.end());
    require(near(limitedPeak, StereoLookaheadLimiter::kCeiling),
            "maximum source boost reaches the limiter ceiling");

    OutputAttenuationRamp ramp;
    ramp.prepare(1'000);
    ramp.setTarget(0.2f);
    std::vector<float> rampFrames(static_cast<size_t>(ramp.rampFrames()) * 2, 0.0f);
    applyPostLimiterOutputAttenuation(ramp, rampFrames.data(), ramp.rampFrames());
    float postLimiterFrame[] = {limitedPeak, limitedPeak};
    applyPostLimiterOutputAttenuation(ramp, postLimiterFrame, 1);
    require(near(postLimiterFrame[0], StereoLookaheadLimiter::kCeiling * 0.2f),
            "duck attenuation remains effective after high boost and limiting");
}

} // namespace

int main() {
    testUnityDuckUnityTransitionAndBounds();
    testHighSourceBoostIsLimitedBeforeDuckAttenuation();
    std::cout << "PASS: Output attenuation ramp host contract\n";
    return 0;
}
