# Neural Sound Oboe Audio Engine

Reusable Android audio engine for low-latency multitrack playback, pitch/tempo
processing, microphone recording, waveform telemetry, WAV writing, device-route
recovery, and optional Media3 video synchronization.

## Artifacts

| Artifact | Purpose |
| --- | --- |
| `com.github.neuralgpt407.andr-oboe-audio-engine:oboe-engine:<tag>` | Oboe playback, effects, recording, and resampling |
| `com.github.neuralgpt407.andr-oboe-audio-engine:oboe-media3:<tag>` | Optional muted-video synchronization backed by Media3 |
| `com.github.neuralgpt407.andr-oboe-audio-engine:oboe-engine:<tag>:native-symbols@zip` | Unstripped native symbols for Play Console and Crashlytics |

The Android baseline is min SDK 24, compile SDK 36, NDK 29, CMake 3.22.1,
Java 11, and Kotlin 2.0. The published native ABIs are `arm64-v8a` and
`armeabi-v7a`.

## Build locally

Create `local.properties` with your Android SDK location, then run:

```shell
./gradlew testDebugUnitTest
./gradlew :oboe-engine:assembleRelease :oboe-media3:assembleRelease
./gradlew :sample:assembleDebug
./gradlew publishToMavenLocal
```

## Consume from JitPack

Add the public JitPack repository to `settings.gradle.kts`. No username or
token is required:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Use the core artifact for playback and recording:

```kotlin
dependencies {
    implementation(
        "com.github.neuralgpt407.andr-oboe-audio-engine:oboe-engine:v0.2.0"
    )
}
```

Use the Media3 artifact instead when video synchronization is required. It
brings `oboe-engine` transitively:

```kotlin
dependencies {
    implementation(
        "com.github.neuralgpt407.andr-oboe-audio-engine:oboe-media3:v0.2.0"
    )
}
```

## Multitrack playback

Each feature owns a session and closes it with the feature lifecycle.
`TrackId` is caller-defined; adding a new stem does not require a library
release.

```kotlin
val engine = AudioEngine(applicationContext)
val mixer = engine.createMixerSession()

val result = mixer.prepare(
    MixerRequest(
        tracks = listOf(
            MixerTrack(TrackId("vocal"), vocalUri),
            MixerTrack(
                id = TrackId("instrumental"),
                uri = instrumentalUri,
                mix = TrackMix(volume = 0.8f),
            ),
        ),
        effects = PlaybackEffects(tempo = 1f, pitchSemitones = 0),
    )
)

if (result == AudioResult.Success) {
    mixer.play()
}

// Observe transport, timing, effects, route, and all track state together.
val state: StateFlow<MixerState> = mixer.state

// ViewModel.onCleared(), owner disposal, or equivalent.
mixer.close()
```

The engine owns audio focus, becoming-noisy handling, file descriptors, route
recovery, native handles, decode threads, and playback polling. Callers own
URIs, permission UX, feature state, and session lifetime.
`MixerState.route.deviceId` may be null briefly while a disconnected or paused
native stream is reopening on the new output device.

Every successfully decoded source is normalized to one canonical 44.1 kHz
stereo processing clock before it enters the mixer. Mono is duplicated to
stereo, stereo preserves left and right, and sources with more than two
channels are averaged to mono and then duplicated to stereo. A 48 kHz Original
track and a 44.1 kHz Denoised track therefore share one duration and position
clock through prepare, progressive append, switching, seek, effects, and
end-of-stream.

Missing or non-positive format metadata, unreadable media, decoder failures,
and resampler failures remain typed public failures. A failed preparation
never publishes a partially prepared set of tracks.

## Media3 synchronization

```kotlin
val session = Media3AudioEngine(applicationContext).createSession()
session.prepare(
    Media3MixerRequest(
        audio = mixerRequest,
        videoUri = videoUri,
    )
)

val player: Player? = session.videoState.value.player
session.mixer.play()
session.close()
```

