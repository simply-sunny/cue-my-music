# YouTube-Track Downloads via yt-dlp + Generated Resource Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide all 206 Minecraft music tracks (92 built-in native tracks + 114 YouTube-sourced tracks) in Cue My Music, with per-track selective download on click and a "Download all" option powered by `yt-dlp` into a generated client resource pack.

**Architecture:** A static JSON catalog (`assets/cue_my_music/youtube_catalog.json`) defines all 114 YouTube tracks. On client startup, `YoutubeTrackRegistry` registers all 114 tracks into `MusicLibrary` alongside the 92 native tracks, and `YoutubePackManager` ensures a local resource pack (`pack.mcmeta` + `sounds.json` pre-declaring all 114 events) exists in the game directory. `YoutubeDownloader` shells out asynchronously to `yt-dlp` to download audio into the pack, after which the client reloads resources and rows become playable through `NativeMinecraftPlayback`. `JukeboxLibraryScreen` displays track download/play states (`↓` missing, `…` downloading, `↻` failed, `>` ready) and a "Download all (N)" footer action.

**Tech Stack:** Java 25, Minecraft 26.2 (Fabric Loom 1.17), JUnit 5, Gson, yt-dlp CLI, OpenAL.

**Spec:** `docs/superpowers/specs/2026-09-05-youtube-downloads-design.md`

## Global Constraints

- MC 26.2 Fabric client mod; zero new runtime jar dependencies.
- No network code inside Java; all external audio fetching is delegated to local `yt-dlp` CLI.
- Native 92 tracks (`VanillaTrackRegistry`) remain 100% untouched and require zero downloads.
- Downloaded tracks play through the existing `NativeMinecraftPlayback` OpenAL path using pre-declared sound events (`cue_my_music:youtube.<slug>`).
- YouTube tracks default to `ambientEligible = false` (opt-in for ambient playback).

---

### Task 1: Generate Catalog JSON & Data Model

**Files:**
- Create: `src/main/resources/assets/cue_my_music/youtube_catalog.json`
- Create: `src/main/java/com/cuemymusic/data/YoutubeCatalogEntry.java`
- Modify: `src/main/java/com/cuemymusic/data/SourceType.java`
- Test: `src/test/java/com/cuemymusic/YoutubeCatalogContractTest.java`

**Interfaces:**
- Produces:
  - `SourceType.YOUTUBE`
  - `record YoutubeCatalogEntry(String slug, String title, String artist, String file, long size, String query)`
  - `assets/cue_my_music/youtube_catalog.json` (114 items)

- [ ] **Step 1: Write the failing test**

```java
package com.cuemymusic;

import com.cuemymusic.data.SourceType;
import com.cuemymusic.data.YoutubeCatalogEntry;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubeCatalogContractTest {

    @Test
    void catalogHasExact114Entries() {
        var stream = getClass().getResourceAsStream("/assets/cue_my_music/youtube_catalog.json");
        assertNotNull(stream, "youtube_catalog.json resource must exist");
        List<YoutubeCatalogEntry> entries = new Gson().fromJson(
                new InputStreamReader(stream, StandardCharsets.UTF_8),
                new TypeToken<List<YoutubeCatalogEntry>>(){}.getType()
        );
        assertEquals(114, entries.size(), "Catalog must contain exactly 114 entries");

        var slugs = new HashSet<String>();
        for (var entry : entries) {
            assertNotNull(entry.slug());
            assertFalse(entry.slug().isBlank());
            assertTrue(entry.slug().matches("^[a-z0-9_.-]+$"), "Slug must be valid identifier path: " + entry.slug());
            assertTrue(slugs.add(entry.slug()), "Slug must be unique: " + entry.slug());
            assertNotNull(entry.title(), "Title missing for " + entry.slug());
            assertNotNull(entry.artist(), "Artist missing for " + entry.slug());
            assertNotNull(entry.file(), "File missing for " + entry.slug());
            assertTrue(entry.file().endsWith(".ogg"), "File must end in .ogg: " + entry.file());
            assertNotNull(entry.query(), "Query missing for " + entry.slug());
            assertFalse(entry.query().isBlank(), "Query cannot be blank for " + entry.slug());
            assertTrue(entry.size() > 0, "Size must be positive for " + entry.slug());
        }
    }

    @Test
    void sourceTypeIncludesYoutube() {
        assertNotNull(SourceType.valueOf("YOUTUBE"));
        assertFalse(SourceType.YOUTUBE.isVanilla());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.cuemymusic.YoutubeCatalogContractTest`
