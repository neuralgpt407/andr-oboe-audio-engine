#include "OboeAudioEngine.h"

#include <android/log.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <system_error>
#include <sys/resource.h>
#include <thread>

namespace {
constexpr const char* kTag = "OboeAudioEngine";
constexpr int kAndroidPriorityAudio = -16;
constexpr int kRenderIdleSleepMs = 2;
constexpr int kStartPrebufferTimeoutMs = 120;
// How long an appended track may lag behind the render head before the
// engine gives up on a seamless join and resyncs every track globally.
constexpr int kAppendJoinWatchdogSeconds = 30;
// Wait before reopening after a disconnect: lets the new route settle and
// lets a Kotlin becoming-noisy pause land first, so an unplug does not leak
// audio out of the speaker. Sliced so shutdown can abort the wait quickly.
constexpr int kStreamRestartDelayMs = 250;
constexpr int kShutdownPollMs = 50;
}

OboeAudioEngine::~OboeAudioEngine() {
    stop();
}

bool OboeAudioEngine::initialize(const int* fds, const int64_t* durations, int trackCount) {
    stop();
    shuttingDown_.store(false, std::memory_order_release);
    restartPending_.store(false, std::memory_order_release);
    streamNeedsReopen_.store(false, std::memory_order_release);
    if (fds == nullptr || trackCount <= 0 || trackCount > kMaxTracks) return false;

    for (int i = 0; i < kMaxTracks; ++i) {
        volumes_[i].store(1.0f, std::memory_order_relaxed);
        leftGains_[i].store(1.0f, std::memory_order_relaxed);
        rightGains_[i].store(1.0f, std::memory_order_relaxed);
        mutes_[i].store(false, std::memory_order_relaxed);
        trackOffsetsMs_[i].store(0, std::memory_order_relaxed);
        trackHeadFrames_[i].store(0, std::memory_order_relaxed);
        trackJoined_[i].store(true, std::memory_order_relaxed);
        trackFadeFramesRemaining_[i].store(0, std::memory_order_relaxed);
        trackJoinDeadlineFrames_[i].store(0, std::memory_order_relaxed);
    }
    appendAnchorFrame_.store(0, std::memory_order_relaxed);
    appendAnchorUs_.store(0, std::memory_order_relaxed);
    joinRescuePending_.store(false, std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(trackMutex_);
        trackCount_ = trackCount;
        durationMs_ = 0;
        decoderThreads_.reserve(static_cast<size_t>(trackCount));
        for (int i = 0; i < trackCount; ++i) {
            auto thread = std::make_unique<NativeDecoderThread>();
            if (!thread->initialize(fds[i])) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "Decoder init failed for track %d", i);
                return false;
            }
            if (i == 0) {
                sampleRate_ = thread->getSampleRate();
            } else if (thread->getSampleRate() != sampleRate_) {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    kTag,
                    "Track %d sample rate %d does not match mixer rate %d",
                    i,
                    thread->getSampleRate(),
                    sampleRate_
                );
                return false;
            }
            durationMs_ = std::max<int64_t>(durationMs_, thread->getDurationMs());
            if (durations != nullptr && durations[i] > 0) {
                durationMs_ = std::max(durationMs_, durations[i]);
            }
            decoderThreads_.push_back(std::move(thread));
        }
        for (auto& thread : decoderThreads_) {
            thread->start();
        }
    }

    if (!openStream(oboe::SharingMode::Exclusive) && !openStream(oboe::SharingMode::Shared)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Unable to open Oboe stream");
        stop();
        return false;
    }

    pitchTempoProcessor_.prepare(sampleRate_, kOutputChannelCount, kMaxOutputFrames);
    outputFifo_.prepare(kOutputChannelCount, kOutputFifoFrames);
    totalFramesWritten_.store(0, std::memory_order_release);
    sourceFramesConsumed_.store(0, std::memory_order_release);
    sourceFramesRendered_.store(0, std::memory_order_release);
    sourceFrameRemainder_.store(0.0, std::memory_order_release);
    outputUnderrunCount_.store(0, std::memory_order_release);
    renderEndReached_.store(false, std::memory_order_release);
    renderThreadStopRequested_.store(false, std::memory_order_release);
    isSeeking_.store(false, std::memory_order_release);
    isPlaying_.store(false, std::memory_order_release);
    return true;
}

