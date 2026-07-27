#include "../../../main/cpp/recorder/RecorderCanonicalRatePolicy.h"

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
}

int main() {
    require(
        recorderCanonicalRateAction(44'100, false) ==
            RecorderCanonicalRateAction::Direct,
        "canonical device input records directly"
    );
    require(
        recorderCanonicalRateAction(48'000, true) ==
            RecorderCanonicalRateAction::Convert,
        "noncanonical input with a converter is converted"
    );
    require(
        recorderCanonicalRateAction(48'000, false) ==
            RecorderCanonicalRateAction::Reject,
        "noncanonical input without a converter is rejected"
    );
    require(
        recorderCanonicalRateAction(0, false) ==
            RecorderCanonicalRateAction::Reject,
        "invalid input rate is rejected"
    );

    std::cout << "PASS: Recorder canonical-rate conversion failure policy checks\n";
    return 0;
}
