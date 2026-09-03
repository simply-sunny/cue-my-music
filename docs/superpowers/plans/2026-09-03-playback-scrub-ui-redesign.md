# Playback, Scrub, and Library UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee one active mod track, provide real buffered OpenAL seeking and duration, and replace the current stretched table with a centered native Minecraft library screen.

**Architecture:** `NativeMinecraftPlayback` becomes the sole playback owner with an explicit startup lifecycle and channel-thread OpenAL probes. Each active catalog entry resolves to one non-streamed file sound, while `MusicDirector` schedules only when playback has no owner. The UI uses a centered `ContainerObjectSelectionList`, native row widgets, and an `AbstractSliderButton` scrub control.

**Tech Stack:** Java 25, Minecraft 26.2 client APIs, Fabric API, Mixin accessors, LWJGL OpenAL, JUnit 5, Gradle/Loom.

**Spec:** `docs/superpowers/specs/2026-09-03-playback-scrub-ui-redesign.md`

## Global Constraints

- Preserve the exact 92-track catalog: 70 background tracks and 22 discs.
- Preserve fuzzy search, Artist/Title/Source sorting, Artist A–Z default, queue persistence, per-row `>` / `||`, and selected count under the checkbox column.
- Use vanilla Minecraft widgets/rendering only; no Cloth Config, custom decoder, new dependency, thumbnail, theme, animation, playlist, import, or Music & Sounds screen change.
- The scrub is visible and interactive only in `PLAYING` with known duration and a seek-capable channel.
- Playback states are exactly `STOPPED`, `STARTING`, `PLAYING`, and `PAUSED`; startup timeout is exactly 2,000 ms.
- Closing the library screen leaves the active track playing.
- OpenAL calls execute through `ChannelAccess.ChannelHandle.execute`; never call OpenAL from the render/client thread.
- Production behavior changes use red-green-refactor. Manual in-game verification remains mandatory.
- Preserve existing uncommitted MVP work; never reset, clean, or overwrite it.

---

## File map

**Create**
- `src/client/java/com/cuemymusic/client/playback/PlaybackLifecycle.java` — small pure ownership state machine used by runtime and tests.
- `src/client/java/com/cuemymusic/client/ui/PlaybackSlider.java` — one native slider bound to cached playback position/duration.
- `src/test/java/com/cuemymusic/client/playback/PlaybackLifecycleTest.java` — startup, replacement, pause, timeout, and completion tests.
- `src/test/java/com/cuemymusic/client/playback/BufferedPlaybackTest.java` — static-buffer, duration math, and clamp tests.

**Modify**
- `src/client/java/com/cuemymusic/client/playback/PlaybackState.java` — add `STARTING`.
- `src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java` — ownership, static file resolution, channel probes, seek, pause/resume.
- `src/client/java/com/cuemymusic/client/playback/MusicDirector.java` — atomic preview replacement and owner-aware scheduling.
- `src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java` — centered native controls/list/footer and removal of manual scrolling/hit testing.
- `src/client/java/com/cuemymusic/client/music/VanillaTrackRegistry.java` — remove fake 180-second assignments.
- Move `src/test/java/com/cuemymusic/JukeboxLibraryScreenLogicTest.java` to `src/test/java/com/cuemymusic/client/ui/JukeboxLibraryScreenLogicTest.java` — test package-visible production layout calculations at normal/narrow widths.
- `src/test/java/com/cuemymusic/PlaybackStateTest.java` — expect four states.
- `docs/ARCHITECTURE.md` — describe final ownership, buffered seek, native list, and real duration.
- `.pi/WORKING_STATE.md` — execution ledger summary only; never commit it.

**Retain unless proven unnecessary**
- `SoundManagerAccessor`, `SoundEngineAccessor`, `ChannelHandleAccessor`, `ChannelAccessor` — map the owned `SoundInstance` to a `Channel`; the source accessor is used only inside `ChannelHandle.execute`.

## Execution workspace

The current `main` checkout contains the prior MVP as uncommitted source changes. Before Task 1:

```bash
git switch -c checkpoint/music-library-mvp
git add README.md build.gradle gradle.properties docs site src
git commit -m "feat: checkpoint minimal music library MVP"
git switch main
git worktree add .worktrees/playback-scrub-ui -b fix/playback-scrub-ui checkpoint/music-library-mvp
```

