# Track Weighting Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a native, persistent Mod Menu settings page that dynamically discovers music pools, applies per-track probability multipliers, provides a renderer-agnostic radial wheel, and exposes the approved pool and preview controls.

**Architecture:** `TrackWeightConfig` owns validated immutable settings and atomic Gson persistence. `WeightedMusicCatalog` flattens Minecraft's loaded weighted sound graph into composed leaf occurrences and performs all deterministic weighted selection. `MusicDirector` owns the active config/catalog and routes playback plus queue projection through that selector; `TrackWeightScreen` and `RadialWeightWidget` provide transactional native UI over a draft copy.

**Tech Stack:** Java 25, Minecraft 26.2 mapped client API, Fabric Loader/API, Mod Menu 20.0.1, Gson 2.14.0 already on the runtime classpath, JUnit 5, Gradle/Loom.

**Spec:** `docs/superpowers/specs/2026-09-12-track-weighting-settings-design.md`

## Global Constraints

- Mod Menu remains a required dependency and its configure action must open `MusicPlayerScreen` first.
- Save at `FabricLoader.getInstance().getConfigDir().resolve("cue-my-music.json")`.
- Values are native-probability multipliers in `[0, 10]`; missing values default to `1×`.
- Anti-Repeat defaults enabled and excludes only the immediately previous resource when another enabled candidate exists.
- A fully `0×` pool intentionally produces no music and no queue entries.
- Pool and track discovery is dynamic; do not add a hardcoded soundtrack catalog.
- No named presets, new dependencies, web views, alternate playback engines, or renderer-specific code.
- The radial wheel uses CPU `NativeImage` rasterization plus vanilla `GuiGraphicsExtractor.blit`; no OpenGL, Vulkan, Metal, Fabric rendering API, shaders, or custom pipelines.
- Settings are transactional: Done saves/applies; Esc discards; save failure retains the screen and draft.
- Existing Pause/Options player layout and player Esc/`×` navigation must not regress.
- Tests precede production changes, and every task ends with its targeted test command and a commit.
- Do not create a release or publish to Modrinth.

---

## File Map

**Create production files**

- `src/client/java/com/cuemymusic/client/music/TrackWeightConfig.java` — immutable settings, validation, Gson serialization, config path, and atomic persistence.
- `src/client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java` — loaded-pool discovery, recursive leaf probability/composition, metadata grouping, and deterministic selection.
- `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java` — transactional native settings screen, list/editor/actions/JSON view, and preview lifecycle.
- `src/client/java/com/cuemymusic/client/ui/RadialWeightWidget.java` — pure wheel model/hit testing, CPU rasterization, dynamic texture lifecycle, and vanilla blit.

**Modify production files**

- `src/client/java/com/cuemymusic/client/music/MusicGraph.java` — expose a tested weighted-leaf traversal using the existing entry/delegate hooks.
- `src/client/java/com/cuemymusic/client/music/MusicDirector.java` — own config/catalog, select configured tracks, project the same configured queue, and expose preview-safe pause/resume.
- `src/client/java/com/cuemymusic/client/CueMyMusicClient.java` — load config and rebuild catalog during the existing resource reload lifecycle.
- `src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java` — add the bottom-center configure button and open `TrackWeightScreen`.
- `src/test/java/com/cuemymusic/ModContractTest.java` — replace obsolete “no config/catalog” and 20-file reduction assumptions with the explicitly approved four-file feature boundary.
- `README.md` — document navigation, multiplier semantics, config path, Anti-Repeat, mute behavior, and backend-independent wheel.

**Create test files**

- `src/test/java/com/cuemymusic/client/music/TrackWeightConfigTest.java`
- `src/test/java/com/cuemymusic/client/music/WeightedMusicCatalogTest.java`
- `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`
- `src/test/java/com/cuemymusic/client/ui/RadialWeightWidgetTest.java`

---

### Task 1: Immutable Config and Atomic Persistence

**Files:**
- Create: `src/client/java/com/cuemymusic/client/music/TrackWeightConfig.java`
- Create: `src/test/java/com/cuemymusic/client/music/TrackWeightConfigTest.java`
- Modify: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Produces: `record TrackWeightConfig(boolean antiRepeat, Map<String, Map<String, Double>> weights)`.
- Produces: `static TrackWeightConfig defaults()`, `static TrackWeightConfig load(Path)`, `void save(Path) throws IOException`, `String toJson()`.
- Produces: `double multiplier(String poolId, String resourceId)`, `TrackWeightConfig withMultiplier(...)`, `withPoolMultipliers(...)`, and `withAntiRepeat(boolean)`.
- Produces: `static Path defaultPath()` using Fabric Loader's supported config directory.

- [ ] **Step 1: Write failing config tests**

Create tests using `@TempDir Path temp` that assert defaults, immutable updates, deep-copy isolation, clamp/default rules, unknown-entry round-trip, malformed-file defaults, and no leftover temporary file:

