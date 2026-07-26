#include "Pcm16WavHeader.h"

#include <cstring>

namespace {
constexpr int kBytesPerSample = 2;

void putLe16(std::array<char, kPcm16WavHeaderBytes>& header, size_t offset, uint16_t value) {
    header[offset] = static_cast<char>(value & 0xff);
    header[offset + 1] = static_cast<char>((value >> 8) & 0xff);
}

void putLe32(std::array<char, kPcm16WavHeaderBytes>& header, size_t offset, uint32_t value) {
    header[offset] = static_cast<char>(value & 0xff);
    header[offset + 1] = static_cast<char>((value >> 8) & 0xff);
    header[offset + 2] = static_cast<char>((value >> 16) & 0xff);
    header[offset + 3] = static_cast<char>((value >> 24) & 0xff);
}
}

std::array<char, kPcm16WavHeaderBytes> buildPcm16WavHeader(
    uint32_t dataBytes,
    int sampleRate,
    int channelCount
) {
    std::array<char, kPcm16WavHeaderBytes> header{};
    std::memcpy(header.data(), "RIFF", 4);
    putLe32(header, 4, 36u + dataBytes);
    std::memcpy(header.data() + 8, "WAVE", 4);
    std::memcpy(header.data() + 12, "fmt ", 4);
    putLe32(header, 16, 16);
    putLe16(header, 20, 1);
    putLe16(header, 22, static_cast<uint16_t>(channelCount));
    putLe32(header, 24, static_cast<uint32_t>(sampleRate));
    putLe32(header, 28, static_cast<uint32_t>(sampleRate * channelCount * kBytesPerSample));
    putLe16(header, 32, static_cast<uint16_t>(channelCount * kBytesPerSample));
    putLe16(header, 34, 16);
    std::memcpy(header.data() + 36, "data", 4);
    putLe32(header, 40, dataBytes);
    return header;
}
