package com.cuemymusic.persistence;

import java.util.ArrayList;
import java.util.List;

public class PersistedLibraryState {

    public List<PersistedTrack> tracks = new ArrayList<>();

    public static class PersistedTrack {
        public String id;
        public String title;
        public String artist;
        public String sourceType;
        public String sourceId;
        public String jukeboxSongId;
        public boolean enabled;
        public boolean ambientEligible;
        public Integer durationSeconds;
    }
}
