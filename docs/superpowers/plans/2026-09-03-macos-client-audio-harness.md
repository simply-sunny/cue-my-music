# macOS Client Audio Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in visible-client test that drives Cue My Music's real playback slider and proves actual seek, switch, pause, and resume behavior from process-isolated macOS audio.

**Architecture:** A test-only Java driver emits phase events and interacts with the running screen. A standalone Swift executable captures only the emitted Java PID through CoreAudio Process Taps, decodes vanilla OGG references with AVFoundation, and validates identity/position/overlap with Accelerate. Gradle conditionally wraps `runClient` only when `testClientAudio` is requested.

**Tech Stack:** Java 25, Fabric client lifecycle events, Gradle/Loom, Swift 6.4, CoreAudio, AudioToolbox, AVFoundation, Accelerate.

**Spec:** `docs/superpowers/specs/2026-09-03-macos-client-audio-harness.md`

## Global Constraints

- macOS 14.2+ only, with an active graphical login session.
- No third-party audio driver, native library dependency, coordinate-based desktop automation, or Accessibility permission.
- `./gradlew test` and `./gradlew build` remain unchanged.
- Test driver classes and generated artifacts are excluded from the production jar.
- The scenario uses `vanilla:sweden`, seeks through the real visible `PlaybackSlider` to exactly 60 seconds, switches to `vanilla:wet_hands`, pauses, resumes, then exits.
- Raw RMS cannot prove identity, seeking, or overlap; use normalized correlation and residual projection.
- Global test timeout is 30 seconds.
- Execute this plan only after Tasks 2–4 of `docs/superpowers/plans/2026-09-03-playback-scrub-ui-redesign.md` are reviewed and complete.

---

## File map

**Create**
- `tools/audio-test/main.swift` — process tap, PCM capture, asset decoding, DSP assertions, JSON report, and `--self-test`.
- `src/client/java/com/cuemymusic/client/testing/AutomatedClientAudioDriver.java` — opt-in client-thread scenario and JSONL phase events.
- `src/test/java/com/cuemymusic/ClientAudioDriverContractTest.java` — production-jar exclusion/property contract.

**Modify**
- `build.gradle` — `testClientAudio` orchestration and test-driver jar exclusion.
- `src/client/java/com/cuemymusic/client/CueMyMusicClient.java` — reflective test-driver registration behind one system property.
- `docs/ARCHITECTURE.md` — opt-in macOS verification command and boundaries.

**Generated and ignored**
- `build/client-audio-test/audio-test`
- `build/client-audio-test/events.jsonl`
- `build/client-audio-test/capture.wav`
- `build/client-audio-test/report.json`

---

### Task 1: Build and Unit-Test the Native Audio Analyzer

**Files:**
- Create: `tools/audio-test/main.swift`

**Interfaces:**
- Consumes: Java PID and JSONL phase events.
- Produces: `audio-test --self-test` and `audio-test --events <path> --output <dir> --assets <dir>`.

- [ ] **Step 1: Write generated-signal self-tests before capture code**

Start `main.swift` with pure helpers and a failing `--self-test` entry point:

```swift
import Foundation
import Accelerate

func rms(_ x: [Float]) -> Float {
    var square: Float = 0
    vDSP_svesq(x, 1, &square, vDSP_Length(x.count))
    return sqrt(square / Float(max(1, x.count)))
}

func normalizedCorrelation(_ sample: [Float], _ reference: [Float], at offset: Int) -> Float {
    guard offset >= 0, offset + sample.count <= reference.count else { return -1 }
    let slice = Array(reference[offset ..< offset + sample.count])
    var dot: Float = 0, sampleEnergy: Float = 0, referenceEnergy: Float = 0
    vDSP_dotpr(sample, 1, slice, 1, &dot, vDSP_Length(sample.count))
    vDSP_svesq(sample, 1, &sampleEnergy, vDSP_Length(sample.count))
    vDSP_svesq(slice, 1, &referenceEnergy, vDSP_Length(sample.count))
    let denominator = sqrt(sampleEnergy * referenceEnergy)
    return denominator > 0 ? dot / denominator : 0
}

func bestMatch(_ sample: [Float], in reference: [Float], step: Int = 240) -> (offset: Int, correlation: Float) {
    precondition(!sample.isEmpty && sample.count <= reference.count)
    var best = (0, -Float.infinity)
    for offset in Swift.stride(from: 0, through: reference.count - sample.count, by: max(1, step)) {
        let score = normalizedCorrelation(sample, reference, at: offset)
        if score > best.1 { best = (offset, score) }
    }
    return best
}
```

