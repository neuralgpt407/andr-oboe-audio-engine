#include "OboeRecorderEngine.h"
#include "RecorderSampleConverter.h"
#include "RecorderStreamConfig.h"

#include <android/log.h>

#include <algorithm>

namespace {
constexpr const char* kTag = "OboeRecorderEngine";
constexpr int kMicStartTimeoutMs = 250;
}

OboeRecorderEngine::~OboeRecorderEngine() {
    releaseMicSession();
}

bool OboeRecorderEngine::startMicSession() {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    return startMicSessionLocked();
}

bool OboeRecorderEngine::startMicSessionLocked() {
    if (micSessionActive_.load(std::memory_order_acquire)) {
        return true;
    }

    failed_.store(false, std::memory_order_release);
    peak_.store(0.0f, std::memory_order_release);
    ringBuffer_.clear();

    if (!openInputStream(oboe::SharingMode::Exclusive) &&
        !openInputStream(oboe::SharingMode::Shared)) {
        failActiveTake("unable to open recorder input stream");
        return false;
    }

    auto stream = getStream();
    if (stream == nullptr) {
        failActiveTake("recorder input stream is unavailable");
        return false;
    }

    const oboe::Result startResult = stream->requestStart();
    if (startResult != oboe::Result::OK) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "requestStart failed: %s",
            oboe::convertToText(startResult)
        );
        closeInputStream();
        failActiveTake("unable to start recorder input stream");
        return false;
    }

    if (recorderStreamClosedDuringStart(stream, kMicStartTimeoutMs)) {
        closeInputStream();
        failActiveTake("recorder input stream closed during start");
        return false;
    }

    micSessionActive_.store(true, std::memory_order_release);
    return true;
}

float OboeRecorderEngine::getPeak() const {
    return peak_.load(std::memory_order_acquire);
}

OboeRecorderTelemetry OboeRecorderEngine::getTelemetry(int32_t lastConsumedBucketIndex) const {
    OboeRecorderTelemetry telemetry;
    telemetry.takeId = takeId_.load(std::memory_order_acquire);
    telemetry.sampleRate = sampleRate_.load(std::memory_order_acquire);
    telemetry.acceptedFrames = acceptedFrames_.load(std::memory_order_acquire);
    telemetry.writtenFrames = framesWritten_.load(std::memory_order_acquire);
    telemetry.rawPeak = peak_.load(std::memory_order_acquire);
    telemetry.waveform = waveformAccumulator_.readDelta(lastConsumedBucketIndex);
    return telemetry;
}

int64_t OboeRecorderEngine::getWrittenDurationMs() const {
    const int rate = sampleRate_.load(std::memory_order_acquire);
    const int64_t frames = framesWritten_.load(std::memory_order_acquire);
    return rate > 0 ? (frames * 1000) / rate : 0;
}

int OboeRecorderEngine::getSampleRate() const {
    return sampleRate_.load(std::memory_order_acquire);
}

bool OboeRecorderEngine::hasFailed() const {
    return failed_.load(std::memory_order_acquire);
}

std::string OboeRecorderEngine::getLastError() const {
    std::lock_guard<std::mutex> lock(errorMutex_);
    return lastError_;
}

