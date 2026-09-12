# Responsive Track-Weighting Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the track-weighting screen around a centered 80% workspace, scrollable clean-name pool tabs with contextual tooltips, responsive track browser, below-wheel editor, icon toolbar, and functional compact preview player.

**Architecture:** Keep `TrackWeightScreen` as the composition root, but move preview transport state into a focused `TrackPreviewController`. Continue using native Minecraft widgets and `MenuTabBar.MenuTabButton`; retain CPU rasterization for the wheel. Preview audio uses a separately owned transport in `MusicDirector` without entering planner selection/history or the normal queue.

**Tech Stack:** Java 25, Minecraft 26.2 client GUI/audio APIs, Fabric Loader/API, Mod Menu 20.0.1, JUnit 5, Gradle/Fabric Loom.

**Spec:** `docs/superpowers/specs/2026-09-12-track-weighting-responsive-layout-design.md`

## Global Constraints

- All settings UI must stay within a centered 80% × 80% workspace.
- Pool tabs use clean human-readable category names in one horizontally scrollable native-style row; no arrow buttons, wrapping, paging, or cycle button. Tooltips explain where known categories play and safely identify unknown custom pools.
- The narrow track browser fills the workspace content area and hides wheel/editor/player.
- Multiplier controls live below the radial wheel, never in a right-hand column.
- Bottom actions are icon-only native buttons with tooltips and narration; preview is icon-only with narration.
- Preview must not mutate configuration, planner sequence/history, queue, Anti-Repeat state, or normal transport ownership.
- The radial wheel remains CPU-rasterized and backend-neutral; no custom rendering pipeline or new dependency.
- `Done` saves; `Esc` discards.
- Every behavior change follows RED/GREEN TDD and receives task-scoped review.
- After each UI task, Gemini launches the client and the user supplies the visual-review screenshot.
- Do not release or publish.

---

### Task 1: Centered Workspace and Scrollable Pool Tabs

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Test: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`

**Interfaces:**
- Consumes: existing `Bounds`, `ScrollablePoolTabBar`, `TabManager`, and dynamic `Model.pools()`.
- Produces: `static Bounds workspaceBounds(int width, int height)` and a tab viewport constrained to that workspace.

- [ ] **Step 1: Write failing workspace and no-arrow tests**

Add these tests (using the existing headless screen factory):

```java
@Test
void workspaceIsCenteredInnerEightyPercent() {
    assertEquals(new Bounds(100, 60, 800, 480), TrackWeightScreen.workspaceBounds(1000, 600));
    assertEquals(new Bounds(30, 21, 240, 167), TrackWeightScreen.workspaceBounds(300, 209));
}

@Test
void poolTabsFillWorkspaceAndHaveNoArrowButtons() {
    TrackWeightScreen screen = screenWithPools(30);
    screen.width = 1000;
    screen.height = 600;
    screen.init();
    Bounds workspace = TrackWeightScreen.workspaceBounds(1000, 600);
    assertEquals(workspace.x(), screen.tabNavigationBar().getX());
    assertEquals(workspace.width(), screen.tabNavigationBar().getWidth());
    assertTrue(screen.tabNavigationBar().maxScroll() > 0);
    assertTrue(screen.children().stream().noneMatch(child -> child instanceof Button button
            && (button.getMessage().getString().equals("<") || button.getMessage().getString().equals(">"))));
}
```

Also assert tabs use clean category titles, known-category tooltips explain where music plays, custom tooltips identify the full pool ID without invented semantics, and wheel scrolling plus selected-tab reveal clamps inside `maxScroll()`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' --console=plain
```

Expected: compile/test failure because `workspaceBounds` and no-arrow viewport behavior do not exist.

- [ ] **Step 3: Implement centered workspace and remove arrows**

Use integer floor geometry:

```java
static Bounds workspaceBounds(int width, int height) {
    int workspaceWidth = width * 4 / 5;
    int workspaceHeight = height * 4 / 5;
    return new Bounds((width - workspaceWidth) / 2, (height - workspaceHeight) / 2,
            workspaceWidth, workspaceHeight);
}
```