bool OboeAudioEngine::appendTrack(int fd, float volume, bool muted, float leftGain, float rightGain) {
    auto thread = std::make_unique<NativeDecoderThread>();
    if (!thread->initialize(fd)) return false;
    if (thread->getSampleRate() != sampleRate_) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Appended track sample rate %d does not match mixer rate %d",
            thread->getSampleRate(),
            sampleRate_
        );
        return false;
    }

    thread->start();

    {
        std::lock_guard<std::mutex> lock(trackMutex_);
        if (trackCount_ >= kMaxTracks) return false;

        // Extractor seeks (MP3 especially) land with a per-target timeline
        // error, so seek the new decoder to the exact anchor the live tracks
        // used and let tryJoinAppendedTrack discard decoded PCM up to the
        // render head — the only sample-exact way to match their timeline.
        thread->seekToUs(appendAnchorUs_.load(std::memory_order_acquire));
        thread->resume();

        const int index = trackCount_;
        volumes_[index].store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_release);
        leftGains_[index].store(std::clamp(leftGain, 0.0f, 2.0f), std::memory_order_release);
        rightGains_[index].store(std::clamp(rightGain, 0.0f, 2.0f), std::memory_order_release);
        mutes_[index].store(muted, std::memory_order_release);
        trackHeadFrames_[index].store(
            appendAnchorFrame_.load(std::memory_order_acquire),
            std::memory_order_release
        );
        trackJoined_[index].store(false, std::memory_order_release);
        trackFadeFramesRemaining_[index].store(kAppendFadeInFrames, std::memory_order_release);
        trackJoinDeadlineFrames_[index].store(
            sourceFramesRendered_.load(std::memory_order_acquire) +
                static_cast<int64_t>(sampleRate_) * kAppendJoinWatchdogSeconds,
            std::memory_order_release
        );
        durationMs_ = std::max<int64_t>(durationMs_, thread->getDurationMs());
        decoderThreads_.push_back(std::move(thread));
        trackCount_ += 1;
    }

    renderEndReached_.store(false, std::memory_order_release);
    return true;
}

bool OboeAudioEngine::play() {
    auto stream = getStream();
    if (stream == nullptr || streamNeedsReopen_.exchange(false, std::memory_order_acq_rel)) {
        if (!reopenStream()) return false;
        stream = getStream();
        if (stream == nullptr) return false;
    }
    renderEndReached_.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(trackMutex_);
        for (auto& thread : decoderThreads_) {
            thread->resume();
        }
    }
    startRenderThread();
    waitForRenderPrebuffer(kStartPrebufferFrames, kStartPrebufferTimeoutMs);
    fadeScale_.store(0.0f, std::memory_order_release);
    isFadingIn_.store(true, std::memory_order_release);
    fadeOutScale_.store(1.0f, std::memory_order_release);
    isFadingOut_.store(false, std::memory_order_release);
    fadeOutComplete_.store(true, std::memory_order_release);
    oboe::Result result = stream->requestStart();
    if (result != oboe::Result::OK) {
        // Stale stream: the device changed while paused, where AAudio never
        // delivers ErrorDisconnected. Reopen on the current route and retry.
        __android_log_print(
            ANDROID_LOG_WARN, kTag,
            "requestStart failed (%s), reopening stream",
            oboe::convertToText(result)
        );
        if (reopenStream()) {
            stream = getStream();
            result = stream != nullptr ? stream->requestStart() : oboe::Result::ErrorClosed;
        }
    }
    if (result != oboe::Result::OK) {
        pause();
        return false;
    }
    isPlaying_.store(true, std::memory_order_release);
    return true;
}

void OboeAudioEngine::pause() {
    isPlaying_.store(false, std::memory_order_release);
    auto stream = getStream();
    if (stream != nullptr) {
        stream->requestPause();
    }
    stopRenderThread();
    {
        std::lock_guard<std::mutex> lock(trackMutex_);
        for (auto& thread : decoderThreads_) {
            thread->pause();
        }
    }
}

void OboeAudioEngine::stop() {
    shuttingDown_.store(true, std::memory_order_release);
    isPlaying_.store(false, std::memory_order_release);
    // Reap any in-flight stream restart before touching the stream: the
    // worker aborts its settle wait once shuttingDown_ is set, and
    // requestRestart re-checks shuttingDown_ under restartThreadMutex_, so
    // no new worker can be spawned after this join.
    {
        std::lock_guard<std::mutex> lock(restartThreadMutex_);
        if (restartThread_.joinable()) {
            restartThread_.join();
        }
    }
    // Order matters: only the render thread spawns join rescues, so stop it
    // first, then reap any in-flight rescue while the stream is still alive
    // for its seekTo/play, then stop the render thread again in case that
    // play() restarted it.
    stopRenderThread();
    {
        std::lock_guard<std::mutex> lock(joinRescueMutex_);
        if (joinRescueThread_.joinable()) {
            joinRescueThread_.join();
        }
    }
    stopRenderThread();
    isPlaying_.store(false, std::memory_order_release);
    closeStream();
    outputFifo_.clear();
    std::lock_guard<std::mutex> lock(trackMutex_);
    for (auto& thread : decoderThreads_) {
        thread->stop();
    }
    decoderThreads_.clear();
    trackCount_ = 0;
    durationMs_ = 0;
    totalFramesWritten_.store(0, std::memory_order_release);
    sourceFramesConsumed_.store(0, std::memory_order_release);
    sourceFramesRendered_.store(0, std::memory_order_release);
    sourceFrameRemainder_.store(0.0, std::memory_order_release);
    renderEndReached_.store(false, std::memory_order_release);
    pitchTempoProcessor_.reset();
}

