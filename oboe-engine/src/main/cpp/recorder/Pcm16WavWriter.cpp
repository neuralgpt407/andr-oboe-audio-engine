#include "Pcm16WavWriter.h"
#include "Pcm16WavHeader.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <limits>

namespace {
constexpr int kBytesPerSample = 2;
constexpr int kFadeMs = 5;
constexpr int kMaxFadeFrames = 256;
constexpr double kLimiterCeiling = 32767.0 * 0.98;
constexpr double kPiOverTwo = 1.57079632679489661923;

uint16_t readLe16(const std::array<char, 2>& bytes) {
    return static_cast<uint16_t>(static_cast<uint8_t>(bytes[0])) |
        (static_cast<uint16_t>(static_cast<uint8_t>(bytes[1])) << 8);
}

uint32_t readLe32(const std::array<char, 4>& bytes) {
    return static_cast<uint32_t>(static_cast<uint8_t>(bytes[0])) |
        (static_cast<uint32_t>(static_cast<uint8_t>(bytes[1])) << 8) |
        (static_cast<uint32_t>(static_cast<uint8_t>(bytes[2])) << 16) |
        (static_cast<uint32_t>(static_cast<uint8_t>(bytes[3])) << 24);
}

int16_t clampSample(double value) {
    const auto rounded = static_cast<int32_t>(value >= 0.0 ? value + 0.5 : value - 0.5);
    return static_cast<int16_t>(std::clamp(
        rounded,
        static_cast<int32_t>(std::numeric_limits<int16_t>::min()),
        static_cast<int32_t>(std::numeric_limits<int16_t>::max())
    ));
}

int16_t boostSample(int16_t sample) {
    const double normalized = static_cast<double>(sample) / 32768.0;
    return clampSample(kLimiterCeiling * std::tanh(2.0 * normalized) / std::tanh(2.0));
}

int16_t equalPowerMix(int16_t original, int16_t replacement, double replacementProgress) {
    const double angle = std::clamp(replacementProgress, 0.0, 1.0) * kPiOverTwo;
    return clampSample(std::clamp(
        static_cast<double>(original) * std::cos(angle) +
            static_cast<double>(replacement) * std::sin(angle),
        -kLimiterCeiling,
        kLimiterCeiling
    ));
}

bool prepareParentDirectory(const std::filesystem::path& filePath) {
    if (!filePath.has_parent_path()) return true;
    std::error_code error;
    std::filesystem::create_directories(filePath.parent_path(), error);
    return !error;
}
}

Pcm16WavWriter::~Pcm16WavWriter() {
    if (isOpen_) close();
}

bool Pcm16WavWriter::createFullDurationDraft(const std::string& path, int sampleRate, int64_t frameCount) {
    if (path.empty() || sampleRate <= 0 || frameCount < 0 ||
        frameCount > std::numeric_limits<uint32_t>::max() / kBytesPerSample) {
        return false;
    }
    const std::filesystem::path filePath(path);
    if (!prepareParentDirectory(filePath)) return false;

    const uint32_t dataBytes = static_cast<uint32_t>(frameCount * kBytesPerSample);
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file.is_open()) return false;
    const auto header = buildPcm16WavHeader(dataBytes, sampleRate, 1);
    file.write(header.data(), static_cast<std::streamsize>(header.size()));
    std::array<char, 4096> silence{};
    int64_t remaining = dataBytes;
    while (remaining > 0) {
        const auto chunk = static_cast<std::streamsize>(std::min<int64_t>(remaining, silence.size()));
        file.write(silence.data(), chunk);
        if (!file) return false;
        remaining -= chunk;
    }
    return static_cast<bool>(file);
}

bool Pcm16WavWriter::validateDraft(const std::string& path, int expectedSampleRate, Pcm16WavInfo* info) {
    if (path.empty() || expectedSampleRate <= 0 || info == nullptr) return false;
    Pcm16WavWriter parser;
    parser.path_ = path;
    parser.sampleRate_ = expectedSampleRate;
    parser.file_.open(path, std::ios::binary | std::ios::in);
    if (!parser.file_.is_open()) return false;
    const bool valid = parser.parseExistingFile(info);
    parser.file_.close();
    return valid;
}