Position `ScrollablePoolTabBar` at `(workspace.x(), workspace.y())`; its viewport width is `workspace.width()`. Delete its left/right `Button` fields and arrow zones. Use clean category-label widths (`font.width(poolDisplayName(pool.id())) + 16`), scissor clipping, wheel/trackpad scrolling, offset clamping, keyboard delegation, and selected-tab auto-reveal. `poolDisplayName` special-cases known vanilla contexts and title-cases path segments; `poolTooltip` explains known playback contexts and returns `Custom music pool: <full-id>` for unknown namespaces/paths. Render header background/separators only within the workspace tab/header bounds.

- [ ] **Step 4: Run targeted and full tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' --console=plain
./gradlew test --rerun --console=plain
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java
git commit -m 'refactor: center track weighting workspace'
```

---

### Task 2: Responsive Collapsible Track Browser and Main Layout

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Test: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`

**Interfaces:**
- Consumes: Task 1 `workspaceBounds`.
- Produces: `ResponsiveLayout`, `BrowserState`, fit-based browser visibility, and below-wheel editor bounds.

- [ ] **Step 1: Write failing layout/state tests**

Define expected production interfaces in tests:

```java
@Test
void wideLayoutPlacesBrowserBesideMainAndEditorBelowWheel() {
    ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(1200, 800, true);
    assertTrue(layout.browserFits());
    assertTrue(layout.browser().right() < layout.main().x());
    assertTrue(layout.wheel().bottom() <= layout.editor().y());
    assertTrue(layout.workspace().contains(layout.bottomToolbar()));
}

@Test
void narrowOpenBrowserFillsContentAndHidesMain() {
    ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(640, 360, true);
    assertFalse(layout.browserFits());
    assertEquals(layout.content(), layout.browser());
    assertNull(layout.wheel());
    assertNull(layout.editor());
}
```

Add `BrowserState` tests: first wide init opens; wide→narrow auto-closes; manual narrow open persists through same-size rebuild; narrow→wide restores side-by-side; toggles do not alter `Model.draft()` or selection.

- [ ] **Step 2: Run tests and verify RED**

Run the targeted test class. Expected failure: missing `ResponsiveLayout`/`BrowserState`.

- [ ] **Step 3: Implement minimal fit-based layout**

Add records/state with fixed design minima:

```java
static final int TAB_HEIGHT = 24;
static final int TOOLBAR_HEIGHT = 20;
static final int GAP = 8;
static final int BROWSER_MIN_WIDTH = 220;
static final int MAIN_MIN_WIDTH = 340;

record ResponsiveLayout(Bounds workspace, Bounds tabs, Bounds content,
        Bounds browser, Bounds main, Bounds wheel, Bounds editor,
        Bounds preview, Bounds bottomToolbar, boolean browserFits,
        boolean browserOpen) {}
```

`browserFits` is true only when `content.width() >= BROWSER_MIN_WIDTH + GAP + MAIN_MIN_WIDTH`. Wide browser width is clamped to `220..300` and approximately 30% of content. In narrow open mode, browser equals content and main/wheel/editor/preview are null. In collapsed mode, main equals content.

Move search/list into browser bounds. Center the wheel in main bounds. Place heading, slider, multiplier shortcuts, and preview area below the wheel. Delete the prior right-column geometry.

Add native icon-only `☰`/`×` toggle with tooltip and narration. When narrow mode first applies, close the browser automatically; a user may reopen it for the overlay view.

- [ ] **Step 4: Run targeted and full tests**

Use the Task 1 commands. Expected: all pass with no geometry overlap at 300×209, 427×254, 640×360, 800×600, and 1920×1080.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java
git commit -m 'feat: add responsive track browser layout'
```

---

### Task 3: Icon-Only Action Toolbar

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Test: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`

**Interfaces:**
- Consumes: Task 2 `ResponsiveLayout.bottomToolbar()` and existing action callbacks.
- Produces: icon-only native controls with stable tooltip/narration copy.

- [ ] **Step 1: Write failing behavior tests**

Assert exact button messages and tooltips:

```java
assertIcon(screen.allButton(), "↺", "Reset pool to native weights");
assertIcon(screen.c418Button(), "♫", "Double C418 tracks");
assertIcon(screen.muteButton(), "∅", "Mute selected pool");
assertIcon(screen.testRollButton(), "⚄", "Test weighted selection");
assertIcon(screen.jsonButton(), "{}", "View and copy JSON");
assertIcon(screen.doneButton(), "✓", "Done");
```

Assert Anti-Repeat uses `⟳`, its tooltip/narration includes On/Off, and pressing every icon preserves its existing behavior. Assert all toolbar button bounds are inside `bottomToolbar()` and do not overlap.

- [ ] **Step 2: Run targeted tests and verify RED**

Expected: existing text labels differ.

- [ ] **Step 3: Implement native icon buttons**

Replace labels only; preserve callbacks. Apply `Tooltip.create(Component.literal(...))` and `createNarration(...)` to every icon except idle preview. Use a selected/on state for Anti-Repeat and update its tooltip/narration after toggling. Keep `▶`/`⏸`/`■` reserved for preview.

- [ ] **Step 4: Verify glyphs and tests**

Run targeted/full tests. Then have Gemini launch the client; the user confirms every glyph renders in Minecraft's bundled font. If a glyph is missing, replace only that glyph with a bundled single-character equivalent and update its test.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java
git commit -m 'feat: compact settings actions into icon toolbar'
```

---

### Task 4: Preview Audio Transport and Root-Cause Fix

**Files:**
- Create: `src/client/java/com/cuemymusic/client/music/TrackPreviewController.java`
- Modify: `src/client/java/com/cuemymusic/client/music/MusicDirector.java`
- Modify: `src/client/java/com/cuemymusic/mixin/SoundBufferLibraryMixin.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Test: `src/test/java/com/cuemymusic/client/music/TrackPreviewControllerTest.java`
- Test: `src/test/java/com/cuemymusic/client/music/MusicDirectorQueueDelayTest.java`
- Test: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`
- Test: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Consumes: catalog `Pool`/`Track`/`Occurrence`, `PinnedMusicInstance`, `TransportClock`, `OggDuration`, director channel pause support.
- Produces: independent preview start/pending/playing/paused/completed state, duration, seek, stop, and immutable UI snapshot.

- [ ] **Step 1: Reproduce premature preview teardown**

Write the regression first:

```java
@Test
void inactiveBeforeFirstActiveDoesNotCompleteAsynchronousPreview() {
    FakeBackend backend = new FakeBackend();
    TrackPreviewController controller = new TrackPreviewController(backend);
    controller.start(pool, track);
    backend.active = false;
    controller.tick(1L);
    assertEquals(State.STARTING, controller.snapshot(1L).state());
    backend.active = true;
    controller.tick(2L);
    assertEquals(State.PLAYING, controller.snapshot(2L).state());
    backend.active = false;
    controller.tick(3L);
    assertEquals(State.IDLE, controller.snapshot(3L).state());
}
```

`FakeBackend` is a test-local implementation of `TrackPreviewController.Backend` that records play/stop/pause/resume calls and exposes its `active` flag. Also test bounded startup failure after 40 ticks, proving a sound that never becomes active cleans up and resumes owned background exactly once.

- [ ] **Step 2: Run and verify RED**

Expected: current `PreviewState.tick(false)` immediately stops and resumes before asynchronous sound activation.

- [ ] **Step 3: Implement focused controller state**

Create:

```java
public final class TrackPreviewController implements AutoCloseable {
    public enum State { IDLE, STARTING, PLAYING, PAUSED }
    public record Snapshot(State state, String resourceId, String title,
            double positionSeconds, double durationSeconds, boolean canSeek) {}

    public interface Backend {
        PinnedMusicInstance play(Pool pool, Track track, long generation, double offsetSeconds);
        void stop(PinnedMusicInstance instance);
        boolean isActive(PinnedMusicInstance instance);
        void setPaused(PinnedMusicInstance instance, boolean paused);
        CompletableFuture<OptionalDouble> duration(Track track, long generation);
        boolean pauseBackground();
        void resumeBackground();
    }