void OboeAudioEngine::seekTo(int64_t ms) {
    {
        std::lock_guard<std::mutex> lock(trackMutex_);
        if (decoderThreads_.empty()) return;
    }

    const int64_t clampedMs = std::clamp(ms, static_cast<int64_t>(0), durationMs_);
    const bool wasPlaying = isPlaying_.load(std::memory_order_acquire);
    // Local copy: a concurrent device-change restart may swap the stream;
    // calls on a stale copy just return ErrorClosed and the polls below
    // break on the Closed state.
    auto stream = getStream();

    if (wasPlaying && stream != nullptr) {
        fadeOutScale_.store(1.0f, std::memory_order_release);
        fadeOutComplete_.store(false, std::memory_order_release);
        isFadingOut_.store(true, std::memory_order_release);

        const auto fadeStart = std::chrono::steady_clock::now();
        while (!fadeOutComplete_.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() - fadeStart < std::chrono::milliseconds(50)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        isFadingOut_.store(false, std::memory_order_release);
        fadeOutComplete_.store(true, std::memory_order_release);
    }

    isSeeking_.store(true, std::memory_order_release);
    if (stream != nullptr) {
        stream->requestPause();
        // Wait for stream to actually reach Paused state before flushing.
        // requestFlush() only works on a Paused stream — calling it while
        // still in Pausing state silently fails, leaving old audio in the
        // internal buffer that plays out as a seek artifact.
        const auto pauseStart = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - pauseStart < std::chrono::milliseconds(100)) {
            const auto state = stream->getState();
            if (state != oboe::StreamState::Pausing &&
                state != oboe::StreamState::Starting &&
                state != oboe::StreamState::Started) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }

    stopRenderThread();
    outputFifo_.clear();

    // Held through resetTrackHeadFrames so a concurrent appendTrack cannot
    // register a decoder that misses this re-seek yet gets marked joined
    // below (it also guards the decoderThreads_ iteration itself).
    std::lock_guard<std::mutex> trackLock(trackMutex_);
    for (int i = 0; i < trackCount_; ++i) {
        auto& thread = decoderThreads_[static_cast<size_t>(i)];
        thread->pause();
        const int64_t sourceMs = std::max<int64_t>(
            0,
            clampedMs + trackOffsetsMs_[i].load(std::memory_order_acquire)
        );
        thread->seekTo(sourceMs);
    }

    if (stream != nullptr) {
        stream->requestFlush();
        const auto flushStart = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - flushStart < std::chrono::milliseconds(50)) {
            const auto state = stream->getState();
            if (state == oboe::StreamState::Flushed ||
                state == oboe::StreamState::Stopped ||
                state == oboe::StreamState::Closed) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }

    for (auto& thread : decoderThreads_) {
        thread->resume();
    }

    const auto start = std::chrono::steady_clock::now();
    while (std::chrono::steady_clock::now() - start < std::chrono::milliseconds(250)) {
        const bool ready = std::all_of(decoderThreads_.begin(), decoderThreads_.end(), [](const auto& thread) {
            return thread->available() >= NativeDecoderThread::kDecodeChunkSamples || thread->isEndOfStream();
        });
        if (ready) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    const int64_t clampedFramePosition = (clampedMs * sampleRate_) / 1000;
    totalFramesWritten_.store(clampedFramePosition, std::memory_order_release);
    sourceFramesConsumed_.store(clampedFramePosition, std::memory_order_release);
    sourceFramesRendered_.store(clampedFramePosition, std::memory_order_release);
    appendAnchorFrame_.store(clampedFramePosition, std::memory_order_release);
    appendAnchorUs_.store(clampedMs * 1000, std::memory_order_release);
    resetTrackHeadFrames(clampedFramePosition);
    sourceFrameRemainder_.store(0.0, std::memory_order_release);
    renderEndReached_.store(false, std::memory_order_release);
    pitchTempoProcessor_.reset();
    fadeOutScale_.store(1.0f, std::memory_order_release);
    isFadingOut_.store(false, std::memory_order_release);
    fadeOutComplete_.store(true, std::memory_order_release);
    isSeeking_.store(false, std::memory_order_release);

    // Don't call requestStart() or set fade-in here. The Kotlin layer
    // calls startPlayback() -> nativePlay() -> play() which handles
    // fade-in and requestStart() in one place. Doing it here too caused
    // a double-start where play() would reset fadeScale_ to 0 after
    // the first start had already begun fading in, producing a pop.
    isPlaying_.store(false, std::memory_order_release);
}

void OboeAudioEngine::setVolume(int trackIdx, float volume) {
    if (trackIdx < 0 || trackIdx >= trackCount_) return;
    volumes_[trackIdx].store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_relaxed);
}

void OboeAudioEngine::setMute(int trackIdx, bool muted) {
    if (trackIdx < 0 || trackIdx >= trackCount_) return;
    mutes_[trackIdx].store(muted, std::memory_order_relaxed);
}

void OboeAudioEngine::setChannelGain(int trackIdx, float leftGain, float rightGain) {
    if (trackIdx < 0 || trackIdx >= trackCount_) return;
    leftGains_[trackIdx].store(std::clamp(leftGain, 0.0f, 2.0f), std::memory_order_relaxed);
    rightGains_[trackIdx].store(std::clamp(rightGain, 0.0f, 2.0f), std::memory_order_relaxed);
}

void OboeAudioEngine::setTrackOffsetMs(int trackIdx, int64_t offsetMs) {
    if (trackIdx < 0 || trackIdx >= trackCount_) return;
    trackOffsetsMs_[trackIdx].store(offsetMs, std::memory_order_release);
}

void OboeAudioEngine::setTempo(float tempoSpeed) {
    pitchTempoProcessor_.setTempo(tempoSpeed);
}

void OboeAudioEngine::setPitchSemitones(int semitones) {
    pitchTempoProcessor_.setPitchSemitones(semitones);
}

int64_t OboeAudioEngine::getPositionMs() const {
    return std::min(
        durationMs_,
        (sourceFramesConsumed_.load(std::memory_order_acquire) * 1000) / sampleRate_
    );
}

int64_t OboeAudioEngine::getDurationMs() const {
    return durationMs_;
}

int OboeAudioEngine::getSampleRate() const {
    return sampleRate_;
}

bool OboeAudioEngine::isPlaying() const {
    return isPlaying_.load(std::memory_order_acquire);
}

oboe::DataCallbackResult OboeAudioEngine::onAudioReady(
    oboe::AudioStream*,
    void* audioData,
    int32_t numFrames
) {
    auto* out = static_cast<float*>(audioData);
    const int outputFrames = std::max<int32_t>(0, numFrames);
    const int sampleCount = outputFrames * kOutputChannelCount;
    std::memset(out, 0, static_cast<size_t>(sampleCount) * sizeof(float));

    if (isSeeking_.load(std::memory_order_acquire)) {
        return oboe::DataCallbackResult::Continue;
    }

    const int framesRead = outputFifo_.read(out, outputFrames);
    if (framesRead < outputFrames &&
        isPlaying_.load(std::memory_order_acquire) &&
        !renderEndReached_.load(std::memory_order_acquire)) {
        outputUnderrunCount_.fetch_add(1, std::memory_order_relaxed);
    }

    for (int s = 0; s < sampleCount; ++s) {
        out[s] = std::clamp(out[s], -1.0f, 1.0f);
    }

    if (isFadingIn_.load(std::memory_order_acquire)) {
        constexpr int32_t kFadeDurationFrames = 1024; // ~23ms at 44.1kHz
        float scale = fadeScale_.load(std::memory_order_relaxed);
        for (int f = 0; f < outputFrames; ++f) {
            scale += 1.0f / kFadeDurationFrames;
            if (scale >= 1.0f) {
                scale = 1.0f;
                isFadingIn_.store(false, std::memory_order_release);
            }
            out[f * 2] *= scale;
            out[f * 2 + 1] *= scale;
        }
        fadeScale_.store(scale, std::memory_order_relaxed);
    }

    if (isFadingOut_.load(std::memory_order_acquire)) {
        constexpr int32_t kFadeOutDurationFrames = 512; // ~12ms at 44.1kHz
        float scale = fadeOutScale_.load(std::memory_order_relaxed);
        for (int f = 0; f < outputFrames; ++f) {
            scale -= 1.0f / kFadeOutDurationFrames;
            if (scale <= 0.0f) {
                scale = 0.0f;
                isFadingOut_.store(false, std::memory_order_release);
                fadeOutComplete_.store(true, std::memory_order_release);
            }
            out[f * 2] *= scale;
            out[f * 2 + 1] *= scale;
        }
        fadeOutScale_.store(scale, std::memory_order_relaxed);
    }

    totalFramesWritten_.fetch_add(outputFrames, std::memory_order_release);
    advanceSourcePositionForOutput(framesRead);

    if (renderEndReached_.load(std::memory_order_acquire) &&
        outputFifo_.availableFrames() == 0) {
        const int64_t durationFrames = (durationMs_ * sampleRate_) / 1000;
        sourceFramesConsumed_.store(durationFrames, std::memory_order_release);
        isPlaying_.store(false, std::memory_order_release);
    }
    return oboe::DataCallbackResult::Continue;
}

void OboeAudioEngine::startRenderThread() {
    std::lock_guard<std::mutex> lock(renderThreadMutex_);
    if (renderThread_.joinable()) {
        if (renderThreadRunning_.load(std::memory_order_acquire)) {
            return;
        }
        if (renderThread_.get_id() == std::this_thread::get_id()) {
            renderThread_.detach();
        } else {
            renderThread_.join();
        }
    }

    renderThreadStopRequested_.store(false, std::memory_order_release);
    renderThreadRunning_.store(true, std::memory_order_release);
    renderThread_ = std::thread(&OboeAudioEngine::renderLoop, this);
}

void OboeAudioEngine::stopRenderThread() {
    renderThreadStopRequested_.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(renderThreadMutex_);
    if (renderThread_.joinable()) {
        if (renderThread_.get_id() == std::this_thread::get_id()) {
            renderThread_.detach();
        } else {
            try {
                renderThread_.join();
            } catch (const std::system_error& error) {
                __android_log_print(
                    ANDROID_LOG_WARN,
                    kTag,
                    "Render thread join failed: %s",
                    error.what()
                );
                if (renderThread_.joinable()) {
                    renderThread_.detach();
                }
            }
        }
    }
    renderThreadRunning_.store(false, std::memory_order_release);
}

void OboeAudioEngine::renderLoop() {
    setpriority(PRIO_PROCESS, 0, kAndroidPriorityAudio);

    while (!renderThreadStopRequested_.load(std::memory_order_acquire)) {
        if (outputFifo_.availableFrames() >= kTargetPrebufferFrames) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kRenderIdleSleepMs));
            continue;
        }

        bool allEnd = false;
        const int framesRendered = renderNextChunk(allEnd);
        if (allEnd && framesRendered == 0 && !pitchTempoProcessor_.hasPendingOutput()) {
            renderEndReached_.store(true, std::memory_order_release);
            break;
        }
        if (framesRendered == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kRenderIdleSleepMs));
        }
    }

    renderThreadRunning_.store(false, std::memory_order_release);
}

