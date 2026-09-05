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