    public TrackPreviewController(Backend backend);
    public void start(Pool pool, Track track);
    public void tick(long nowNanos);
    public void togglePause(long nowNanos);
    public void seek(double seconds, long nowNanos);
    public void stop();
    public Snapshot snapshot(long nowNanos);
    @Override public void close();
}
```

It owns `seenActive`, a 40-tick STARTING deadline, preview generation, `TransportClock`, duration, and the existing background-pause ownership flag. `tick()` asks `backend.isActive`; inactive completes only after `seenActive` or startup timeout. Tests use a tiny fake `Backend`; production supplies a `MusicDirector` backend without test-only production hooks.

- [ ] **Step 4: Add independent preview offset ownership**

Extend `MusicDirector.OffsetRequest` with an owner:

```java
public enum StreamOwner { TRANSPORT, PREVIEW }
public record OffsetRequest(StreamOwner owner, long generation, double seconds) {}
```

Keep separate current generations for normal transport and preview. Replace `isCurrentGeneration(request.generation())` with `isCurrentOffsetRequest(request)`, dispatching validation by owner. `SoundBufferLibraryMixin` calls the new validation method before `applyOffset`.

Add director methods used only by `TrackPreviewController`:

```java
PinnedMusicInstance playPreview(Identifier eventId, Sound sound, long generation, double offsetSeconds);
void stopPreview(PinnedMusicInstance instance);
boolean previewActive(PinnedMusicInstance instance);
boolean setPreviewPaused(PinnedMusicInstance instance, boolean paused);
CompletableFuture<OptionalDouble> probeDuration(Sound sound, long generation, StreamOwner owner);
```

Register PREVIEW offset requests before `SoundManager.play`. Clear only the matching preview request on replacement/stop. Normal transport generation, planner counters, queue, and offset request remain unchanged.

- [ ] **Step 5: Test preview transport isolation**

Cover:

- actual selected `Occurrence.sound()` reaches `PinnedMusicInstance.resolve()`;
- only one preview and replacement cleanup;
- STARTING→PLAYING→natural completion;
- pause/resume clock;
- seek stops/reopens preview at the requested offset;
- unknown duration disables seek;
- Done/Esc/removal/pool-track change/resource reload cleanup;
- background pause acquired once and resumed once;
- normal planner sequence/history, queue projection, delay, and transport generation remain unchanged.

Run targeted music/UI tests, then full suite.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/cuemymusic/client/music/TrackPreviewController.java \
  src/client/java/com/cuemymusic/client/music/MusicDirector.java \
  src/client/java/com/cuemymusic/mixin/SoundBufferLibraryMixin.java \
  src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/music/TrackPreviewControllerTest.java \
  src/test/java/com/cuemymusic/client/music/MusicDirectorQueueDelayTest.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m 'fix: add owned track preview transport'
```

---

### Task 5: Compact Preview Player UI

**Files:**
- Create: `src/client/java/com/cuemymusic/client/ui/TrackPreviewPlayer.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Test: `src/test/java/com/cuemymusic/client/ui/TrackPreviewPlayerTest.java`
- Test: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`
- Test: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Consumes: Task 4 `TrackPreviewController.Snapshot` and actions.
- Produces: idle `▶` control and active compact title/progress/scrub/`▶|⏸`/`■` player below the wheel editor.

- [ ] **Step 1: Write failing player-model tests**

Test pure formatting and state mapping:

```java
assertEquals("▶", TrackPreviewPlayer.playPauseIcon(State.PAUSED));
assertEquals("⏸", TrackPreviewPlayer.playPauseIcon(State.PLAYING));
assertEquals("1:05 / 3:20", TrackPreviewPlayer.timeText(65, 200));
assertEquals("0:00 / --:--", TrackPreviewPlayer.timeText(0, Double.NaN));
```

Build a headless screen and assert IDLE has only `▶`; STARTING/PLAYING/PAUSED replace it with the compact player; narrow browser-overlay mode hides both.

- [ ] **Step 2: Run tests and verify RED**