int OboeAudioEngine::renderNextChunk(bool& allEnd) {
    allEnd = false;
    const int writableFrames = outputFifo_.availableWriteFrames();
    if (writableFrames <= 0) {
        return 0;
    }

    const int outputFrames = std::min(kRenderChunkFrames, writableFrames);
    if (pitchTempoProcessor_.isBypassed()) {
        const int sourceFramesRead = mixSourceFrames(mixBuffer_.data(), outputFrames, allEnd);
        if (sourceFramesRead <= 0) {
            return 0;
        }
        sourceFramesRendered_.fetch_add(sourceFramesRead, std::memory_order_release);
        return outputFifo_.write(mixBuffer_.data(), sourceFramesRead);
    }

    const int inputFramesNeeded = std::min(
        pitchTempoProcessor_.inputFramesNeeded(outputFrames),
        kMaxInputFrames
    );
    int sourceFramesRead = 0;
    if (inputFramesNeeded > 0) {
        sourceFramesRead = mixSourceFrames(mixBuffer_.data(), inputFramesNeeded, allEnd);
        if (sourceFramesRead > 0) {
            sourceFramesRendered_.fetch_add(sourceFramesRead, std::memory_order_release);
        }
    }

    const int processedFrames = pitchTempoProcessor_.process(
        sourceFramesRead > 0 ? mixBuffer_.data() : nullptr,
        sourceFramesRead,
        processedBuffer_.data(),
        outputFrames
    );
    if (processedFrames <= 0) {
        return 0;
    }
    return outputFifo_.write(processedBuffer_.data(), processedFrames);
}