oboe::DataCallbackResult OboeRecorderEngine::onAudioReady(
    oboe::AudioStream* audioStream,
    void* audioData,
    int32_t numFrames
) {
    if (audioStream == nullptr || audioData == nullptr || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    const int channelCount = std::max(1, audioStream->getChannelCount());
    const oboe::AudioFormat format = audioStream->getFormat();
    float peak = 0.0f;
    int32_t framesRemaining = numFrames;
    int32_t sourceFrameOffset = 0;

    while (framesRemaining > 0) {
        const int32_t framesInSlice = std::min<int32_t>(framesRemaining, kMaxCallbackFrames);

        // Produce PCM16 mono at the canonical output rate: either directly
        // (device already at that rate) or via the resampler. `peak` is measured
        // on the recorded output in both cases.
        const int16_t* writeBuffer;
        int32_t writeFrames;
        if (resampler_ == nullptr) {
            convertInputSliceToPcm16Mono(
                audioData,
                format,
                channelCount,
                sourceFrameOffset,
                framesInSlice,
                callbackBuffer_.data(),
                &peak
            );
            writeBuffer = callbackBuffer_.data();
            writeFrames = framesInSlice;
        } else {
            float inputPeakIgnored = 0.0f;
            convertInputSliceToFloatMono(
                audioData,
                format,
                channelCount,
                sourceFrameOffset,
                framesInSlice,
                resampleInputScratch_.data(),
                &inputPeakIgnored
            );
            const int32_t produced = resampler_->process(
                resampleInputScratch_.data(),
                framesInSlice,
                resampleOutputScratch_.data(),
                static_cast<int32_t>(resampleOutputScratch_.size())
            );
            floatMonoToPcm16(
                resampleOutputScratch_.data(),
                produced,
                resampledPcm16Scratch_.data(),
                &peak
            );
            writeBuffer = resampledPcm16Scratch_.data();
            writeFrames = produced;
        }

        if (writeFrames > 0) {
            const uint64_t armedEpoch = callbackFence_.epoch();
            if (armedEpoch != 0 && callbackFence_.tryEnter(armedEpoch)) {
                if (!failed_.load(std::memory_order_acquire)) {
                    const size_t frameCount = static_cast<size_t>(writeFrames);
                    if (ringBuffer_.writeAllOrNothing(writeBuffer, frameCount)) {
                        acceptedFrames_.fetch_add(writeFrames, std::memory_order_release);
                        waveformAccumulator_.appendPcm16Mono(writeBuffer, writeFrames);
                    } else {
                        failed_.store(true, std::memory_order_release);
                        callbackFence_.disarm();
                    }
                }
                callbackFence_.leave();
                if (failed_.load(std::memory_order_acquire)) {
                    break;
                }
            }
        }

        framesRemaining -= framesInSlice;
        sourceFrameOffset += framesInSlice;
    }

    peak_.store(peak, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void OboeRecorderEngine::disarmAndAwaitProducers() {
    callbackFence_.disarmAndAwait();
}

void OboeRecorderEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected) {
        failActiveTake("recorder input stream disconnected");
    }
    micSessionActive_.store(false, std::memory_order_release);
}

bool OboeRecorderEngine::openInputStream(oboe::SharingMode sharingMode) {
    auto stream = openRecorderInputStream(sharingMode, this, this, kTag);
    if (stream == nullptr) {
        return false;
    }
    deviceSampleRate_ = stream->getSampleRate();
    sampleRate_.store(kOutputSampleRate, std::memory_order_release);
    configureResampler(deviceSampleRate_);
    setStream(std::move(stream));
    return true;
}

void OboeRecorderEngine::configureResampler(int deviceSampleRate) {
    resampleInputScratch_.clear();
    resampleOutputScratch_.clear();
    resampledPcm16Scratch_.clear();

    if (deviceSampleRate <= 0 || deviceSampleRate == kOutputSampleRate) {
        // Device already runs at the canonical rate: record directly, no
        // conversion needed.
        resampler_.reset();
        return;
    }

    resampler_ = neuralsound::audio::SampleRateConverter::make(
        /*channelCount=*/1,
        deviceSampleRate,
        kOutputSampleRate,
        neuralsound::audio::ResampleQuality::Medium
    );
    if (resampler_ == nullptr) {
        // Could not build a converter: fall back to recording at the device
        // rate so pitch stays correct, labeling the WAV with that rate.
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "Failed to create resampler for %d -> %d; recording at device rate",
            deviceSampleRate,
            kOutputSampleRate
        );
        sampleRate_.store(deviceSampleRate, std::memory_order_release);
        return;
    }

    const int32_t maxOutputFrames = resampler_->maxOutputFramesFor(kMaxCallbackFrames);
    resampleInputScratch_.assign(static_cast<size_t>(kMaxCallbackFrames), 0.0f);
    resampleOutputScratch_.assign(static_cast<size_t>(maxOutputFrames), 0.0f);
    resampledPcm16Scratch_.assign(static_cast<size_t>(maxOutputFrames), 0);
}

std::shared_ptr<oboe::AudioStream> OboeRecorderEngine::getStream() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return inputStream_;
}

void OboeRecorderEngine::setStream(std::shared_ptr<oboe::AudioStream> stream) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    inputStream_ = std::move(stream);
}

void OboeRecorderEngine::closeInputStream() {
    auto stream = getStream();
    setStream(nullptr);
    if (stream != nullptr) {
        stream->requestStop();
        stream->close();
    }
}

void OboeRecorderEngine::failActiveTake(const std::string& message) {
    callbackFence_.disarm();
    failed_.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(errorMutex_);
    lastError_ = message;
}