```java
@Test void defaultsAndImmutableUpdates() {
    TrackWeightConfig base = TrackWeightConfig.defaults();
    TrackWeightConfig changed = base.withMultiplier("minecraft:music.game", "minecraft:music/game/sweden", 2.0);
    assertTrue(base.antiRepeat());
    assertEquals(1.0, base.multiplier("minecraft:music.game", "minecraft:music/game/sweden"));
    assertEquals(2.0, changed.multiplier("minecraft:music.game", "minecraft:music/game/sweden"));
}

@Test void validationClampsFiniteValuesAndDefaultsMalformedValues(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("cue-my-music.json");
    Files.writeString(file, "{\"version\":1,\"antiRepeat\":false,\"weights\":{\"p\":{\"high\":99,\"low\":-2,\"bad\":\"x\"}}}");
    TrackWeightConfig config = TrackWeightConfig.load(file);
    assertFalse(config.antiRepeat());
    assertEquals(10.0, config.multiplier("p", "high"));
    assertEquals(0.0, config.multiplier("p", "low"));
    assertEquals(1.0, config.multiplier("p", "bad"));
}

@Test void saveRoundTripsUnknownEntriesAtomically(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("cue-my-music.json");
    TrackWeightConfig expected = TrackWeightConfig.defaults()
            .withMultiplier("pack:music.custom", "pack:music/custom/song", 3.5);
    expected.save(file);
    assertEquals(expected, TrackWeightConfig.load(file));
    assertFalse(Files.exists(temp.resolve("cue-my-music.json.tmp")));
}
```

Also assert malformed top-level JSON returns defaults without rewriting the malformed file and `toJson()` emits `version: 1` plus retained unknown entries.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.music.TrackWeightConfigTest' --console=plain
```

Expected: test compilation fails because `TrackWeightConfig` does not exist.

- [ ] **Step 3: Implement the immutable config**

Use a canonical record constructor to validate/deep-copy nested maps and keep the public API identifier-string based:

```java
public record TrackWeightConfig(boolean antiRepeat, Map<String, Map<String, Double>> weights) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int VERSION = 1;

    public TrackWeightConfig {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        weights.forEach((pool, tracks) -> {
            Map<String, Double> valid = new LinkedHashMap<>();
            tracks.forEach((track, value) -> valid.put(track, normalize(value)));
            copy.put(pool, Map.copyOf(valid));
        });
        weights = Map.copyOf(copy);
    }

    private static double normalize(Double value) {
        return value == null || !Double.isFinite(value) ? 1.0 : Math.clamp(value, 0.0, 10.0);
    }

    public double multiplier(String poolId, String resourceId) {
        return weights.getOrDefault(poolId, Map.of()).getOrDefault(resourceId, 1.0);
    }
}
```

Implement `withMultiplier` and `withPoolMultipliers` by copying only the affected nested map. `load` parses a `JsonObject`, reads `antiRepeat` only when it is boolean, then walks each pool object and numeric track entry independently. A malformed track entry is omitted (therefore defaults to `1×`) without dropping valid sibling pools/tracks. Catch malformed top-level JSON or I/O once, log it, and return defaults. Reject unsupported `version` values as top-level malformed input. `save` creates parent directories, writes `<name>.tmp`, attempts `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, and retries with `REPLACE_EXISTING` only on `AtomicMoveNotSupportedException`; delete the temporary file in `finally`.

`toJson()` builds an object containing exactly `version`, `antiRepeat`, and `weights`. `defaultPath()` returns `FabricLoader.getInstance().getConfigDir().resolve("cue-my-music.json")`.

- [ ] **Step 4: Update the approved reduction contract**

Change `ModContractTest` so `noObsoleteProductionSourcesRemain` no longer rejects filenames containing `config` or `catalog`, but retains every other obsolete-system rejection. Change the production ceiling from 20 to 24 and assert this task's approved config class exists:

```java
assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/music/TrackWeightConfig.java")));
assertTrue(productionFiles <= 24, "expected approved production surface, found " + productionFiles);
```

Task 2 adds the catalog existence assertion, Task 5 adds the settings-screen assertion, and Task 7 adds the radial-widget assertion in the same contract.

- [ ] **Step 5: Run config and contract tests**

Run:

```bash
./gradlew test --tests 'com.cuemymusic.client.music.TrackWeightConfigTest' --tests 'com.cuemymusic.ModContractTest' --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/cuemymusic/client/music/TrackWeightConfig.java \
  src/test/java/com/cuemymusic/client/music/TrackWeightConfigTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m "feat: persist track weighting settings"
```

---

### Task 2: Dynamic Weighted Graph Catalog

**Files:**
- Create: `src/client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java`
- Create: `src/test/java/com/cuemymusic/client/music/WeightedMusicCatalogTest.java`
- Modify: `src/client/java/com/cuemymusic/client/music/MusicGraph.java`
- Modify: `src/test/java/com/cuemymusic/client/music/MusicGraphComposeTest.java`
- Modify: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Produces: `MusicGraph.WeightedLeaf(Sound sound, double nativeProbability)` and `MusicGraph.weightedLeaves(...)`.
- Produces: `WeightedMusicCatalog.Occurrence(String resourceId, Sound sound, double nativeProbability)`.
- Produces: `WeightedMusicCatalog.Track(String resourceId, String title, String composer, double nativeProbability, List<Occurrence> occurrences)`.
- Produces: `WeightedMusicCatalog.Pool(String id, List<Track> tracks, List<Occurrence> occurrences)`.
- Produces: `static WeightedMusicCatalog empty()`, `static WeightedMusicCatalog discover(SoundManager)`, `List<Pool> pools()`, and `Optional<Pool> pool(String)`.

- [ ] **Step 1: Write failing recursive-probability tests**

Extend the existing graph test doubles and assert direct `3:1` weights become `.75/.25`, nested conditional weights multiply, duplicate resource paths remain separate leaves, composed volume/pitch survive, silence is absent, and cycles terminate:

```java
@Test void weightedLeavesMultiplyConditionalNestedProbability() {
    Sound a = file("music/game/a", 1.0F, 1.0F, 3, false, false);
    Sound b = file("music/game/b", 1.0F, 1.0F, 1, false, false);
    WeighedSoundEvents root = event("minecraft:music.game", List.of(a, b));
    List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(
            root, id -> null, entry -> null, entry -> null);
    assertEquals(0.75, leaves.get(0).nativeProbability(), 1e-9);
    assertEquals(0.25, leaves.get(1).nativeProbability(), 1e-9);
}
```