bool OboeAudioEngine::waitForRenderPrebuffer(int minFrames, int timeoutMs) {
    const auto start = std::chrono::steady_clock::now();
    while (std::chrono::steady_clock::now() - start < std::chrono::milliseconds(timeoutMs)) {
        if (outputFifo_.availableFrames() >= minFrames ||
            renderEndReached_.load(std::memory_order_acquire) ||
            renderThreadStopRequested_.load(std::memory_order_acquire)) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kRenderIdleSleepMs));
    }
    return outputFifo_.availableFrames() > 0;
}

void OboeAudioEngine::resetTrackHeadFrames(int64_t framePosition) {
    const int count = trackCount_;
    for (int i = 0; i < count; ++i) {
        const int64_t offsetFrames =
            (trackOffsetsMs_[i].load(std::memory_order_acquire) * sampleRate_) / 1000;
        trackHeadFrames_[i].store(
            std::max<int64_t>(0, framePosition + offsetFrames),
            std::memory_order_release
        );
        trackJoined_[i].store(true, std::memory_order_release);
        trackFadeFramesRemaining_[i].store(0, std::memory_order_release);
    }
}

bool OboeAudioEngine::tryJoinAppendedTrack(
    int index,
    NativeDecoderThread* thread,
    int64_t chunkStartFrame,
    int requestedSamples
) {
    int64_t headFrame = trackHeadFrames_[index].load(std::memory_order_acquire);
    if (chunkStartFrame < headFrame) {
        return false;
    }

    // Discard decoded audio between the track's head and the live render
    // head. Decode outpaces realtime, so the gap shrinks every chunk until
    // it closes; already-playing tracks are never stalled on this one.
    int64_t skipSamples = (chunkStartFrame - headFrame) * kOutputChannelCount;
    int64_t discardedFrames = 0;
    while (skipSamples > 0) {
        const size_t available = thread->available();
        if (available == 0) break;
        const int slice = static_cast<int>(std::min<int64_t>(
            skipSamples,
            static_cast<int64_t>(std::min(available, readBuffer_.size()))
        ));
        const int discarded = thread->read(readBuffer_.data(), slice);
        if (discarded <= 0) break;
        skipSamples -= discarded;
        discardedFrames += discarded / kOutputChannelCount;
    }
    if (discardedFrames > 0) {
        headFrame += discardedFrames;
        trackHeadFrames_[index].store(headFrame, std::memory_order_release);
    }

    const bool eos = thread->isEndOfStream();
    if (skipSamples > 0) {
        if (eos && thread->available() == 0) {
            // The stem ended inside the gap; join as drained so the mix
            // loop skips it like any other finished track.
            trackJoined_[index].store(true, std::memory_order_release);
            return true;
        }
        maybeRescueStalledJoin(index, chunkStartFrame);
        return false;
    }

    if (!eos && thread->available() < static_cast<size_t>(requestedSamples)) {
        maybeRescueStalledJoin(index, chunkStartFrame);
        return false;
    }

    trackJoined_[index].store(true, std::memory_order_release);
    return true;
}

