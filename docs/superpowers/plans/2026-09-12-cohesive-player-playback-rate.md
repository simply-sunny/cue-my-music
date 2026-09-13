# Cohesive Player and Playback Rate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one responsive player composition for compact Pause/Options and expanded Mod Menu use, with explicit playback states, a read-only queue drawer, and an honest session-only varispeed Playback Rate.

**Architecture:** Keep the weighting branch as the base and evolve the existing shared `PauseMusicWidget` rather than merging the experimental branch or adding a UI framework. `MusicDirector` remains the single owner of transport facts and session rate, `TransportClock` tracks source-media time, and narrow mixin hooks apply rate only to generation-tagged transport channels.

**Tech Stack:** Java 25, Fabric Loader/API, Minecraft 26.2 client GUI and sound engine, Sponge Mixin, OpenAL-backed vanilla `Channel`, JUnit 5, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-12-cohesive-player-playback-rate-design.md`

## Global Constraints

- Work on `feature/track-weighting-settings`; do not merge or cherry-pick `feature/playback-effects`.
- Playback Rate is `0.50×–2.00×`, defaults to `1.00×`, steps by `0.05×`, changes speed and pitch together, persists only for the running game process, and never affects previews.
- Do not add `WsolaAudioStream`, PCM resampling, independent pitch/tempo, source switching, advanced/debug controls, crossfade, queue editing, or a new dependency.
- Preserve `TrackPreviewController`, `StreamOwner`, weighted selection, Anti-Repeat, deterministic projection, transactional settings, and renderer-neutral radial rendering.
- Only generation-tagged `StreamOwner.TRANSPORT` channels receive rate hooks; PREVIEW and unrelated audio remain untouched.
- `MusicPlayerScreen` keeps `Esc → Mod Menu`, `× → vanilla Options`, and Configure Track Pools navigation.
- Follow TDD: observe each focused test fail before changing production code.
- Run local Gradle checks only; do not add or use GitHub Actions.
- Do not push, merge, tag, release, or publish without explicit approval.

## File Structure

- `src/client/java/com/cuemymusic/client/music/TransportClock.java` — source-media clock and finite Playback Rate policy.
- `src/client/java/com/cuemymusic/client/music/EngineTransport.java` — narrow per-instance native pause and rate bridge.
- `src/client/java/com/cuemymusic/client/music/MusicDirector.java` — transport state, track metadata, session rate, and owned-channel commands.
- `src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java` — the single compact/expanded player composition, pure layout helpers, controls, states, and queue drawer.
- `src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java` — dedicated-screen navigation and Configure action only.
- `src/client/java/com/cuemymusic/mixin/ChannelAudibleMixin.java` — applies the current rate when an owned transport channel becomes audible.
- `src/client/java/com/cuemymusic/mixin/SoundEngineTransportMixin.java` — applies rate to an already-created owned transport channel.
- `src/test/java/com/cuemymusic/client/music/TransportClockTest.java` — rate math and continuity.
- `src/test/java/com/cuemymusic/client/music/MusicDirectorSessionTest.java` — pure transport-state and session semantics.
- `src/test/java/com/cuemymusic/client/ui/PauseTransportPanelTest.java` — compact/expanded geometry, queue placement, and slider mapping.
- `src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java` — labels, state presentation, mode visibility, and navigation regressions.
- `src/test/java/com/cuemymusic/ModContractTest.java` — ownership boundaries and forbidden playback-effects surface.
- `README.md` — user-facing player and honest varispeed description.

---

### Task 1: Rate-aware source-media clock

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/music/TransportClock.java`
- Test: `src/test/java/com/cuemymusic/client/music/TransportClockTest.java`

**Interfaces:**
- Consumes: existing `noteStarted`, `notePaused`, `noteResumed`, `retarget`, `positionSeconds`, and `reset` methods.
- Produces: `RATE_MIN`, `RATE_MAX`, `RATE_DEFAULT`; `double playbackRate()`; `void setPlaybackRate(double rate, long nowNanos)`; `static double clampRate(double rate)`.

