// S2-AS3: the playback limiter must behave like the export filter
// `alimiter limit=0.98 attack=5 release=50` so preview and export sound the same.
// Reference envelope: a 1 kHz burst boosted by +12 dB settles at the 0.98 ceiling
// within the 5 ms attack, never exceeds it, and the gain recovers within the 50 ms
// release once the burst ends.

#include "StereoLookaheadLimiter.h"

#include <cmath>
#include <cstdio>
#include <vector>

using neuralsound::audio::dsp::StereoLookaheadLimiter;

namespace {
int g_failures = 0;
void check(bool condition, const char* message) {
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++g_failures;
    }
}

double db(double ratio) { return 20.0 * std::log10(ratio); }

double peak(const std::vector<float>& s, int from, int to) {
    double p = 0.0;
    for (int f = from; f < to; ++f) {
        p = std::max(p, static_cast<double>(std::fabs(s[static_cast<size_t>(f * 2)])));
    }
    return p;
}
} // namespace

int main() {
    constexpr int kRate = 44100;
    constexpr double kPi = 3.14159265358979323846;
    const double boost = std::pow(10.0, 12.0 / 20.0);
    const int burstFrames = kRate / 5;            // 200 ms at +12 dB
    const int tailFrames = kRate / 2;             // 500 ms quiet tone at -20 dBFS
    const int total = burstFrames + tailFrames;

    std::vector<float> input(static_cast<size_t>(total * 2));
    for (int f = 0; f < total; ++f) {
        const double base = std::sin(2.0 * kPi * 1000.0 * f / kRate);
        const double v = f < burstFrames ? 0.9 * boost * base : 0.1 * base;
        input[static_cast<size_t>(f * 2)] = static_cast<float>(v);
        input[static_cast<size_t>(f * 2 + 1)] = static_cast<float>(v);
    }

    StereoLookaheadLimiter limiter;
    limiter.prepare(kRate);
    std::vector<float> output;
    output.reserve(input.size() + 4096);
    std::vector<float> block(1024 * 2 + 8192);
    for (int f = 0; f < total; f += 512) {
        const int n = std::min(512, total - f);
        const int produced = limiter.process(&input[static_cast<size_t>(f * 2)], n, block.data());
        output.insert(output.end(), block.begin(), block.begin() + produced * 2);
    }
    while (limiter.hasPendingOutput()) {
        const int produced = limiter.drain(block.data(), 512);
        if (produced <= 0) break;
        output.insert(output.end(), block.begin(), block.begin() + produced * 2);
    }
    const int latency = limiter.latencyFrames();
    const int outFrames = static_cast<int>(output.size() / 2);
    check(outFrames >= total, "every input frame is emitted after the lookahead delay");
    std::printf("latency %d frames (%.2f ms)\n", latency, latency * 1000.0 / kRate);
    check(latency >= kRate * 4 / 1000 && latency <= kRate * 6 / 1000, "lookahead is about 5 ms");

    const double overall = peak(output, 0, outFrames);
    std::printf("overall peak %.4f\n", overall);
    check(overall <= StereoLookaheadLimiter::kCeiling + 1e-4, "output never exceeds the 0.98 ceiling");

    // Steady state inside the burst (after the 5 ms attack): sits at the ceiling.
    const double held = peak(output, latency + kRate / 50, latency + burstFrames - kRate / 100);
    std::printf("held peak %.4f (%.2f dB from ceiling)\n", held, db(held / 0.98));
    check(std::fabs(db(held / 0.98)) <= 0.5, "burst is held within 0.5 dB of the ceiling");

    // After the 50 ms release (allow 3x for the exponential tail), the quiet tone is unattenuated.
    const int recovered = latency + burstFrames + 3 * kRate / 20;
    const double tail = peak(output, recovered, recovered + kRate / 10);
    std::printf("tail peak %.4f (%.2f dB from 0.1)\n", tail, db(tail / 0.1));
    check(std::fabs(db(tail / 0.1)) <= 0.5, "gain recovers within the release window");

    if (g_failures == 0) {
        std::printf("PASS: Limiter matches alimiter reference envelope\n");
        return 0;
    }
    return 1;
}