void OboeAudioEngine::maybeRescueStalledJoin(int index, int64_t chunkStartFrame) {
    if (chunkStartFrame < trackJoinDeadlineFrames_[index].load(std::memory_order_acquire)) {
        return;
    }
    if (joinRescuePending_.exchange(true, std::memory_order_acq_rel)) return;
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "Appended track %d failed to join in time, resyncing all tracks",
        index
    );
    // Runs off-thread: seekTo stops the render thread, and this is called
    // from the render thread itself. Kept joinable so stop() can reap it
    // before the engine is destroyed.
    std::lock_guard<std::mutex> lock(joinRescueMutex_);
    if (joinRescueThread_.joinable()) {
        joinRescueThread_.join();
    }
    joinRescueThread_ = std::thread([this]() {
        const bool wasPlaying = isPlaying_.load(std::memory_order_acquire);
        seekTo(getPositionMs());
        if (wasPlaying) {
            play();
        }
        joinRescuePending_.store(false, std::memory_order_release);
    });
}

void OboeAudioEngine::advanceSourcePositionForOutput(int outputFrames) {
    if (outputFrames <= 0) return;

    const double tempo = std::clamp(
        static_cast<double>(pitchTempoProcessor_.currentTempo()),
        0.5,
        1.5
    );
    const double exactFrames =
        static_cast<double>(outputFrames) * tempo +
        sourceFrameRemainder_.load(std::memory_order_relaxed);
    const auto sourceFrames = static_cast<int64_t>(std::floor(exactFrames));
    sourceFrameRemainder_.store(exactFrames - static_cast<double>(sourceFrames), std::memory_order_relaxed);
    if (sourceFrames > 0) {
        sourceFramesConsumed_.fetch_add(sourceFrames, std::memory_order_release);
    }
}