For nesting, use a root containing a direct leaf and `NestedStub`; assert every returned probability equals the product of its edge's weight divided by its containing event total.

- [ ] **Step 2: Run graph tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.MusicGraphComposeTest' --console=plain
```

Expected: compilation fails because `WeightedLeaf` and `weightedLeaves` do not exist.

- [ ] **Step 3: Implement weighted leaf traversal**

Add one public traversal beside existing `eligibleFiles`/`resolveFile`. At each event, sum positive `Weighted.getWeight()` values, skip a zero total, and recurse with `parentProbability * entryWeight / totalWeight`. Use a path-local identity set—add before recursion and remove after—so duplicate branches remain while cycles terminate. Compose nested definitions with `composeDefinition` on unwind and exclude `isSilence` leaves.

Core recursion:

```java
double probability = parentProbability * entry.getWeight() / totalWeight;
if (entry instanceof Sound sound) {
    if (!isSilence(sound)) leaves.add(new WeightedLeaf(sound, probability));
} else {
    Identifier nested = nestedEventOf.apply(entry);
    collectWeighted(eventLookup.apply(nested), probability, eventLookup,
            nestedEventOf, nestedDefinitionOf, path, childLeaves);
    Sound definition = nestedDefinitionOf.apply(entry);
    childLeaves.forEach(leaf -> leaves.add(new WeightedLeaf(
            definition == null ? leaf.sound() : composeDefinition(definition, leaf.sound()),
            leaf.nativeProbability())));
}
```

Avoid a global visited set because the same nested event can legitimately be reached through multiple weighted branches.

- [ ] **Step 4: Write failing dynamic catalog tests**

Create `WeightedMusicCatalogTest` around a package-private `fromEvents(Collection<Identifier>, Function<Identifier, WeighedSoundEvents>, ...)` factory. Assert only paths equal to `music` or beginning `music.` become pools, identifiers are sorted stably, duplicate resource paths group into one `Track`, grouped native probability sums occurrences, and translation/resource fallback metadata is deterministic.

```java
@Test void groupsDuplicateOccurrencesWithoutDiscardingCandidates() {
    WeightedMusicCatalog catalog = catalogWithDuplicateSwedenPaths();
    WeightedMusicCatalog.Pool pool = catalog.pool("minecraft:music.game").orElseThrow();
    WeightedMusicCatalog.Track sweden = pool.tracks().stream()
            .filter(track -> track.resourceId().endsWith("sweden.ogg")).findFirst().orElseThrow();
    assertEquals(2, sweden.occurrences().size());
    assertEquals(sweden.occurrences().stream().mapToDouble(Occurrence::nativeProbability).sum(),
            sweden.nativeProbability(), 1e-9);
}
```

- [ ] **Step 5: Run catalog tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.WeightedMusicCatalogTest' --console=plain
```

Expected: compilation fails because `WeightedMusicCatalog` does not exist.

- [ ] **Step 6: Implement discovery and grouping**

`discover(SoundManager sounds)` calls `sounds.getAvailableSounds()`, filters music event IDs, and delegates to the package-private factory with `sounds::getSoundEvent`, `MusicGraph::nestedEventId`, and `MusicGraph::nestedDefinition`.

Create occurrence resource IDs from `sound.getPath().toString()`. Group by resource ID in insertion-preserving maps; sum native probabilities and retain every occurrence. Build display credit from the leaf location's `toShortLanguageKey().replace('/', '.')`, `Language.getInstance()`, and `MusicDirector.splitCredit`; otherwise use the final path segment and a null composer. Sort pools by ID and tracks by case-insensitive title then resource ID. Add the exact `WeightedMusicCatalog.java` existence assertion to `ModContractTest` in this task.

- [ ] **Step 7: Run graph, catalog, and legacy selection tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.MusicGraph*' \
  --tests 'com.cuemymusic.client.music.WeightedMusicCatalogTest' \
  --tests 'com.cuemymusic.client.music.VanillaSelectionTest' --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/client/java/com/cuemymusic/client/music/MusicGraph.java \
  src/client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java \
  src/test/java/com/cuemymusic/client/music/MusicGraphComposeTest.java \
  src/test/java/com/cuemymusic/client/music/WeightedMusicCatalogTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m "feat: discover weighted music pools"
```

---

### Task 3: Shared Deterministic Configured Selector

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java`
- Modify: `src/test/java/com/cuemymusic/client/music/WeightedMusicCatalogTest.java`

**Interfaces:**
- Produces: `Optional<Occurrence> select(Pool pool, TrackWeightConfig config, String previousResourceId, long seed)`.
- Produces: `List<Occurrence> project(Pool pool, TrackWeightConfig config, String previousResourceId, long sessionSeed, long startIndex, int count)`.
- Produces: `Map<String, Double> chances(Pool pool, TrackWeightConfig config, String previousResourceId)` for UI and radial wedges.

- [ ] **Step 1: Write failing selector tests**

Add tests for deterministic repeatability, native `3:1` preservation at all `1×`, multiplier-adjusted chance, duplicate-path aggregation, immediate Anti-Repeat exclusion/fallback, muted empty result, and projected no-adjacent-repeat behavior:

```java
@Test void chancesMultiplyNativeProbabilityAndNormalizeByResource() {
    Pool pool = pool(occurrence("a", .75), occurrence("b", .25));
    TrackWeightConfig config = TrackWeightConfig.defaults().withMultiplier(pool.id(), "b", 3.0);
    Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, null);
    assertEquals(.5, chances.get("a"), 1e-9);
    assertEquals(.5, chances.get("b"), 1e-9);
}

@Test void antiRepeatUsesSoleEnabledTrackAsFallback() {
    Pool pool = pool(occurrence("only", 1.0));
    assertEquals("only", WeightedMusicCatalog.select(pool,
            TrackWeightConfig.defaults(), "only", 7L).orElseThrow().resourceId());
}

@Test void mutedPoolHasNoSelectionOrProjection() {
    Pool pool = pool(occurrence("a", .5), occurrence("b", .5));
    TrackWeightConfig muted = TrackWeightConfig.defaults()
            .withPoolMultipliers(pool.id(), Map.of("a", 0.0, "b", 0.0));
    assertTrue(WeightedMusicCatalog.select(pool, muted, null, 1L).isEmpty());
    assertTrue(WeightedMusicCatalog.project(pool, muted, null, 1L, 0L, 5).isEmpty());
}
```

Assert `project` advances the previous resource between projected choices but does not mutate a `MusicPlanner` or config object.

- [ ] **Step 2: Run selector tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.WeightedMusicCatalogTest' --console=plain
```

Expected: compilation fails because `select`, `project`, and `chances` do not exist.

- [ ] **Step 3: Implement one normalized-weight path**

Create one private `adjustedOccurrences(...)` helper. Multiply each occurrence's finite positive native probability by `config.multiplier(pool.id(), occurrence.resourceId())`. If Anti-Repeat is enabled and at least one positive occurrence has a different resource ID, zero every occurrence matching `previousResourceId`.

`select` computes total adjusted weight, returns empty at zero, obtains `RandomSource.create(seed).nextDouble() * total`, and walks in stable occurrence order. Return the final positive occurrence on floating-point tail error.

`chances` sums adjusted occurrence weights by resource ID and divides by total. `project` repeatedly calls `select` with `MusicPlanner.selectionSeed(sessionSeed, pool.id(), index)`, appends the result, updates its local previous resource, and stops on empty.

- [ ] **Step 4: Run selector tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.WeightedMusicCatalogTest' --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java \
  src/test/java/com/cuemymusic/client/music/WeightedMusicCatalogTest.java
git commit -m "feat: select music with configured weights"
```

---

### Task 4: Director, Queue, Mute, and Reload Integration

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/music/MusicDirector.java`
- Modify: `src/client/java/com/cuemymusic/client/CueMyMusicClient.java`
- Modify: `src/test/java/com/cuemymusic/client/music/MusicDirectorQueueDelayTest.java`
- Modify: `src/test/java/com/cuemymusic/client/music/MusicDirectorSessionTest.java`
- Modify: `src/test/java/com/cuemymusic/client/music/MusicPlannerQueueInteractionTest.java`

**Interfaces:**
- Consumes: Task 1 config and Task 3 catalog selector.
- Produces: `void initializeWeighting(Path path)`, `void reloadWeightedCatalog(SoundManager sounds)`.
- Produces: `TrackWeightConfig weightingConfig()`, `WeightedMusicCatalog weightedCatalog()`.
- Produces: `void saveWeightingConfig(TrackWeightConfig draft) throws IOException`; apply only after save succeeds.
- Produces: package-private `Sound chooseFresh(Identifier eventId, WeighedSoundEvents fallback, long seed, String previousResourceId)` and `List<WeightedMusicCatalog.Occurrence> projectFresh(Identifier eventId, String previousResourceId, long startIndex, int count)`; production and tests call these same paths.

- [ ] **Step 1: Write failing integration tests**

Use package-private injection setters or constructor-free pure helpers—not Minecraft mocks—to assert:

1. a configured multiplier changes the pinned resource selected for known seeds;
2. actual selection and first queue projection return the same resource for the same pool/index/previous state;
3. a muted known pool returns `SoundManager.INTENTIONALLY_EMPTY_SOUND` and an empty queue;
4. an undiscovered pool still falls back to `WeighedSoundEvents.getSound` rather than silencing startup/reload races;
5. save failure leaves the previously active config object unchanged;
6. session reset clears history used by Anti-Repeat but retains config;
7. reload replaces catalog and preserves config.

Representative parity assertion (the test installs config/catalog into the singleton's private fields with the same reflection pattern already used by this test suite, then restores them in `@AfterEach`):

```java
@Test void actualAndQueueUseSameConfiguredSelector() {
    Identifier poolId = Identifier.parse("minecraft:music.game");
    long index = director.planner().peekSequence(poolId.toString());
    long seed = MusicPlanner.selectionSeed(director.planner().getSessionSeed(), poolId.toString(), index);
    Sound actual = director.chooseFresh(poolId, weighedFallback, seed, null);
    Sound queued = director.projectFresh(poolId, null, index, 1).getFirst().sound();
    assertEquals(actual.getPath(), queued.getPath());
}
```

- [ ] **Step 2: Run director tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.MusicDirectorQueueDelayTest' \
  --tests 'com.cuemymusic.client.music.MusicDirectorSessionTest' \
  --tests 'com.cuemymusic.client.music.MusicPlannerQueueInteractionTest' --console=plain
```

Expected: compilation fails on the new weighting APIs.

- [ ] **Step 3: Add config/catalog ownership**

Add volatile fields initialized safely before resources exist:

```java
private volatile TrackWeightConfig weightingConfig = TrackWeightConfig.defaults();
private volatile WeightedMusicCatalog weightedCatalog = WeightedMusicCatalog.empty();
private Path weightingPath;
```

