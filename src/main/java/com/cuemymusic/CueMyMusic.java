package com.cuemymusic;

import com.cuemymusic.config.CueMyMusicConfig;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.persistence.PersistenceManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class CueMyMusic implements ModInitializer {
    public static final String MOD_ID = "cue_my_music";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CueMyMusic instance;
    private PersistenceManager persistenceManager;
    private MusicLibrary library;
    private CueMyMusicConfig config;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[Cue My Music] Initializing common");

        persistenceManager = new PersistenceManager();
        Path configDir = persistenceManager.getConfigDir();
        LOGGER.info("[Cue My Music] Data dir: {}", persistenceManager.getDataDir());

        // Load or create config
        config = persistenceManager.loadConfig();

        // Initialize library (tracks are mostly client-side, but structure is common)
        library = new MusicLibrary();
        // Load persisted library overrides (enabled flags, presets)
        var persisted = persistenceManager.loadLibraryIndex();
        if (persisted != null) {
            library.applyPersistedState(persisted);
            LOGGER.info("[Cue My Music] Loaded persisted library state: {} tracks", library.getAllTracks().size());
        }
    }

    public static CueMyMusic getInstance() {
        return instance;
    }

    public PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }

    public MusicLibrary getLibrary() {
        return library;
    }

    public CueMyMusicConfig getConfig() {
        return config;
    }

    public void saveAll() {
        try {
            persistenceManager.saveConfig(config);
            persistenceManager.saveLibraryIndex(library.toPersistedState());
        } catch (Exception e) {
            LOGGER.error("[Cue My Music] Failed to save", e);
        }
    }
}