Self-tests generate deterministic sine mixtures and assert:

```swift
let rate = 48_000
let reference = (0 ..< rate * 4).map { Float(sin(2 * Double.pi * 440 * Double($0) / Double(rate))) }
let sample = Array(reference[rate ..< rate * 2])
precondition(abs(rms(sample) - 0.707) < 0.01)
let match = bestMatch(sample, in: reference)
precondition(match.correlation > 0.99)
precondition(abs(match.offset - rate) <= 240)
```

Add a second independent tone, project it from a mixture, and prove residual correlation detects 20% overlap but accepts 0% overlap. Add silence checks for peak `< 0.001` and RMS `< 0.0005`.

- [ ] **Step 2: Compile and verify RED**

Run:

```bash
mkdir -p build/client-audio-test
swiftc -O -framework CoreAudio -framework AudioToolbox -framework AVFoundation -framework Accelerate tools/audio-test/main.swift -o build/client-audio-test/audio-test
build/client-audio-test/audio-test --self-test
```

Expected: failure because capture/event/report modes are not implemented yet; generated DSP checks execute before that failure.

- [ ] **Step 3: Implement process-only CoreAudio capture**

Use these native APIs and fail with an infrastructure error for every non-`noErr` result:

```swift
var processObject = AudioObjectID(0)
var address = AudioObjectPropertyAddress(
    mSelector: kAudioHardwarePropertyTranslatePIDToProcessObject,
    mScope: kAudioObjectPropertyScopeGlobal,
    mElement: kAudioObjectPropertyElementMain)
var pid = pid_t(targetPID)
var size = UInt32(MemoryLayout<AudioObjectID>.size)
AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &processObject)

let tapDescription = CATapDescription(stereoMixdownOfProcesses: [processObject])
var tapID = AudioObjectID(0)
AudioHardwareCreateProcessTap(tapDescription, &tapID)
```

Read `kAudioTapPropertyUID`, create a private aggregate device with one sub-tap dictionary keyed by `kAudioSubTapUIDKey`, register an `AudioDeviceIOProc`, and append interleaved Float32 stereo frames under a lock. Start with `AudioDeviceStart` and stop/destroy in `defer`:

```swift
defer {
    AudioDeviceStop(aggregateID, ioProcID)
    AudioDeviceDestroyIOProcID(aggregateID, ioProcID)
    AudioHardwareDestroyAggregateDevice(aggregateID)
    AudioHardwareDestroyProcessTap(tapID)
}
```

Never capture the default input or whole-system mix.

- [ ] **Step 4: Implement event parsing, OGG decoding, and reference resampling**

Decode each JSONL line into:

```swift
struct PhaseEvent: Codable {
    let phase: String
    let epochMs: Int64
    let pid: Int32?
    let trackId: String?
    let positionSeconds: Double?
    let success: Bool?
    let error: String?
}
```

Resolve asset hashes from the Loom asset index with `JSONSerialization`, map the first two hash characters to `assets/objects/<prefix>/<hash>`, decode with `AVAudioFile`, and resample to capture format through `AVAudioConverter`. Mix references to mono before DSP so output channel layout does not affect matching.

- [ ] **Step 5: Implement exact product assertions and report**

Slice capture by phase epoch timestamps and enforce:

```swift
assertMetric("sweden.start.correlation", startMatch.correlation, atLeast: 0.85)
assertRange("sweden.start.offset", seconds(startMatch.offset), 0.0 ... 3.5)
assertMetric("sweden.seek.correlation", seekMatch.correlation, atLeast: 0.85)
assertRange("sweden.seek.offset", seconds(seekMatch.offset), 60.0 ... 62.5)
assertMetric("wet_hands.switch.correlation", switchMatch.correlation, atLeast: 0.85)
assertMetric("sweden.residual.variance", swedenResidualVariance, atMost: 0.03)
assertMetric("pause.peak", pause.map(abs).max() ?? 0, atMost: 0.001)
assertMetric("pause.rms", rms(pause), atMost: 0.0005)
assertMetric("wet_hands.resume.correlation", resumeMatch.correlation, atLeast: 0.85)
assertMetric("wet_hands.resume.offset_delta", resumeOffsetDelta, atMost: 0.5)
```

Write measured values, thresholds, infrastructure/product classification, and pass/fail to `report.json`. Write captured Float32 stereo frames to `capture.wav` using `AVAudioFile`.

- [ ] **Step 6: Run self-tests**

Run the compile command from Step 2 and:

```bash
build/client-audio-test/audio-test --self-test
```

Expected: all generated DSP, event parsing, and report checks pass without launching Minecraft.

- [ ] **Step 7: Commit**

```bash
git add tools/audio-test/main.swift
git commit -m "test: add native macOS audio analyzer"
```

- [ ] **Step 8: Gemini reviewer gate**

Review process isolation, native-resource cleanup, PCM format handling, correlation math, residual projection, thresholds, deterministic self-tests, and clear infrastructure/product failures.

---

### Task 2: Add the Test-Only In-Game Scenario

**Files:**
- Create: `src/client/java/com/cuemymusic/client/testing/AutomatedClientAudioDriver.java`
- Create: `src/test/java/com/cuemymusic/ClientAudioDriverContractTest.java`
- Modify: `src/client/java/com/cuemymusic/client/CueMyMusicClient.java`
- Modify: `build.gradle`

**Interfaces:**
- Consumes: reviewed playback APIs and `PlaybackSlider` from the playback/UI plan.
- Produces: `AutomatedClientAudioDriver.register()` and `build/client-audio-test/events.jsonl`.

- [ ] **Step 1: Write the production-boundary test first**

Create:

```java
@Test void clientAudioDriverIsOptInAndExcludedFromJar() throws Exception {
    var source = Files.readString(Path.of("src/client/java/com/cuemymusic/client/CueMyMusicClient.java"));
    assertTrue(source.contains("cuemymusic.autotest"));
    assertTrue(source.contains("Class.forName"));
    var gradle = Files.readString(Path.of("build.gradle"));
    assertTrue(gradle.contains("com/cuemymusic/client/testing/**"));
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.ClientAudioDriverContractTest'
```

Expected: failure because property/reflection/exclusion do not exist.

- [ ] **Step 3: Add reflective opt-in registration and jar exclusion**

In `CueMyMusicClient.onInitializeClient()` after normal library registration:

```java
if (Boolean.getBoolean("cuemymusic.autotest")) {
    try {
        Class.forName("com.cuemymusic.client.testing.AutomatedClientAudioDriver")
                .getMethod("register").invoke(null);
    } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Client audio test driver unavailable", e);
    }
}
```

In Gradle:

```groovy
tasks.named('jar') {
    exclude 'com/cuemymusic/client/testing/**'
}
tasks.named('sourcesJar') {
    exclude 'com/cuemymusic/client/testing/**'
}
```

- [ ] **Step 4: Implement a deterministic client-tick state machine**

`AutomatedClientAudioDriver.register()` creates the output directory, truncates `events.jsonl`, and registers one `END_CLIENT_TICK` callback. Use an enum with exactly:

```java
WAIT_READY, BASELINE, PLAY_A, WAIT_A, SEEK_A, WAIT_SEEK,
SWITCH_B, WAIT_B, PAUSE_B, WAIT_PAUSE, RESUME_B, WAIT_RESUME, FINISH, FAILED
```

Each transition writes one JSON object with `phase`, `epochMs`, `pid`, `trackId`, `positionSeconds`, `success`, and `error` fields. The first event emits `ProcessHandle.current().pid()`.

Read tracks by stable IDs from `CueMyMusic.getInstance().getLibrary()`. Open `new JukeboxLibraryScreen(null)` after readiness. Use the client thread only.

For the seek phase, find the single visible `AbstractSliderButton` in `screen.children()`, verify it is active, compute `x = slider.getX() + round(slider.getWidth() * 60 / duration)`, and dispatch:

```java
var click = new MouseButtonEvent(x, slider.getY() + slider.getHeight() / 2.0,
        new MouseButtonInfo(0, 0));
screen.mouseClicked(click, false);
screen.mouseReleased(click);
```

Do not call `MusicDirector.seek` directly. Wait for the seek future/runtime position to report success before emitting `seek_applied`.

Use event-driven state checks plus fixed capture windows from the spec. Fail after 30 seconds with the last state and call `Minecraft.stop()` after flushing the final event.

- [ ] **Step 5: Set a deterministic test sound profile**

Before baseline capture, snapshot every `SoundSource` volume, set master/music to 1.0 and all other categories to 0.0 through Minecraft options, then stop existing sounds. Emit `baseline` only after one second of silence. Restore every snapshotted volume in both `FINISH` and `FAILED` before stopping the client.

- [ ] **Step 6: Run Java tests and prove jar exclusion**

```bash
./gradlew test --tests 'com.cuemymusic.ClientAudioDriverContractTest'
./gradlew build
jar tf build/libs/cue-my-music-1.0.0.jar | rg 'AutomatedClientAudioDriver' && exit 1 || true
```

Expected: test/build pass and the production jar contains no driver class.

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/client/java/com/cuemymusic/client/CueMyMusicClient.java src/client/java/com/cuemymusic/client/testing/AutomatedClientAudioDriver.java src/test/java/com/cuemymusic/ClientAudioDriverContractTest.java
git commit -m "test: add opt-in client audio scenario"
```

- [ ] **Step 8: Gemini reviewer gate**

Review production exclusion, property isolation, readiness, client-thread access, real slider event dispatch, phase ordering, 30-second timeout, options restoration, event flushing, and guaranteed client exit.

---

### Task 3: Orchestrate the Visible Client and Capture

**Files:**
- Modify: `build.gradle`
- Modify: `docs/ARCHITECTURE.md`

**Interfaces:**
- Consumes: Task 1 `audio-test`, Task 2 driver/events, Loom `runClient`.
- Produces: `./gradlew testClientAudio`.

- [ ] **Step 1: Add the opt-in Gradle orchestration**

Register an alias task and conditionally wrap `runClient` only when that alias is in the graph:

```groovy
def audioTestOutput = layout.buildDirectory.dir('client-audio-test')
def testClientAudio = tasks.register('testClientAudio') {
    group = 'verification'
    description = 'Runs the visible macOS Minecraft client and verifies process audio.'
    dependsOn 'runClient'
}