Expected: FAIL (SourceType.YOUTUBE not found, resource missing).

- [ ] **Step 3: Implement SourceType, YoutubeCatalogEntry, and generate youtube_catalog.json**

In `src/main/java/com/cuemymusic/data/SourceType.java`:
```java
package com.cuemymusic.data;

public enum SourceType {
    VANILLA,
    MUSIC_DISC,
    YOUTUBE;

    public boolean isVanilla() {
        return this == VANILLA || this == MUSIC_DISC;
    }
}
```

In `src/main/java/com/cuemymusic/data/YoutubeCatalogEntry.java`:
```java
package com.cuemymusic.data;

public record YoutubeCatalogEntry(
    String slug,
    String title,
    String artist,
    String file,
    long size,
    String query
) {}
```

Generate `src/main/resources/assets/cue_my_music/youtube_catalog.json` by matching `manifest.json` YouTube files with `download_youtube.py` search queries, ensuring all 114 entries have valid lowercase identifier slugs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.cuemymusic.YoutubeCatalogContractTest`
Expected: PASS (114 entries verified).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/assets/cue_my_music/youtube_catalog.json src/main/java/com/cuemymusic/data/SourceType.java src/main/java/com/cuemymusic/data/YoutubeCatalogEntry.java src/test/java/com/cuemymusic/YoutubeCatalogContractTest.java
git commit -m "feat: add YouTube catalog JSON and SourceType.YOUTUBE data model"
```

---

### Task 2: YoutubeTrackRegistry & Library Integration

**Files:**
- Create: `src/client/java/com/cuemymusic/client/music/YoutubeTrackRegistry.java`
- Modify: `src/client/java/com/cuemymusic/client/ui/TrackDetailWidget.java:55-70`
- Test: `src/test/java/com/cuemymusic/YoutubeTrackRegistryTest.java`
- Modify: `src/test/java/com/cuemymusic/PersistenceRoundTripTest.java`

**Interfaces:**
- Consumes: `YoutubeCatalogEntry`, `SourceType.YOUTUBE`, `MusicLibrary`, `MusicTrack`
- Produces:
  - `YoutubeTrackRegistry.registerAll(MusicLibrary library)`
  - `YoutubeTrackRegistry.getCatalog(): List<YoutubeCatalogEntry>`
  - `YoutubeTrackRegistry.getBySlug(String slug): Optional<YoutubeCatalogEntry>`
  - `YoutubeTrackRegistry.trackCount(): int` (114)

- [ ] **Step 1: Write the failing test**

```java
package com.cuemymusic;

import com.cuemymusic.client.music.VanillaTrackRegistry;
import com.cuemymusic.client.music.YoutubeTrackRegistry;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubeTrackRegistryTest {
    private MusicLibrary library;

    @BeforeEach
    void setUp() {
        library = new MusicLibrary();
        VanillaTrackRegistry.registerAll(library);
        YoutubeTrackRegistry.registerAll(library);
    }

    @Test
    void totalTracksIs206() {
        assertEquals(206, library.getAllTracks().size(), "Total tracks must be 92 vanilla + 114 youtube = 206");
        assertEquals(114, library.getAllTracks().stream().filter(t -> t.getSourceType() == SourceType.YOUTUBE).count());
    }

    @Test
    void youtubeTracksDefaultAmbientFalseAndEnabledTrue() {
        for (var t : library.getAllTracks()) {
            if (t.getSourceType() == SourceType.YOUTUBE) {
                assertTrue(t.getId().startsWith("youtube:"));
                assertTrue(t.getSourceId().startsWith("cue_my_music:youtube."));
                assertTrue(t.isEnabled());
                assertFalse(t.isAmbientEligible(), "YouTube tracks must default to ambientEligible=false");
            }
        }
    }

    @Test
    void youtubeTracksSearchable() {
        var results = library.filter(library.getAllTracks(), "villager song");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("youtube:20_million_villager_song")));
    }

    @Test
    void youtubePersistenceRoundTrip() {
        var track = library.getTrack("youtube:20_million_villager_song").orElseThrow();
        track.setAmbientEligible(true);
        var state = library.toPersistedState();

        var restored = new MusicLibrary();
        VanillaTrackRegistry.registerAll(restored);
        YoutubeTrackRegistry.registerAll(restored);
        restored.applyPersistedState(state);

        assertTrue(restored.getTrack("youtube:20_million_villager_song").orElseThrow().isAmbientEligible());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.cuemymusic.YoutubeTrackRegistryTest`