- [ ] **Step 1: Add failing rate and clamp tests**

Append focused tests proving source-time scaling and finite policy:

```java
@Test void playbackRateScalesSourcePosition() {
    TransportClock slow = new TransportClock();
    slow.setPlaybackRate(0.5, 0L);
    slow.noteStarted(10.0, 0L);
    assertEquals(12.0, slow.positionSeconds(4_000_000_000L, Double.NaN), 1e-9);

    TransportClock fast = new TransportClock();
    fast.setPlaybackRate(2.0, 0L);
    fast.noteStarted(10.0, 0L);
    assertEquals(18.0, fast.positionSeconds(4_000_000_000L, Double.NaN), 1e-9);
}

@Test void playbackRateClampsAndRejectsNonFiniteValues() {
    assertEquals(0.5, TransportClock.clampRate(0.1), 1e-9);
    assertEquals(2.0, TransportClock.clampRate(4.0), 1e-9);
    assertEquals(1.0, TransportClock.clampRate(Double.NaN), 1e-9);
    assertEquals(1.0, TransportClock.clampRate(Double.POSITIVE_INFINITY), 1e-9);
}
```

- [ ] **Step 2: Run the clock tests and confirm RED**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.music.TransportClockTest
```

Expected: compilation fails because the Playback Rate API does not exist.

- [ ] **Step 3: Add continuity and paused-change tests**

Add tests that would expose position jumps or reset loss:

```java
@Test void changingRateWhileRunningKeepsPositionContinuous() {
    TransportClock clock = new TransportClock();
    clock.noteStarted(10.0, 0L);
    clock.setPlaybackRate(2.0, 4_000_000_000L);
    assertEquals(14.0, clock.positionSeconds(4_000_000_000L, Double.NaN), 1e-9);
    assertEquals(18.0, clock.positionSeconds(6_000_000_000L, Double.NaN), 1e-9);
}

@Test void changingRateWhilePausedKeepsFrozenPosition() {
    TransportClock clock = new TransportClock();
    clock.noteStarted(10.0, 0L);
    clock.notePaused(4_000_000_000L);
    clock.setPlaybackRate(2.0, 50_000_000_000L);
    assertEquals(14.0, clock.positionSeconds(99_000_000_000L, Double.NaN), 1e-9);
    clock.noteResumed(100_000_000_000L);
    assertEquals(18.0, clock.positionSeconds(102_000_000_000L, Double.NaN), 1e-9);
}

@Test void transportResetRetainsSessionRate() {
    TransportClock clock = new TransportClock();
    clock.setPlaybackRate(1.5, 0L);
    clock.reset();
    assertEquals(1.5, clock.playbackRate(), 1e-9);
}
```

- [ ] **Step 4: Implement the minimal clock policy**

Add constants and state:

```java
public static final double RATE_MIN = 0.5;
public static final double RATE_MAX = 2.0;
public static final double RATE_DEFAULT = 1.0;
private double playbackRate = RATE_DEFAULT;
```

Implement `setPlaybackRate` by snapshotting `rawPosition(nowNanos, Double.NaN)` under the old rate before replacing the anchor and rate. When paused, change only the rate. Advance running source position with:

```java
offsetSeconds + Math.max(0.0, elapsed) * playbackRate
```

Clamp finite values to the constants and map non-finite values to `RATE_DEFAULT`. Do not reset `playbackRate` in `reset()`.

- [ ] **Step 5: Run clock tests and confirm GREEN**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.music.TransportClockTest
```

Expected: all `TransportClockTest` tests pass.

- [ ] **Step 6: Commit the clock change**

```bash
git add src/client/java/com/cuemymusic/client/music/TransportClock.java \
  src/test/java/com/cuemymusic/client/music/TransportClockTest.java
git commit -m "feat: track playback rate in transport clock"
```

---