`initializeWeighting(path)` stores the path and loads the config. `saveWeightingConfig(draft)` calls `draft.save(weightingPath)` first and assigns `weightingConfig = draft` only after success. `reloadWeightedCatalog` builds a complete local catalog and then swaps the volatile reference.

Call `initializeWeighting(TrackWeightConfig.defaultPath())` during `CueMyMusicClient.onInitializeClient`. Extend the existing resource listener's `apply` to call the director's existing `onResourcesReloaded()` and then `reloadWeightedCatalog(Minecraft.getInstance().getSoundManager())` on the client thread.

- [ ] **Step 4: Route playback and queue through one selector**

In `pinnedInstance`, after pending Previous handling, look up the event pool in the current catalog. If absent, retain the exact vanilla `weighed.getSound(RandomSource.create(seed))` fallback. If present, call `WeightedMusicCatalog.select` with the newest planner-history resource as previous. If selection is empty, pass `SoundManager.INTENTIONALLY_EMPTY_SOUND` to `trackStart`; otherwise record and start the selected composed occurrence.

Refactor `upcomingTracks` to call `WeightedMusicCatalog.project` from `planner.peekSequence(eventId.toString())` and map occurrences through one shared `trackInfo(Sound)` formatter. Keep the fallback projection only when the event has not yet been cataloged. Ensure projected Anti-Repeat chains from the last actual history resource through each projected result.

- [ ] **Step 5: Run integration and legacy music tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.music.*' --console=plain
```

Expected: all music tests pass, including old Previous, delay, transport, and vanilla fallback contracts.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/cuemymusic/client/music/MusicDirector.java \
  src/client/java/com/cuemymusic/client/CueMyMusicClient.java \
  src/test/java/com/cuemymusic/client/music/MusicDirectorQueueDelayTest.java \
  src/test/java/com/cuemymusic/client/music/MusicDirectorSessionTest.java \
  src/test/java/com/cuemymusic/client/music/MusicPlannerQueueInteractionTest.java
git commit -m "feat: apply track weights to music selection"
```

---

### Task 5: Player Configure Button and Transactional Screen Shell

**Files:**
- Create: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Create: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java`
- Modify: `src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java`
- Modify: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Consumes: `MusicDirector.weightingConfig()`, `weightedCatalog()`, and `saveWeightingConfig(...)`.
- Produces: `TrackWeightScreen(MusicPlayerScreen parent)` with a draft captured at construction.
- Produces: `MusicPlayerScreen.openTrackSettings()`.
- Produces: package-private `Bounds configureButtonBounds(int width, int height)` and `boolean usesWideLayout(int width)` for geometry tests.

- [ ] **Step 1: Write failing navigation and geometry tests**

Assert exact approved copy, 200×20 bottom-center bounds with six-pixel bottom margin, wide breakpoint behavior, and source-level transitions where headless `Screen` construction is unavailable:

```java
@Test void configureButtonIsLongAndBottomCentered() {
    TrackWeightScreen.Bounds bounds = MusicPlayerScreen.configureButtonBounds(692, 423);
    assertEquals(200, bounds.width());
    assertEquals(20, bounds.height());
    assertEquals((692 - 200) / 2, bounds.x());
    assertEquals(423 - 6 - 20, bounds.y());
}

@Test void settingsDraftIsIndependentFromSavedConfig() {
    TrackWeightConfig saved = TrackWeightConfig.defaults();
    TrackWeightConfig draft = TrackWeightScreen.updateWeight(saved, "p", "t", 5.0);
    assertEquals(1.0, saved.multiplier("p", "t"));
    assertEquals(5.0, draft.multiplier("p", "t"));
}
```

Add source assertions that the player label is exactly `⚙ Configure Track Pools…`, its narration is exact, `TrackWeightScreen.onClose()` returns to `parent`, and save success returns to `parent` only after `saveWeightingConfig` returns.

- [ ] **Step 2: Run UI tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' \
  --tests 'com.cuemymusic.client.ui.PauseWidgetStateTest' --console=plain
```

Expected: compilation fails because `TrackWeightScreen` and button geometry APIs do not exist.

- [ ] **Step 3: Add the player button**

Override `MusicPlayerScreen.init()` and add one native button:

```java
@Override
protected void init() {
    Bounds b = configureButtonBounds(width, height);
    addRenderableWidget(Button.builder(Component.literal(CONFIGURE_LABEL), button -> openTrackSettings())
            .bounds(b.x(), b.y(), b.width(), b.height())
            .tooltip(Tooltip.create(Component.literal(CONFIGURE_NARRATION)))
            .createNarration(ignored -> Component.literal(CONFIGURE_NARRATION))
            .build());
}
```

Keep the shared music panel attached by existing Fabric screen events. `openTrackSettings()` calls `minecraft.setScreenAndShow(new TrackWeightScreen(this))`.

- [ ] **Step 4: Implement the transactional shell**

`TrackWeightScreen` stores `parent`, `saved`, and mutable field `draft` whose values are immutable `TrackWeightConfig` instances. `onClose()` returns to `parent` without saving. `saveAndClose()` catches `IOException`; on success returns to parent, on failure stores `Component.literal("Could not save cue-my-music.json")` for rendering and stays open. Task 8 adds the preview stop call to both exits once preview state exists. Add the exact `TrackWeightScreen.java` existence assertion to `ModContractTest` in this task.

Implement `usesWideLayout(width)` as `width >= 640`; both branches initially render title and Done so this task stays independently runnable. Do not build the rest of the controls until Task 6.

- [ ] **Step 5: Run UI and contract tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' \
  --tests 'com.cuemymusic.client.ui.PauseWidgetStateTest' \
  --tests 'com.cuemymusic.ModContractTest' --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java \
  src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java \
  src/test/java/com/cuemymusic/client/ui/PauseWidgetStateTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m "feat: open track settings from music player"