bool Pcm16WavWriter::open(const std::string& path, int sampleRate, int channelCount, int64_t startOffsetMs) {
    if (path.empty() || sampleRate <= 0 || channelCount != 1 || startOffsetMs < 0) {
        lastError_ = "invalid wav writer parameters";
        return false;
    }
    const int64_t wholeSeconds = startOffsetMs / 1'000;
    const int64_t remainingMs = startOffsetMs % 1'000;
    if (wholeSeconds > std::numeric_limits<int64_t>::max() / sampleRate) {
        lastError_ = "start offset is too large";
        return false;
    }
    const int64_t wholeFrames = wholeSeconds * sampleRate;
    const int64_t remainingFrames =
        (remainingMs * static_cast<int64_t>(sampleRate)) / 1'000;
    if (wholeFrames > std::numeric_limits<int64_t>::max() - remainingFrames) {
        lastError_ = "start offset is too large";
        return false;
    }
    return openAtFrame(
        path,
        sampleRate,
        channelCount,
        wholeFrames + remainingFrames
    );
}

bool Pcm16WavWriter::openAtFrame(
    const std::string& path,
    int sampleRate,
    int channelCount,
    int64_t startOffsetFrames
) {
    if (path.empty() || sampleRate <= 0 || channelCount != 1 || startOffsetFrames < 0) {
        lastError_ = "invalid wav writer parameters";
        return false;
    }
    if (isOpen_) close();

    path_ = path;
    sampleRate_ = sampleRate;
    channelCount_ = channelCount;
    framesWritten_ = 0;
    pendingSamples_.clear();
    pendingOriginalSamples_.clear();
    lastError_.clear();
    if (startOffsetFrames > std::numeric_limits<int64_t>::max() / kBytesPerSample) {
        lastError_ = "start offset is too large";
        return false;
    }
    writeOffsetBytes_ = startOffsetFrames * kBytesPerSample;
    writeCursorBytes_ = writeOffsetBytes_;

    const std::filesystem::path filePath(path_);
    const bool exists = std::filesystem::exists(filePath);
    if (!exists) {
        if (!prepareParentDirectory(filePath) || !createFullDurationDraft(path_, sampleRate_, 0)) {
            lastError_ = "unable to create wav file";
            return false;
        }
    }

    file_.open(path_, std::ios::binary | std::ios::in | std::ios::out);
    if (!file_.is_open()) {
        lastError_ = "unable to open wav file";
        return false;
    }
    Pcm16WavInfo info;
    if (!parseExistingFile(&info)) {
        file_.close();
        return false;
    }
    initialDataBytes_ = info.frameCount * kBytesPerSample;
    currentDataBytes_ = initialDataBytes_;
    isOpen_ = true;
    if (!ensureDataSize(writeOffsetBytes_)) {
        close();
        return false;
    }
    return true;
}

bool Pcm16WavWriter::write(const int16_t* samples, size_t frameCount) {
    if (!isOpen_ || samples == nullptr) {
        lastError_ = "writer is not open";
        return false;
    }
    const int fadeFrames = fadeFrameCount();
    for (size_t index = 0; index < frameCount; ++index) {
        int16_t original = 0;
        if (!readOriginalSample(writeCursorBytes_ + static_cast<int64_t>(pendingSamples_.size()) * kBytesPerSample, &original)) {
            return false;
        }
        const int16_t replacement = boostSample(samples[index]);
        const int64_t sampleIndex = framesWritten_ + static_cast<int64_t>(pendingSamples_.size());
        if (sampleIndex < fadeFrames) {
            const double progress = fadeFrames == 1 ? 1.0 : static_cast<double>(sampleIndex) / (fadeFrames - 1);
            if (!writeSample(equalPowerMix(original, replacement, progress))) return false;
            continue;
        }
        pendingSamples_.push_back(replacement);
        pendingOriginalSamples_.push_back(original);
        if (pendingSamples_.size() > static_cast<size_t>(fadeFrames)) {
            if (!writeSample(pendingSamples_.front())) return false;
            pendingSamples_.erase(pendingSamples_.begin());
            pendingOriginalSamples_.erase(pendingOriginalSamples_.begin());
        }
    }
    return true;
}