### Task 2: Owned native-channel rate and explicit director status

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/music/EngineTransport.java`
- Modify: `src/client/java/com/cuemymusic/client/music/MusicDirector.java`
- Modify: `src/client/java/com/cuemymusic/mixin/ChannelAudibleMixin.java`
- Modify: `src/client/java/com/cuemymusic/mixin/SoundEngineTransportMixin.java`
- Test: `src/test/java/com/cuemymusic/client/music/MusicDirectorSessionTest.java`
- Test: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Consumes: Task 1’s `TransportClock.setPlaybackRate`, `playbackRate`, and clamp constants; existing generation-tagged `MusicDirector.TaggedStream` transport ownership.
- Produces: `EngineTransport.cueMyMusic$setInstancePitch(SoundInstance, float)`; `MusicDirector.PlaybackStatus`; `static PlaybackStatus statusFor(boolean, boolean, boolean, boolean, int)`; `PlaybackStatus playbackStatus()`; `Optional<TrackInfo> currentTrack()`; `double playbackRate()`; `void setPlaybackRate(double)`.

- [ ] **Step 1: Add failing pure status tests**

In `MusicDirectorSessionTest`, assert every explicit state without requiring a running client:

```java
@Test void transportStatusDistinguishesLifecycleStates() {
    assertEquals(MusicDirector.PlaybackStatus.NO_TRACK,
            MusicDirector.statusFor(false, false, false, false, 0));
    assertEquals(MusicDirector.PlaybackStatus.COOLDOWN,
            MusicDirector.statusFor(false, false, false, false, 20));
    assertEquals(MusicDirector.PlaybackStatus.LOADING,
            MusicDirector.statusFor(true, false, false, false, 0));
    assertEquals(MusicDirector.PlaybackStatus.PLAYING,
            MusicDirector.statusFor(true, true, true, false, 0));
    assertEquals(MusicDirector.PlaybackStatus.PAUSED,
            MusicDirector.statusFor(true, true, true, true, 0));
    assertEquals(MusicDirector.PlaybackStatus.COOLDOWN,
            MusicDirector.statusFor(true, true, false, false, 20));
}
```

- [ ] **Step 2: Add failing ownership contract tests**

In `ModContractTest`, read the relevant source files and enforce the narrow implementation:

```java
@Test void playbackRateUsesOwnedChannelWithoutDspOrPreviewWrapping() throws Exception {
    Path music = SRC.resolve("client/java/com/cuemymusic/client/music");
    assertFalse(Files.exists(music.resolve("WsolaAudioStream.java")));

    String engine = Files.readString(music.resolve("EngineTransport.java"));
    assertTrue(engine.contains("cueMyMusic$setInstancePitch"));

    String audible = Files.readString(SRC.resolve(
            "client/java/com/cuemymusic/mixin/ChannelAudibleMixin.java"));
    assertTrue(audible.contains("TaggedStream"));
    assertTrue(audible.contains("playbackRate"));

    String buffers = Files.readString(SRC.resolve(
            "client/java/com/cuemymusic/mixin/SoundBufferLibraryMixin.java"));
    assertFalse(buffers.contains("WsolaAudioStream"));
}
```

Also assert forbidden source/advanced/crossfade identifiers are absent from production Java sources rather than allowing the experimental UI surface.

- [ ] **Step 3: Run focused tests and confirm RED**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.music.MusicDirectorSessionTest \
  --tests com.cuemymusic.ModContractTest
```

Expected: compilation/assertion failures because status, rate bridge, and contract are absent.

- [ ] **Step 4: Implement director state and track metadata**

Add:

```java
public enum PlaybackStatus { NO_TRACK, LOADING, PLAYING, PAUSED, COOLDOWN }

static PlaybackStatus statusFor(
        boolean tracked, boolean audibleStarted, boolean active, boolean paused, int delayTicks) {
    if (tracked && !audibleStarted) return PlaybackStatus.LOADING;
    if (tracked && active) return paused ? PlaybackStatus.PAUSED : PlaybackStatus.PLAYING;
    return delayTicks > 0 ? PlaybackStatus.COOLDOWN : PlaybackStatus.NO_TRACK;
}
```

