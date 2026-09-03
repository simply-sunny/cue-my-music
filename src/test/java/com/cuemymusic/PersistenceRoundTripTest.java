package com.cuemymusic;

import com.cuemymusic.client.music.VanillaTrackRegistry;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.SourceType;
import com.cuemymusic.persistence.PersistedLibraryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PersistenceRoundTripTest {

    private MusicLibrary library;

    @BeforeEach void setUp() {
        library = new MusicLibrary();
        VanillaTrackRegistry.registerAll(library);
    }

    @Test void roundTripPreservesEnabledAndAmbient() {
        library.getTrack("vanilla:sweden").orElseThrow().setEnabled(false);
        library.getTrack("vanilla:sweden").orElseThrow().setAmbientEligible(false);
        var state = library.toPersistedState();
        var restored = new MusicLibrary();
        VanillaTrackRegistry.registerAll(restored);
        restored.applyPersistedState(state);
        assertFalse(restored.getTrack("vanilla:sweden").orElseThrow().isEnabled());
        assertFalse(restored.getTrack("vanilla:sweden").orElseThrow().isAmbientEligible());
    }

    @Test void staleNotRecreated() {
        var state = library.toPersistedState();
        var stale = new PersistedLibraryState.PersistedTrack();
        stale.id="vanilla:game"; stale.title="Game"; stale.artist="C418"; stale.sourceType="VANILLA";
        stale.sourceId="minecraft:music.game"; stale.enabled=true; stale.ambientEligible=true;
        state.tracks.add(stale);
        var restored = new MusicLibrary();
        VanillaTrackRegistry.registerAll(restored);
        int before = restored.getAllTracks().size();
        restored.applyPersistedState(state);
        assertEquals(before, restored.getAllTracks().size());
        assertTrue(restored.getTrack("vanilla:game").isEmpty());
    }

    @Test void vanillaDiscCountsUnchanged() {
        var state = library.toPersistedState();
        var restored = new MusicLibrary(); VanillaTrackRegistry.registerAll(restored); restored.applyPersistedState(state);
        assertEquals(70, restored.getAllTracks().stream().filter(t->t.getSourceType()==SourceType.VANILLA).count());
        assertEquals(22, restored.getAllTracks().stream().filter(t->t.getSourceType()==SourceType.MUSIC_DISC).count());
    }
}