All Muse workers run from `.worktrees/playback-scrub-ui`. `.pi/` remains uncommitted in the original checkout. No agent resets or cleans either checkout.

---

### Task 1: Confirm Playback Root Causes In Game

**Files:**
- Temporarily modify: `src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java`
- Temporarily modify: `src/client/java/com/cuemymusic/client/playback/MusicDirector.java`
- Evidence: Prism instance `minecraft/logs/latest.log`

**Interfaces:**
- Consumes: current `play`, `isPlaying`, `seek`, and `MusicDirector.tick` behavior.
- Produces: a written evidence note identifying whether startup attachment and streamed seeking hypotheses are confirmed; no permanent production code.

- [ ] **Step 1: Record the untouched baseline**

Run:

```bash
./gradlew clean test build
shasum -a 256 build/libs/cue-my-music-1.0.0.jar
```

Expected: build succeeds and the checksum is recorded in the task report.

- [ ] **Step 2: Add transition-only diagnostics**

Add one SLF4J logger to `NativeMinecraftPlayback` and log only these events:

```java
LOGGER.info("CMM_DIAG start track={} sound={} state={}",
        track.getId(), System.identityHashCode(sound), state);
LOGGER.info("CMM_DIAG channel track={} sound={} attached={} active={}",
        currentTrack.getId(), System.identityHashCode(currentSound), handle != null,
        mc.getSoundManager().isActive(currentSound));
LOGGER.info("CMM_DIAG seek track={} source={} requested={} alError={}",
        currentTrack.getId(), source, seconds, AL10.alGetError());
```

Immediately before `MusicDirector.tick()` calls `playNext`, log:

```java
LOGGER.info("CMM_DIAG autoplay state={} current={}",
        nativePlayback.getState(), nativePlayback.getCurrentTrackId().orElse("none"));
```

Do not log every tick. Use booleans/transition checks so each start, first attachment, seek, and autoplay decision emits once.

- [ ] **Step 3: Build and install the diagnostic jar**

Run:

```bash
./gradlew test build
cp build/libs/cue-my-music-1.0.0.jar "/Users/smpb/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/cue-my-music-1.0.0.jar"
shasum -a 256 build/libs/cue-my-music-1.0.0.jar "/Users/smpb/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/cue-my-music-1.0.0.jar"
```

Expected: both checksums match.

- [ ] **Step 4: Reproduce exactly once**

In Prism 26.2:

1. Wait until vanilla background music is audible.
2. Open Cue My Music and press `>` on Sweden.
3. Drag the scrub from the start to approximately 50%.
4. Immediately press `>` on another track.
5. Pause and resume that track once, then exit Minecraft.

Run:

```bash
rg "CMM_DIAG|OpenAL|Mixin.*Accessor" "/Users/smpb/Library/Application Support/PrismLauncher/instances/26.2/minecraft/logs/latest.log"
```

Expected evidence:
- `STARTING` is absent in baseline and `isActive=false` can occur after a start; if an autoplay follows, the double-start hypothesis is confirmed.
- Streamed seek reports a non-zero OpenAL error or audible position remains unchanged; either confirms replacement is required.
- Accessor mixins apply without an error; otherwise fix accessor mapping before Task 2.

- [ ] **Step 5: Remove diagnostics and preserve evidence**

Revert only the `CMM_DIAG` logger statements/flags. Write the exact observed transitions and OpenAL error code into the SDD task report. Do not commit diagnostic code.

- [ ] **Step 6: Reviewer gate**

A Muse reviewer must classify each hypothesis as confirmed, rejected, or unresolved from the log. Stop only if both leading hypotheses remain unresolved; otherwise proceed with the confirmed minimal architecture.

---

### Task 2: Make Playback Ownership Atomic

**Files:**
- Create: `src/client/java/com/cuemymusic/client/playback/PlaybackLifecycle.java`
- Create: `src/test/java/com/cuemymusic/client/playback/PlaybackLifecycleTest.java`
- Modify: `src/client/java/com/cuemymusic/client/playback/PlaybackState.java`
- Modify: `src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java`
- Modify: `src/client/java/com/cuemymusic/client/playback/MusicDirector.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java`
- Modify: `src/test/java/com/cuemymusic/PlaybackStateTest.java`

