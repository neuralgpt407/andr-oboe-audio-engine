#pragma once

#include "Pcm16WavWriter.h"
#include "RecorderCallbackFence.h"
#include "RecorderCanonicalRatePolicy.h"
#include "RecorderFailureState.h"
#include "RecorderWaveformAccumulator.h"
#include "SPSCRingBuffer.h"
#include "SampleRateConverter.h"

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct OboeRecordingResult {
    int64_t takeDurationMs = 0;
    int64_t acceptedFrames = 0;
    int64_t writtenFrames = 0;
    int sampleRate = 0;
    bool failed = false;
};

struct OboeRecorderTelemetry {
    uint64_t takeId = 0;
    int sampleRate = 0;
    int64_t acceptedFrames = 0;
    int64_t writtenFrames = 0;
    float rawPeak = 0.0f;
    RecorderWaveformDelta waveform;
};

class OboeRecorderEngine
    : public oboe::AudioStreamDataCallback,
      public oboe::AudioStreamErrorCallback {
public:
    OboeRecorderEngine() = default;
    ~OboeRecorderEngine() override;

    OboeRecorderEngine(const OboeRecorderEngine&) = delete;
    OboeRecorderEngine& operator=(const OboeRecorderEngine&) = delete;

    bool startMicSession();
    bool startWriting(const std::string& outputPath, int64_t startOffsetMs);
    bool startWritingAtFrame(const std::string& outputPath, int64_t startOffsetFrames);
    void pauseWriting();
    OboeRecordingResult stopWriting();
    void releaseMicSession();

    float getPeak() const;
    OboeRecorderTelemetry getTelemetry(int32_t lastConsumedBucketIndex) const;
    int64_t getWrittenDurationMs() const;
    int getSampleRate() const;
    bool hasFailed() const;
    OboeRecorderFailureSnapshot getFailureSnapshot() const;

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override;

    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) override;

private:
    enum class StartOffsetUnit {
        Milliseconds,
        PcmFrames,
    };

    static constexpr int kMaxCallbackFrames = 4096;
    static constexpr int kWriterChunkFrames = 2048;
    // Canonical recording rate written to the WAV file, regardless of the
    // device's native mic rate. Input is resampled to this rate on capture.
    static constexpr int kOutputSampleRate = kRecorderCanonicalSampleRate;

    bool openInputStream(oboe::SharingMode sharingMode);
    bool configureResampler(int deviceSampleRate);
    bool startMicSessionLocked();
    bool startWritingLocked(
        const std::string& outputPath,
        int64_t startOffset,
        StartOffsetUnit startOffsetUnit
    );
    void pauseWritingLocked();
    void runWriterLoop(Pcm16WavWriter& writer);
    std::shared_ptr<oboe::AudioStream> getStream() const;
    void setStream(std::shared_ptr<oboe::AudioStream> stream);
    void closeInputStream();
    void failActiveTake(const std::string& message, OboeRecorderErrorCode errorCode);
    void disarmAndAwaitProducers();

    mutable std::mutex operationMutex_;
    mutable std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::thread writerThread_;
    SPSCRingBuffer ringBuffer_;
    std::array<int16_t, kMaxCallbackFrames> callbackBuffer_{};
    std::array<int16_t, kWriterChunkFrames> writerBuffer_{};
    // Sample-rate conversion (device rate -> kOutputSampleRate). Null / passthrough
    // when the device already runs at kOutputSampleRate. Configured off the
    // realtime thread in openInputStream; used only from onAudioReady afterwards.
    int deviceSampleRate_ = 0;
    std::unique_ptr<neuralsound::audio::SampleRateConverter> resampler_;
    std::vector<float> resampleInputScratch_;
    std::vector<float> resampleOutputScratch_;
    std::vector<int16_t> resampledPcm16Scratch_;
    RecorderWaveformAccumulator waveformAccumulator_;
    std::atomic<bool> micSessionActive_{false};
    std::atomic<bool> writerRunning_{false};
    std::atomic<bool> writerStopRequested_{false};
    RecorderFailureState failureState_;
    std::atomic<float> peak_{0.0f};
    std::atomic<int64_t> framesWritten_{0};
    std::atomic<int64_t> acceptedFrames_{0};
    std::atomic<uint64_t> takeId_{0};
    RecorderCallbackFence callbackFence_;
    std::atomic<int> sampleRate_{0};
};
