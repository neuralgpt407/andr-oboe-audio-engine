#include "../../../main/cpp/recorder/Pcm16WavHeader.h"
#include "../../../main/cpp/recorder/Pcm16WavWriter.h"
#include "../../../main/cpp/recorder/RecorderWaveformAccumulator.h"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

int16_t readLe16(const std::vector<uint8_t>& bytes, size_t offset) {
    return static_cast<int16_t>(
        static_cast<uint16_t>(bytes[offset]) |
        (static_cast<uint16_t>(bytes[offset + 1]) << 8)
    );
}

uint32_t readLe32(const std::vector<uint8_t>& bytes, size_t offset) {
    return static_cast<uint32_t>(bytes[offset]) |
        (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
        (static_cast<uint32_t>(bytes[offset + 2]) << 16) |
        (static_cast<uint32_t>(bytes[offset + 3]) << 24);
}

void writeLe32(std::ofstream& file, uint32_t value) {
    const char bytes[] = {
        static_cast<char>(value & 0xff),
        static_cast<char>((value >> 8) & 0xff),
        static_cast<char>((value >> 16) & 0xff),
        static_cast<char>((value >> 24) & 0xff),
    };
    file.write(bytes, sizeof(bytes));
}

std::vector<uint8_t> readAll(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    return std::vector<uint8_t>(
        std::istreambuf_iterator<char>(input),
        std::istreambuf_iterator<char>()
    );
}

void require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

void writeSamples(const std::filesystem::path& path, const std::vector<int16_t>& samples) {
    std::fstream file(path, std::ios::binary | std::ios::in | std::ios::out);
    file.seekp(44, std::ios::beg);
    file.write(
        reinterpret_cast<const char*>(samples.data()),
        static_cast<std::streamsize>(samples.size() * sizeof(int16_t))
    );
    require(static_cast<bool>(file), "seed draft samples");
}

void testFullDurationDraft(const std::filesystem::path& path) {
    require(Pcm16WavWriter::createFullDurationDraft(path.string(), 1000, 20), "create full-duration draft");
    Pcm16WavInfo info;
    require(Pcm16WavWriter::validateDraft(path.string(), 1000, &info), "validate full-duration draft");
    require(info.frameCount == 20, "full-duration draft has exact frame count");
    require(info.dataOffsetBytes == 44, "new draft uses standard data offset");

    const auto bytes = readAll(path);
    require(bytes.size() == 44 + 40, "full-duration draft is preallocated with silence");
    require(readLe32(bytes, 40) == 40, "full-duration draft data size is persisted");
    for (size_t i = 44; i < bytes.size(); ++i) {
        require(bytes[i] == 0, "full-duration draft is blank");
    }
}

void testMismatchDoesNotMutate(const std::filesystem::path& path) {
    const auto stereoHeader = buildPcm16WavHeader(8, 1000, 2);
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    file.write(stereoHeader.data(), static_cast<std::streamsize>(stereoHeader.size()));
    const int16_t payload[] = {10, 20, 30, 40};
    file.write(reinterpret_cast<const char*>(payload), sizeof(payload));
    file.close();

    const auto before = readAll(path);
    Pcm16WavWriter writer;
    require(!writer.open(path.string(), 1000, 1, 0), "stereo draft is rejected");
    require(readAll(path) == before, "rejected header never mutates source bytes");

    require(Pcm16WavWriter::createFullDurationDraft(path.string(), 800, 4), "create rate mismatch draft");
    const auto rateMismatch = readAll(path);
    require(!writer.open(path.string(), 1000, 1, 0), "sample-rate mismatch is rejected");
    require(readAll(path) == rateMismatch, "sample-rate mismatch never mutates source bytes");

    const auto pcmHeader = buildPcm16WavHeader(8, 1000, 1);
    std::ofstream formatFile(path, std::ios::binary | std::ios::trunc);
    formatFile.write(pcmHeader.data(), static_cast<std::streamsize>(pcmHeader.size()));
    formatFile.seekp(20, std::ios::beg);
    const char nonPcmFormat[] = {3, 0};
    formatFile.write(nonPcmFormat, sizeof(nonPcmFormat));
    formatFile.seekp(44, std::ios::beg);
    formatFile.write(reinterpret_cast<const char*>(payload), sizeof(payload));
    formatFile.close();
    const auto formatMismatch = readAll(path);
    require(!writer.open(path.string(), 1000, 1, 0), "non-PCM draft is rejected");
    require(readAll(path) == formatMismatch, "non-PCM mismatch never mutates source bytes");
}

void testRiffChunkParsing(const std::filesystem::path& path) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    file.write("RIFF", 4);
    writeLe32(file, 56);
    file.write("WAVEJUNK", 8);
    writeLe32(file, 4);
    file.write("test", 4);
    const auto standardHeader = buildPcm16WavHeader(8, 1000, 1);
    file.write(standardHeader.data() + 12, 24);
    file.write(standardHeader.data() + 36, 8);
    const int16_t samples[] = {100, 200, 300, 400};
    file.write(reinterpret_cast<const char*>(samples), sizeof(samples));
    file.close();

    Pcm16WavInfo info;
    require(Pcm16WavWriter::validateDraft(path.string(), 1000, &info), "parse RIFF chunks before fmt/data");
    require(info.dataOffsetBytes == 56, "discover non-standard data chunk offset");
    Pcm16WavWriter writer;
    require(writer.open(path.string(), 1000, 1, 0), "open parsed RIFF draft");
    const int16_t replacement[] = {1000, 1000, 1000, 1000};
    require(writer.write(replacement, 4), "replace parsed RIFF draft");
    require(writer.close(), "close parsed RIFF draft");
    const auto bytes = readAll(path);
    require(std::string(bytes.begin() + 12, bytes.begin() + 16) == "JUNK", "preserve non-audio RIFF chunks");
}