**Interfaces:**
- Consumes: `MusicTrack`, `SoundManager.play/stop`, accessor map from `SoundInstance` to `ChannelHandle`.
- Produces: `PlaybackLifecycle`, `NativeMinecraftPlayback.tick(Minecraft,long)`, `hasOwnership()`, and `MusicDirector.previewTrack(Minecraft,MusicTrack)`.

- [ ] **Step 1: Write lifecycle tests first**

Create the test in package `com.cuemymusic.client.playback`:

```java
@Test void startingOwnsPlaybackAndTimesOutAtTwoSeconds() {
    var life = new PlaybackLifecycle();
    life.start(track("a"), 1_000);
    assertEquals(PlaybackState.STARTING, life.state());
    assertTrue(life.occupied());
    assertFalse(life.startTimedOut(2_999));
    assertTrue(life.startTimedOut(3_000));
}

@Test void attachmentPauseAndStopHaveExplicitStates() {
    var life = new PlaybackLifecycle();
    life.start(track("a"), 0);
    life.attached();
    assertEquals(PlaybackState.PLAYING, life.state());
    life.pause();
    assertEquals(PlaybackState.PAUSED, life.state());
    assertTrue(life.occupied());
    life.stop();
    assertEquals(PlaybackState.STOPPED, life.state());
    assertFalse(life.occupied());
}

@Test void rapidReplacementLeavesOnlyNewestTrackOwned() {
    var life = new PlaybackLifecycle();
    life.start(track("a"), 0);
    life.start(track("b"), 10);
    assertEquals("b", life.track().orElseThrow().getId());
    assertEquals(PlaybackState.STARTING, life.state());
}

@Test void directorBlocksAutoStartForEveryOwnedState() {
    assertFalse(MusicDirector.blocksAutoStart(PlaybackState.STOPPED));
    assertTrue(MusicDirector.blocksAutoStart(PlaybackState.STARTING));
    assertTrue(MusicDirector.blocksAutoStart(PlaybackState.PLAYING));
    assertTrue(MusicDirector.blocksAutoStart(PlaybackState.PAUSED));
}
```

Use a local `track(id)` helper returning `new MusicTrack(id,id,"Artist",SourceType.VANILLA)`.

Update `PlaybackStateTest.statesExist()` to expect four values and `STARTING`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.playback.PlaybackLifecycleTest' --tests 'com.cuemymusic.PlaybackStateTest'
```

Expected: compilation fails because `PlaybackLifecycle` and `STARTING` do not exist.

- [ ] **Step 3: Implement the minimal pure lifecycle**

Implement exactly one ownership object:

```java
final class PlaybackLifecycle {
    static final long START_TIMEOUT_MS = 2_000;
    private PlaybackState state = PlaybackState.STOPPED;
    private MusicTrack track;
    private long startedAtMs;