bool Pcm16WavWriter::close() {
    if (!isOpen_) return true;
    bool ok = flushPendingSamples();
    ok = updateHeader() && ok;
    file_.flush();
    file_.close();
    isOpen_ = false;
    return ok;
}

int64_t Pcm16WavWriter::framesWritten() const { return framesWritten_; }
int64_t Pcm16WavWriter::dataBytes() const { return currentDataBytes_; }
const std::string& Pcm16WavWriter::lastError() const { return lastError_; }

bool Pcm16WavWriter::parseExistingFile(Pcm16WavInfo* info) {
    std::array<char, 12> riff{};
    file_.seekg(0, std::ios::beg);
    file_.read(riff.data(), static_cast<std::streamsize>(riff.size()));
    if (!file_ || std::memcmp(riff.data(), "RIFF", 4) != 0 || std::memcmp(riff.data() + 8, "WAVE", 4) != 0) {
        lastError_ = "invalid RIFF/WAVE header";
        return false;
    }
    bool hasFormat = false;
    bool hasData = false;
    uint32_t dataBytes = 0;
    int64_t cursor = 12;
    while (true) {
        std::array<char, 8> chunkHeader{};
        file_.seekg(cursor, std::ios::beg);
        file_.read(chunkHeader.data(), static_cast<std::streamsize>(chunkHeader.size()));
        if (!file_) break;
        std::array<char, 4> sizeBytes{};
        std::memcpy(sizeBytes.data(), chunkHeader.data() + 4, sizeBytes.size());
        const uint32_t chunkSize = readLe32(sizeBytes);
        const int64_t payloadOffset = cursor + 8;
        if (std::memcmp(chunkHeader.data(), "fmt ", 4) == 0) {
            if (chunkSize < 16) {
                lastError_ = "invalid wav format chunk";
                return false;
            }
            std::array<char, 16> fmt{};
            file_.seekg(payloadOffset, std::ios::beg);
            file_.read(fmt.data(), static_cast<std::streamsize>(fmt.size()));
            if (!file_) {
                lastError_ = "truncated wav format chunk";
                return false;
            }
            std::array<char, 2> formatBytes{fmt[0], fmt[1]};
            std::array<char, 2> channelBytes{fmt[2], fmt[3]};
            std::array<char, 4> rateBytes{fmt[4], fmt[5], fmt[6], fmt[7]};
            std::array<char, 2> bitsBytes{fmt[14], fmt[15]};
            if (readLe16(formatBytes) != 1 || readLe16(channelBytes) != 1 ||
                readLe16(bitsBytes) != 16 || static_cast<int>(readLe32(rateBytes)) != sampleRate_) {
                lastError_ = "wav format does not match locked mono PCM16 sample rate";
                return false;
            }
            hasFormat = true;
        } else if (std::memcmp(chunkHeader.data(), "data", 4) == 0) {
            if (hasData || chunkSize % kBytesPerSample != 0) {
                lastError_ = "invalid wav data chunk";
                return false;
            }
            dataOffsetBytes_ = payloadOffset;
            dataSizeFieldOffset_ = cursor + 4;
            dataBytes = chunkSize;
            hasData = true;
        }
        const int64_t paddedSize = static_cast<int64_t>(chunkSize) + (chunkSize & 1U);
        if (payloadOffset > std::numeric_limits<int64_t>::max() - paddedSize) {
            lastError_ = "invalid wav chunk size";
            return false;
        }
        cursor = payloadOffset + paddedSize;
    }
    file_.clear();
    std::error_code error;
    const int64_t fileSize = static_cast<int64_t>(std::filesystem::file_size(path_, error));
    if (error || !hasFormat || !hasData || dataOffsetBytes_ + static_cast<int64_t>(dataBytes) > fileSize) {
        lastError_ = "truncated or incomplete wav file";
        return false;
    }
    info->sampleRate = sampleRate_;
    info->frameCount = dataBytes / kBytesPerSample;
    info->dataOffsetBytes = dataOffsetBytes_;
    return true;
}