```

---

### Task 6: Native Pool, Track, Weight, Search, JSON, and Pool Actions UI

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Modify: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`

**Interfaces:**
- Consumes: catalog `Pool`/`Track`, config immutable update methods, selector `chances`/`select`.
- Produces: package-private pure helpers `filterTracks`, `sliderToMultiplier`, `multiplierToSlider`, `applyAll`, `applyC418`, `mute`, and `testRoll`.
- Produces: nested native `TrackList extends ObjectSelectionList<TrackEntry>` and read-only `JsonScreen extends Screen`.
- Produces: screen accessors `selectedPool()`, `selectedTrack()`, and `draft()` used by radial/preview tasks.

- [ ] **Step 1: Write failing pure UI behavior tests**

Cover case-insensitive title/composer/resource filtering, slider endpoints/half steps, all/C418/mute semantics, chance refresh, Test Roll determinism/non-mutation, and complete draft JSON:

```java
@Test void searchMatchesTitleComposerAndResource() {
    List<Track> tracks = List.of(track("minecraft:sounds/music/game/sweden.ogg", "Sweden", "C418"));
    assertEquals(1, TrackWeightScreen.filterTracks(tracks, "swed").size());
    assertEquals(1, TrackWeightScreen.filterTracks(tracks, "c418").size());
    assertEquals(1, TrackWeightScreen.filterTracks(tracks, "music/game").size());
    assertTrue(TrackWeightScreen.filterTracks(tracks, "raine").isEmpty());
}

@Test void c418ActionChangesOnlyCreditedC418Tracks() {
    TrackWeightConfig draft = TrackWeightConfig.defaults();
    TrackWeightConfig changed = TrackWeightScreen.applyC418(draft, poolWithC418AndUnknown());
    assertEquals(2.0, changed.multiplier("p", "c418"));
    assertEquals(1.0, changed.multiplier("p", "unknown"));
}
```

Assert `testRoll` calls the same catalog selector and does not alter the input config or any supplied planner sequence value.

- [ ] **Step 2: Run screen tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' --console=plain
```

Expected: compilation fails on the new helper methods.

- [ ] **Step 3: Implement pure helpers and native controls**

In `init()`:

- Build a `CycleButton<Pool>` from sorted `catalog.pools()`; pool change selects its first track and rebuilds the list/editor.
- Add `EditBox` with hint `Search track or composer…`; responder filters list entries.
- Add `TrackList` rows showing title left and formatted `multiplier + "×  " + percent + "%"` right; narration includes title, composer, multiplier, and chance.
- Add a `WeightSlider extends AbstractSliderButton`; map `value` to `Math.round(value * 20.0) / 2.0` and call `draft = draft.withMultiplier(...)` from `applyValue()`.
- Add approved `0×`, `0.5×`, `1×`, `2×`, `5×` buttons.
- Add All 1×, C418 2×, Mute 0×, Anti-Repeat, Test Roll, JSON, and Done.
- Use `GuiGraphicsExtractor.text`/`centeredText` for selected title, composer/resource, chance, and save errors.

For narrow mode, omit the wheel slot and reduce list height enough to keep every action reachable; rebuild on `resize` through normal Screen initialization.

`JsonScreen` uses `MultiLineEditBox.builder().setShowBackground(true).build(font, width, height, title)`, sets the draft JSON, disables editing, adds Copy (`minecraft.keyboardHandler.setClipboard(json)`) and Back buttons, and returns to the same settings screen.

- [ ] **Step 4: Run screen tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 5: Run all UI regressions**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.*' --console=plain
```

Expected: settings tests and existing panel geometry/navigation tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java
git commit -m "feat: add native track weighting controls"
```

---

### Task 7: Renderer-Agnostic Radial Wheel

**Files:**
- Create: `src/client/java/com/cuemymusic/client/ui/RadialWeightWidget.java`
- Create: `src/test/java/com/cuemymusic/client/ui/RadialWeightWidgetTest.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Modify: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`
- Modify: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Consumes: ordered `Map<String, Double>` normalized chances and selected resource ID.
- Produces: `RadialWeightWidget(int x, int y, int size, Consumer<String> onSelect)`.
- Produces: `void setModel(List<Slice> slices, String selectedResourceId)` and `void close()`.
- Produces: `record Slice(String resourceId, String label, double chance, int color)`.
- Produces: pure `static int stableColor(String)`, `static Optional<String> hitTest(...)`, and package-private `static int[] rasterize(...)`.

- [ ] **Step 1: Write failing wheel model tests**

Assert stable identifier colors, normalized wedge boundaries, hole/outside rejection, angle hit tests, muted-ring pixels, selected highlight pixels, and dimension clamping:

```java
@Test void hitTestSelectsWedgeByAngleAndRejectsCenterHole() {
    List<Slice> slices = List.of(slice("a", .25), slice("b", .75));
    assertEquals("a", RadialWeightWidget.hitTest(slices, 50, 10, 100).orElseThrow());
    assertEquals("b", RadialWeightWidget.hitTest(slices, 90, 50, 100).orElseThrow());
    assertTrue(RadialWeightWidget.hitTest(slices, 50, 50, 100).isEmpty());
}

@Test void rasterIsDeterministicAndMutedPoolDrawsOnlyRing() {
    assertArrayEquals(RadialWeightWidget.rasterize(64, List.of(), null),
            RadialWeightWidget.rasterize(64, List.of(), null));
    assertTrue(Arrays.stream(RadialWeightWidget.rasterize(64, List.of(), null))
            .noneMatch(pixel -> pixel == RadialWeightWidget.stableColor("a")));
}
```

Name exact sample coordinates after choosing the wheel's `innerRadius = size * .34` and `outerRadius = size * .48`; use the same top-start clockwise angle transform in tests and implementation.

- [ ] **Step 2: Run wheel tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.RadialWeightWidgetTest' --console=plain
```

