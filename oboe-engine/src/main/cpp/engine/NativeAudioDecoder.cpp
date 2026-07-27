#include "NativeAudioDecoder.h"

#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <string>

namespace {
constexpr const char* kTag = "NativeAudioDecoder";
constexpr int64_t kCodecTimeoutUs = 2000;

void logMediaStatus(const char* operation, media_status_t status) {
    if (status != AMEDIA_OK) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s failed: %d", operation, status);
    }
}
}

NativeAudioDecoder::~NativeAudioDecoder() {
    release();
}

bool NativeAudioDecoder::initialize(int fd) {
    release();
    dupFd_ = dup(fd);
    if (dupFd_ < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dup(fd) failed");
        return false;
    }

    extractor_ = AMediaExtractor_new();
    if (extractor_ == nullptr) {
        release();
        return false;
    }

    media_status_t status = AMediaExtractor_setDataSourceFd(extractor_, dupFd_, 0, LONG_MAX);
    if (status != AMEDIA_OK) {
        logMediaStatus("setDataSourceFd", status);
        release();
        return false;
    }

    const size_t trackCount = AMediaExtractor_getTrackCount(extractor_);
    AMediaFormat* selectedFormat = nullptr;
    std::string mime;
    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mimeChars = nullptr;
        if (format != nullptr &&
            AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mimeChars) &&
            mimeChars != nullptr &&
            std::strncmp(mimeChars, "audio/", 6) == 0) {
            AMediaExtractor_selectTrack(extractor_, i);
            selectedFormat = format;
            mime = mimeChars;
            break;
        }
        if (format != nullptr) {
            AMediaFormat_delete(format);
        }
    }

    if (selectedFormat == nullptr || mime.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "No audio track found");
        release();
        return false;
    }

    int32_t sampleRate = 0;
    int32_t channelCount = 0;
    int64_t durationUs = 0;
    if (AMediaFormat_getInt32(selectedFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate)) {
        sampleRate_ = sampleRate;
    }
    if (AMediaFormat_getInt32(selectedFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount)) {
        channelCount_ = channelCount;
    }
    if (AMediaFormat_getInt64(selectedFormat, AMEDIAFORMAT_KEY_DURATION, &durationUs)) {
        durationMs_ = durationUs / 1000;
    }
    if (sampleRate_ <= 0 || channelCount_ < 1 || channelCount_ > 2) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Unsupported audio format: sampleRate=%d channelCount=%d",
            sampleRate_,
            channelCount_
        );
        AMediaFormat_delete(selectedFormat);
        release();
        return false;
    }

    codec_ = AMediaCodec_createDecoderByType(mime.c_str());
    if (codec_ == nullptr) {
        AMediaFormat_delete(selectedFormat);
        release();
        return false;
    }

    status = AMediaCodec_configure(codec_, selectedFormat, nullptr, nullptr, 0);
    AMediaFormat_delete(selectedFormat);
    if (status != AMEDIA_OK) {
        logMediaStatus("configure", status);
        release();
        return false;
    }

    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        logMediaStatus("start", status);
        release();
        return false;
    }

    inputDone_ = false;
    isEndOfStream_ = false;
    clearPending();
    return true;
}

int NativeAudioDecoder::decode(int16_t* output, int maxSamples) {
    if (codec_ == nullptr || output == nullptr || maxSamples <= 0) return -1;

    int written = 0;
    if (!pendingBuffer_.empty()) {
        const size_t available = pendingBuffer_.size() - pendingOffset_;
        const size_t copyCount = std::min(available, static_cast<size_t>(maxSamples));
        std::memcpy(output, pendingBuffer_.data() + pendingOffset_, copyCount * sizeof(int16_t));
        pendingOffset_ += copyCount;
        written += static_cast<int>(copyCount);
        if (pendingOffset_ >= pendingBuffer_.size()) {
            clearPending();
        }
        if (written >= maxSamples) return written;
    }

    while (written < maxSamples && !isEndOfStream_) {
        feedInput();
        const int drained = drainOutput(output + written, maxSamples - written);
        if (drained > 0) {
            written += drained;
        } else if (drained == 0) {
            break;
        } else {
            isEndOfStream_ = true;
            break;
        }
    }

    if (written > 0) return written;
    return isEndOfStream_ ? -1 : 0;
}

bool NativeAudioDecoder::feedInput() {
    if (inputDone_ || codec_ == nullptr || extractor_ == nullptr) return false;

    const ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, kCodecTimeoutUs);
    if (inputIndex < 0) return false;

    size_t bufferSize = 0;
    uint8_t* buffer = AMediaCodec_getInputBuffer(codec_, inputIndex, &bufferSize);
    if (buffer == nullptr) return false;

    const ssize_t sampleSize = AMediaExtractor_readSampleData(extractor_, buffer, bufferSize);
    if (sampleSize < 0) {
        AMediaCodec_queueInputBuffer(
            codec_,
            inputIndex,
            0,
            0,
            0,
            AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
        );
        inputDone_ = true;
        return false;
    }

    const int64_t sampleTimeUs = AMediaExtractor_getSampleTime(extractor_);
    AMediaCodec_queueInputBuffer(
        codec_,
        inputIndex,
        0,
        static_cast<size_t>(sampleSize),
        sampleTimeUs,
        0
    );
    AMediaExtractor_advance(extractor_);
    return true;
}