Track `audibleStarted` explicitly: set it false for a fresh start and seek reopen, true only in the current generation’s `noteAudibleStart`, and false in `resetTransport`. `playbackStatus()` supplies `transportInstance != null`, this audible-start fact, actual `SoundManager.isActive`, `transportPaused`, and `remainingDelayTicks()`. This separates asynchronous loading from a track that previously played and has now ended. `currentTrack()` derives metadata from `transportInstance.pinnedSound()` so Loading can show the selected track. Preserve `nowPlaying()` for callers that specifically require active-only semantics.

- [ ] **Step 5: Implement session rate and native channel bridge**

Add to `EngineTransport`:

```java
void cueMyMusic$setInstancePitch(SoundInstance instance, float pitch);
```

Implement it in `SoundEngineTransportMixin` through the existing `instanceToChannel` map and `ChannelHandle.execute`, with a no-op for missing/stopped handles.

In `MusicDirector`, delegate `playbackRate()` to the clock. `setPlaybackRate(double rate)` must clamp/snapshot through the clock, then apply the resulting float only to the current `transportInstance` through `EngineTransport`; it must never reference `previewInstance`.

In `ChannelAudibleMixin`, add a tagged-stream `play()` HEAD hook that applies `director.playbackRate()` before playback begins. Keep the existing TAIL hook for `noteAudibleStart` and sticky pause. Leave untagged channels untouched and preserve generation validation.

- [ ] **Step 6: Run focused and preview-regression tests**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.music.TransportClockTest \
  --tests com.cuemymusic.client.music.MusicDirectorSessionTest \
  --tests com.cuemymusic.client.music.TrackPreviewControllerTest \
  --tests com.cuemymusic.ModContractTest
```

Expected: all selected tests pass; the contract confirms no DSP wrapper and preview ownership tests remain green.

- [ ] **Step 7: Commit owned rate behavior**

```bash
git add src/client/java/com/cuemymusic/client/music/EngineTransport.java \
  src/client/java/com/cuemymusic/client/music/MusicDirector.java \
  src/client/java/com/cuemymusic/mixin/ChannelAudibleMixin.java \
  src/client/java/com/cuemymusic/mixin/SoundEngineTransportMixin.java \
  src/test/java/com/cuemymusic/client/music/MusicDirectorSessionTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m "feat: apply session playback rate to owned music"
```

---

### Task 3: Cohesive compact and expanded geometry

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java`
- Test: `src/test/java/com/cuemymusic/client/ui/PauseTransportPanelTest.java`
- Test: `src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java`

**Interfaces:**
- Consumes: existing attach lifecycle and Task 2’s director status/rate API.
- Produces: `PauseMusicWidget.Mode { COMPACT, EXPANDED }`; mode-based `panelLayout(...)`; one `Panel` implementation for both hosts; expanded effect bounds; queue placement metadata.

- [ ] **Step 1: Replace centered-boolean expectations with failing mode tests**

Add tests that name the layouts explicitly:

```java
@Test void compactModeStaysTopRightWithoutEffects() {
    PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(
            692, 423, 60, 40, 9, false, true, PauseMusicWidget.Mode.COMPACT);
    assertEquals(PauseMusicWidget.MARGIN, layout.boxY());
    assertFalse(layout.effectsVisible());
}

@Test void expandedModeCentersHierarchyAndShowsEffects() {
    PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(
            692, 423, 60, 40, 9, false, false, PauseMusicWidget.Mode.EXPANDED);
    assertTrue(layout.boxX() > PauseMusicWidget.MARGIN);
    assertTrue(layout.progressY() > layout.titleY());
    assertTrue(layout.buttonsY() > layout.progressY());
    assertTrue(layout.rateY() > layout.buttonsY());
    assertTrue(layout.effectsVisible());
}
```

Update existing centered-player tests to pass `Mode.EXPANDED`; compact tests pass `Mode.COMPACT`.

- [ ] **Step 2: Add failing responsive drawer tests**

Cover wide side placement and narrow below placement:

```java
@Test void expandedDrawerUsesSideOnlyWhenBothRegionsFit() {
    var wide = PauseMusicWidget.panelLayout(
            854, 508, 60, 40, 9, true, false, PauseMusicWidget.Mode.EXPANDED);
    assertEquals(PauseMusicWidget.QueuePlacement.SIDE, wide.queuePlacement());
    assertTrue(wide.queueCardX() > wide.boxX() + wide.boxWidth());

    var narrow = PauseMusicWidget.panelLayout(
            300, 209, 60, 40, 9, true, false, PauseMusicWidget.Mode.EXPANDED);
    assertEquals(PauseMusicWidget.QueuePlacement.BELOW, narrow.queuePlacement());
    assertTrue(narrow.queueCardY() >= narrow.boxY() + narrow.playerCardHeight());
    assertTrue(narrow.queueCardX() >= PauseMusicWidget.MARGIN);
    assertTrue(narrow.queueCardX() + narrow.queueCardWidth() <= 300 - PauseMusicWidget.MARGIN);
}
```

- [ ] **Step 3: Run UI geometry tests and confirm RED**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.ui.PauseTransportPanelTest \
  --tests com.cuemymusic.client.ui.PauseWidgetStateTest
```

Expected: compilation fails because mode, effect bounds, and drawer placement do not exist.

- [ ] **Step 4: Implement the minimum shared layout model**

Replace the ambiguous centered boolean with:

```java
enum Mode { COMPACT, EXPANDED }
enum QueuePlacement { CLOSED, SIDE, BELOW }
```

Extend `PanelLayout` only with coordinates needed by real widgets: `progressY`, `rateX`, `rateY`, `rateWidth`, `effectsVisible`, and `queuePlacement`. Keep compact dimensions and forced-minimize calculations unchanged.

Expanded layout must reserve vertical rows in order: track metadata, full-width progress, transport, Playback Rate, then Configure. Choose SIDE only when the main card, gap, queue minimum, and two margins fit; otherwise choose BELOW and clamp total bounds to the screen. Delete superseded boolean overloads after migrating all callers and tests.

- [ ] **Step 5: Make the existing panel select mode from its host**

Use one expression:

```java
Mode mode = screen instanceof MusicPlayerScreen ? Mode.EXPANDED : Mode.COMPACT;
```

Keep one `Panel`, one widget list, one refresh path, and one background extractor. Do not add a second player class or abstraction layer. Expanded-only widgets are created once but set invisible/inactive in compact mode.

- [ ] **Step 6: Run UI tests and confirm GREEN**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.ui.PauseTransportPanelTest \
  --tests com.cuemymusic.client.ui.PauseWidgetStateTest
```

Expected: all selected tests pass, including legacy margins, minimum widths, focus clearing, and navigation.

- [ ] **Step 7: Commit the shared geometry**

```bash
git add src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java \
  src/test/java/com/cuemymusic/client/ui/PauseTransportPanelTest.java \
  src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java
git commit -m "refactor: unify compact and expanded player layout"
```

---

### Task 4: Playback Rate control and explicit player presentation

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java`
- Test: `src/test/java/com/cuemymusic/client/ui/PauseTransportPanelTest.java`
- Test: `src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java`

**Interfaces:**
- Consumes: Task 2’s `MusicDirector.PlaybackStatus`, `currentTrack`, `playbackRate`, and `setPlaybackRate`; Task 3’s expanded effect bounds and drawer placement.
- Produces: `PlaybackRateSlider.rateFor(double)`, `valueFor(double)`, `snapRate(double)`, and mode/state-derived labels and activation.

- [ ] **Step 1: Add failing slider mapping tests**

```java
@Test void playbackRateSliderMapsAndSnapsTheApprovedRange() {
    assertEquals(0.50, PauseMusicWidget.PlaybackRateSlider.rateFor(0.0), 1e-9);
    assertEquals(2.00, PauseMusicWidget.PlaybackRateSlider.rateFor(1.0), 1e-9);
    assertEquals(1.00, PauseMusicWidget.PlaybackRateSlider.rateFor(
            PauseMusicWidget.PlaybackRateSlider.valueFor(1.00)), 1e-9);
    assertEquals(1.05, PauseMusicWidget.PlaybackRateSlider.snapRate(1.03), 1e-9);
}
```

Add source/text assertions that the visible label says `Playback Rate`, includes both `speed` and `pitch` in its tooltip/narration, and never says tempo or WSOLA.

- [ ] **Step 2: Add failing presentation tests for every state**

Keep state-to-copy logic pure with a small record, for example:

```java
record Presentation(String title, String playPauseText, String endText,
                    boolean scrubEnabled, boolean playPauseEnabled) {}