void testContainedReplacement(const std::filesystem::path& path) {
    require(Pcm16WavWriter::createFullDurationDraft(path.string(), 1000, 16), "create contained draft");
    std::vector<int16_t> original(16);
    for (size_t index = 0; index < original.size(); ++index) {
        original[index] = static_cast<int16_t>(6000 + index * 100);
    }
    writeSamples(path, original);

    Pcm16WavWriter writer;
    require(writer.open(path.string(), 1000, 1, 4), "open contained replacement");
    const int16_t replacement[] = {-8000, -8000, -8000, -8000, -8000, -8000};
    require(writer.write(replacement, 6), "write contained replacement");
    require(writer.close(), "close contained replacement");

    const auto bytes = readAll(path);
    for (size_t index = 0; index < 4; ++index) {
        require(readLe16(bytes, 44 + index * 2) == original[index], "left side of contained replacement is preserved");
    }
    for (size_t index = 10; index < original.size(); ++index) {
        require(readLe16(bytes, 44 + index * 2) == original[index], "right side of contained replacement is preserved");
    }
    const int16_t before = readLe16(bytes, 44 + 3 * 2);
    const int16_t first = readLe16(bytes, 44 + 4 * 2);
    const int16_t last = readLe16(bytes, 44 + 9 * 2);
    const int16_t after = readLe16(bytes, 44 + 10 * 2);
    require(std::abs(static_cast<int>(first) - before) < 5000, "fade-in bounds punch discontinuity");
    require(std::abs(static_cast<int>(after) - last) < 5000, "fade-out bounds punch discontinuity");
}

