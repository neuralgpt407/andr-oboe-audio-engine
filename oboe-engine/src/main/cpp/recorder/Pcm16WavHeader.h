#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

constexpr int kPcm16WavHeaderBytes = 44;

std::array<char, kPcm16WavHeaderBytes> buildPcm16WavHeader(
    uint32_t dataBytes,
    int sampleRate,
    int channelCount
);