```

Test:

```java
@Test void playerPresentationIsExplicitForNoTrackLoadingAndCooldown() {
    var none = PauseMusicWidget.presentation(
            MusicDirector.PlaybackStatus.NO_TRACK, Optional.empty(), false, 0);
    assertEquals("No music playing", none.title());
    assertFalse(none.scrubEnabled());
    assertFalse(none.playPauseEnabled());

    var loading = PauseMusicWidget.presentation(
            MusicDirector.PlaybackStatus.LOADING,
            Optional.of(new MusicDirector.TrackInfo("Sweden", "C418")), false, 0);
    assertEquals("Sweden", loading.title());
    assertFalse(loading.playPauseEnabled());

    var cooldown = PauseMusicWidget.presentation(
            MusicDirector.PlaybackStatus.COOLDOWN, Optional.empty(), false, 80);
    assertEquals("0:04", cooldown.endText());
}
```

Add PLAYING/PAUSED checks for `||`/`>` and unknown-duration checks proving the displayed total is `--:--` and scrub is disabled.

- [ ] **Step 3: Run focused UI tests and confirm RED**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.ui.PauseTransportPanelTest \
  --tests com.cuemymusic.client.ui.PauseWidgetStateTest
```

Expected: compilation/assertion failures for the slider and presentation API.

- [ ] **Step 4: Implement the expanded-only rate slider**

Implement `PlaybackRateSlider` as an `AbstractSliderButton` using Task 1 constants and `RATE_STEP = 0.05`. Use locale-stable formatting:

```java
setMessage(Component.literal(String.format(
        Locale.ROOT, "Playback Rate %.2f×", rateFor(value))));
```

`applyValue()` calls only `MusicDirector.getInstance().setPlaybackRate(rateFor(value))`. Each refresh syncs from the director without recursively applying. Its tooltip and narration explicitly state: “Changes playback speed and pitch.” Keep it hidden and inactive in compact mode.

- [ ] **Step 5: Drive widgets from the pure presentation**

Use `MusicDirector.playbackStatus()` and `currentTrack()` once per refresh. Set title/artist, progress availability, play/pause icon, End/cooldown action, tooltip, narration, and active flags from that snapshot.

For unknown duration, continue showing elapsed time with `--:--` and disable scrubbing. For Loading, show known metadata but do not claim audio is playing. Preserve previous/next validity from the director.

- [ ] **Step 6: Make the queue drawer intentional and explicit**

When open, call `upcomingTracks(5)` once per refresh. If empty, show exactly one visible line, `No upcoming tracks`, and hide the other four. Otherwise show tracks in returned order. Change queue tooltip/narration between “Open upcoming tracks” and “Close upcoming tracks.” Never invoke planner mutation from rendering or toggling.

Place the existing Configure button in the expanded hierarchy through `MusicPlayerScreen`/shared layout bounds without duplicating transport controls. Keep `Esc` and `×` destinations unchanged.

- [ ] **Step 7: Run all UI and selector regression tests**

Run:

```bash
./gradlew test --tests com.cuemymusic.client.ui.PauseTransportPanelTest \
  --tests com.cuemymusic.client.ui.PauseWidgetStateTest \
  --tests com.cuemymusic.client.music.MusicDirectorQueueDelayTest \
  --tests com.cuemymusic.client.music.WeightedMusicCatalogTest \
  --tests com.cuemymusic.client.music.TrackPreviewControllerTest \
  --tests com.cuemymusic.client.ui.TrackPreviewPlayerTest
```

Expected: all selected tests pass; queue, weighting, and preview behavior remain unchanged.

