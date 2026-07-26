#include "../../../main/cpp/recorder/RecorderWaveformAccumulator.h"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {
void require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

bool near(float actual, float expected) {
    return std::fabs(actual - expected) < 0.02f;
}
}

int main() {
    RecorderWaveformAccumulator accumulator;
    accumulator.reset(1000);

    const int16_t firstBucket[] = {0, 8192, -16384, 4096};
    accumulator.appendPcm16Mono(firstBucket, 4);
    auto delta = accumulator.readDelta(0);
    require(delta.completedBucketRms.empty(), "partial buckets are not committed");
    require(delta.partialBucketFrames == 4, "partial bucket reports exact accepted frames");
    require(near(delta.partialBucketRms, 0.286f), "partial bucket reports raw RMS rather than peak");

    int16_t completeBucket[28] = {};
    accumulator.appendPcm16Mono(completeBucket, 28);
    delta = accumulator.readDelta(0);
    require(delta.firstBucketIndex == 0, "delta preserves the caller cursor");
    require(delta.completedBucketRms.size() == 1, "only newly completed bucket is returned");
    require(near(delta.completedBucketRms[0], 0.101f), "completed RMS includes every frame in the bucket");
    require(delta.partialBucketFrames == 0, "no synthetic partial frames are reported");

    accumulator.reset(44100);
    int16_t tone[2823] = {};
    tone[0] = 16384;
    accumulator.appendPcm16Mono(tone, 2823);
    delta = accumulator.readDelta(0);
    require(delta.completedBucketRms.size() == 2, "44.1kHz rational buckets complete without drift");
    require(delta.firstBucketIndex == 0, "rational bucket cursor starts at zero");
    require(accumulator.readDelta(2).completedBucketRms.empty(), "consumed buckets are never replayed");

    accumulator.reset(1000);
    require(accumulator.readDelta(0).completedBucketRms.empty(), "reset clears telemetry");

    std::cout << "PASS: RecorderWaveformAccumulator RMS delta/rational-cursor checks\n";
    return 0;
}
