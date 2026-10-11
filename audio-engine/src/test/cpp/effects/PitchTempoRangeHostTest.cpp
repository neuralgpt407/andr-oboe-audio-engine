// Host contract test for the tempo/pitch range exposed by PitchTempo (FEAT-0001 FR-6):
// tempo 0.5..2.0 and pitch -10..10 must be applied, not silently narrowed.

#include "PitchTempoProcessor.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {
int g_failures = 0;

void check(bool condition, const char* message) {
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++g_failures;
    }
}

struct RunResult {
    long inputFrames = 0;
    long outputFrames = 0;
    bool finite = true;
};

RunResult run(float tempo, int pitch) {
    constexpr int kRate = 44100;
    constexpr int kChannels = 2;
    constexpr int kBlock = 512;
    PitchTempoProcessor processor;
    processor.prepare(kRate, kChannels, kBlock);
    processor.setTempo(tempo);
    processor.setPitchSemitones(pitch);

    std::vector<float> input(static_cast<size_t>(kBlock * 4 * kChannels));
    std::vector<float> output(static_cast<size_t>(kBlock * kChannels));
    RunResult result;
    double phase = 0.0;
    for (int block = 0; block < 400; ++block) {
        const int needed = processor.inputFramesNeeded(kBlock);
        for (int f = 0; f < needed; ++f) {
            const float v = 0.3f * static_cast<float>(std::sin(phase));
            phase += 2.0 * 3.14159265358979 * 440.0 / kRate;
            input[static_cast<size_t>(f * 2)] = v;
            input[static_cast<size_t>(f * 2 + 1)] = v;
        }
        const int produced = processor.process(input.data(), needed, output.data(), kBlock);
        for (int i = 0; i < produced * kChannels; ++i) {
            if (!std::isfinite(output[static_cast<size_t>(i)])) result.finite = false;
        }
        result.inputFrames += needed;
        result.outputFrames += produced;
    }
    return result;
}
} // namespace

int main() {
    PitchTempoProcessor clampProbe;
    clampProbe.prepare(44100, 2, 512);
    clampProbe.setTempo(2.0f);
    check(std::fabs(clampProbe.currentTempo() - 2.0f) < 1e-6f, "tempo 2.0 is accepted unchanged");
    clampProbe.setTempo(3.0f);
    check(std::fabs(clampProbe.currentTempo() - 2.0f) < 1e-6f, "tempo above 2.0 clamps to 2.0");
    clampProbe.setTempo(0.25f);
    check(std::fabs(clampProbe.currentTempo() - 0.5f) < 1e-6f, "tempo below 0.5 clamps to 0.5");

    const RunResult fast = run(2.0f, -10);
    check(fast.finite, "tempo 2.0 output is finite");
    const double fastRatio = static_cast<double>(fast.inputFrames) / static_cast<double>(fast.outputFrames);
    std::printf("tempo 2.0: in=%ld out=%ld ratio=%.3f\n", fast.inputFrames, fast.outputFrames, fastRatio);
    check(fastRatio > 1.9 && fastRatio < 2.1, "tempo 2.0 consumes about twice the frames it outputs");

    const RunResult slow = run(0.5f, 10);
    check(slow.finite, "tempo 0.5 output is finite");
    const double slowRatio = static_cast<double>(slow.inputFrames) / static_cast<double>(slow.outputFrames);
    std::printf("tempo 0.5: in=%ld out=%ld ratio=%.3f\n", slow.inputFrames, slow.outputFrames, slowRatio);
    check(slowRatio > 0.45 && slowRatio < 0.55, "tempo 0.5 consumes about half the frames it outputs");

    if (g_failures == 0) {
        std::printf("PASS: PitchTempoRange\n");
        return 0;
    }
    return 1;
}