Expected: missing `TrackPreviewPlayer` and current text preview button.

- [ ] **Step 3: Implement compact player**

`TrackPreviewPlayer` is a focused widget bundle rather than a second screen:

```java
final class TrackPreviewPlayer {
    TrackPreviewPlayer(Font font, Supplier<Snapshot> snapshot,
            Runnable togglePause, DoubleConsumer seek, Runnable stop);
    List<AbstractWidget> widgets();
    void setBounds(TrackWeightScreen.Bounds bounds);
    void tick();
}
```

It owns native widgets only: title `StringWidget`, progress slider, play/pause `Button`, and stop `Button`. `TrackWeightScreen` adds `widgets()` through its normal widget path. `tick()` synchronizes without invoking slider callbacks recursively. Scrub is disabled when `snapshot.canSeek()` is false.

In `TrackWeightScreen`, idle preview is `▶` with narration `Preview selected track` and no visual tooltip. Starting preview swaps the idle control for `TrackPreviewPlayer` in `ResponsiveLayout.preview()`. The preview bounds consume the available lower main-panel region beneath the radial wheel and multiplier editor, matching the user-marked runtime layout rather than remaining a one-row button slot. Stopping/natural completion swaps it back. Browser-overlay mode does not render or focus hidden player widgets.

- [ ] **Step 4: Run targeted/full tests and runtime review**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackPreviewPlayerTest' \
  --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' --console=plain
./gradlew test --rerun --console=plain
```

Have Gemini launch Minecraft. The user verifies audible preview, player appearance, pause/resume, scrub, stop, and no overlap via screenshot.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/TrackPreviewPlayer.java \
  src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/TrackPreviewPlayerTest.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m 'feat: show compact settings preview player'
```

---

### Task 6: Documentation, Verification, and Delivery

**Files:**
- Modify: `README.md`
- Create locally: `.pi/track-weighting-proof/README.md`
- Create locally: `.pi/track-weighting-proof/*.png`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: final docs, build artifact, user-approved screenshots, review verdict, and pushed branch.

- [ ] **Step 1: Update README**

Document centered responsive workspace, scrollable clean-name tabs with contextual tooltips, collapsible browser, icon toolbar, below-wheel editor, and compact preview player. Remove descriptions contradicted by the new layout.

- [ ] **Step 2: Run clean verification**

```bash
./gradlew clean test build --console=plain
git diff --check
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
roots = [ET.parse(p).getroot() for p in glob.glob('build/test-results/test/TEST-*.xml')]
print({k: sum(int(r.attrib.get(k, 0)) for r in roots)
       for k in ('tests', 'failures', 'errors', 'skipped')})
PY
```

Require zero failures/errors.

- [ ] **Step 3: Inspect jar**

Verify Mod Menu metadata remains, new classes exist, and dependency classes are not bundled:

```bash
unzip -p build/libs/cue-my-music-*.jar fabric.mod.json | grep -A4 modmenu
unzip -l build/libs/cue-my-music-*.jar | grep -E 'TrackPreview|TrackWeight|WeightedMusicCatalog|RadialWeightWidget'
! unzip -l build/libs/cue-my-music-*.jar | grep -q 'com/google/gson\|com/terraformersmc'
```

- [ ] **Step 4: User visual/runtime review**

Gemini launches `./gradlew runClient --console=plain` and leaves it open. The user supplies client-window screenshots showing wide browser, collapsed browser, narrow browser overlay, scrolled clean-name tabs and contextual hover tooltip, below-wheel editor, icon tooltips, active preview player in the lower region, and muted pool. Record only verified behavior in `.pi/track-weighting-proof/README.md`.

- [ ] **Step 5: Independent nonvisual review**

Request a fresh Gemini reviewer across selection math, persistence, responsive state, preview ownership/seek lifecycle, accessibility, resource reload, renderer neutrality, and regressions. Correct Critical/Important findings test-first and rerun clean verification.

- [ ] **Step 6: Commit and integrate without release**

Commit README, rebase onto `origin/main`, run clean verification once more, and push verified commits to `main`. Do not tag, release, or publish to Modrinth.
