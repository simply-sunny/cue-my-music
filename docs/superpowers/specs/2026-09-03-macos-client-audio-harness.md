# macOS Client Audio Harness Design

## Goal

Provide one opt-in Gradle test that launches a visible Minecraft 26.2 client, drives Cue My Music through play, seek, switch, pause, and resume, captures the Java process's actual system audio, and verifies the audible result rather than trusting internal OpenAL state.

## Scope

This harness is macOS-only and developer-facing. It is not part of normal `test` or `build`, does not run headlessly, and is excluded from the production mod jar.

Out of scope: Windows/Linux capture backends, coordinate-based mouse automation, screenshot comparison, third-party virtual audio drivers, generalized audio testing libraries, and CI support without an active macOS GUI session.

## Architecture

### Gradle entry point

`./gradlew testClientAudio` builds the mod and a small native Swift executable, launches a visible `runClient` process with `-Dcuemymusic.autotest=true`, waits for structured test events, captures audio, analyzes the recording, and exits non-zero on failure.

The task is opt-in. `./gradlew test` and `./gradlew build` remain unchanged and require no native capture permission.

### Test-only in-game driver

A Java driver is compiled onto the development `runClient` classpath but excluded from the production jar. When `cuemymusic.autotest=true`, `CueMyMusicClient` loads it by class name with reflection; production startup neither links nor initializes the excluded class.

The driver waits until the client window, title screen, resource manager, and sound engine are ready. It writes newline-delimited JSON events under `build/client-audio-test/events.jsonl`. The first event includes `ProcessHandle.current().pid()` so the external harness targets the correct Java process without guessing from process names or window titles.

The driver opens `JukeboxLibraryScreen` and runs one deterministic sequence on the client thread:

1. silence/setup;
2. play `vanilla:sweden` from the beginning;
3. dispatch a real mouse click/drag to the visible `PlaybackSlider` position corresponding to exactly 60 seconds;
4. switch immediately to `vanilla:wet_hands`;
5. pause Wet Hands;
6. resume Wet Hands;
7. stop playback and close the client.

Every phase emits its wall-clock timestamp, track ID, requested position, playback state, and success/failure result. The driver times out and exits with a failure event instead of hanging.

This tests the actual playback API used by the screen. It does not synthesize fragile pixel coordinates or grant macOS Accessibility control.

### Native process-audio capture

A standalone Swift executable under `tools/audio-test` uses only Apple frameworks:

- CoreAudio `kAudioHardwarePropertyTranslatePIDToProcessObject` maps the emitted Java PID to an audio process object.
- `CATapDescription(stereoMixdownOfProcesses:)` and `AudioHardwareCreateProcessTap` create a process-isolated tap.
- A private aggregate device containing the tap supplies Float32 stereo frames through `AudioDeviceCreateIOProcID` and `AudioDeviceStart`.
- `AVFoundation` decodes the matching Minecraft OGG references.
- `AVAudioConverter` resamples references to the captured device format.
- Accelerate/vDSP computes normalized cross-correlation and projection residuals.

The harness destroys the IO proc, aggregate device, and process tap in `defer` cleanup paths, including assertion failure and client timeout.

Gemini verified these APIs locally on this host: macOS 27.0 arm64, Swift 6.4, 48 kHz Float32 stereo capture from the running Minecraft Java PID. No BlackHole, Soundflower, or Loopback driver is required. The local verification did not trigger Screen Recording or Microphone permission; macOS policy changes may still require user approval on another machine.

## Reference audio

The harness resolves the Minecraft asset index at runtime. It looks up:

- `minecraft/sounds/music/game/sweden.ogg`;
- `minecraft/sounds/music/game/wet_hands.ogg`.

It maps each asset hash to the existing Loom asset object store and decodes that OGG. Hashes and absolute cache paths are not hard-coded.

Before capture, the test profile sets master and music volume to 1.0 and all unrelated sound categories to 0.0. Vanilla background starts remain suppressed by the mod while the scenario owns playback.

## Synchronization

Java events use epoch milliseconds. Swift timestamps captured frame blocks against epoch time and slices windows relative to each phase event. The harness waits for phase events rather than sleeping blindly; short settling windows account for audio-device and game-tick latency.

A 30-second global timeout kills the client process tree and reports the last received event.

## Assertions

Raw volume alone is insufficient. Assertions identify the actual recording and its position.

### Initial playback

For audio captured 1–3 seconds after Sweden starts:

- normalized cross-correlation against Sweden is at least 0.85;
- the best reference offset is within 0.0–3.5 seconds.

### Audible seek

For audio captured 0.5–2.5 seconds after the 60-second seek:

- normalized cross-correlation against Sweden is at least 0.85;
- the best reference offset is within 60.0–62.5 seconds.

A source that continues near its pre-seek position fails even if its internal clock reports 60 seconds.

### Switch without overlap

For audio captured 1–3 seconds after switching to Wet Hands:

- normalized cross-correlation against Wet Hands is at least 0.85;
- after subtracting the optimal Wet Hands projection, Sweden explains less than 3% of residual variance.

This combines external audio proof with the runtime's one-owner invariant. The residual check is not replaced by an RMS threshold.

### Pause and resume

During the settled pause window:

- peak absolute sample is below 0.001;
- RMS is below 0.0005.

After resume:

- Wet Hands correlation is at least 0.85;
- the matched offset continues from the pre-pause position within 0.5 seconds.

## Outputs

Each run writes only ignored build artifacts:

- `build/client-audio-test/events.jsonl`;
- `build/client-audio-test/capture.wav`;
- `build/client-audio-test/report.json`;
- `build/client-audio-test/audio-test` native executable.

The console prints one line per assertion with measured and required values. On failure it retains capture/report files; on success it may retain them for diagnosis but never stages them.

## Failure handling

The test fails clearly when:

- no GUI session or visible client starts;
- the Java PID cannot be translated to a CoreAudio process;
- a process tap or aggregate device cannot be created;
- reference assets are missing;
- the driver emits a failure/timeout event;
- capture contains no audible expected track;
- any correlation, offset, residual, pause, or resume assertion fails.

Infrastructure failures are reported separately from product assertion failures.

## Testing the harness

Swift unit checks use generated PCM arrays to prove correlation, offset detection, residual overlap rejection, and silence thresholds before the harness is trusted against Minecraft.

The end-to-end test must first run with a temporary one-line regression injection that changes the active sound back to streamed mode, and fail specifically because the post-seek reference offset remains near playback time instead of 60 seconds. The regression injection is then restored; the same unchanged harness must pass against buffered playback.

## Operational requirements

- macOS 14.2 or newer;
- active graphical login session;
- Swift toolchain and macOS SDK;
- Minecraft assets downloaded by Loom;
- output device available;
- no other requirement beyond the existing project dependencies and Apple system frameworks.