The adapter keeps video muted, follows mixer play/pause, mirrors tempo, corrects
drift above 250 ms, and reports first-frame/aspect-ratio state.

## Waveform analysis

`NativeAudioWaveformAnalyzer` decodes in a background native pipeline that is
independent of the real-time playback callback. The caller retains ownership of
the source descriptor; the analyzer duplicates it for native work and releases
all native resources on success, failure, or cancellation.

```kotlin
val analyzer = NativeAudioWaveformAnalyzer()

val result = contentResolver.openFileDescriptor(sourceUri, "r")!!.use { source ->
    analyzer.analyze(source)
}

when (result) {
    is NativeWaveformAnalysisResult.Success -> render(result.levels)
    is NativeWaveformAnalysisResult.Failure -> handle(result.error)
}

// Cancels analyses currently owned by this analyzer.
analyzer.cancel()
```

The stable cache metadata is
`NativeAudioWaveformAnalyzer.ALGORITHM_VERSION == 3` and
`MAX_OUTPUT_SAMPLES == 1024`. Version 3 selects the larger absolute magnitude
from each stereo PCM16 frame, safely handles `-32768`, and computes RMS levels
over 32 ms windows, including the final partial window. Oversized envelopes are
reduced by RMS to the requested bound. Tracks are not normalized against each
other, so their absolute amplitudes remain comparable. Failures distinguish
unreadable, unsupported, corrupt, empty, cancelled, invalid-argument, and
native-unavailable outcomes.

## Recording

The host app must request `android.permission.RECORD_AUDIO` before opening the
mic session.

```kotlin
val recorder = AudioEngine(applicationContext)
    .createFramePreciseRecorderSession()

recorder.startMicSession()
recorder.startWriting(
    FrameRecordingRequest(
        outputFile = outputFile,
        startOffsetFrames = cumulativePcmFrames,
    )
)
val telemetry = recorder.telemetry(afterBucketIndex = 0)
val recording = recorder.stopWriting()
recorder.releaseMicSession()
recorder.close()
```

Recording opens the device-native input rate and converts to the canonical
44.1 kHz WAV rate when needed. If that conversion cannot be configured, opening
the mic session fails instead of producing a noncanonical WAV.
`RecordingResult` reports accepted and written frame counts so callers can
reject incomplete files. Invalid lifecycle calls return
`RecorderError.InvalidState`; `RecordingResult.file` is null when no output
file was created.

`FramePreciseRecorderSession.state` atomically exposes status and the matching
typed failure. `currentFailure` is a replaying `StateFlow<RecorderFailure?>`:
asynchronous disconnect, overflow, and writer errors are visible to late
collectors, and an accepted new recording start clears the previous failure.
The original `createRecorderSession()` and millisecond-oriented
`RecordingRequest` API remain available and source-compatible.

## Release and symbols

Create and push an immutable Git tag such as `v0.2.0`, then look up
`neuralgpt407/andr-oboe-audio-engine` on
[JitPack](https://jitpack.io/#neuralgpt407/andr-oboe-audio-engine). JitPack
builds the two Maven publications from that tag; there is no package upload or
registry credential. The tag is the dependency version, including its `v`
prefix.

Use semantic versioning: patch releases for compatible fixes, minor releases
for compatible features, and major releases for breaking public API changes.
Never move or reuse a published tag.

Consumer apps should resolve the version-matched symbol classifier into their
Play/Crashlytics symbol packaging task instead of reading this project’s build
directory:

```kotlin
val nativeDebugSymbols by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(
        nativeDebugSymbols.name,
        "com.github.neuralgpt407.andr-oboe-audio-engine:" +
            "oboe-engine:v0.2.0:native-symbols@zip"
    )
}
```

Wire that resolvable configuration into the consuming app's symbol-packaging
task. The classifier contains unstripped symbols for `arm64-v8a` and
`armeabi-v7a` and must use the same immutable version as the runtime artifact.

## Third-party code

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Vendored license files
remain beside their sources.
