# Neural Sound Audio Engine

One Android audio SDK for the Neural Sound apps (Denoise, Music Separation, Music
Separation On-Device):

- multitrack playback on one clock (stems, A/B buses), live track append, per-track volume,
  mute, balance, offset and boost;
- master pitch/tempo, lookahead limiter and focus ducking;
- a muted Media3 video kept in sync with the audio;
- microphone recording into 44.1 kHz WAV takes, with frame-exact punch-in;
- offline file waveforms.

It is built on Oboe (AAudio/OpenSL ES) with a native mixer, NDK MediaCodec decoding and
Signalsmith Stretch.

## Artifact

| Artifact | Purpose |
| --- | --- |
| `com.github.neuralgpt407.andr-oboe-audio-engine:audio-engine:<tag>` | Player (with built-in video), recorder, waveform analysis |
| `com.github.neuralgpt407.andr-oboe-audio-engine:audio-engine:<tag>:native-symbols@zip` | Unstripped native symbols for Play Console / Crashlytics |

The Android baseline is min SDK 24, compile SDK 36, NDK 29, CMake 3.22.1, Java 11 and
Kotlin 2.0. The published ABIs are `arm64-v8a` and `armeabi-v7a`. The native library is
`libneuralsound_audio_engine.so`; it exports only `JNI_OnLoad`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.neuralgpt407.andr-oboe-audio-engine:audio-engine:v1.0.1")
}
```

## Quick start

The SDK is organized around one entry point and three session types. Every session exposes
**one `StateFlow`** for what the UI renders and **one event `Flow`** for one-off happenings.
Calls are main-safe; `suspend` functions wait for native work. Failures are always a typed
`AudioResult.Err(AudioError)` and never contain media URIs or paths.

```kotlin
val engine = AudioEngine.create(context)          // one per process, e.g. in your DI graph

// Player: one per screen (player, recording preview, separation preview).
val player = engine.newPlayer(PlayerOptions(maxTracks = 10, focus = FocusPolicy.Duck()))
player.load(
    PlayerSource(
        tracks = listOf(
            Track(TrackId("original"), originalUri, TrackSettings(muted = true)),
            Track(TrackId("denoised"), denoisedUri, TrackSettings(boostDb = 6)),
        ),
        videoUri = videoUri,                        // optional muted video
        autoPlay = true,
    ),
)
player.appendTrack(Track(TrackId("vocal"), vocalUri))          // joins at the playhead
player.updateTrack(TrackId("original")) { it.copy(muted = false, balance = ChannelGain.balance(-0.3f)) }
player.setPitchTempo(PitchTempo(semitones = -2, tempo = 1.25f))
player.setRange(PlaybackRange(10_000, 20_000), loop = true)
player.seekToFraction(0.5f); player.seekBy(-10_000); player.seekAndPlay(30_000)

player.state.collect { s -> /* status, positionMs, durationMs, tracks, pitchTempo, route, video */ }
player.events.collect { e -> /* Completed, TrackFailed, FocusChanged, RouteChanged, Failed */ }