void testExtendedReplacement(const std::filesystem::path& path) {
    require(Pcm16WavWriter::createFullDurationDraft(path.string(), 1000, 5), "create extension source");
    writeSamples(path, {1000, 2000, 3000, 4000, 5000});

    Pcm16WavWriter writer;
    require(writer.open(path.string(), 1000, 1, 4), "open extending replacement");
    const int16_t replacement[] = {4000, 4000, 4000, 4000};
    require(writer.write(replacement, 4), "write extending replacement");
    require(writer.close(), "close extending replacement");

    Pcm16WavInfo info;
    require(Pcm16WavWriter::validateDraft(path.string(), 1000, &info), "validate extended draft");
    require(info.frameCount == 8, "replacement extends into implicit silence");
    const auto bytes = readAll(path);
    for (size_t index = 0; index < 4; ++index) {
        require(readLe16(bytes, 44 + index * 2) == static_cast<int16_t>((index + 1) * 1000), "extension preserves frames before range");
    }
}

void testExactFrameReplacementPreservesSuffix(const std::filesystem::path& path) {
    constexpr int kSampleRate = 44'100;
    constexpr int64_t kStartFrame = 100;
    require(Pcm16WavWriter::createFullDurationDraft(path.string(), kSampleRate, 1'002), "create exact-frame source");
    std::vector<int16_t> original(1'002);
    for (size_t index = 0; index < original.size(); ++index) {
        original[index] = static_cast<int16_t>(500 + index);
    }
    writeSamples(path, original);

    Pcm16WavWriter writer;
    require(writer.openAtFrame(path.string(), kSampleRate, 1, kStartFrame), "open non-millisecond frame offset");
    std::vector<int16_t> replacement(300, -4'000);
    require(writer.write(replacement.data(), replacement.size()), "write exact-frame replacement");
    require(writer.close(), "close exact-frame replacement");
    require(writer.framesWritten() == static_cast<int64_t>(replacement.size()), "exact-frame writer reports every accepted frame");

    Pcm16WavInfo info;
    require(Pcm16WavWriter::validateDraft(path.string(), kSampleRate, &info), "validate exact-frame replacement");
    require(info.frameCount == static_cast<int64_t>(original.size()), "contained replacement retains duration");
    const auto after = readAll(path);
    for (size_t index = 0; index < static_cast<size_t>(kStartFrame); ++index) {
        require(readLe16(after, 44 + index * 2) == original[index], "exact-frame replacement preserves prefix");
    }
    for (size_t index = kStartFrame + replacement.size(); index < original.size(); ++index) {
        require(readLe16(after, 44 + index * 2) == original[index], "exact-frame replacement preserves suffix");
    }
    require(
        readLe16(after, 44 + (kStartFrame + 220) * 2) != original[kStartFrame + 220],
        "replacement begins from the requested PCM-frame region"
    );
}

void testTenFramePreciseContinuationCycles(const std::filesystem::path& path) {
    constexpr int kSampleRate = 44'100;
    constexpr int kFramesPerCycle = 1'411;
    constexpr int kCycleCount = 10;
    constexpr int kSuffixFrames = 73;
    constexpr int64_t kAcceptedFrames =
        static_cast<int64_t>(kFramesPerCycle) * kCycleCount;
    require(
        Pcm16WavWriter::createFullDurationDraft(
            path.string(),
            kSampleRate,
            kAcceptedFrames + kSuffixFrames
        ),
        "create ten-cycle source"
    );
    std::vector<int16_t> original(
        static_cast<size_t>(kAcceptedFrames + kSuffixFrames),
        300
    );
    writeSamples(path, original);
    const auto before = readAll(path);

    int64_t cumulativeAcceptedFrames = 0;
    for (int cycle = 0; cycle < kCycleCount; ++cycle) {
        Pcm16WavWriter writer;
        require(
            writer.openAtFrame(
                path.string(),
                kSampleRate,
                1,
                cumulativeAcceptedFrames
            ),
            "reopen continuation at exact cumulative frame"
        );
        std::vector<int16_t> segment(
            kFramesPerCycle,
            static_cast<int16_t>(2'000 + cycle * 100)
        );
        RecorderWaveformAccumulator telemetry;
        telemetry.reset(kSampleRate);

        require(writer.write(segment.data(), segment.size()), "write continuation segment");
        telemetry.appendPcm16Mono(segment.data(), kFramesPerCycle);
        require(writer.close(), "close continuation segment");
        require(
            writer.framesWritten() == static_cast<int64_t>(segment.size()),
            "accepted and written frames remain equal"
        );

        const RecorderWaveformDelta delta = telemetry.readDelta(0);
        require(delta.firstBucketIndex == 0, "new segment telemetry cursor starts at zero");
        require(delta.completedBucketRms.size() == 1, "accepted segment aligns to one 32 ms waveform bucket");
        require(
            std::fabs(
                delta.completedBucketRms[0] -
                    static_cast<float>(segment[0]) / 32768.0f
            ) < 0.0001f,
            "waveform RMS remains aligned with the accepted segment"
        );
        require(delta.partialBucketFrames == 0, "waveform bucket has no frame drift");
        require(
            telemetry.readDelta(1).completedBucketRms.empty(),
            "consumed telemetry cursor does not replay a bucket"
        );
        cumulativeAcceptedFrames += writer.framesWritten();
    }

    require(cumulativeAcceptedFrames == kAcceptedFrames, "ten cycles preserve cumulative accepted frames");
    require(
        (cumulativeAcceptedFrames * 1'000) / kSampleRate == 319,
        "ten cycles preserve cumulative frame-derived duration"
    );
    Pcm16WavInfo info;
    require(Pcm16WavWriter::validateDraft(path.string(), kSampleRate, &info), "validate ten-cycle draft");
    require(
        info.frameCount == kAcceptedFrames + kSuffixFrames,
        "ten cycles retain the untouched suffix duration"
    );
    const auto after = readAll(path);
    const size_t suffixByteOffset = 44 + static_cast<size_t>(kAcceptedFrames) * 2;
    require(
        std::equal(
            before.begin() + static_cast<std::ptrdiff_t>(suffixByteOffset),
            before.end(),
            after.begin() + static_cast<std::ptrdiff_t>(suffixByteOffset)
        ),
        "ten cycles retain every untouched suffix byte"
    );
}

void testBoostAndCeiling(const std::filesystem::path& path) {
    require(Pcm16WavWriter::createFullDurationDraft(path.string(), 1000, 20), "create gain draft");
    Pcm16WavWriter writer;
    require(writer.open(path.string(), 1000, 1, 0), "open gain draft");
    std::vector<int16_t> quiet(12, 1000);
    require(writer.write(quiet.data(), quiet.size()), "write quiet samples");
    require(writer.close(), "close quiet samples");
    auto bytes = readAll(path);
    require(readLe16(bytes, 44 + 6 * 2) > 1800, "writer thread boosts quiet PCM by roughly 6 dB");

    require(writer.open(path.string(), 1000, 1, 0), "reopen gain draft");
    std::vector<int16_t> loud(12, 32767);
    require(writer.write(loud.data(), loud.size()), "write loud samples");
    require(writer.close(), "close loud samples");
    bytes = readAll(path);
    require(std::abs(static_cast<int>(readLe16(bytes, 44 + 6 * 2))) <= 32113, "limiter ceiling remains at 0.98");
}

}

int main() {
    const auto tempDir = std::filesystem::temp_directory_path() / "oboe_recorder_wav_writer_test";
    std::filesystem::remove_all(tempDir);
    std::filesystem::create_directories(tempDir);

    testFullDurationDraft(tempDir / "full.wav");
    testMismatchDoesNotMutate(tempDir / "mismatch.wav");
    testRiffChunkParsing(tempDir / "chunks.wav");
    testContainedReplacement(tempDir / "contained.wav");
    testExtendedReplacement(tempDir / "extended.wav");
    testExactFrameReplacementPreservesSuffix(tempDir / "exact-frame.wav");
    testTenFramePreciseContinuationCycles(tempDir / "ten-cycles.wav");
    testBoostAndCeiling(tempDir / "gain.wav");

    std::cout << "PASS: Pcm16WavWriter exact-frame replacement/ten-cycle continuation checks\n";
    std::cout << "artifact=" << tempDir << '\n';
    return 0;
}