int OboeAudioEngine::mixSourceFrames(float* outputInterleaved, int frames, bool& allEnd) {
    if (outputInterleaved == nullptr || frames <= 0) {
        allEnd = false;
        return 0;
    }

    const int requestedFrames = std::min(frames, kMaxInputFrames);
    const int requestedSamples = requestedFrames * kOutputChannelCount;
    std::memset(outputInterleaved, 0, static_cast<size_t>(requestedSamples) * sizeof(float));
    const int64_t chunkStartFrame = sourceFramesRendered_.load(std::memory_order_acquire);
    const int64_t durationFrames = (durationMs_ * sampleRate_) / 1000;
    if (chunkStartFrame >= durationFrames) {
        allEnd = true;
        return 0;
    }

    std::vector<NativeDecoderThread*> threads;
    int trackCount = 0;
    {
        std::lock_guard<std::mutex> lock(trackMutex_);
        trackCount = trackCount_;
        threads.reserve(decoderThreads_.size());
        for (auto& thread : decoderThreads_) {
            threads.push_back(thread.get());
        }
    }

    allEnd = false;
    std::array<bool, kMaxTracks> shouldReadTrack{};
    std::array<int, kMaxTracks> leadingSilentFrames{};
    std::array<int, kMaxTracks> sourceFramesToRead{};
    bool waitForReadiness = false;

    for (int i = 0; i < trackCount; ++i) {
        const int64_t offsetFrames =
            (trackOffsetsMs_[i].load(std::memory_order_acquire) * sampleRate_) / 1000;
        const int64_t sourceFrameCount =
            (static_cast<int64_t>(threads[static_cast<size_t>(i)]->getDurationMs()) * sampleRate_) / 1000;
        const TrackReadWindow readWindow = trackReadWindow(
            chunkStartFrame,
            requestedFrames,
            sourceFrameCount,
            offsetFrames
        );
        leadingSilentFrames[static_cast<size_t>(i)] = readWindow.leadingSilentFrames;
        sourceFramesToRead[static_cast<size_t>(i)] = readWindow.sourceFrames;
        if (readWindow.sourceFrames == 0) {
            continue;
        }

        if (!trackJoined_[i].load(std::memory_order_acquire) &&
            !tryJoinAppendedTrack(
                i,
                threads[static_cast<size_t>(i)],
                chunkStartFrame,
                readWindow.sourceFrames * kOutputChannelCount
            )) {
            continue;
        }

        const size_t available = threads[static_cast<size_t>(i)]->available();
        const bool eos = threads[static_cast<size_t>(i)]->isEndOfStream();
        const bool drained = eos && available == 0;

        if (drained) {
            sourceFramesToRead[static_cast<size_t>(i)] = 0;
            continue;
        }

        if (available == 0) {
            waitForReadiness = true;
            continue;
        }

        shouldReadTrack[static_cast<size_t>(i)] = true;
        const int requestedTrackSamples = readWindow.sourceFrames * kOutputChannelCount;
        if (available < static_cast<size_t>(requestedTrackSamples)) {
            if (!eos) {
                waitForReadiness = true;
            } else {
                sourceFramesToRead[static_cast<size_t>(i)] =
                    static_cast<int>(available) / kOutputChannelCount;
            }
        }
    }

    if (waitForReadiness) {
        return 0;
    }

    for (int i = 0; i < trackCount; ++i) {
        const int sourceFrames = sourceFramesToRead[static_cast<size_t>(i)];
        if (!shouldReadTrack[static_cast<size_t>(i)] || sourceFrames <= 0) {
            continue;
        }

        const float volume = volumes_[i].load(std::memory_order_relaxed);
        const float leftGain = leftGains_[i].load(std::memory_order_relaxed);
        const float rightGain = rightGains_[i].load(std::memory_order_relaxed);
        const bool muted = mutes_[i].load(std::memory_order_relaxed);

        const int read = threads[static_cast<size_t>(i)]->read(
            readBuffer_.data(),
            sourceFrames * kOutputChannelCount
        );
        if (read > 0) {
            allEnd = false;
            if (!muted && volume > 0.0f) {
                int fadeFrames = trackFadeFramesRemaining_[i].load(std::memory_order_relaxed);
                const int readFrames = read / kOutputChannelCount;
                for (int frame = 0; frame < readFrames; ++frame) {
                    float fadeGain = 1.0f;
                    if (fadeFrames > 0) {
                        fadeGain = static_cast<float>(kAppendFadeInFrames - fadeFrames) /
                            static_cast<float>(kAppendFadeInFrames);
                        fadeFrames -= 1;
                    }
                    for (int channel = 0; channel < kOutputChannelCount; ++channel) {
                        const int sample = frame * kOutputChannelCount + channel;
                        const int outputSample =
                            (leadingSilentFrames[static_cast<size_t>(i)] + frame) *
                                kOutputChannelCount + channel;
                        const float channelGain = channel == 0 ? leftGain : rightGain;
                        outputInterleaved[outputSample] +=
                            (static_cast<float>(readBuffer_[sample]) / 32768.0f) *
                            volume *
                            channelGain *
                            fadeGain;
                    }
                }
                trackFadeFramesRemaining_[i].store(std::max(0, fadeFrames), std::memory_order_relaxed);
            } else {
                const int readFrames = read / kOutputChannelCount;
                const int fadeFrames = trackFadeFramesRemaining_[i].load(std::memory_order_relaxed);
                trackFadeFramesRemaining_[i].store(
                    std::max(0, fadeFrames - readFrames),
                    std::memory_order_relaxed
                );
            }
        } else if (read == 0) {
            allEnd = false;
        }
    }

    return requestedFrames;
}

