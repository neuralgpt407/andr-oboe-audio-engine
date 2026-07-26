#pragma once

#include "AudioFifo.h"
#include "NativeDecoderThread.h"
#include "PitchTempoProcessor.h"
#include "TrackReadOffset.h"

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

class OboeAudioEngine
    : public oboe::AudioStreamDataCallback,
      public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kMaxTracks = 8;
    static constexpr int kOutputChannelCount = 2;
    static constexpr int kMaxOutputFrames = NativeDecoderThread::kDecodeChunkSamples / kOutputChannelCount;
    static constexpr int kMaxInputFrames = kMaxOutputFrames * 2;
    static constexpr int kMaxInputSamples = kMaxInputFrames * kOutputChannelCount;
    static constexpr int kMaxOutputSamples = kMaxOutputFrames * kOutputChannelCount;
    static constexpr int kRenderChunkFrames = kMaxOutputFrames;
    static constexpr int kOutputFifoFrames = kMaxOutputFrames * 8;
    static constexpr int kStartPrebufferFrames = kMaxOutputFrames;
    static constexpr int kTargetPrebufferFrames = kMaxOutputFrames * 2;
    static constexpr int kAppendFadeInFrames = 1024;

    OboeAudioEngine() = default;
    ~OboeAudioEngine() override;

    bool initialize(const int* fds, const int64_t* durations, int trackCount);
    bool appendTrack(int fd, float volume, bool muted, float leftGain, float rightGain);
    bool play();
    void pause();
    void stop();
    void handleDeviceChange();
    void seekTo(int64_t ms);
    void setVolume(int trackIdx, float volume);
    void setMute(int trackIdx, bool muted);
    void setChannelGain(int trackIdx, float leftGain, float rightGain);
    void setTrackOffsetMs(int trackIdx, int64_t offsetMs);
    void setTempo(float tempoSpeed);
    void setPitchSemitones(int semitones);
    int64_t getPositionMs() const;
    int64_t getDurationMs() const;
    int getSampleRate() const;
    int getOutputDeviceId() const;
    bool isPlaying() const;

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override;

    void onErrorAfterClose(
        oboe::AudioStream* audioStream,
        oboe::Result error
    ) override;

private:
    bool openStream(oboe::SharingMode sharingMode);
    void closeStream();
    std::shared_ptr<oboe::AudioStream> getStream() const;
    void setStream(std::shared_ptr<oboe::AudioStream> stream);
    bool reopenStream();
    void requestRestart();
    void startRenderThread();
    void stopRenderThread();
    void renderLoop();
    int renderNextChunk(bool& allEnd);
    bool waitForRenderPrebuffer(int minFrames, int timeoutMs);
    void resetTrackHeadFrames(int64_t framePosition);
    bool tryJoinAppendedTrack(
        int index,
        NativeDecoderThread* thread,
        int64_t chunkStartFrame,
        int requestedSamples
    );
    void maybeRescueStalledJoin(int index, int64_t chunkStartFrame);
    void advanceSourcePositionForOutput(int outputFrames);
    int mixSourceFrames(float* outputInterleaved, int frames, bool& allEnd);
    void restartStream();

    std::vector<std::unique_ptr<NativeDecoderThread>> decoderThreads_;
    // Guarded by streamMutex_; methods work on a shared_ptr copy from
    // getStream() so a concurrent reopen can swap the stream underneath
    // (calls on the stale copy just return ErrorClosed).
    std::shared_ptr<oboe::AudioStream> audioStream_;
    mutable std::mutex streamMutex_;
    // Serializes close+open pairs so concurrent reopen paths (restart worker
    // vs play() retry) cannot each open a stream and leak one.
    std::mutex reopenMutex_;
    std::mutex renderThreadMutex_;
    std::mutex trackMutex_;
    std::mutex joinRescueMutex_;
    std::mutex restartThreadMutex_;
    std::thread renderThread_;
    std::thread joinRescueThread_;
    std::thread restartThread_;
    std::array<std::atomic<float>, kMaxTracks> volumes_{};
    std::array<std::atomic<float>, kMaxTracks> leftGains_{};
    std::array<std::atomic<float>, kMaxTracks> rightGains_{};
    std::array<std::atomic<bool>, kMaxTracks> mutes_{};
    std::array<std::atomic<int64_t>, kMaxTracks> trackOffsetsMs_{};
    std::atomic<int64_t> totalFramesWritten_{0};
    std::atomic<int64_t> sourceFramesConsumed_{0};
    std::atomic<int64_t> sourceFramesRendered_{0};
    std::atomic<double> sourceFrameRemainder_{0.0};
    std::atomic<bool> isSeeking_{false};
    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> renderThreadRunning_{false};
    std::atomic<bool> renderThreadStopRequested_{false};
    std::atomic<bool> renderEndReached_{false};
    std::atomic<int64_t> outputUnderrunCount_{0};
    std::atomic<float> fadeScale_{1.0f};
    std::atomic<bool> isFadingIn_{false};
    std::atomic<float> fadeOutScale_{1.0f};
    std::atomic<bool> isFadingOut_{false};
    std::atomic<bool> fadeOutComplete_{true};
    std::atomic<bool> restartPending_{false};
    // Set when the output device changed while paused: AAudio only delivers
    // ErrorDisconnected to a started stream, so the next play() must reopen.
    std::atomic<bool> streamNeedsReopen_{false};
    std::atomic<bool> shuttingDown_{false};
    int64_t durationMs_ = 0;
    int sampleRate_ = 44100;
    int trackCount_ = 0;
    // Source frame where the track's decoder delivers its next sample.
    std::array<std::atomic<int64_t>, kMaxTracks> trackHeadFrames_{};
    std::array<std::atomic<bool>, kMaxTracks> trackJoined_{};
    std::array<std::atomic<int>, kMaxTracks> trackFadeFramesRemaining_{};
    std::array<std::atomic<int64_t>, kMaxTracks> trackJoinDeadlineFrames_{};
    // Last position every live decoder was extractor-seeked to (0 at init,
    // the target of the most recent seekTo otherwise). Extractor seeks are
    // not sample-accurate for MP3, so appended tracks must reuse this exact
    // anchor to inherit the same timeline error as the tracks already
    // playing. Guarded by trackMutex_ so the frame/us pair stays consistent.
    std::atomic<int64_t> appendAnchorFrame_{0};
    std::atomic<int64_t> appendAnchorUs_{0};
    std::atomic<bool> joinRescuePending_{false};
    PitchTempoProcessor pitchTempoProcessor_;
    AudioFifo outputFifo_;
    std::array<int16_t, kMaxInputSamples> readBuffer_{};
    std::array<float, kMaxInputSamples> mixBuffer_{};
    std::array<float, kMaxOutputSamples> processedBuffer_{};
};