    void start(MusicTrack next, long now) {
        track = Objects.requireNonNull(next);
        startedAtMs = now;
        state = PlaybackState.STARTING;
    }
    void attached() { if (state == PlaybackState.STARTING) state = PlaybackState.PLAYING; }
    void pause() { if (state == PlaybackState.PLAYING) state = PlaybackState.PAUSED; }
    void resume(long now) {
        if (state == PlaybackState.PAUSED) { startedAtMs = now; state = PlaybackState.STARTING; }
    }
    void stop() { track = null; startedAtMs = 0; state = PlaybackState.STOPPED; }
    boolean occupied() { return state != PlaybackState.STOPPED; }
    boolean startTimedOut(long now) {
        return state == PlaybackState.STARTING && now - startedAtMs >= START_TIMEOUT_MS;
    }
    PlaybackState state() { return state; }
    Optional<MusicTrack> track() { return Optional.ofNullable(track); }
}
```

Add `STARTING` between `STOPPED` and `PLAYING` in `PlaybackState`.

- [ ] **Step 4: Route native playback through lifecycle ownership**

In `NativeMinecraftPlayback`:

- Replace `currentTrack` and direct state mutations with one `PlaybackLifecycle lifecycle`.
- Keep `currentSound` as the one runtime handle paired with lifecycle ownership.
- In `play`, stop the prior owned sound before assigning/playing the new sound, call `lifecycle.start(track, now)`, then queue `SoundManager.play` exactly once.
- Add `tick(Minecraft mc, long now)`:

```java
public void tick(Minecraft mc, long now) {
    synchronized (lock) {
        if (lifecycle.state() == PlaybackState.STARTING) {
            var handle = channelHandle(mc, currentSound);
            if (handle != null && !handle.isStopped()) lifecycle.attached();
            else if (lifecycle.startTimedOut(now)) stopLocked(mc);
        } else if (lifecycle.state() == PlaybackState.PLAYING
                && currentSound != null
                && !mc.getSoundManager().isActive(currentSound)) {
            stopLocked(mc); // natural completion
        }
    }
}
```

`isPlaying()` must return `lifecycle.state() == PLAYING`; it must not use asynchronous `SoundManager.isActive()` as the ownership predicate. `isPaused`, `getState`, and `getCurrentTrack` delegate to the lifecycle.

- [ ] **Step 5: Make director replacement atomic and scheduling owner-aware**

Add:

```java
public synchronized boolean previewTrack(Minecraft mc, MusicTrack track) {
    if (track == null) return false;
    var current = getCurrentTrack().orElse(null);
    if (current != null && current.getId().equals(track.getId())) return togglePause(mc);
    if (mc.getMusicManager() != null) mc.getMusicManager().stopPlaying();
    mc.getSoundManager().stop(null, SoundSource.MUSIC);
    boolean started = nativePlayback.play(mc, track);
    if (started) { nextDelayMs = 0; trackEndMs = 0; skipRequested = false; }
    return started;
}
```

Add `static boolean blocksAutoStart(PlaybackState state) { return state != PlaybackState.STOPPED; }`. At the start of `MusicDirector.tick` call `nativePlayback.tick(mc, System.currentTimeMillis())`; then return immediately when `blocksAutoStart(nativePlayback.getState())` is true. Replace duration-derived scheduling with a delay that begins only after ownership clears:

```java
if (nativePlayback.hasOwnership()) { trackEndMs = 0; return; }
if (trackEndMs == 0) trackEndMs = System.currentTimeMillis();
if (System.currentTimeMillis() - trackEndMs >= nextDelayMs) playNext(mc);
```

Remove UI calls to `MusicManager.stopPlaying`, `SoundManager.stop(MUSIC)`, `stopCurrent`, and `playTrack`; `JukeboxLibraryScreen.previewTrack` calls only `director.previewTrack(mc, track)`.

- [ ] **Step 6: Run targeted and full tests**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.playback.PlaybackLifecycleTest' --tests 'com.cuemymusic.PlaybackStateTest'
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/client/java/com/cuemymusic/client/playback src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java src/test/java/com/cuemymusic/client/playback src/test/java/com/cuemymusic/PlaybackStateTest.java
git commit -m "fix: make music playback single-owner"
```

- [ ] **Step 8: Muse reviewer gate**

Review for exactly-one `SoundManager.play` per replacement, `STARTING` ownership during attachment, timeout cleanup, paused ownership, natural completion, and absence of UI stop/start sequencing. Critical/Important findings return to the same worker before Task 3.

---

### Task 3: Buffer One Track and Seek on the Channel Thread

**Files:**
- Create: `src/test/java/com/cuemymusic/client/playback/BufferedPlaybackTest.java`
- Modify: `src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java`
- Modify: `src/client/java/com/cuemymusic/client/playback/MusicDirector.java`
- Modify: `src/client/java/com/cuemymusic/client/music/VanillaTrackRegistry.java`

**Interfaces:**
- Consumes: lifecycle and channel lookup from Task 2; `SoundManager.getSoundEvent`, `WeighedSoundEvents.getSound`, `ChannelHandle.execute`.
- Produces: cached `positionSeconds()`, `durationSeconds()`, `canSeek()`, and `CompletableFuture<Boolean> seek(float)`.

- [ ] **Step 1: Write buffered sound and math tests first**

Create tests in package `com.cuemymusic.client.playback`:

```java
@Test void exactFileSoundIsStaticNotStreamed() {
    var sound = new NativeMinecraftPlayback.BufferedFileSoundInstance(
            Identifier.fromNamespaceAndPath("minecraft", "music/game/sweden"), SoundSource.MUSIC);
    assertFalse(sound.getSound().shouldStream());
}

@Test void durationUsesPcmShape() {
    assertEquals(2.0f, NativeMinecraftPlayback.durationSeconds(352_800, 2, 16, 44_100), 0.001f);
    assertEquals(-1.0f, NativeMinecraftPlayback.durationSeconds(0, 2, 16, 44_100));
}

@Test void seekClampUsesRealDuration() {
    assertEquals(0f, NativeMinecraftPlayback.clampSeek(-4f, 120f));
    assertEquals(60f, NativeMinecraftPlayback.clampSeek(60f, 120f));
    assertEquals(120f, NativeMinecraftPlayback.clampSeek(999f, 120f));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.playback.BufferedPlaybackTest'
```