Expected: compilation fails because `RadialWeightWidget` does not exist.

- [ ] **Step 3: Implement pure raster and hit testing**

For each pixel, calculate center-relative radius and `angle = (atan2(dx, -dy) + 2π) % 2π`. Pixels outside the annulus remain transparent. Walk cumulative slice chances to choose a stable ABGR color; brighten pixels within two pixels of a selected wedge's boundaries. Render a dark gray annulus when no slices have positive chance.

`stableColor` selects from a fixed readable 16-color palette using `Math.floorMod(resourceId.hashCode(), PALETTE.length)`; this is deterministic and does not encode composer/catalog knowledge.

- [ ] **Step 4: Implement vanilla dynamic-texture lifecycle**

On model change:

1. close/release the prior texture through `minecraft.getTextureManager().release(textureId)`;
2. create `NativeImage(size, size, true)` and copy the pure raster pixels with `setPixel`;
3. construct `DynamicTexture(() -> "Cue My Music radial weights", image)`;
4. register under a stable per-widget `Identifier.fromNamespaceAndPath("cue_my_music", "dynamic/radial_weights")`;
5. render only with `extractor.blit(textureId, getX(), getY(), size, size, 0F, 0F, 1F, 1F)`;
6. show hover tooltip via `setTooltipForNextFrame` and call `onSelect` from `onClick` when `hitTest` returns a resource;
7. release the texture from `close()` and when the settings screen is removed.

Do not import `RenderSystem`, OpenGL/LWJGL GL classes, Fabric rendering packages, `RenderPipeline`, or shader classes. Add the exact `RadialWeightWidget.java` existence assertion and a contract assertion scanning that file for those forbidden imports/tokens.

- [ ] **Step 5: Add wheel only to wide settings layout**

In `TrackWeightScreen.init`, create/add the wheel only when `usesWideLayout(width)` is true. Feed slices from `WeightedMusicCatalog.chances`, preserving track-list order. On weight, pool, selected-track, search-independent chance, or Anti-Repeat changes, call `setModel`; wheel selection invokes the same `selectTrack(resourceId)` as list rows.

- [ ] **Step 6: Run wheel, screen, and contract tests**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.RadialWeightWidgetTest' \
  --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' \
  --tests 'com.cuemymusic.ModContractTest' --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/RadialWeightWidget.java \
  src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/test/java/com/cuemymusic/client/ui/RadialWeightWidgetTest.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m "feat: add backend-neutral radial weight wheel"
```

---

### Task 8: Actual Track Preview Lifecycle

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java`
- Modify: `src/client/java/com/cuemymusic/client/music/MusicDirector.java`
- Modify: `src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java`
- Modify: `src/test/java/com/cuemymusic/client/music/MusicDirectorQueueDelayTest.java`
- Modify: `src/test/java/com/cuemymusic/ModContractTest.java`

**Interfaces:**
- Consumes: selected catalog `Track.occurrences()` and `PinnedMusicInstance`.
- Produces: `boolean pauseForPreview()` and `void resumeAfterPreview(boolean pausedByPreview)` on `MusicDirector`.
- Produces: nested package-private `PreviewState` in `TrackWeightScreen`, with one instance and ownership boolean.

- [ ] **Step 1: Write failing preview lifecycle tests**

Test the state transitions as pure callbacks so no sound engine mock is required:

```java
@Test void previewStopsPreviousAndResumesOnlyOwnedPause() {
    List<String> calls = new ArrayList<>();
    PreviewState state = new PreviewState(
            sound -> calls.add("play:" + sound.resourceId()),
            sound -> calls.add("stop:" + sound.resourceId()),
            () -> { calls.add("pause"); return true; },
            () -> calls.add("resume"));
    state.toggle(track("a"));
    state.toggle(track("b"));
    state.close();
    assertEquals(List.of("pause", "play:a", "stop:a", "play:b", "stop:b", "resume"), calls);
}
```

Also assert second press stops, natural inactive detection resumes once, no-current-music does not issue resume, and preview operations do not change config/planner sequence.

- [ ] **Step 2: Run preview tests and verify RED**

```bash
./gradlew test --tests 'com.cuemymusic.client.ui.TrackWeightScreenTest' \
  --tests 'com.cuemymusic.client.music.MusicDirectorQueueDelayTest' --console=plain
```

Expected: compilation fails on `PreviewState`, `pauseForPreview`, or `resumeAfterPreview`.

- [ ] **Step 3: Add preview-safe director methods**

`pauseForPreview()` returns false unless tracked music is active and not already paused; otherwise call the existing native channel pause path and return true. `resumeAfterPreview(true)` resumes only if transport still exists and remains paused; `resumeAfterPreview(false)` is a no-op. Do not touch planner sequence/history, delay, transport generation, or offset requests.

- [ ] **Step 4: Connect preview to Minecraft sound playback**

Choose the selected track's first stable occurrence and create:

```java
PinnedMusicInstance preview = new PinnedMusicInstance(
        Identifier.parse(selectedPool.id()), occurrence.sound(), RandomSource.create(0x435545L), 0L, 0.0);
minecraft.getSoundManager().play(preview);
```