val snapshot = player.detach()                    // hand off to another screen...
engine.newPlayer().restore(snapshot!!)            // ...and continue where it left off
player.release()
```

```kotlin
// Recorder: warm mic, any number of takes, pushed meter and waveform.
val recorder = engine.newRecorder()
recorder.openMic()                                        // needs RECORD_AUDIO
val take = recorder.record(
    TakeRequest(file, startFrame = Recorder.framesForMs(30_000), mode = TakeMode.PUNCH_IN),
).getOrNull()!!
take.waveform.collect { buckets -> /* 32 ms RMS buckets */ }
recorder.meter.collect { meter -> /* peak, rms, clipping */ }
take.pause(); take.resume()                               // crossfaded punch-in segments
val result = take.stop()                                  // TakeResult(frames, fileDurationMs, ...)
```

```kotlin
// Waveform of a file (cancel by cancelling the coroutine; cache by algorithmVersion).
val waveform = engine.waveforms.analyze(parcelFileDescriptor).getOrNull()
```

## Behaviour

**Player**
- **Tracks.** Up to `PlayerOptions.maxTracks` per player (default 8, up to 16). Any sample
  rate and channel count is normalized to 44.1 kHz stereo on one clock.
- **Offsets.** `TrackSettings.offsetMs` is a read offset: a positive value reads the track
  ahead (it sounds earlier, e.g. to compensate recording latency), and a negative value
  delays it behind silence.
- **Signal chain.** Per-track boost (0–18 dB, 5 ms ramp) → mix → pitch/tempo (±10 semitones,
  0.5–2.0×) → linked lookahead limiter (0.98 ceiling, 5 ms lookahead, 50 ms release) → duck
  ramp → safety clamp. The limiter matches the export filter
  `alimiter limit=0.98 attack=5 release=50`.
- **Clamping.** Out-of-range settings are clamped; the applied values are what
  `PlayerState` reports.
- **End of playback.** A range repeats only when `loop = true`. A natural end emits one
  `PlayerEvent.Completed` and moves to `ENDED`.
- **Audio focus.**
  - `Duck` ramps down on may-duck losses.
  - `PauseAndResume` pauses on them.
  - With either policy, a transient loss pauses and auto-resumes unless the user paused or
    played in between, and a permanent loss always pauses.
  - `Ignore` never requests focus. Use it for secondary players such as recording previews,
    so they don't interrupt the main one.
- **Routes.** `pauseOnNoisy` pauses when headphones are unplugged. Route changes reopen the
  stream. `PlayerState.route.kind` tells recording UIs whether monitoring-grade output (wired
  or USB) is connected.
- **Video.** `PlayerState.video.player` is a Media3 `Player` to put in a `PlayerView`. The
  video follows play/pause/tempo and is drift-corrected: nothing within 75 ms, a small speed
  nudge (at most ±10 %) up to 450 ms, and a seek above that. On re-attach the frame appears
  within 450 ms when keyframes are at most 2 s apart; files with sparse keyframes (e.g.
  WhatsApp videos) take up to the time needed to decode from the previous keyframe. Use `video.suspendPresentation()` / `attachPresentation()` /
  `detachPresentation()` while audio keeps playing, and rebind views when
  `VideoState.revision` changes.
- **Diagnostics.** `player.diagnostics()` reports audio-callback and render-thread load and
  underruns.

**Recorder**
- **Takes** are 44.1 kHz mono PCM16 WAV whatever the device input rate. `TakeMode.NEW`
  replaces the file; `PUNCH_IN` overwrites from `startFrame` with a 5 ms crossfade and keeps
  everything else.
- **Saturation.** Input saturation (soft tanh) is off unless `TakeRequest.saturation = true`.
- **Disconnects.** If the input device disconnects, the partial take is finalized as a valid
  WAV and `RecorderEvent.InputLost` is emitted.

## Migrating from the app-local engines

| App-local engine (`AudioMixerManager`) | `audio-engine` |
| --- | --- |
| `AudioMixerSessionFactory.create()` + owner tokens | `engine.newPlayer()` per owner, `release()` |
| `initializePlayers` / `preparePlayers(map, volumes, gains, …)` | `load(PlayerSource(tracks, videoUri, startAtMs, range, loop, pitchTempo, autoPlay))` |
| `AudioTrackType` enum | `TrackId(enum.name)` (keeps persisted names) |
| `setVolume` / `setMuted` / `setChannelGain` / `setTrackOffset` / `setSourceBoostDb` | `updateTrack(id) { it.copy(...) }` |
| `setTempo` / `setPitchSemitones` / `applyPlaybackAdjustments` | `setPitchTempo(PitchTempo(...))` |
| `seekTo(fraction)` / `seekToMs` / `seekBack` / `seekForward` / `seekToAndPlay` | `seekToFraction` / `seekTo` / `seekBy(±10_000)` / `seekAndPlay` |
| `currentPosition`, `totalDuration`, `isPlaying`, `videoAspectRatio`, … flows | `player.state` |
| `naturalPlaybackCompletions` | `player.events` → `PlayerEvent.Completed` |
| `releasePlayersPreservingPlaybackState` | `detach()` + `restore(snapshot)` |
| `attachVideoPresentation` / `detachVideoPresentation` | `player.video.attachPresentation()` / `detachPresentation()` |
| `OboeRecorderEngine.startMicSession` / `startWritingAtFrame` / `telemetry(i)` | `recorder.openMic()` / `record(TakeRequest(...))` / `take.waveform` |
| `NativeAudioWaveformAnalyzer.analyze(fd)` | `engine.waveforms.analyze(fd)` |

## Build and verify

```shell
./gradlew :audio-engine:testDebugUnitTest :audio-engine:apiCheck   # needs a host c++ for the DSP host tests
./gradlew :audio-engine:assembleRelease
./gradlew :audio-engine:connectedDebugAndroidTest                  # physical device
```

The public API is locked by `audio-engine/api/audio-engine.api`
(binary-compatibility-validator). After 1.0, releases may only add to it. The EngineTest app
(a separate repository) runs every app use case against the SDK, either through a composite
build or from JitPack.

## Release and symbols

Push an immutable tag such as `v1.0.0`; JitPack builds `audio-engine` from it. The tag,
including its `v` prefix, is the dependency version. Before tagging, qualify a local
publication with [`verification/release`](verification/release/README.md) and
[`verification/consumer-smoke`](verification/consumer-smoke). Use semantic versioning, and
never move or reuse a published tag.

Resolve the version-matched symbols into your Play/Crashlytics packaging:

```kotlin
val nativeDebugSymbols by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(nativeDebugSymbols.name, "com.github.neuralgpt407.andr-oboe-audio-engine:audio-engine:v1.0.1:native-symbols@zip")
}
```

## Third-party code

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Vendored license files remain beside
their sources.
