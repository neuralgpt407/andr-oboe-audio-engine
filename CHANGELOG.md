# Changelog

## v1.0.0 — unreleased

A new public API, designed for the three Neural Sound apps (FEAT-0001). It is not
source-compatible with v0.2.0, which no app shipped on.

### Artifacts
- **Single artifact `audio-engine`.** It replaces `oboe-engine` and `oboe-media3`; video is
  now built into the player.

### API
- **Entry points.** `AudioEngine.create(context)` → `newPlayer(options)`, `newRecorder()`,
  `waveforms`.
- **`Player`.**
  - One `state` flow and one `events` flow.
  - Single-call `updateTrack`.
  - `seekToFraction` / `seekBy` / `seekAndPlay`.
  - `setRange(range, loop)`.
  - `detach()` / `restore()` for hand-offs.
  - `video` presentation control.
  - `diagnostics()`.
- **`Recorder`.** Warm mic, `record(TakeRequest)` (NEW or PUNCH_IN, optional saturation),
  `Take.pause/resume/stop`, pushed `meter` and `Take.waveform` buckets.
- **Errors.** One error model: `AudioResult<T>` / `AudioError`. Track failures name the
  `TrackId` and a `TrackFailureReason`, never a URI.

### Engine
- **Signal chain.** Per-track source boost (0–18 dB) and a master lookahead limiter that
  matches the export `alimiter`, plus a post-limiter duck ramp, ported from the On-Device
  engine.
- **Serialized lifecycle.** A render-lifecycle coordinator serializes
  play/pause/seek/restart/append. Appending after end of stream restarts rendering, and
  decoder slots are published lock-free.
- **Track limit.** Configurable per player (default 8, up to 16).
- **Tempo** now reaches 2.0×. Pitch is ±10 semitones. The public constants equal the native
  limits, and values outside them are clamped and reported.
- **Focus.** `FocusPolicy` (Duck, PauseAndResume, Ignore). Ducking uses the native
  attenuation ramp.
- **Completion.** A natural-completion event, and `PlayerStatus.ENDED`.
- **Video.** Muted video sync with a 75 ms dead-band, a proportional speed nudge below
  450 ms and hysteretic hard seeks above it; attach/suspend/detach while audio plays. The
  video player runs video-only, so seeks no longer wait for a muted audio track.
- **Robustness.** The oboe#2325 orphan-drain workaround on stream close. Finer decoder
  failure kinds (`NoAudioTrack`, `UnsupportedCodec`).

### Behaviour changes
Compared with the apps' local engines:
- Input saturation is **off** by default; set `TakeRequest.saturation = true` for the
  previous sound.
- The limiter is always active (it only acts above 0.98).
- A playback range no longer loops unless `loop = true`.
- Volume and mute are independent; volume 0 no longer mutes the track.