`PreviewState` stops a prior preview before starting another, updates button text between `Play Sound` and `Stop Sound`, and checks `SoundManager.isActive(preview)` from `TrackWeightScreen.tick()` for natural completion. `onClose`, successful Done, JSON transition, screen `removed`, and resource/pool invalidation call one idempotent stop method. Resume only when `pauseForPreview()` returned true for this preview session.

- [ ] **Step 5: Finalize production-boundary assertions**

Ensure `ModContractTest` now names all four approved production files, allows config/catalog names only for this approved subsystem, caps Java production files at 24, and rejects renderer-specific wheel imports.

- [ ] **Step 6: Run all unit tests**

```bash
./gradlew test --console=plain
```

Expected: all tests pass with zero failures/errors.

- [ ] **Step 7: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java \
  src/client/java/com/cuemymusic/client/music/MusicDirector.java \
  src/test/java/com/cuemymusic/client/ui/TrackWeightScreenTest.java \
  src/test/java/com/cuemymusic/client/music/MusicDirectorQueueDelayTest.java \
  src/test/java/com/cuemymusic/ModContractTest.java
git commit -m "feat: preview tracks from weighting settings"
```

---

### Task 9: Documentation, Runtime Evidence, Review, and Delivery

**Files:**
- Modify: `README.md`
- Create locally (gitignored evidence): `.pi/track-weighting-proof/README.md`
- Create locally (gitignored evidence): `.pi/track-weighting-proof/*.png`

**Interfaces:**
- Consumes: complete Tasks 1–8.
- Produces: user documentation, clean build artifact, client-window-only screenshots, independent review verdict, and pushed commits.

- [ ] **Step 1: Update README**

Document:

```markdown
### Track weighting

Open **Mod Menu → Cue My Music → ⚙ Configure Track Pools…**. Cue My Music discovers loaded music pools and resource-pack tracks dynamically. `1×` preserves native chance, `0×` disables a track, and **Mute 0×** silences the selected pool. **Done** saves to `config/cue-my-music.json`; Esc discards the draft.

The radial chance wheel is CPU-generated and displayed through Minecraft's ordinary GUI texture path; it has no OpenGL, Vulkan, Metal, shader, or Fabric rendering API dependency.
```

Also mention Anti-Repeat, Test Roll, C418/All/Mute pool actions, actual track preview, JSON copy, and narrow list fallback. Do not document named presets.

- [ ] **Step 2: Run fresh clean verification**

```bash
./gradlew clean test build --console=plain
git diff --check
```

Expected: `BUILD SUCCESSFUL`, every JUnit XML suite reports zero failures/errors, and `git diff --check` emits nothing.

Count tests explicitly:

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
roots = [ET.parse(path).getroot() for path in glob.glob('build/test-results/test/TEST-*.xml')]
print({key: sum(int(root.attrib.get(key, 0)) for root in roots)
       for key in ('tests', 'failures', 'errors', 'skipped')})
PY
```

Expected: `failures: 0` and `errors: 0`.

- [ ] **Step 3: Inspect the artifact**

```bash
unzip -p build/libs/cue-my-music-*.jar fabric.mod.json | grep -A4 'modmenu'
unzip -l build/libs/cue-my-music-*.jar | grep -E 'TrackWeight(Config|Screen)|WeightedMusicCatalog|RadialWeightWidget'
! unzip -l build/libs/cue-my-music-*.jar | grep -q 'com/google/gson\|com/terraformersmc'
```

Expected: Mod Menu entrypoint/dependency remain; all four new mod classes exist; Gson and Mod Menu classes are not bundled.

- [ ] **Step 4: Exercise runtime behavior and capture proof**

Run:

```bash
./gradlew runClient --console=plain
```

Using Minecraft 26.2 and Mod Menu 20.0.1, verify:

1. Mod Menu → Cue My Music opens the player first.
2. The bottom-center long configure button opens settings.
3. Wide layout shows dynamic pools/list/editor and radial wheel.
4. Hover/click wedge selection matches list selection/chance.
5. Search and all three pool actions work; no preset control exists.
6. Test Roll changes selection only, not playback.
7. Preview pauses/resumes current music and never overlaps a second preview.
8. Done persists and affects future queue/selection after restart.
9. Esc discards a changed draft.
10. Muted pool yields no playback/queue.
11. Narrow layout hides the wheel and keeps all native controls reachable.
12. Experimental Metal renderer, when available locally, displays the wheel through the normal GUI path; absence of that optional mod does not block vanilla runtime verification.

Capture only the Minecraft client window with ScreenCaptureKit while another app is frontmost. Save wide wheel, hover, narrow fallback, saved reload, and muted pool images under `.pi/track-weighting-proof/`; document window size, GUI scale, MC/Mod Menu versions, and each image's asserted state in its README.

- [ ] **Step 5: Request independent Gemini review**

Give a fresh `gemini-reviewer` the approved spec, implementation diff `45ad9ca..HEAD`, changed files, clean-build evidence, artifact checks, and screenshot paths. Require explicit PASS/FAIL across selection math, persistence safety, resource reloads, UI navigation/accessibility, preview ownership, graphics-backend neutrality, and regression risk. Fix confirmed Critical/Important findings with a failing regression test first; rerun Step 2 after every correction.

- [ ] **Step 6: Commit documentation and any verified corrections**

```bash
git add README.md
git commit -m "docs: explain track weighting settings"
```

If README is already included in a final correction commit, do not create an empty commit.

- [ ] **Step 7: Push without releasing**

```bash
git fetch origin
test "$(git rev-list --count HEAD..origin/main)" = 0
git rebase origin/main
git push origin main
git status --short --branch
```

Expected: push succeeds and status reports `main...origin/main` with no working-tree changes. Do not tag, create a GitHub release, or publish to Modrinth.