- [ ] **Step 8: Commit the cohesive player behavior**

```bash
git add src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java \
  src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java \
  src/test/java/com/cuemymusic/client/ui/PauseTransportPanelTest.java \
  src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java
git commit -m "feat: add cohesive expanded player controls"
```

---

### Task 5: Documentation and complete local verification

**Files:**
- Modify: `README.md`
- Modify if generated proof needs recording: `.pi/track-weighting-proof/README.md`
- Verify: all production and test sources

**Interfaces:**
- Consumes: completed compact/expanded player and rate behavior.
- Produces: honest user documentation and auditable local verification evidence.

- [ ] **Step 1: Update README without overstating the effect**

Describe the dedicated player, queue drawer, and:

```markdown
Playback Rate adjusts music from 0.50× to 2.00× for the current game session.
Like a turntable, it changes playback speed and pitch together. Track previews
and other Minecraft sounds are unaffected.
```

Do not use “tempo,” “pitch-preserving,” “time stretch,” or “WSOLA.” Keep the existing local verification command.

- [ ] **Step 2: Run static guard scans**

Run:

```bash
rg -n "WsolaAudioStream|Source Mode|RESOURCE_PACK|CUSTOM_FOLDER|Crossfade|Pitch [+-]|Show advanced|Hide advanced" \
  src/client src/main README.md
rg -n "setPlaybackRate|setInstancePitch|Playback Rate" src/client README.md
```

Expected: the first command has no matches; the second shows only the approved clock/director/channel/UI/docs path.

- [ ] **Step 3: Run a clean complete test and build**

Run:

```bash
./gradlew clean test build
```

Expected: exit 0 with no failed or skipped tests and a jar under `build/libs/`.

- [ ] **Step 4: Inspect JUnit XML rather than trusting console summary**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
files = sorted(Path('build/test-results/test').glob('TEST-*.xml'))
assert files, 'no JUnit XML files found'
tests = failures = errors = skipped = 0
for path in files:
    root = ET.parse(path).getroot()
    tests += int(root.attrib.get('tests', 0))
    failures += int(root.attrib.get('failures', 0))
    errors += int(root.attrib.get('errors', 0))
    skipped += int(root.attrib.get('skipped', 0))
print({'tests': tests, 'failures': failures, 'errors': errors, 'skipped': skipped})
assert failures == errors == skipped == 0
PY
```

Expected: nonzero test count with `failures`, `errors`, and `skipped` all zero.

- [ ] **Step 5: Inspect the built jar boundary**

Run:

```bash
jar tf build/libs/cue-my-music-0.1.1.jar > /tmp/cue-my-music-jar.txt
rg "fabric.mod.json|MusicPlayerScreen|PauseMusicWidget|TrackWeightScreen|TrackPreviewController" \
  /tmp/cue-my-music-jar.txt
! rg "WsolaAudioStream|com/google/gson|com/terraformersmc" /tmp/cue-my-music-jar.txt
```

Expected: approved classes and metadata are present; forbidden/embedded dependency classes are absent.

- [ ] **Step 6: Commit documentation**

```bash
git add README.md
git commit -m "docs: describe honest playback rate"
```

- [ ] **Step 7: Launch Minecraft and collect user visual review**

Run:

```bash
./gradlew runClient
```

Leave the client open. Ask the user for screenshots covering compact, minimized, expanded, wide queue drawer, narrow below drawer, no-track/loading, playing, paused, cooldown, empty queue, and unknown-duration presentations. Record accepted screenshots and dimensions under `.pi/track-weighting-proof/`; do not substitute automated aesthetic judgment for user approval.

- [ ] **Step 8: Re-run verification after any screenshot-driven correction**

For each confirmed correction, add or adjust one focused failing test, make the minimal fix, and rerun that test. Then rerun Steps 2–5 before claiming completion.

- [ ] **Step 9: Stop at the integration gate**

Report commit list, test totals, jar inspection, and screenshot status. Do not merge into `main`, push, tag, release, or publish until the user explicitly approves the requested operation.