int NativeAudioDecoder::drainOutput(int16_t* output, int maxSamples) {
    AMediaCodecBufferInfo info{};
    const ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, kCodecTimeoutUs);

    if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return 0;
    if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* format = AMediaCodec_getOutputFormat(codec_);
        if (format != nullptr) {
            int32_t decodedSampleRate = sampleRate_;
            int32_t decodedChannelCount = channelCount_;
            AMediaFormat_getInt32(
                format,
                AMEDIAFORMAT_KEY_SAMPLE_RATE,
                &decodedSampleRate
            );
            AMediaFormat_getInt32(
                format,
                AMEDIAFORMAT_KEY_CHANNEL_COUNT,
                &decodedChannelCount
            );
            if (
                decodedSampleRate != sampleRate_ ||
                decodedChannelCount < 1 ||
                decodedChannelCount > 2
            ) {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    kTag,
                    "Decoder output format changed to unsupported sampleRate=%d channelCount=%d",
                    decodedSampleRate,
                    decodedChannelCount
                );
                isEndOfStream_ = true;
            } else {
                channelCount_ = decodedChannelCount;
            }
            AMediaFormat_delete(format);
        }
        return isEndOfStream_ ? -1 : 0;
    }
    if (outputIndex < 0) return 0;

    size_t bufferSize = 0;
    uint8_t* buffer = AMediaCodec_getOutputBuffer(codec_, outputIndex, &bufferSize);
    int copied = 0;
    if (buffer != nullptr && info.size > 0) {
        const auto* samples = reinterpret_cast<int16_t*>(buffer + info.offset);
        const int inputSamples = info.size / static_cast<int>(sizeof(int16_t));
        pendingBuffer_.clear();
        pendingOffset_ = 0;

        if (channelCount_ == 1) {
            pendingBuffer_.reserve(inputSamples * 2);
            for (int i = 0; i < inputSamples; ++i) {
                pendingBuffer_.push_back(samples[i]);
                pendingBuffer_.push_back(samples[i]);
            }
        } else {
            pendingBuffer_.assign(samples, samples + inputSamples);
        }

        const int outputChannelCount = channelCount_ == 1 ? 2 : std::max(1, channelCount_);
        if (trimBeforeUs_ >= 0 && sampleRate_ > 0 && info.presentationTimeUs >= 0) {
            const int64_t frameCount = static_cast<int64_t>(pendingBuffer_.size()) / outputChannelCount;
            const int64_t bufferEndUs = info.presentationTimeUs + (frameCount * 1000000LL) / sampleRate_;
            if (bufferEndUs <= trimBeforeUs_) {
                clearPending();
            } else {
                if (info.presentationTimeUs < trimBeforeUs_) {
                    const int64_t framesToDrop =
                        ((trimBeforeUs_ - info.presentationTimeUs) * sampleRate_) / 1000000LL;
                    pendingOffset_ = std::min(
                        static_cast<size_t>(std::max<int64_t>(0, framesToDrop) * outputChannelCount),
                        pendingBuffer_.size()
                    );
                }
                trimBeforeUs_ = -1;
            }
        }

        const size_t available = pendingBuffer_.size() - pendingOffset_;
        const size_t copyCount = std::min(available, static_cast<size_t>(maxSamples));
        if (copyCount > 0) {
            std::memcpy(output, pendingBuffer_.data() + pendingOffset_, copyCount * sizeof(int16_t));
        }
        copied = static_cast<int>(copyCount);
        pendingOffset_ += copyCount;
        if (pendingOffset_ >= pendingBuffer_.size()) {
            clearPending();
        }
    }

    if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
        isEndOfStream_ = true;
    }
    AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);
    if (copied > 0) return copied;
    return isEndOfStream_ ? -1 : 0;
}

bool NativeAudioDecoder::seekTo(int64_t ms) {
    return seekToUs(ms * 1000);
}

bool NativeAudioDecoder::seekToUs(int64_t us) {
    if (extractor_ == nullptr || codec_ == nullptr) return false;
    AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    AMediaCodec_flush(codec_);
    trimBeforeUs_ = us;
    inputDone_ = false;
    isEndOfStream_ = false;
    clearPending();
    return true;
}

void NativeAudioDecoder::release() {
    clearPending();
    if (codec_ != nullptr) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (extractor_ != nullptr) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
    if (dupFd_ >= 0) {
        close(dupFd_);
        dupFd_ = -1;
    }
    inputDone_ = false;
    isEndOfStream_ = false;
    trimBeforeUs_ = -1;
    sampleRate_ = 0;
    channelCount_ = 0;
    durationMs_ = 0;
}

void NativeAudioDecoder::clearPending() {
    pendingBuffer_.clear();
    pendingOffset_ = 0;
}
