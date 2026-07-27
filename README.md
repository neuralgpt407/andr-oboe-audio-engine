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
        "com.github.neuralgpt407.andr-oboe-audio-engine:oboe-engine:v0.1.0"
    )
}
```

Use the Media3 artifact instead when video synchronization is required. It
brings `oboe-engine` transitively:

```kotlin
dependencies {
    implementation(
        "com.github.neuralgpt407.andr-oboe-audio-engine:oboe-media3:v0.1.0"
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

All stems in one mixer session must use the same sample rate and must be mono
or stereo. The engine duplicates mono to stereo and rejects missing metadata,
multichannel audio, and mismatched sample rates with
`AudioFailure.UnsupportedTrackFormat`; it never mixes incompatible PCM
frame-for-frame.

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

## Recording

The host app must request `android.permission.RECORD_AUDIO` before opening the
mic session.

```kotlin
val recorder = AudioEngine(applicationContext).createRecorderSession()

recorder.startMicSession()
recorder.startWriting(RecordingRequest(outputFile))
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
file was created. `RecorderSession.status` also transitions to `FAILED` when
native capture reports a disconnect or writer overflow asynchronously.

## Release and symbols

Create and push an immutable Git tag such as `v0.1.0`, then look up
`neuralgpt407/andr-oboe-audio-engine` on
[JitPack](https://jitpack.io/#neuralgpt407/andr-oboe-audio-engine). JitPack
builds the two Maven publications from that tag; there is no package upload or
registry credential. The tag is the dependency version, including its `v`
prefix.

Use semantic versioning: patch releases for compatible fixes, minor releases
for compatible features, and major releases for breaking public API changes.
Never move or reuse a published tag.

Consumer apps should resolve the symbol classifier into their Play/Crashlytics
symbol packaging task instead of reading this project’s build directory.

## Third-party code

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Vendored license files
remain beside their sources.