Expected: compilation fails because the buffered sound and math methods do not exist.

- [ ] **Step 3: Resolve every catalog entry to one static file sound**

Rename the current inner exact-file instance to `BufferedFileSoundInstance` and set `stream=false`:

```java
this.fileSound = new Sound(fileId, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f),
        1, Sound.Type.FILE, false, false, 16);
```

Change sound creation to accept `Minecraft` and resolve discs before construction:

```java
private SoundInstance createSound(Minecraft mc, MusicTrack track) {
    Identifier id = Identifier.tryParse(track.getSourceId());
    if (id == null) return null;
    Identifier fileId = id;
    if (track.getSourceType() == SourceType.MUSIC_DISC) {
        WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(id);
        if (event == null) return null;
        Sound chosen = event.getSound(SoundInstance.createUnseededRandom());
        fileId = chosen.getLocation();
    }
    return new BufferedFileSoundInstance(fileId, SoundSource.MUSIC);
}
```

Use the same buffered class for background tracks and discs. Log one concise warning if resolution fails.

- [ ] **Step 4: Cache position, duration, and seek capability from the channel executor**

Add volatile cached values, cleared on every stop/replacement:

```java
private volatile float positionSeconds = -1;
private volatile float durationSeconds = -1;
private volatile boolean seekCapable;
private volatile boolean probePending;
```

After Task 2 transitions to `PLAYING`, enqueue at most one probe per client tick:

```java
private void probe(ChannelAccess.ChannelHandle handle) {
    if (probePending) return;
    probePending = true;
    handle.execute(channel -> {
        try {
            int source = ((ChannelAccessor) channel).cueMyMusic$getSource();
            int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
            int size = AL10.alGetBufferi(buffer, AL10.AL_SIZE);
            int channels = AL10.alGetBufferi(buffer, AL10.AL_CHANNELS);
            int bits = AL10.alGetBufferi(buffer, AL10.AL_BITS);
            int frequency = AL10.alGetBufferi(buffer, AL10.AL_FREQUENCY);
            int error = AL10.alGetError();
            if (error == AL10.AL_NO_ERROR) {
                durationSeconds = durationSeconds(size, channels, bits, frequency);
                positionSeconds = AL10.alGetSourcef(source, AL10.AL_SEC_OFFSET);
                seekCapable = durationSeconds > 0 && AL10.alGetError() == AL10.AL_NO_ERROR;
            } else seekCapable = false;
        } finally { probePending = false; }
    });
}
```

Implement:

```java
static float durationSeconds(int bytes, int channels, int bits, int frequency) {
    if (bytes <= 0 || channels <= 0 || bits <= 0 || frequency <= 0) return -1;
    return bytes / (channels * frequency * (bits / 8f));
}
static float clampSeek(float requested, float duration) {
    return Math.max(0, Math.min(requested, duration));
}
```

Clear stale OpenAL errors before the probe and check the error after each position/seek operation. Never cache the OpenAL source integer outside the callback.

- [ ] **Step 5: Make seeking asynchronous and truthful**

Expose:

```java
public CompletableFuture<Boolean> seek(Minecraft mc, float requested) {
    var handle = channelHandle(mc, currentSound);
    float duration = durationSeconds;
    if (handle == null || !seekCapable || duration <= 0)
        return CompletableFuture.completedFuture(false);
    float target = clampSeek(requested, duration);
    var result = new CompletableFuture<Boolean>();
    handle.execute(channel -> {
        int source = ((ChannelAccessor) channel).cueMyMusic$getSource();
        clearAlErrors();
        AL10.alSourcef(source, AL10.AL_SEC_OFFSET, target);
        boolean ok = AL10.alGetError() == AL10.AL_NO_ERROR;
        if (ok) positionSeconds = target;
        result.complete(ok);
    });
    return result.completeOnTimeout(false, 1, TimeUnit.SECONDS);
}
```

`MusicDirector.seek` returns the same future. The UI does not synthesize position after a request; subsequent render sync reads cached `positionSeconds`.

Pause stores cached position, stops the owned sound, and retains lifecycle ownership. Resume creates one new buffered sound in `STARTING`; after attachment it issues one pending seek to the paused position.

