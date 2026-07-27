#include "OboeAudioEngine.h"
#include "OboeRecorderEngine.h"

#include <jni.h>

#include <cstddef>
#include <memory>
#include <vector>

namespace {
OboeAudioEngine* fromHandle(jlong handle) {
    return reinterpret_cast<OboeAudioEngine*>(handle);
}

OboeRecorderEngine* recorderFromHandle(jlong handle) {
    return reinterpret_cast<OboeRecorderEngine*>(handle);
}

jlong nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new OboeAudioEngine());
}

jboolean nativeInitializeTracks(JNIEnv* env, jobject, jlong handle, jintArray jFds, jlongArray jDurations, jint count) {
    OboeAudioEngine* engine = fromHandle(handle);
    if (engine == nullptr || jFds == nullptr || count <= 0) return JNI_FALSE;

    jint* fds = env->GetIntArrayElements(jFds, nullptr);
    if (fds == nullptr) return JNI_FALSE;
    jlong* durations = nullptr;
    if (jDurations != nullptr) {
        durations = env->GetLongArrayElements(jDurations, nullptr);
    }

    const bool success = engine->initialize(
        reinterpret_cast<int*>(fds),
        reinterpret_cast<int64_t*>(durations),
        count
    );

    if (durations != nullptr) {
        env->ReleaseLongArrayElements(jDurations, durations, JNI_ABORT);
    }
    env->ReleaseIntArrayElements(jFds, fds, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

jboolean nativeAppendTrack(
    JNIEnv*,
    jobject,
    jlong handle,
    jint fd,
    jfloat volume,
    jboolean muted,
    jfloat leftGain,
    jfloat rightGain
) {
    if (auto* engine = fromHandle(handle)) {
        return engine->appendTrack(
            fd,
            volume,
            muted == JNI_TRUE,
            leftGain,
            rightGain
        ) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

jboolean nativePlay(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = fromHandle(handle)) {
        return engine->play() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}
void nativePause(JNIEnv*, jobject, jlong handle) { if (auto* engine = fromHandle(handle)) engine->pause(); }
jboolean nativeIsPlaying(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = fromHandle(handle)) {
        return engine->isPlaying() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}
jboolean nativeSeekTo(JNIEnv*, jobject, jlong handle, jlong ms) {
    if (auto* engine = fromHandle(handle)) {
        return engine->seekTo(ms) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}
void nativeOnDeviceChanged(JNIEnv*, jobject, jlong handle) { if (auto* engine = fromHandle(handle)) engine->handleDeviceChange(); }
void nativeSetVolume(JNIEnv*, jobject, jlong handle, jint index, jfloat volume) { if (auto* engine = fromHandle(handle)) engine->setVolume(index, volume); }
void nativeSetMute(JNIEnv*, jobject, jlong handle, jint index, jboolean muted) { if (auto* engine = fromHandle(handle)) engine->setMute(index, muted == JNI_TRUE); }
void nativeSetChannelGain(JNIEnv*, jobject, jlong handle, jint index, jfloat leftGain, jfloat rightGain) { if (auto* engine = fromHandle(handle)) engine->setChannelGain(index, leftGain, rightGain); }
void nativeSetTrackOffset(JNIEnv*, jobject, jlong handle, jint index, jlong offsetMs) { if (auto* engine = fromHandle(handle)) engine->setTrackOffsetMs(index, offsetMs); }
void nativeSetTempo(JNIEnv*, jobject, jlong handle, jfloat speed) { if (auto* engine = fromHandle(handle)) engine->setTempo(speed); }
void nativeSetPitchSemitones(JNIEnv*, jobject, jlong handle, jint semitones) { if (auto* engine = fromHandle(handle)) engine->setPitchSemitones(semitones); }

jlong nativeGetPositionMs(JNIEnv*, jobject, jlong handle) { if (auto* engine = fromHandle(handle)) return engine->getPositionMs(); return 0; }
jlong nativeGetDurationMs(JNIEnv*, jobject, jlong handle) { if (auto* engine = fromHandle(handle)) return engine->getDurationMs(); return 0; }
jint nativeGetOutputDeviceId(JNIEnv*, jobject, jlong handle) { if (auto* engine = fromHandle(handle)) return engine->getOutputDeviceId(); return 0; }
jlong nativeGetLastFailure(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = fromHandle(handle)) {
        return static_cast<jlong>(engine->getLastFailure().encode());
    }
    return static_cast<jlong>(
        NativeAudioFailureSnapshot{
            NativeAudioDecoderFailure::DecoderFailure,
            -1,
        }.encode()
    );
}
void nativeClearLastFailure(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = fromHandle(handle)) {
        engine->clearLastFailure();
    }
}

void nativeRelease(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->stop();
        delete engine;
    }
}

bool registerRequiredNativeMethods(
    JNIEnv* env,
    const char* className,
    JNINativeMethod* methods,
    size_t methodCount
) {
    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) return false;

    const jint result = env->RegisterNatives(clazz, methods, methodCount);
    env->DeleteLocalRef(clazz);
    return result == JNI_OK;
}

jlong nativeCreateRecorder(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new OboeRecorderEngine());
}