Expected: FAIL (YoutubeTrackRegistry not found).

- [ ] **Step 3: Implement YoutubeTrackRegistry and update TrackDetailWidget**

In `src/client/java/com/cuemymusic/client/music/YoutubeTrackRegistry.java`:
```java
package com.cuemymusic.client.music;

import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import com.cuemymusic.data.YoutubeCatalogEntry;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class YoutubeTrackRegistry {
    private static final List<YoutubeCatalogEntry> CATALOG = new ArrayList<>();
    private static final Map<String, YoutubeCatalogEntry> BY_SLUG = new LinkedHashMap<>();

    static {
        try (var stream = YoutubeTrackRegistry.class.getResourceAsStream("/assets/cue_my_music/youtube_catalog.json")) {
            if (stream != null) {
                List<YoutubeCatalogEntry> list = new Gson().fromJson(
                        new InputStreamReader(stream, StandardCharsets.UTF_8),
                        new TypeToken<List<YoutubeCatalogEntry>>(){}.getType()
                );
                if (list != null) {
                    CATALOG.addAll(list);
                    for (var entry : list) {
                        BY_SLUG.put(entry.slug(), entry);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Cue My Music] Failed to load youtube catalog: " + e.getMessage());
        }
    }

    private YoutubeTrackRegistry() {}

    public static List<YoutubeCatalogEntry> getCatalog() {
        return List.copyOf(CATALOG);
    }

    public static Optional<YoutubeCatalogEntry> getBySlug(String slug) {
        return Optional.ofNullable(BY_SLUG.get(slug));
    }

    public static int trackCount() {
        return CATALOG.size();
    }

    public static void registerAll(MusicLibrary library) {
        for (var entry : CATALOG) {
            String id = "youtube:" + entry.slug();
            if (library.getTrack(id).isPresent()) continue;
            MusicTrack track = new MusicTrack(id, entry.title(), entry.artist(), SourceType.YOUTUBE);
            track.setSourceId("cue_my_music:youtube." + entry.slug());
            track.setEnabled(true);
            track.setAmbientEligible(false);
            library.addOrReplaceTrack(track);
        }
    }
}
```

