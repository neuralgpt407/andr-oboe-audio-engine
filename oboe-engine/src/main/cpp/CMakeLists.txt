cmake_minimum_required(VERSION 3.22.1)
project("neuralsound_audio_engine")

find_package(oboe REQUIRED CONFIG)

add_library(neuralsound_audio_engine SHARED
    dsp/DecodedAudioNormalizer.cpp
    dsp/SampleRateConverter.cpp
    effects/AudioFifo.cpp
    effects/PitchTempoProcessor.cpp
    engine/NativeAudioDecoder.cpp
    engine/NativeDecoderThread.cpp
    engine/OboeAudioEngine.cpp
    waveform/NativeAudioWaveformAnalyzer.cpp
    waveform/WaveformAnalysisCore.cpp
    waveform/WaveformRmsAccumulator.cpp
    recorder/OboeRecorderEngine.cpp
    recorder/OboeRecorderEngineWriter.cpp
    recorder/RecorderErrorCallback.cpp
    recorder/RecorderMicSessionCoordinator.cpp
    recorder/Pcm16WavHeader.cpp
    recorder/Pcm16WavWriter.cpp
    recorder/RecorderCanonicalRatePolicy.cpp
    recorder/RecorderFailureState.cpp
    recorder/RecorderWaveformAccumulator.cpp
    recorder/RecorderSampleConverter.cpp
    recorder/RecorderStreamConfig.cpp
    third_party/oboe-resampler/IntegerRatio.cpp
    third_party/oboe-resampler/LinearResampler.cpp
    third_party/oboe-resampler/MultiChannelResampler.cpp
    third_party/oboe-resampler/PolyphaseResampler.cpp
    third_party/oboe-resampler/PolyphaseResamplerMono.cpp
    third_party/oboe-resampler/PolyphaseResamplerStereo.cpp
    third_party/oboe-resampler/SincResampler.cpp
    third_party/oboe-resampler/SincResamplerStereo.cpp
    jni/jni_bridge.cpp
)

target_include_directories(neuralsound_audio_engine PRIVATE
    dsp
    engine
    common
    effects
    waveform
    recorder
    third_party
    third_party/signalsmith-stretch
    third_party/oboe-resampler
)

# Compile the vendored Oboe resampler under a private namespace so its symbols
# never collide with the identical resampler compiled inside liboboe.so.
target_compile_definitions(neuralsound_audio_engine PRIVATE RESAMPLER_OUTER_NAMESPACE=neuralsound_dsp)

set_target_properties(neuralsound_audio_engine PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

target_link_libraries(neuralsound_audio_engine
    android
    log
    mediandk
    oboe::oboe
)

set(NATIVE_EXPORT_MAP "${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map")
set_property(
    TARGET neuralsound_audio_engine
    APPEND
    PROPERTY LINK_DEPENDS "${NATIVE_EXPORT_MAP}"
)
target_link_options(neuralsound_audio_engine PRIVATE
    "-Wl,--build-id"
    "-Wl,--version-script=${NATIVE_EXPORT_MAP}"
)
