# Neural Sound Oboe Audio Engine

Reusable Android audio engine for low-latency multitrack playback, pitch/tempo
processing, microphone recording, waveform telemetry, WAV writing, device-route
recovery, and optional Media3 video synchronization.

## Artifacts

| Artifact | Purpose |
| --- | --- |
| `com.neuralsound.audio:oboe-engine:<version>` | Oboe playback, effects, recording, and resampling |
| `com.neuralsound.audio:oboe-media3:<version>` | Optional muted-video synchronization backed by Media3 |
| `com.neuralsound.audio:oboe-engine:<version>:native-symbols@zip` | Unstripped native symbols for Play Console and Crashlytics |

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

## Consume from GitHub Packages

Add the package repository to `settings.gradle.kts`. Keep credentials in
environment variables or user-level Gradle properties, never in a project:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/neuralgpt407/andr-oboe-audio-engine") {
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .get()
            }
        }
    }
}
```

Then add one or both artifacts:

```kotlin
dependencies {
    implementation("com.neuralsound.audio:oboe-engine:0.1.0")
    implementation("com.neuralsound.audio:oboe-media3:0.1.0")
}
```

For local development, put `gpr.user` and a token with `read:packages` in
`~/.gradle/gradle.properties`.

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
44.1 kHz WAV rate when needed. `RecordingResult` reports accepted and written
frame counts so callers can reject incomplete files. Invalid lifecycle calls
return `RecorderError.InvalidState`; `RecordingResult.file` is null when no
output file was created.

## Release and symbols

Create a GitHub release/tag such as `v0.1.0`. The publish workflow derives the
Maven version from the tag and publishes both AARs plus the
`native-symbols.zip` classifier.

Consumer apps should resolve the symbol classifier into their Play/Crashlytics
symbol packaging task instead of reading this project’s build directory.

## Third-party code

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Vendored license files
remain beside their sources.
