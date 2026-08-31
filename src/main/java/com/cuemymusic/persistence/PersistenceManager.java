package com.cuemymusic.persistence;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.config.CueMyMusicConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles file I/O under {@code .minecraft/cue-my-music/} (or game dir for MVP).
 *
 * <p>For MVP the data directory is {@code FabricLoader.getInstance().getGameDir().resolve("cue-my-music")}.
 * Sub-directories {@code audio} and {@code covers} are created on demand.</p>
 */
public class PersistenceManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // -------------------------------------------------------------------------
    // Directory helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the game directory, falling back to {@code user.dir} when
     * FabricLoader is not initialized (e.g. unit tests).
     */
    private Path resolveGameDir() {
        try {
            var instance = FabricLoader.getInstance();
            if (instance != null) {
                Path gameDir = instance.getGameDir();
                if (gameDir != null) {
                    return gameDir;
                }
            }
        } catch (Exception e) {
            // FabricLoader not available in test env — fall through
            CueMyMusic.LOGGER.debug("[Cue My Music] FabricLoader not available, using user.dir fallback: {}", e.toString());
        }
        // Fallback for tests / standalone
        String userDir = System.getProperty("user.dir", ".");
        return Path.of(userDir);
    }

    /**
     * Returns {@code <gameDir>/cue-my-music}, creating it if necessary.
     */
    public Path getDataDir() {
        Path dir = resolveGameDir().resolve("cue-my-music");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to create data dir {}", dir, e);
        }
        return dir;
    }

    /**
     * Alias for {@link #getDataDir()} — config lives alongside library data in MVP.
     */
    public Path getConfigDir() {
        return getDataDir();
    }

    /**
     * Returns {@code <dataDir>/audio}, creating it if necessary.
     */
    public Path getAudioCacheDir() {
        Path dir = getDataDir().resolve("audio");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to create audio cache dir {}", dir, e);
        }
        return dir;
    }

    /**
     * Returns {@code <dataDir>/covers}, creating it if necessary.
     */
    public Path getCoversDir() {
        Path dir = getDataDir().resolve("covers");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to create covers dir {}", dir, e);
        }
        return dir;
    }

    // -------------------------------------------------------------------------
    // File handles
    // -------------------------------------------------------------------------

    public File getConfigFile() {
        return getDataDir().resolve("config.json").toFile();
    }

    public File getLibraryFile() {
        return getDataDir().resolve("library.json").toFile();
    }

    // -------------------------------------------------------------------------
    // Config I/O
    // -------------------------------------------------------------------------

    /**
     * Loads {@code config.json} via Gson. Returns defaults on failure or if file is missing.
     */
    public CueMyMusicConfig loadConfig() {
        File file = getConfigFile();
        if (!file.exists()) {
            CueMyMusic.LOGGER.info("[Cue My Music] No config.json found, using defaults");
            return new CueMyMusicConfig();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            CueMyMusicConfig loaded = GSON.fromJson(reader, CueMyMusicConfig.class);
            if (loaded == null) {
                CueMyMusic.LOGGER.warn("[Cue My Music] config.json was empty/null, using defaults");
                return new CueMyMusicConfig();
            }
            return loaded;
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to read config.json, using defaults", e);
            return new CueMyMusicConfig();
        } catch (Exception e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to parse config.json, using defaults", e);
            return new CueMyMusicConfig();
        }
    }

    /**
     * Persists the given config to {@code config.json}.
     */
    public void saveConfig(CueMyMusicConfig config) {
        File file = getConfigFile();
        // Ensure parent exists (getDataDir already does, but be defensive)
        try {
            Files.createDirectories(file.toPath().getParent());
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to create parent dir for config.json", e);
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath())) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to save config.json", e);
        }
    }

    // -------------------------------------------------------------------------
    // Library I/O
    // -------------------------------------------------------------------------

    /**
     * Loads {@code library.json} via Gson. Returns {@code null} if file is missing.
     */
    public PersistedLibraryState loadLibraryIndex() {
        File file = getLibraryFile();
        if (!file.exists()) {
            CueMyMusic.LOGGER.info("[Cue My Music] No library.json found");
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            PersistedLibraryState state = GSON.fromJson(reader, PersistedLibraryState.class);
            return state;
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to read library.json", e);
            return null;
        } catch (Exception e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to parse library.json", e);
            return null;
        }
    }

    /**
     * Persists the library state to {@code library.json}.
     */
    public void saveLibraryIndex(PersistedLibraryState state) {
        File file = getLibraryFile();
        try {
            Files.createDirectories(file.toPath().getParent());
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to create parent dir for library.json", e);
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath())) {
            GSON.toJson(state, writer);
        } catch (IOException e) {
            CueMyMusic.LOGGER.error("[Cue My Music] Failed to save library.json", e);
        }
    }
}
