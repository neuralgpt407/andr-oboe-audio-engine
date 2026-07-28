#include "OboeRecorderEngine.h"
#include "RecorderCanonicalRatePolicy.h"
#include "RecorderErrorCallback.h"
#include "RecorderSampleConverter.h"
#include "RecorderStreamConfig.h"

#include <android/log.h>

#include <algorithm>

namespace {
constexpr const char* kTag = "OboeRecorderEngine";
constexpr int kMicStartTimeoutMs = 250;
}

OboeRecorderEngine::OboeRecorderEngine()
    : failureState_(std::make_shared<RecorderFailureState>()),
      callbackFence_(std::make_shared<RecorderCallbackFence>()),
      micSessionCoordinator_(std::make_shared<RecorderMicSessionCoordinator>(
          failureState_,
          callbackFence_
      )) {}

OboeRecorderEngine::~OboeRecorderEngine() {
    releaseMicSession();
}

bool OboeRecorderEngine::startMicSession() {
    std::lock_guard<std::mutex> operationLock(operationMutex_);
    return startMicSessionLocked();
}

bool OboeRecorderEngine::startMicSessionLocked() {
    if (micSessionCoordinator_->isActive()) {
        return true;
    }

    closeInputStream();
    errorCallback_.reset();
    failureState_->reset();
    peak_.store(0.0f, std::memory_order_release);
    ringBuffer_.clear();

    auto sessionToken = micSessionCoordinator_->beginOpening();
    bool streamOpened = openInputStream(oboe::SharingMode::Exclusive, sessionToken);
    if (!streamOpened) {
        micSessionCoordinator_->abandonOpening(sessionToken);
        sessionToken = micSessionCoordinator_->beginOpening();
        streamOpened = openInputStream(oboe::SharingMode::Shared, sessionToken);
    }
    if (!streamOpened) {
        micSessionCoordinator_->abandonOpening(sessionToken);
        failActiveTake(
            "unable to open recorder input stream",
            OboeRecorderErrorCode::MicSessionOpenFailed
        );
        return false;
    }

    auto stream = getStream();
    if (stream == nullptr) {
        failActiveTake(
            "recorder input stream is unavailable",
            OboeRecorderErrorCode::MicSessionOpenFailed
        );
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
        micSessionCoordinator_->abandonOpening(sessionToken);
        failActiveTake(
            "unable to start recorder input stream",
            OboeRecorderErrorCode::MicSessionOpenFailed
        );
        return false;
    }

    if (recorderStreamClosedDuringStart(stream, kMicStartTimeoutMs)) {
        closeInputStream();
        micSessionCoordinator_->abandonOpening(sessionToken);
        failActiveTake(
            "recorder input stream closed during start",
            OboeRecorderErrorCode::MicSessionOpenFailed
        );
        return false;
    }

    if (!micSessionCoordinator_->activate(sessionToken)) {
        closeInputStream();
        failActiveTake(
            "recorder input stream closed during start",
            OboeRecorderErrorCode::MicSessionOpenFailed
        );
        return false;
    }
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
    return failureState_->hasFailed();
}

OboeRecorderFailureSnapshot OboeRecorderEngine::getFailureSnapshot() const {
    return failureState_->snapshot();
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
            const uint64_t armedEpoch = callbackFence_->epoch();
            if (armedEpoch != 0 && callbackFence_->tryEnter(armedEpoch)) {
                if (!failureState_->hasFailed()) {
                    const size_t frameCount = static_cast<size_t>(writeFrames);
                    if (ringBuffer_.writeAllOrNothing(writeBuffer, frameCount)) {
                        acceptedFrames_.fetch_add(writeFrames, std::memory_order_release);
                        waveformAccumulator_.appendPcm16Mono(writeBuffer, writeFrames);
                    } else {
                        failureState_->publishRealtimeOverflow();
                        callbackFence_->disarm();
                    }
                }
                callbackFence_->leave();
                if (failureState_->hasFailed()) {
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
    callbackFence_->disarmAndAwait();
}

bool OboeRecorderEngine::openInputStream(
    oboe::SharingMode sharingMode,
    RecorderMicSessionCoordinator::SessionToken sessionToken
) {
    auto errorCallback = std::make_shared<RecorderErrorCallback>(
        micSessionCoordinator_,
        sessionToken
    );
    auto stream = openRecorderInputStream(
        sharingMode,
        this,
        errorCallback,
        kTag
    );
    if (stream == nullptr) {
        return false;
    }
    deviceSampleRate_ = stream->getSampleRate();
    sampleRate_.store(kOutputSampleRate, std::memory_order_release);
    if (!configureResampler(deviceSampleRate_)) {
        stream->close();
        return false;
    }
    setStream(std::move(stream));
    errorCallback_ = std::move(errorCallback);
    return true;
}

bool OboeRecorderEngine::configureResampler(int deviceSampleRate) {
    resampleInputScratch_.clear();
    resampleOutputScratch_.clear();
    resampledPcm16Scratch_.clear();

    const RecorderCanonicalRateAction initialAction =
        recorderCanonicalRateAction(deviceSampleRate, true);
    if (initialAction == RecorderCanonicalRateAction::Reject) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Recorder reported invalid device sample rate %d",
            deviceSampleRate
        );
        resampler_.reset();
        return false;
    }

    if (initialAction == RecorderCanonicalRateAction::Direct) {
        // Device already runs at the canonical rate: record directly.
        resampler_.reset();
        return true;
    }

    resampler_ = neuralsound::audio::SampleRateConverter::make(
        /*channelCount=*/1,
        deviceSampleRate,
        kOutputSampleRate,
        neuralsound::audio::ResampleQuality::Medium
    );
    if (
        recorderCanonicalRateAction(deviceSampleRate, resampler_ != nullptr) !=
        RecorderCanonicalRateAction::Convert
    ) {
        // The public recorder contract is canonical 44.1 kHz output. Never
        // produce a file at another rate when conversion cannot be configured.
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Failed to create required resampler for %d -> %d",
            deviceSampleRate,
            kOutputSampleRate
        );
        return false;
    }

    const int32_t maxOutputFrames = resampler_->maxOutputFramesFor(kMaxCallbackFrames);
    resampleInputScratch_.assign(static_cast<size_t>(kMaxCallbackFrames), 0.0f);
    resampleOutputScratch_.assign(static_cast<size_t>(maxOutputFrames), 0.0f);
    resampledPcm16Scratch_.assign(static_cast<size_t>(maxOutputFrames), 0);
    return true;
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

void OboeRecorderEngine::failActiveTake(
    const std::string& message,
    OboeRecorderErrorCode errorCode
) {
    callbackFence_->disarm();
    failureState_->publish(errorCode, message);
}