tasks.named('runClient') {
    doFirst {
        if (!gradle.taskGraph.hasTask(testClientAudio.get())) return
        if (!System.getProperty('os.name').toLowerCase().contains('mac'))
            throw new GradleException('testClientAudio requires macOS 14.2+')
        def out = audioTestOutput.get().asFile
        out.mkdirs()
        exec {
            commandLine 'swiftc', '-O', '-framework', 'CoreAudio', '-framework', 'AudioToolbox',
                    '-framework', 'AVFoundation', '-framework', 'Accelerate',
                    'tools/audio-test/main.swift', '-o', new File(out, 'audio-test')
        }
        delete new File(out, 'events.jsonl')
        project.ext.cmmAudioHarness = new ProcessBuilder(
                new File(out, 'audio-test').absolutePath,
                '--events', new File(out, 'events.jsonl').absolutePath,
                '--output', out.absolutePath,
                '--assets', new File(gradle.gradleUserHomeDir, 'caches/fabric-loom/assets').absolutePath)
                .directory(project.projectDir).inheritIO().start()
        jvmArgs '-Dcuemymusic.autotest=true'
    }
    doLast {
        if (!project.ext.has('cmmAudioHarness')) return
        int exit = project.ext.cmmAudioHarness.waitFor()
        if (exit != 0) throw new GradleException("Client audio assertions failed; see ${audioTestOutput.get().asFile}/report.json")
    }
}
```

Add this no-op-for-normal-runs finalizer so a failed client cannot leave capture running:

```groovy
def cleanupClientAudio = tasks.register('cleanupClientAudio') {
    doLast {
        if (project.ext.has('cmmAudioHarness') && project.ext.cmmAudioHarness.isAlive()) {
            project.ext.cmmAudioHarness.destroyForcibly()
            project.ext.cmmAudioHarness.waitFor()
        }
    }
}
tasks.named('runClient') { finalizedBy cleanupClientAudio }
```

- [ ] **Step 2: Run analyzer self-tests through Gradle**

Before starting the harness in `doFirst`, invoke its `--self-test`; fail immediately if non-zero.

- [ ] **Step 3: Establish end-to-end RED with a temporary regression injection**

Temporarily change only the channel-thread seek callback so it reports success without calling `AL10.alSourcef`; leave buffered loading, real duration, and slider capability unchanged. Then run:

```bash
./gradlew testClientAudio --rerun-tasks
```

Expected: the visible client launches and exits; the task fails specifically at `sweden.seek.offset`, with the matched offset near elapsed playback rather than 60 seconds. Infrastructure setup, Sweden identity, slider dispatch, and event capture must pass. Restore the real `AL10.alSourcef` call immediately afterward and verify `git diff` contains no regression injection.

Record the failing `report.json` in the task report but do not commit generated artifacts.

- [ ] **Step 4: Run unchanged test against reviewed buffered playback/UI**

After the playback/UI plan Tasks 3–4 are complete:

```bash
./gradlew testClientAudio --rerun-tasks
```

Expected: all assertions pass, including Sweden offset 60.0–62.5, Sweden residual variance below 0.03 after switch, pause silence, and resumed Wet Hands offset delta below 0.5 seconds.

- [ ] **Step 5: Document the opt-in command**

Add to `docs/ARCHITECTURE.md`:

```markdown
### macOS audible client test

`./gradlew testClientAudio` launches a visible client and captures only its Java process through CoreAudio Process Taps. It verifies audible track identity, a real 60-second scrub, no Sweden residual after switching to Wet Hands, pause silence, and resume continuity. Requires macOS 14.2+, Swift, downloaded Loom assets, and an active GUI session. It is not part of normal `test`/`build`; the driver is excluded from the production jar.
```

- [ ] **Step 6: Run all verification**

```bash
./gradlew clean test build
./gradlew testClientAudio
jar tf build/libs/cue-my-music-1.0.0.jar | rg 'AutomatedClientAudioDriver' && exit 1 || true
```

Expected: normal tests/build and audible client test pass; production jar excludes the driver.

- [ ] **Step 7: Commit**

```bash
git add build.gradle docs/ARCHITECTURE.md
git commit -m "test: automate macOS client audio verification"
```

- [ ] **Step 8: Final Gemini review**

Review the full harness against the spec, generated self-test evidence, baseline RED report, final GREEN report, process cleanup, production jar contents, and ordinary test/build behavior. One fix worker addresses confirmed Critical/Important findings, followed by one scoped re-review.
