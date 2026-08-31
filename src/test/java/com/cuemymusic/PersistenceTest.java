package com.cuemymusic;

import com.cuemymusic.data.*;
import com.cuemymusic.persistence.PersistedLibraryState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PersistenceTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void testSerializationRoundTripWithGson() {
        MusicLibrary lib = new MusicLibrary();
        MusicTrack local = new MusicTrack("local.test123", "My Song", "Local Artist", SourceType.LOCAL_GENERIC);
        local.setLocalAudioPath("/tmp/my_song.ogg");
        local.setCoverArtPath("/tmp/cover.jpg");
        local.setSourceId("/tmp/my_song.ogg");
        local.setEnabled(false);
        local.setAmbientEligible(false);
        local.setFavorite(true);
        local.setDurationSeconds(210);

        MusicTrack vanilla = new MusicTrack("vanilla.game.calm1.c418", "Calm1", "C418", SourceType.VANILLA);
        vanilla.setSourceId("minecraft:music.game.calm1.c418");
        vanilla.setEnabled(true);
        vanilla.setAmbientEligible(true);

        lib.addOrReplaceTrack(local);
        lib.addOrReplaceTrack(vanilla);

        MusicPreset custom = new MusicPreset("my_custom", "My Custom", false);
        custom.setDescription("A custom preset");
        custom.addTrack(local.getId());
        custom.addTrack(vanilla.getId());
        lib.addOrReplacePreset(custom);
        lib.setActivePresetId("my_custom");

        // to persisted
        PersistedLibraryState state = lib.toPersistedState();
        assertNotNull(state);
        assertEquals("my_custom", state.activePresetId);
        assertEquals(2, state.tracks.size());
        var localPersisted = state.tracks.stream().filter(t -> t.id.equals("local.test123")).findFirst().orElseThrow();
        assertEquals("/tmp/my_song.ogg", localPersisted.localAudioPath);
        assertFalse(localPersisted.enabled);
        assertFalse(localPersisted.ambientEligible);
        assertTrue(localPersisted.favorite);
        assertEquals(210, localPersisted.durationSeconds);

        // Gson round-trip
        String json = GSON.toJson(state);
        assertNotNull(json);
        assertTrue(json.contains("local.test123"));
        assertTrue(json.contains("my_custom"));

        PersistedLibraryState deserialized = GSON.fromJson(json, PersistedLibraryState.class);
        assertNotNull(deserialized);
        assertEquals("my_custom", deserialized.activePresetId);
        assertEquals(2, deserialized.tracks.size());

        // apply to fresh library
        MusicLibrary restored = new MusicLibrary();
        restored.addOrReplaceTrack(new MusicTrack("vanilla.game.calm1.c418", "Calm1", "C418", SourceType.VANILLA) {{
            setSourceId("minecraft:music.game.calm1.c418");
        }});
        restored.applyPersistedState(deserialized);

        assertEquals("my_custom", restored.getActivePresetId());
        var restoredLocal = restored.getTrack("local.test123").orElseThrow();
        assertEquals("My Song", restoredLocal.getTitle());
        assertEquals("/tmp/my_song.ogg", restoredLocal.getLocalAudioPath());
        assertEquals("/tmp/cover.jpg", restoredLocal.getCoverArtPath());
        assertFalse(restoredLocal.isEnabled());
        assertFalse(restoredLocal.isAmbientEligible());
        assertTrue(restoredLocal.isFavorite());
        assertEquals(210, restoredLocal.getDurationSeconds());

        var restoredPreset = restored.getPreset("my_custom").orElseThrow();
        assertEquals("My Custom", restoredPreset.getName());
        assertEquals("A custom preset", restoredPreset.getDescription());
        assertTrue(restoredPreset.getTrackIds().contains("local.test123"));
        assertTrue(restoredPreset.getTrackIds().contains("vanilla.game.calm1.c418"));
    }

    @Test
    void testFlagsPersistedCorrectly() {
        MusicLibrary lib = new MusicLibrary();
        MusicTrack t = new MusicTrack("local.flags", "Flags", "Artist", SourceType.SPOTIFY);
        t.setLocalAudioPath("relative/path.ogg");
        t.setEnabled(true);
        t.setAmbientEligible(true);
        t.setFavorite(false);
        lib.addOrReplaceTrack(t);

        // toggle flags then persist
        t.setEnabled(false);
        t.setFavorite(true);
        t.setAmbientEligible(false);

        PersistedLibraryState state = lib.toPersistedState();
        var pt = state.tracks.stream().filter(x -> x.id.equals("local.flags")).findFirst().orElseThrow();
        assertFalse(pt.enabled);
        assertTrue(pt.favorite);
        assertFalse(pt.ambientEligible);
        assertEquals("SPOTIFY", pt.sourceType);

        MusicLibrary restored = new MusicLibrary();
        restored.addOrReplaceTrack(new MusicTrack("local.flags", "Flags", "Artist", SourceType.SPOTIFY) {{
            setLocalAudioPath("relative/path.ogg");
        }});
        restored.applyPersistedState(state);
        var r = restored.getTrack("local.flags").orElseThrow();
        assertFalse(r.isEnabled());
        assertTrue(r.isFavorite());
        assertFalse(r.isAmbientEligible());
    }

    @Test
    void testVanillaEnabledMerging() {
        MusicLibrary lib = new MusicLibrary();
        MusicTrack v = new MusicTrack("vanilla.game.hal.c418", "Hal", "C418", SourceType.VANILLA);
        v.setSourceId("minecraft:music.game.hal.c418");
        v.setEnabled(true);
        lib.addOrReplaceTrack(v);
        v.setEnabled(false); // user disables
        PersistedLibraryState state = lib.toPersistedState();

        MusicLibrary fresh = new MusicLibrary();
        MusicTrack freshV = new MusicTrack("vanilla.game.hal.c418", "Hal", "C418", SourceType.VANILLA);
        freshV.setSourceId("minecraft:music.game.hal.c418");
        freshV.setEnabled(true);
        fresh.addOrReplaceTrack(freshV);
        assertTrue(fresh.getTrack("vanilla.game.hal.c418").orElseThrow().isEnabled());
        fresh.applyPersistedState(state);
        assertFalse(fresh.getTrack("vanilla.game.hal.c418").orElseThrow().isEnabled());
    }

    @Test
    void testBuiltInPresetsNotDuplicatedUnlessOverridden() {
        MusicLibrary lib = new MusicLibrary();
        // by default built-ins have no explicit trackIds, so they shouldn't be persisted unless overridden
        PersistedLibraryState state = lib.toPersistedState();
        // built-ins with empty trackIds are filtered
        long builtInPersisted = state.presets.stream().filter(p -> p.builtIn).count();
        assertEquals(0, builtInPersisted);

        // now add a track to a built-in
        lib.getPreset("c418_only").orElseThrow().addTrack("some_id");
        PersistedLibraryState state2 = lib.toPersistedState();
        long builtIn2 = state2.presets.stream().filter(p -> p.builtIn).count();
        assertEquals(1, builtIn2);
        assertTrue(state2.presets.stream().anyMatch(p -> p.id.equals("c418_only")));
    }

    @Test
    void testApplyPersistedStateHandlesNullGracefully() {
        MusicLibrary lib = new MusicLibrary();
        int before = lib.getAllTracks().size();
        lib.applyPersistedState(null);
        assertEquals(before, lib.getAllTracks().size());
        // empty state
        lib.applyPersistedState(new PersistedLibraryState());
        assertEquals(before, lib.getAllTracks().size());
    }

    @Test
    void testPersistedStateEqualityViaJson() {
        MusicLibrary lib = new MusicLibrary();
        lib.addOrReplaceTrack(new MusicTrack("local.a", "A", "Artist", SourceType.LOCAL_GENERIC) {{
            setLocalAudioPath("/tmp/a.ogg");
            setSourceId("/tmp/a.ogg");
        }});
        PersistedLibraryState s1 = lib.toPersistedState();
        String j1 = GSON.toJson(s1);
        PersistedLibraryState s2 = GSON.fromJson(j1, PersistedLibraryState.class);
        String j2 = GSON.toJson(s2);
        assertEquals(j1, j2);
    }
}