jboolean nativeRecorderStartMicSession(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        return recorder->startMicSession() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

jboolean nativeRecorderStartWriting(JNIEnv* env, jobject, jlong handle, jstring outputPath, jlong startOffsetMs) {
    auto* recorder = recorderFromHandle(handle);
    if (recorder == nullptr || outputPath == nullptr) {
        return JNI_FALSE;
    }

    const char* chars = env->GetStringUTFChars(outputPath, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    const bool result = recorder->startWriting(chars, startOffsetMs);
    env->ReleaseStringUTFChars(outputPath, chars);
    return result ? JNI_TRUE : JNI_FALSE;
}

void nativeRecorderPauseWriting(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        recorder->pauseWriting();
    }
}

void nativeRecorderReleaseMicSession(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        recorder->releaseMicSession();
    }
}

jlongArray nativeRecorderStopWriting(JNIEnv* env, jobject, jlong handle) {
    jlongArray array = env->NewLongArray(5);
    if (array == nullptr) {
        return nullptr;
    }

    OboeRecordingResult result{};
    if (auto* recorder = recorderFromHandle(handle)) {
        result = recorder->stopWriting();
    }

    const jlong values[] = {
        result.takeDurationMs,
        result.acceptedFrames,
        result.writtenFrames,
        result.sampleRate,
        result.failed ? 1 : 0
    };
    env->SetLongArrayRegion(array, 0, 5, values);
    return array;
}

jfloat nativeRecorderGetMicPeak(JNIEnv*, jobject, jlong handle) { if (auto* recorder = recorderFromHandle(handle)) return recorder->getPeak(); return 0.0f; }

jobject nativeRecorderGetTelemetry(JNIEnv* env, jobject, jlong handle, jint lastConsumedBucketIndex) {
    OboeRecorderTelemetry telemetry{};
    if (auto* recorder = recorderFromHandle(handle)) {
        telemetry = recorder->getTelemetry(lastConsumedBucketIndex);
    }
    jfloatArray completed = env->NewFloatArray(
        static_cast<jsize>(telemetry.waveform.completedBucketRms.size())
    );
    if (completed == nullptr) return nullptr;
    if (!telemetry.waveform.completedBucketRms.empty()) {
        env->SetFloatArrayRegion(
            completed,
            0,
            static_cast<jsize>(telemetry.waveform.completedBucketRms.size()),
            telemetry.waveform.completedBucketRms.data()
        );
    }
    jclass clazz = env->FindClass(
        "com/neuralsound/audio/RecorderTelemetry"
    );
    if (clazz == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "(JIJJFI[FFI)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(clazz);
        return nullptr;
    }
    jobject result = env->NewObject(
        clazz,
        constructor,
        static_cast<jlong>(telemetry.takeId),
        static_cast<jint>(telemetry.sampleRate),
        static_cast<jlong>(telemetry.acceptedFrames),
        static_cast<jlong>(telemetry.writtenFrames),
        static_cast<jfloat>(telemetry.rawPeak),
        static_cast<jint>(telemetry.waveform.firstBucketIndex),
        completed,
        static_cast<jfloat>(telemetry.waveform.partialBucketRms),
        static_cast<jint>(telemetry.waveform.partialBucketFrames)
    );
    env->DeleteLocalRef(completed);
    env->DeleteLocalRef(clazz);
    return result;
}

jlong nativeRecorderGetWrittenDurationMs(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        return recorder->getWrittenDurationMs();
    }
    return 0;
}

jint nativeRecorderGetSampleRate(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        return recorder->getSampleRate();
    }
    return 0;
}

jboolean nativeRecorderHasFailed(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        return recorder->hasFailed() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}

jint nativeRecorderGetLastErrorCode(JNIEnv*, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        return static_cast<jint>(recorder->getLastErrorCode());
    }
    return static_cast<jint>(OboeRecorderErrorCode::WriterFileError);
}

jstring nativeRecorderGetLastError(JNIEnv* env, jobject, jlong handle) {
    if (auto* recorder = recorderFromHandle(handle)) {
        return env->NewStringUTF(recorder->getLastError().c_str());
    }
    return env->NewStringUTF("recorder handle is invalid");
}