- [ ] **Step 6: Remove fake durations**

Remove all four `setDurationSeconds(180)` calls from `VanillaTrackRegistry`. `MusicDirector.getDurationSeconds()` delegates only to runtime `NativeMinecraftPlayback.durationSeconds()` and returns a float. Do not delete persisted `MusicTrack.durationSeconds` in this task; retaining the field preserves JSON compatibility, but runtime playback ignores it.

- [ ] **Step 7: Run targeted and full tests**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.playback.BufferedPlaybackTest' --tests 'com.cuemymusic.client.playback.PlaybackLifecycleTest'
./gradlew test
./gradlew compileClientJava
```

Expected: all commands succeed with no direct OpenAL calls outside a `ChannelHandle.execute` callback. Verify:

```bash
rg -n 'AL10\.' src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java
```

Every reported call must be inside `handle.execute`/`probe` callback or a callback-only helper.

- [ ] **Step 8: Commit**

```bash
git add src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java src/client/java/com/cuemymusic/client/playback/MusicDirector.java src/client/java/com/cuemymusic/client/music/VanillaTrackRegistry.java src/test/java/com/cuemymusic/client/playback/BufferedPlaybackTest.java
git commit -m "fix: buffer active music for reliable seeking"
```

- [ ] **Step 9: Muse reviewer gate**

Review static-vs-streamed loading, disc event resolution, channel-thread confinement, AL error handling, real duration math, pending resume seek, future completion, stale-cache clearing, and resource cleanup. Critical/Important findings return to the worker before Task 4.

---

### Task 4: Replace the Manual Table with a Native Compact List

**Files:**
- Create: `src/client/java/com/cuemymusic/client/ui/PlaybackSlider.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java`
- Delete: `src/test/java/com/cuemymusic/JukeboxLibraryScreenLogicTest.java`
- Create: `src/test/java/com/cuemymusic/client/ui/JukeboxLibraryScreenLogicTest.java`

**Interfaces:**
- Consumes: Task 3 `positionSeconds`, `durationSeconds`, `canSeek`, and async `seek`.
- Produces: `JukeboxLibraryScreen.Layout`, `TrackList`, `TrackRow`, and `PlaybackSlider`.

- [ ] **Step 1: Replace duplicate test math with production layout tests**

Move the test to package `com.cuemymusic.client.ui`, delete the test-local `ROW_H`, `clampScroll`, and `rowY` copies, and test package-visible `JukeboxLibraryScreen.computeLayout(width,height)` directly:

```java
@Test void normalLayoutIsCenteredAndKeepsScrubBesideSort() {
    var l = JukeboxLibraryScreen.computeLayout(854, 480);
    assertEquals(620, l.contentW());
    assertEquals(117, l.contentL());
    assertFalse(l.scrubWrapped());
    assertTrue(l.searchRight() <= l.sortX());
    assertTrue(l.sortRight() <= l.scrubX());
}

@Test void narrowLayoutWrapsScrubWithoutOverlap() {
    var l = JukeboxLibraryScreen.computeLayout(400, 300);
    assertEquals(360, l.contentW());
    assertTrue(l.scrubWrapped());
    assertTrue(l.searchRight() <= l.sortX());
    assertTrue(l.scrubY() > l.searchY());
    assertTrue(l.listTop() > l.scrubBottom());
}

@Test void rowsUseAccessibleTargets() {
    assertEquals(20, JukeboxLibraryScreen.ROW_H);
    assertEquals(20, JukeboxLibraryScreen.ROW_CONTROL_SIZE);
}
```

- [ ] **Step 2: Run layout tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.JukeboxLibraryScreenLogicTest'
```

Expected: compilation fails because production `Layout` and constants do not exist.

- [ ] **Step 3: Implement one deterministic layout record**

In `JukeboxLibraryScreen`, add package-visible constants and a static record/method. Use these exact rules:

```java
static final int ROW_H = 20, ROW_CONTROL_SIZE = 20;
static Layout computeLayout(int width, int height) {
    int contentW = Math.min(620, width - 40);
    int contentL = (width - contentW) / 2;
    int searchW = Math.min(180, contentW - 118);
    int searchX = contentL;
    int sortX = searchX + searchW + 8;
    int sortW = 110;
    int inlineScrubX = sortX + sortW + 8;
    boolean wrapped = contentL + contentW - inlineScrubX < 140;
    int scrubX = wrapped ? contentL : inlineScrubX;
    int scrubY = wrapped ? 54 : 30;
    int scrubW = wrapped ? contentW : contentL + contentW - inlineScrubX;
    int listTop = wrapped ? 96 : 72;
    int doneY = height - 24;
    return new Layout(/* exact fields asserted above plus list/footer bounds */);
}
```