bool OboeAudioEngine::openStream(oboe::SharingMode sharingMode) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(sharingMode)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(sampleRate_)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "openStream failed: %s", oboe::convertToText(result));
        return false;
    }
    setStream(std::move(stream));
    return true;
}

int OboeAudioEngine::getOutputDeviceId() const {
    const auto stream = getStream();
    return stream != nullptr ? stream->getDeviceId() : oboe::kUnspecified;
}

void OboeAudioEngine::closeStream() {
    auto stream = getStream();
    setStream(nullptr);
    if (stream != nullptr) {
        // Blocking close happens outside streamMutex_ so readers never wait
        // on it. Closing an already-closed stream (post-disconnect) is a
        // harmless error return.
        stream->requestStop();
        stream->close();
    }
}

std::shared_ptr<oboe::AudioStream> OboeAudioEngine::getStream() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return audioStream_;
}

void OboeAudioEngine::setStream(std::shared_ptr<oboe::AudioStream> stream) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    audioStream_ = std::move(stream);
}

bool OboeAudioEngine::reopenStream() {
    std::lock_guard<std::mutex> lock(reopenMutex_);
    closeStream();
    // Reopen with kUnspecified so Android routes to the current active device.
    if (!openStream(oboe::SharingMode::Exclusive) && !openStream(oboe::SharingMode::Shared)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Unable to reopen Oboe stream");
        return false;
    }
    streamNeedsReopen_.store(false, std::memory_order_release);
    return true;
}

void OboeAudioEngine::onErrorAfterClose(
    oboe::AudioStream* /*stream*/,
    oboe::Result error
) {
    if (error != oboe::Result::ErrorDisconnected) return;
    requestRestart();
}

void OboeAudioEngine::requestRestart() {
    if (shuttingDown_.load(std::memory_order_acquire)) return;
    // Exchange returns the old value; only proceed if we are the first to set it true.
    if (restartPending_.exchange(true, std::memory_order_acq_rel)) return;
    // Must not block the caller (Oboe error callback thread) with the actual
    // restart — hand it to a joinable worker so stop() can reap it.
    std::lock_guard<std::mutex> lock(restartThreadMutex_);
    if (shuttingDown_.load(std::memory_order_acquire)) {
        // stop() set shuttingDown_ and joined under this mutex; spawning now
        // would leave a thread nobody joins.
        restartPending_.store(false, std::memory_order_release);
        return;
    }
    if (restartThread_.joinable()) {
        // Previous worker has already cleared restartPending_, so it is
        // exiting; this join is near-instant.
        restartThread_.join();
    }
    restartThread_ = std::thread([this]() {
        for (int waited = 0;
             waited < kStreamRestartDelayMs && !shuttingDown_.load(std::memory_order_acquire);
             waited += kShutdownPollMs) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kShutdownPollMs));
        }
        if (!shuttingDown_.load(std::memory_order_acquire)) {
            restartStream();
        }
        restartPending_.store(false, std::memory_order_release);
    });
}

void OboeAudioEngine::restartStream() {
    // Stop the render thread first so nothing is writing to the FIFO while
    // the stream is swapped out.
    stopRenderThread();
    if (!reopenStream()) {
        pause();
        return;
    }
    // Only restart audio if the user had not already paused.
    if (!isPlaying_.load(std::memory_order_acquire)) return;
    auto stream = getStream();
    if (stream == nullptr) {
        pause();
        return;
    }
    startRenderThread();
    waitForRenderPrebuffer(kStartPrebufferFrames, kStartPrebufferTimeoutMs);
    fadeScale_.store(0.0f, std::memory_order_release);
    isFadingIn_.store(true, std::memory_order_release);
    oboe::Result result = stream->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(
            ANDROID_LOG_WARN, kTag,
            "restart requestStart failed: %s",
            oboe::convertToText(result)
        );
        pause();
    }
}

void OboeAudioEngine::handleDeviceChange() {
    if (shuttingDown_.load(std::memory_order_acquire)) return;
    if (isPlaying_.load(std::memory_order_acquire)) {
        auto stream = getStream();
        const auto state = stream != nullptr ? stream->getState() : oboe::StreamState::Closed;
        if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting) {
            // Healthy or auto-rerouted; a real disconnect raises
            // ErrorDisconnected, which drives requestRestart itself.
            return;
        }
        requestRestart();
    } else {
        // A paused stream never receives ErrorDisconnected, so mark it stale
        // and let the next play() reopen on the current default device.
        streamNeedsReopen_.store(true, std::memory_order_release);
    }
}