Update `TrackDetailWidget.java` to format and color `SourceType.YOUTUBE` (e.g., label "YouTube", color `0xFFFF5555`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.cuemymusic.YoutubeTrackRegistryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/music/YoutubeTrackRegistry.java src/client/java/com/cuemymusic/client/ui/TrackDetailWidget.java src/test/java/com/cuemymusic/YoutubeTrackRegistryTest.java
git commit -m "feat: implement YoutubeTrackRegistry registering 114 catalog tracks"
```

---

### Task 3: YoutubePackManager (Storage & sounds.json Generator)

**Files:**
- Create: `src/client/java/com/cuemymusic/client/download/YoutubePackManager.java`
- Test: `src/test/java/com/cuemymusic/client/download/YoutubePackManagerTest.java`

**Interfaces:**
- Consumes: `YoutubeTrackRegistry.getCatalog()`, `Path gameDir` / `Path dataDir`
- Produces:
  - `YoutubePackManager(Path packRoot)`
  - `Path getPackRoot()`
  - `Path getSoundsDir()`
  - `Path getSoundFile(String slug)`
  - `boolean isTrackReady(String slug)`
  - `void ensurePackStructure() throws IOException`
  - `String generateSoundsJson()`

- [ ] **Step 1: Write the failing test**

```java
package com.cuemymusic.client.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubePackManagerTest {

    @Test
    void packStructureCreatedAndValid(@TempDir Path tempDir) throws Exception {
        var packRoot = tempDir.resolve("cue-my-music-pack");
        var manager = new YoutubePackManager(packRoot);

        manager.ensurePackStructure();

        assertTrue(Files.exists(packRoot.resolve("pack.mcmeta")), "pack.mcmeta must exist");
        Path soundsJson = packRoot.resolve("assets/cue_my_music/sounds.json");
        assertTrue(Files.exists(soundsJson), "sounds.json must exist");
        assertTrue(Files.isDirectory(manager.getSoundsDir()), "sounds directory must exist");

        String json = Files.readString(soundsJson);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(114, obj.keySet().size(), "sounds.json must contain all 114 sound events");

        // Verify a specific event
        assertTrue(obj.has("youtube.20_million_villager_song"));
        var entry = obj.getAsJsonObject("youtube.20_million_villager_song");
        var sounds = entry.getAsJsonArray("sounds");
        assertEquals(1, sounds.size());
        var soundObj = sounds.get(0).getAsJsonObject();
        assertEquals("cue_my_music:youtube/20_million_villager_song", soundObj.get("name").getAsString());
        assertEquals(false, soundObj.get("stream").getAsBoolean());
    }

    @Test
    void isTrackReadyChecksSize(@TempDir Path tempDir) throws Exception {
        var packRoot = tempDir.resolve("cue-my-music-pack");
        var manager = new YoutubePackManager(packRoot);
        manager.ensurePackStructure();

        assertFalse(manager.isTrackReady("test_slug"));

        Path testFile = manager.getSoundFile("test_slug");
        Files.write(testFile, new byte[500]); // < 1024 bytes
        assertFalse(manager.isTrackReady("test_slug"));

        Files.write(testFile, new byte[2048]); // > 1024 bytes
        assertTrue(manager.isTrackReady("test_slug"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.cuemymusic.client.download.YoutubePackManagerTest`
Expected: FAIL (YoutubePackManager not found).

- [ ] **Step 3: Implement YoutubePackManager**

In `src/client/java/com/cuemymusic/client/download/YoutubePackManager.java`:
```java
package com.cuemymusic.client.download;

import com.cuemymusic.client.music.YoutubeTrackRegistry;
import com.cuemymusic.data.YoutubeCatalogEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class YoutubePackManager {
    private final Path packRoot;
    private final Path soundsDir;
    private final Path soundsJsonPath;

    public YoutubePackManager(Path packRoot) {
        this.packRoot = packRoot;
        this.soundsDir = packRoot.resolve("assets/cue_my_music/sounds/youtube");
        this.soundsJsonPath = packRoot.resolve("assets/cue_my_music/sounds.json");
    }

    public Path getPackRoot() { return packRoot; }
    public Path getSoundsDir() { return soundsDir; }
    public Path getSoundFile(String slug) { return soundsDir.resolve(slug + ".ogg"); }

    public boolean isTrackReady(String slug) {
        Path file = getSoundFile(slug);
        try {
            return Files.exists(file) && Files.size(file) > 1024;
        } catch (IOException e) {
            return false;
        }
    }

    public void ensurePackStructure() throws IOException {
        Files.createDirectories(soundsDir);
        Path mcmeta = packRoot.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            String mcmetaJson = """
                {
                  "pack": {
                    "description": "Cue My Music YouTube Audio Pack",
                    "pack_format": 48
                  }
                }
                """;
            Files.writeString(mcmeta, mcmetaJson);
        }
        if (!Files.exists(soundsJsonPath)) {
            Files.createDirectories(soundsJsonPath.getParent());
            Files.writeString(soundsJsonPath, generateSoundsJson());
        }
    }

    public String generateSoundsJson() {
        JsonObject root = new JsonObject();
        List<YoutubeCatalogEntry> catalog = YoutubeTrackRegistry.getCatalog();
        for (var entry : catalog) {
            JsonObject event = new JsonObject();
            JsonArray sounds = new JsonArray();
            JsonObject soundObj = new JsonObject();
            soundObj.addProperty("name", "cue_my_music:youtube/" + entry.slug());
            soundObj.addProperty("stream", false);
            sounds.add(soundObj);
            event.add("sounds", sounds);
            root.add("youtube." + entry.slug(), event);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.cuemymusic.client.download.YoutubePackManagerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/download/YoutubePackManager.java src/test/java/com/cuemymusic/client/download/YoutubePackManagerTest.java
git commit -m "feat: implement YoutubePackManager for pack layout and sounds.json generation"
```

---

### Task 4: YoutubeDownloader (yt-dlp Process Execution & State Machine)

**Files:**
- Create: `src/client/java/com/cuemymusic/client/download/YoutubeDownloader.java`
- Test: `src/test/java/com/cuemymusic/client/download/YoutubeDownloaderTest.java`

**Interfaces:**
- Consumes: `YoutubePackManager`, `YoutubeCatalogEntry`
- Produces:
  - `enum DownloadStatus { MISSING, DOWNLOADING, READY, FAILED }`
  - `YoutubeDownloader(YoutubePackManager packManager)`
  - `boolean isYtDlpAvailable()`
  - `DownloadStatus getStatus(String slug)`
  - `void downloadTrack(String slug, Runnable onComplete)`
  - `void downloadAll(Runnable onTrackComplete, Runnable onAllComplete)`
  - `int getMissingCount()`
  - `boolean isDownloading()`

- [ ] **Step 1: Write the failing test**

```java
package com.cuemymusic.client.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubeDownloaderTest {

    @Test
    void statusTransitions(@TempDir Path tempDir) throws Exception {
        var packManager = new YoutubePackManager(tempDir);
        packManager.ensurePackStructure();

        var downloader = new YoutubeDownloader(packManager, cmd -> 0); // Mock successful runner

        assertEquals(YoutubeDownloader.DownloadStatus.MISSING, downloader.getStatus("20_million_villager_song"));

        // Simulate ready file
        Files.write(packManager.getSoundFile("20_million_villager_song"), new byte[2048]);
        assertEquals(YoutubeDownloader.DownloadStatus.READY, downloader.getStatus("20_million_villager_song"));
    }

    @Test
    void buildCommandContainsExpectedArguments(@TempDir Path tempDir) {
        var packManager = new YoutubePackManager(tempDir);
        var downloader = new YoutubeDownloader(packManager, cmd -> 0);

        List<String> cmd = downloader.buildCommand("20_million_villager_song", "The 20 Million Villager Song");
        assertEquals("yt-dlp", cmd.get(0));
        assertTrue(cmd.contains("-x"));
        assertTrue(cmd.contains("--audio-format"));
        assertTrue(cmd.contains("ogg"));
        assertTrue(cmd.contains("ytsearch1:The 20 Million Villager Song"));
        assertTrue(cmd.stream().anyMatch(s -> s.endsWith("20_million_villager_song.ogg")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.cuemymusic.client.download.YoutubeDownloaderTest`
Expected: FAIL (YoutubeDownloader not found).

- [ ] **Step 3: Implement YoutubeDownloader**

In `src/client/java/com/cuemymusic/client/download/YoutubeDownloader.java`:
```java
package com.cuemymusic.client.download;

import com.cuemymusic.client.music.YoutubeTrackRegistry;
import com.cuemymusic.data.YoutubeCatalogEntry;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

public class YoutubeDownloader {
    public enum DownloadStatus {
        MISSING,
        DOWNLOADING,
        READY,
        FAILED
    }

    public interface ProcessRunner {
        int run(List<String> command) throws Exception;
    }

    private static final ProcessRunner DEFAULT_RUNNER = command -> {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        return p.waitFor();
    };

    private final YoutubePackManager packManager;
    private final ProcessRunner processRunner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CueMyMusic-Downloader");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, DownloadStatus> inMemoryStatus = new ConcurrentHashMap<>();
    private Boolean ytDlpAvailable = null;

    public YoutubeDownloader(YoutubePackManager packManager) {
        this(packManager, DEFAULT_RUNNER);
    }

    public YoutubeDownloader(YoutubePackManager packManager, ProcessRunner processRunner) {
        this.packManager = packManager;
        this.processRunner = processRunner;
    }

    public synchronized boolean isYtDlpAvailable() {
        if (ytDlpAvailable != null) return ytDlpAvailable;
        try {
            int exit = processRunner.run(List.of("yt-dlp", "--version"));
            ytDlpAvailable = (exit == 0);
        } catch (Exception e) {
            ytDlpAvailable = false;
        }
        return ytDlpAvailable;
    }

    public DownloadStatus getStatus(String slug) {
        if (packManager.isTrackReady(slug)) {
            inMemoryStatus.remove(slug);
            return DownloadStatus.READY;
        }
        return inMemoryStatus.getOrDefault(slug, DownloadStatus.MISSING);
    }

    public boolean isDownloading() {
        return inMemoryStatus.values().stream().anyMatch(s -> s == DownloadStatus.DOWNLOADING);
    }

    public int getMissingCount() {
        int count = 0;
        for (var entry : YoutubeTrackRegistry.getCatalog()) {
            if (getStatus(entry.slug()) != DownloadStatus.READY) {
                count++;
            }
        }
        return count;
    }

    public List<String> buildCommand(String slug, String query) {
        File dest = packManager.getSoundFile(slug).toFile();
        return List.of(
                "yt-dlp",
                "-x",
                "--audio-format", "ogg",
                "-o", dest.getAbsolutePath(),
                "ytsearch1:" + query
        );
    }

    public void downloadTrack(String slug, Runnable onComplete) {
        var opt = YoutubeTrackRegistry.getBySlug(slug);
        if (opt.isEmpty()) return;
        var entry = opt.get();

        if (packManager.isTrackReady(slug)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        inMemoryStatus.put(slug, DownloadStatus.DOWNLOADING);
        executor.submit(() -> {
            try {
                int exit = processRunner.run(buildCommand(slug, entry.query()));
                if (exit == 0 && packManager.isTrackReady(slug)) {
                    inMemoryStatus.put(slug, DownloadStatus.READY);
                } else {
                    inMemoryStatus.put(slug, DownloadStatus.FAILED);
                }
            } catch (Exception e) {
                inMemoryStatus.put(slug, DownloadStatus.FAILED);
            } finally {
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public void downloadAll(Runnable onTrackComplete, Runnable onAllComplete) {
        executor.submit(() -> {
            for (var entry : YoutubeTrackRegistry.getCatalog()) {
                if (!packManager.isTrackReady(entry.slug())) {
                    inMemoryStatus.put(entry.slug(), DownloadStatus.DOWNLOADING);
                    try {
                        int exit = processRunner.run(buildCommand(entry.slug(), entry.query()));
                        if (exit == 0 && packManager.isTrackReady(entry.slug())) {
                            inMemoryStatus.put(entry.slug(), DownloadStatus.READY);
                        } else {
                            inMemoryStatus.put(entry.slug(), DownloadStatus.FAILED);
                        }
                    } catch (Exception e) {
                        inMemoryStatus.put(entry.slug(), DownloadStatus.FAILED);
                    }
                    if (onTrackComplete != null) onTrackComplete.run();
                }
            }
            if (onAllComplete != null) onAllComplete.run();
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.cuemymusic.client.download.YoutubeDownloaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/download/YoutubeDownloader.java src/test/java/com/cuemymusic/client/download/YoutubeDownloaderTest.java
git commit -m "feat: implement YoutubeDownloader for yt-dlp execution and state tracking"
```

---

### Task 5: Playback & Client Init Wiring

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/CueMyMusicClient.java`
- Modify: `src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java`
- Test: `src/test/java/com/cuemymusic/client/playback/NativeMinecraftPlaybackTest.java` (or contract test)

**Interfaces:**
- Consumes: `YoutubeTrackRegistry`, `YoutubePackManager`, `YoutubeDownloader`, `CueMyMusicClient`
- Produces:
  - Client startup registers YouTube tracks and ensures resource pack directory structure.
  - `NativeMinecraftPlayback` handles `SourceType.YOUTUBE` by resolving sound event from `SoundManager`.

- [ ] **Step 1: Write / Update the failing test**

In `src/test/java/com/cuemymusic/VanillaRegistryTest.java` or new test verifying `NativeMinecraftPlayback` sound event parsing:
```java
@Test
void youtubeTrackSoundEventParsesValidIdentifier() {
    for (var entry : YoutubeTrackRegistry.getCatalog()) {
        String sourceId = "cue_my_music:youtube." + entry.slug();
        assertNotNull(net.minecraft.resources.Identifier.tryParse(sourceId));
    }
}
```

- [ ] **Step 2: Run test to verify it fails/passes**

Run: `./gradlew test --tests com.cuemymusic.VanillaRegistryTest`

- [ ] **Step 3: Update CueMyMusicClient and NativeMinecraftPlayback**

In `src/client/java/com/cuemymusic/client/CueMyMusicClient.java`:
- Initialize `YoutubePackManager` and `YoutubeDownloader`.
- Register `YoutubeTrackRegistry.registerAll(inst.getLibrary())` right after `VanillaTrackRegistry.registerAll(...)`.
- Ensure pack structure is created on startup.

In `src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java`:
- Ensure `createSound` treats `SourceType.YOUTUBE` like `SourceType.MUSIC_DISC` (looks up `SoundEvent` via `SoundManager.getSoundEvent(id)` and uses `event.getSound(...).getLocation()`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/CueMyMusicClient.java src/client/java/com/cuemymusic/client/playback/NativeMinecraftPlayback.java
git commit -m "feat: wire YoutubeTrackRegistry and pack manager into client init and playback"
```

---

### Task 6: UI — JukeboxLibraryScreen Row & Footer Controls

**Files:**
- Modify: `src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java`
- Modify: `src/test/java/com/cuemymusic/client/ui/JukeboxLibraryScreenLogicTest.java`

**Interfaces:**
- Consumes: `YoutubeDownloader`, `DownloadStatus`, `SourceType.YOUTUBE`
- Produces:
  - Row preview button state mapping:
    - Missing YouTube -> `↓` (starts download)
    - Downloading YouTube -> `…` (disabled)
    - Failed YouTube -> `↻` (retries download)
    - Ready / Vanilla -> `>` or `||` (preview playback)
  - Footer button: "Download all (N)" displayed when missing YouTube tracks exist.

- [ ] **Step 1: Write the failing UI logic test**

In `src/test/java/com/cuemymusic/client/ui/JukeboxLibraryScreenLogicTest.java`:
```java
@Test
void downloadAllButtonLayoutFitsInFooter() {
    var l = JukeboxLibraryScreen.computeLayout(854, 480);
    assertTrue(l.doneY() > l.listBottom());
    // Ensure footer elements do not overlap done button
    int downloadAllW = 120;
    int downloadAllX = l.contentL() + 90;
    assertTrue(downloadAllX + downloadAllW < l.doneX() || downloadAllX > l.doneX() + l.doneW());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.cuemymusic.client.ui.JukeboxLibraryScreenLogicTest`

- [ ] **Step 3: Update JukeboxLibraryScreen implementation**

In `src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java`:
1. Add `downloadAllButton` to footer widgets.
2. In `TrackRow.extractContent`:
   - Determine `DownloadStatus` for `SourceType.YOUTUBE` tracks.
   - For `MISSING`, button text is `↓`, click triggers `downloader.downloadTrack(...)`.
   - For `DOWNLOADING`, button text is `…`, button disabled.
   - For `FAILED`, button text is `↻`, click retries download.
   - For `READY` or vanilla, button text is `>` / `||`, click triggers `previewTrack(track)`.
3. In `extractRenderState`:
   - Update `downloadAllButton` visibility and text `"Download all (" + missingCount + ")"`.
   - When download completes, schedule client resource reload (`mc.reloadResourcePacks()`) and refresh screen.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.cuemymusic.client.ui.JukeboxLibraryScreenLogicTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/cuemymusic/client/ui/JukeboxLibraryScreen.java src/test/java/com/cuemymusic/client/ui/JukeboxLibraryScreenLogicTest.java
git commit -m "feat: add download button states and download all footer action to JukeboxLibraryScreen"
```

---

### Task 7: Documentation & Release Contract Verification

**Files:**
- Modify: `README.md`
- Modify: `src/test/java/com/cuemymusic/PublicReleaseContractTest.java`

**Interfaces:**
- Produces:
  - Updated `README.md` documenting YouTube catalog, `yt-dlp` requirement, and selective/all downloading.
  - Updated `PublicReleaseContractTest` ensuring all documentation matches implemented features.

- [ ] **Step 1: Update README.md and PublicReleaseContractTest**

In `README.md`:
- Document 206 total tracks (92 vanilla + 114 extended YouTube tracks).
- Document `yt-dlp` requirement for downloading YouTube tracks.
- Document controls for selective download (`↓`) and "Download all".

In `src/test/java/com/cuemymusic/PublicReleaseContractTest.java`:
- Update assertions to reflect YouTube download support and 206 tracks.

- [ ] **Step 2: Run full build and test suite**

Run: `./gradlew clean test build`
Expected: BUILD SUCCESSFUL with 100% tests passing.

- [ ] **Step 3: Commit**

```bash
git add README.md src/test/java/com/cuemymusic/PublicReleaseContractTest.java
git commit -m "docs: update README and release contract tests for YouTube track support"
```
