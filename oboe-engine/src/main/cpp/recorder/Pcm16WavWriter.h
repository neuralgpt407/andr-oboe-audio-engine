#pragma once

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

struct Pcm16WavInfo {
    int sampleRate = 0;
    int64_t frameCount = 0;
    int64_t dataOffsetBytes = 0;
};

class Pcm16WavWriter {
public:
    Pcm16WavWriter() = default;
    ~Pcm16WavWriter();

    Pcm16WavWriter(const Pcm16WavWriter&) = delete;
    Pcm16WavWriter& operator=(const Pcm16WavWriter&) = delete;

    static bool createFullDurationDraft(const std::string& path, int sampleRate, int64_t frameCount);
    static bool validateDraft(const std::string& path, int expectedSampleRate, Pcm16WavInfo* info);

    bool open(const std::string& path, int sampleRate, int channelCount, int64_t startOffsetMs);
    bool openAtFrame(
        const std::string& path,
        int sampleRate,
        int channelCount,
        int64_t startOffsetFrames
    );
    bool write(const int16_t* samples, size_t frameCount);
    bool close();

    int64_t framesWritten() const;
    int64_t dataBytes() const;
    const std::string& lastError() const;

private:
    bool parseExistingFile(Pcm16WavInfo* info);
    bool writeHeader(uint32_t dataBytes);
    bool updateHeader();
    bool ensureDataSize(int64_t targetBytes);
    bool readOriginalSample(int64_t dataByteOffset, int16_t* sample);
    bool writeSample(int16_t sample);
    bool flushPendingSamples();
    int fadeFrameCount() const;

    std::fstream file_;
    std::string path_;
    std::string lastError_;
    int sampleRate_ = 0;
    int channelCount_ = 0;
    int64_t dataOffsetBytes_ = 0;
    int64_t dataSizeFieldOffset_ = 0;
    int64_t initialDataBytes_ = 0;
    int64_t currentDataBytes_ = 0;
    int64_t writeOffsetBytes_ = 0;
    int64_t writeCursorBytes_ = 0;
    int64_t framesWritten_ = 0;
    std::vector<int16_t> pendingSamples_;
    std::vector<int16_t> pendingOriginalSamples_;
    bool isOpen_ = false;
};