void nativeRecorderRelease(JNIEnv*, jobject, jlong handle) {
    auto* recorder = recorderFromHandle(handle);
    if (recorder != nullptr) {
        recorder->releaseMicSession();
        delete recorder;
    }
}

JNINativeMethod kMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeInitializeTracks", "(J[I[JI)Z", reinterpret_cast<void*>(nativeInitializeTracks)},
    {"nativeAppendTrack", "(JIFZFF)Z", reinterpret_cast<void*>(nativeAppendTrack)},
    {"nativePlay", "(J)Z", reinterpret_cast<void*>(nativePlay)},
    {"nativeIsPlaying", "(J)Z", reinterpret_cast<void*>(nativeIsPlaying)},
    {"nativePause", "(J)V", reinterpret_cast<void*>(nativePause)},
    {"nativeSeekTo", "(JJ)Z", reinterpret_cast<void*>(nativeSeekTo)},
    {"nativeOnDeviceChanged", "(J)V", reinterpret_cast<void*>(nativeOnDeviceChanged)},
    {"nativeSetVolume", "(JIF)V", reinterpret_cast<void*>(nativeSetVolume)},
    {"nativeSetMute", "(JIZ)V", reinterpret_cast<void*>(nativeSetMute)},
    {"nativeSetChannelGain", "(JIFF)V", reinterpret_cast<void*>(nativeSetChannelGain)},
    {"nativeSetTrackOffset", "(JIJ)V", reinterpret_cast<void*>(nativeSetTrackOffset)},
    {"nativeSetTempo", "(JF)V", reinterpret_cast<void*>(nativeSetTempo)},
    {"nativeSetPitchSemitones", "(JI)V", reinterpret_cast<void*>(nativeSetPitchSemitones)},
    {"nativeGetPositionMs", "(J)J", reinterpret_cast<void*>(nativeGetPositionMs)},
    {"nativeGetDurationMs", "(J)J", reinterpret_cast<void*>(nativeGetDurationMs)},
    {"nativeGetOutputDeviceId", "(J)I", reinterpret_cast<void*>(nativeGetOutputDeviceId)},
    {"nativeGetLastFailure", "(J)J", reinterpret_cast<void*>(nativeGetLastFailure)},
    {"nativeClearLastFailure", "(J)V", reinterpret_cast<void*>(nativeClearLastFailure)},
    {"nativeRelease", "(J)V", reinterpret_cast<void*>(nativeRelease)},
};

JNINativeMethod kRecorderMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(nativeCreateRecorder)},
    {"nativeStartMicSession", "(J)Z", reinterpret_cast<void*>(nativeRecorderStartMicSession)},
    {"nativeStartWriting", "(JLjava/lang/String;J)Z", reinterpret_cast<void*>(nativeRecorderStartWriting)},
    {"nativePauseWriting", "(J)V", reinterpret_cast<void*>(nativeRecorderPauseWriting)},
    {"nativeStopWriting", "(J)[J", reinterpret_cast<void*>(nativeRecorderStopWriting)},
    {"nativeGetMicPeak", "(J)F", reinterpret_cast<void*>(nativeRecorderGetMicPeak)},
    {"nativeGetTelemetry", "(JI)Lcom/neuralsound/audio/RecorderTelemetry;", reinterpret_cast<void*>(nativeRecorderGetTelemetry)},
    {"nativeGetWrittenDurationMs", "(J)J", reinterpret_cast<void*>(nativeRecorderGetWrittenDurationMs)},
    {"nativeGetSampleRate", "(J)I", reinterpret_cast<void*>(nativeRecorderGetSampleRate)},
    {"nativeHasFailed", "(J)Z", reinterpret_cast<void*>(nativeRecorderHasFailed)},
    {"nativeGetLastErrorCode", "(J)I", reinterpret_cast<void*>(nativeRecorderGetLastErrorCode)},
    {"nativeGetLastError", "(J)Ljava/lang/String;", reinterpret_cast<void*>(nativeRecorderGetLastError)},
    {"nativeReleaseMicSession", "(J)V", reinterpret_cast<void*>(nativeRecorderReleaseMicSession)},
    {"nativeRelease", "(J)V", reinterpret_cast<void*>(nativeRecorderRelease)},
};
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    if (!registerRequiredNativeMethods(
            env,
            "com/neuralsound/audio/internal/NativeMixerController",
            kMethods,
            sizeof(kMethods) / sizeof(kMethods[0])
        )) {
        return JNI_ERR;
    }

    if (!registerRequiredNativeMethods(
        env,
        "com/neuralsound/audio/internal/NativeRecorderSession",
        kRecorderMethods,
        sizeof(kRecorderMethods) / sizeof(kRecorderMethods[0])
    )) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
