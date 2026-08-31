package com.cuemymusic.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Data holder for Gson serialization of the library index.
 * No logic — just fields that Gson reads/writes.
 */
public class PersistedLibraryState {

    public String activePresetId;
    public List<PersistedTrack> tracks = new ArrayList<>();
    public List<PersistedPreset> presets = new ArrayList<>();

    /** Persisted form of a single track. */
    public static class PersistedTrack {
        public String id;
        public String title;
        public String artist;
        /** SourceType name (e.g. "VANILLA", "MUSIC_DISC", "LOCAL_GENERIC"). Stored as String for Gson stability. */
        public String sourceType;
        public String sourceId;
        public String localAudioPath;
        public String coverArtPath;
        public boolean enabled;
        public boolean ambientEligible;
        public boolean favorite;
        public Integer durationSeconds;
    }

    /** Persisted form of a preset/playlist. */
    public static class PersistedPreset {
        public String id;
        public String name;
        public String description;
        public boolean builtIn;
        public List<String> trackIds = new ArrayList<>();
    }
}