bool Pcm16WavWriter::writeHeader(uint32_t dataBytes) {
    const uint32_t riffBytes = static_cast<uint32_t>(std::min<int64_t>(
        std::numeric_limits<uint32_t>::max(), dataOffsetBytes_ + static_cast<int64_t>(dataBytes) - 8
    ));
    std::array<char, 4> values{};
    for (int index = 0; index < 4; ++index) values[index] = static_cast<char>((dataBytes >> (index * 8)) & 0xff);
    file_.seekp(dataSizeFieldOffset_, std::ios::beg);
    file_.write(values.data(), static_cast<std::streamsize>(values.size()));
    for (int index = 0; index < 4; ++index) values[index] = static_cast<char>((riffBytes >> (index * 8)) & 0xff);
    file_.seekp(4, std::ios::beg);
    file_.write(values.data(), static_cast<std::streamsize>(values.size()));
    if (!file_) {
        lastError_ = "wav header write failed";
        return false;
    }
    return true;
}

bool Pcm16WavWriter::updateHeader() {
    if (currentDataBytes_ > std::numeric_limits<uint32_t>::max()) {
        lastError_ = "wav data is too large";
        return false;
    }
    return writeHeader(static_cast<uint32_t>(currentDataBytes_));
}

bool Pcm16WavWriter::ensureDataSize(int64_t targetBytes) {
    if (targetBytes <= currentDataBytes_) return true;
    file_.seekp(dataOffsetBytes_ + currentDataBytes_, std::ios::beg);
    std::array<char, 4096> silence{};
    int64_t remaining = targetBytes - currentDataBytes_;
    while (remaining > 0) {
        const auto chunk = static_cast<std::streamsize>(std::min<int64_t>(remaining, silence.size()));
        file_.write(silence.data(), chunk);
        if (!file_) {
            lastError_ = "silence padding failed";
            return false;
        }
        remaining -= chunk;
    }
    currentDataBytes_ = targetBytes;
    return true;
}

bool Pcm16WavWriter::readOriginalSample(int64_t dataByteOffset, int16_t* sample) {
    if (dataByteOffset + kBytesPerSample > currentDataBytes_) {
        *sample = 0;
        return true;
    }
    std::array<char, kBytesPerSample> bytes{};
    file_.seekg(dataOffsetBytes_ + dataByteOffset, std::ios::beg);
    file_.read(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    if (!file_) {
        lastError_ = "original sample read failed";
        return false;
    }
    *sample = static_cast<int16_t>(readLe16(bytes));
    return true;
}

bool Pcm16WavWriter::writeSample(int16_t sample) {
    if (!ensureDataSize(writeCursorBytes_ + kBytesPerSample)) return false;
    file_.seekp(dataOffsetBytes_ + writeCursorBytes_, std::ios::beg);
    file_.write(reinterpret_cast<const char*>(&sample), sizeof(sample));
    if (!file_) {
        lastError_ = "wav write failed";
        return false;
    }
    writeCursorBytes_ += kBytesPerSample;
    framesWritten_ += 1;
    return true;
}

bool Pcm16WavWriter::flushPendingSamples() {
    const size_t count = pendingSamples_.size();
    for (size_t index = 0; index < count; ++index) {
        const double replacementProgress = count == 1 ? 0.0 : 1.0 - static_cast<double>(index) / (count - 1);
        if (!writeSample(equalPowerMix(
                pendingOriginalSamples_[index], pendingSamples_[index], replacementProgress))) {
            return false;
        }
    }
    pendingSamples_.clear();
    pendingOriginalSamples_.clear();
    return true;
}

int Pcm16WavWriter::fadeFrameCount() const {
    return std::max(1, std::min(kMaxFadeFrames, (sampleRate_ * kFadeMs) / 1000));
}