Support Minecraft GUI widths of 320 pixels and above. Use `contentW = Math.min(620, width - 40)` and `searchW = Math.max(150, Math.min(180, contentW - 118))`; do not add alternate layouts beyond inline/wrapped.

- [ ] **Step 4: Implement the native playback slider**

Create one `AbstractSliderButton` subclass:

```java
final class PlaybackSlider extends AbstractSliderButton {
    private final MusicDirector director = MusicDirector.getInstance();
    private float duration;
    private boolean userDragging;

    PlaybackSlider(int x, int y, int width) {
        super(x, y, width, 20, Component.empty(), 0);
        visible = false;
        active = false;
    }

    void sync(float position, float duration, boolean seekable) {
        this.duration = duration;
        visible = active = seekable && duration > 0;
        if (!userDragging && visible) value = Math.clamp(position / duration, 0, 1);
        updateMessage();
    }

    @Override protected void updateMessage() {
        setMessage(Component.literal(format((float)(value * duration)) + " / " + format(duration)));
    }

    @Override protected void applyValue() {
        if (active) director.seek(Minecraft.getInstance(), (float)(value * duration));
    }
}
```

Override click/release to maintain `userDragging` while delegating to the superclass. Use the inherited vanilla slider textures, keyboard handling, and narration. Do not owner-draw a second scrub bar.

- [ ] **Step 5: Replace manual scrolling and row hit tests with `ContainerObjectSelectionList`**

Add one inner list and one inner row class:

```java
final class TrackList extends ContainerObjectSelectionList<TrackRow> {
    TrackList(Minecraft mc, Layout l) {
        super(mc, l.contentW(), l.listHeight(), l.listTop(), ROW_H);
        setX(l.contentL());
    }
    void setTracks(List<MusicTrack> tracks) {
        clearEntries();
        tracks.forEach(t -> addEntry(new TrackRow(t)));
    }
}

final class TrackRow extends ContainerObjectSelectionList.Entry<TrackRow> {
    private final MusicTrack track;
    private final Button queued;
    private final Button preview;

    TrackRow(MusicTrack track) {
        this.track = track;
        queued = Button.builder(Component.literal(track.isAmbientEligible() ? "☑" : "☐"), b -> {
                    track.setAmbientEligible(!track.isAmbientEligible());
                    b.setMessage(Component.literal(track.isAmbientEligible() ? "☑" : "☐"));
                    CueMyMusic.getInstance().saveAll();
                })
                .createNarration(ignored -> Component.literal(
                        (track.isAmbientEligible() ? "Remove from queue: " : "Add to queue: ") + track.getTitle()))
                .bounds(0, 0, ROW_CONTROL_SIZE, ROW_CONTROL_SIZE).build();
        preview = Button.builder(Component.literal(">"), b -> previewTrack(track))
                .createNarration(ignored -> Component.literal("Preview " + track.getTitle() + " by " + track.getArtist()))
                .bounds(0, 0, ROW_CONTROL_SIZE, ROW_CONTROL_SIZE).build();
    }

    @Override public List<? extends GuiEventListener> children() { return List.of(queued, preview); }
    @Override public List<? extends NarratableEntry> narratables() { return List.of(queued, preview); }
}
```

In `extractContent`, position the child widgets from the entry’s current `getContentX()/getContentY()`, render source/title/artist between them, ellipsize to column widths, and update preview message to `||` only for the current `PLAYING` track. Use white/gray text and native list hover/focus; do not restore alternating neon custom rows.

- [ ] **Step 6: Rebuild the screen around native widgets**

`init()` creates only search, sort, `PlaybackSlider`, `TrackList`, and Done. `rebuild()` retains dedup/filter/sort, then calls `trackList.setTracks(displayed)` when the list exists.

`extractRenderState()` renders title, subtitle, simple column labels, selected count aligned to the checkbox x-coordinate, and total count at the content right edge. It calls:

```java
var state = director.getState();
boolean seekable = state == PlaybackState.PLAYING && director.canSeek();
slider.sync(director.getPositionSeconds(), director.getDurationSeconds(), seekable);
```

Remove custom scissor, scrollbar, row fills, checkbox drawing, preview drawing, row mouse handlers, scrub mouse handlers, fake `effDur`, and local scroll fields. `onClose` saves and returns to the parent but does not stop playback; `removed()` does not stop playback.

- [ ] **Step 7: Run targeted and full tests**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.JukeboxLibraryScreenLogicTest'
./gradlew compileClientJava
./gradlew test
```

Expected: all pass. Also verify removed manual behavior:

```bash
! rg 'scrubDragging|effDur|mouseDragged|rowY|enableScissor|SoundSource.MUSIC' src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java
```

- [ ] **Step 8: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/PlaybackSlider.java src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java src/test/java/com/cuemymusic/JukeboxLibraryScreenLogicTest.java src/test/java/com/cuemymusic/client/ui/JukeboxLibraryScreenLogicTest.java
git commit -m "refactor: use native compact music library UI"
```

- [ ] **Step 9: Muse reviewer gate**

Review normal/narrow layout, stable table position, native scroll behavior, 20-pixel targets, keyboard/narration support, Artist default, sort cycle, fuzzy rebuild, queue persistence, `>`/`||`, no double-click action, scrub visibility contract, and footer alignment. Critical/Important findings return to the worker before Task 5.

---

### Task 5: Documentation, Build, Deployment, and Final Verification

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `.pi/WORKING_STATE.md` (not committed)
- Build: `build/libs/cue-my-music-1.0.0.jar`
- Install: `/Users/smpb/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/cue-my-music-1.0.0.jar`

**Interfaces:**
- Consumes: reviewed Tasks 2–4.
- Produces: accurate architecture documentation, passing clean build, installed matching jar, manual validation evidence.

- [ ] **Step 1: Update architecture documentation**

Replace stale claims with these facts:

- single owner and `STOPPED/STARTING/PLAYING/PAUSED` lifecycle;
- two-second attachment timeout;
- active OGG only is buffered/static;
- discs resolve event to underlying file;
- duration and position come from OpenAL on `ChannelHandle.execute`;
- no 180-second scrub fallback;
- centered `ContainerObjectSelectionList` and native slider/widgets;
- closing the screen leaves playback active.

Remove the stale statement that `durationSeconds` is always null and remove any description of custom/manual row scrolling.

- [ ] **Step 2: Run fresh verification**

Run:

```bash
./gradlew clean test build
```

Expected: `BUILD SUCCESSFUL`; all JUnit XML suites have zero failures/errors.

Run:

```bash
rg -n 'setDurationSeconds\(180\)|effDur|streamed exact|per-row scrub|No global bar' src docs/ARCHITECTURE.md
```

Expected: no matches.

- [ ] **Step 3: Install and verify byte identity**

Run:

```bash
cp build/libs/cue-my-music-1.0.0.jar "/Users/smpb/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/cue-my-music-1.0.0.jar"
shasum -a 256 build/libs/cue-my-music-1.0.0.jar "/Users/smpb/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/cue-my-music-1.0.0.jar"
```

Expected: checksums match.

- [ ] **Step 4: Perform mandatory in-game validation**

In Prism 26.2:

1. Start a preview while vanilla music is audible; confirm one recording remains.
2. Seek to approximately 25%, 50%, and 90%; confirm audible position and displayed time move together.
3. Rapidly switch track A to B; confirm A stops.
4. Pause/resume B; confirm no second layer and scrub hides while paused.
5. Close/reopen the library; confirm B continues and the UI reflects it.
6. Inspect normal and narrow GUI scales for overlap, clipping, focus, narration, and native visual consistency.

Record PASS/FAIL for each item and relevant `latest.log` warnings.

- [ ] **Step 5: Commit docs**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: document buffered native playback"
```

- [ ] **Step 6: Final Muse review**

Review the complete branch diff against the approved spec, task reports, deferred findings, test XML, jar checksums, and manual checklist. One final Muse fix worker addresses all confirmed Critical/Important findings, followed by one scoped re-review.

- [ ] **Step 7: Update durable state**

Update `.pi/WORKING_STATE.md` with final commits, test/build evidence, installed checksum, manual results, remaining limitations, and next actions. Do not stage `.pi/WORKING_STATE.md`.
