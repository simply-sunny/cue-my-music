package com.cuemymusic;

import com.cuemymusic.data.*;
import com.cuemymusic.persistence.PersistedLibraryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MusicLibraryTest {

    private MusicLibrary library;

    @BeforeEach
    void setUp() {
        library = new MusicLibrary();
        // start clean but keep built-in presets
    }

    private MusicTrack vanillaTrack(String id, String title) {
        MusicTrack t = new MusicTrack(id, title, "C418", SourceType.VANILLA);
        t.setSourceId("minecraft:music.game." + id + ".c418");
        t.setEnabled(true);
        t.setAmbientEligible(true);
        return t;
    }

    private MusicTrack discTrack(String id, String title) {
        MusicTrack t = new MusicTrack(id, title, "C418", SourceType.MUSIC_DISC);
        t.setSourceId("minecraft:music_disc." + id);
        t.setEnabled(true);
        t.setAmbientEligible(false);
        return t;
    }

    private MusicTrack localTrack(String id, String path) {
        MusicTrack t = new MusicTrack(id, "Local " + id, "Local Artist", SourceType.LOCAL_GENERIC);
        t.setLocalAudioPath(path);
        t.setSourceId(path);
        t.setEnabled(true);
        t.setAmbientEligible(true);
        return t;
    }

    @Test
    void testAddAndRetrieveTrack() {
        MusicTrack t = vanillaTrack("vanilla.test.calm", "Calm");
        library.addOrReplaceTrack(t);
        var found = library.getTrack(t.getId());
        assertTrue(found.isPresent());
        assertEquals("Calm", found.get().getTitle());
        assertEquals(1, library.getAllTracks().stream().filter(x -> x.getId().equals(t.getId())).count());

        // replace
        MusicTrack t2 = new MusicTrack(t.getId(), "Calm Remix", "C418", SourceType.VANILLA);
        t2.setSourceId(t.getSourceId());
        library.addOrReplaceTrack(t2);
        assertEquals("Calm Remix", library.getTrack(t.getId()).orElseThrow().getTitle());
    }

    @Test
    void testVanillaAndDiscCollections() {
        library.addOrReplaceTrack(vanillaTrack("v1", "Aria"));
        library.addOrReplaceTrack(discTrack("d1", "13"));
        library.addOrReplaceTrack(discTrack("d2", "Cat"));
        library.addOrReplaceTrack(localTrack("l1", "/tmp/a.ogg"));

        var vanilla = library.getTracksForCollection(MusicCollection.VANILLA);
        assertEquals(1, vanilla.size());
        assertTrue(vanilla.stream().allMatch(t -> t.getSourceType() == SourceType.VANILLA));

        var discs = library.getTracksForCollection(MusicCollection.MUSIC_DISCS);
        assertEquals(2, discs.size());
        assertTrue(discs.stream().allMatch(t -> t.getSourceType() == SourceType.MUSIC_DISC));

        var all = library.getTracksForCollection(MusicCollection.ALL);
        assertEquals(4, all.size());

        var local = library.getTracksForCollection(MusicCollection.LOCAL);
        assertEquals(1, local.size());
    }

    @Test
    void testPresetMembership() {
        library.addOrReplaceTrack(vanillaTrack("v1", "Aria"));
        library.addOrReplaceTrack(vanillaTrack("v2", "Danny"));
        library.addOrReplaceTrack(discTrack("d1", "13"));

        MusicPreset myMix = library.getPreset("my_mix").orElseThrow();
        myMix.addTrack("v1");
        myMix.addTrack("d1");

        var tracks = library.getTracksForPreset("my_mix");
        assertEquals(2, tracks.size());
        assertTrue(tracks.stream().anyMatch(t -> t.getId().equals("v1")));
        assertTrue(tracks.stream().anyMatch(t -> t.getId().equals("d1")));

        myMix.removeTrack("v1");
        var after = library.getTracksForPreset("my_mix");
        assertEquals(1, after.size());
        assertFalse(after.stream().anyMatch(t -> t.getId().equals("v1")));
    }

    @Test
    void testEnabledAmbientFiltering() {
        var t1 = vanillaTrack("v1", "EnabledAmbient");
        t1.setEnabled(true); t1.setAmbientEligible(true);
        var t2 = vanillaTrack("v2", "Disabled");
        t2.setEnabled(false); t2.setAmbientEligible(true);
        var t3 = vanillaTrack("v3", "NotAmbient");
        t3.setEnabled(true); t3.setAmbientEligible(false);
        var t4 = localTrack("l1", "/tmp/exists.ogg");
        t4.setEnabled(true); t4.setAmbientEligible(true);

        var input = List.of(t1, t2, t3, t4);

        var enabledOnly = library.filter(input, null, true, false, null);
        assertEquals(3, enabledOnly.size());
        assertFalse(enabledOnly.stream().anyMatch(t -> t.getId().equals("v2")));

        var ambientOnly = library.filter(input, null, false, true, null);
        assertEquals(3, ambientOnly.size());
        assertFalse(ambientOnly.stream().anyMatch(t -> t.getId().equals("v3")));

        var both = library.filter(input, null, true, true, null);
        assertEquals(2, both.size());
        assertTrue(both.stream().anyMatch(t -> t.getId().equals("v1")));
        assertTrue(both.stream().anyMatch(t -> t.getId().equals("l1")));
    }

    @Test
    void testSearchFiltering() {
        library.addOrReplaceTrack(vanillaTrack("v_aria", "Aria Math"));
        library.addOrReplaceTrack(vanillaTrack("v_danny", "Danny"));
        library.addOrReplaceTrack(discTrack("d_cat", "Cat"));

        var input = library.getAllTracks();

        var searchAria = library.filter(input, "aria", false, false, null);
        assertEquals(1, searchAria.size());
        assertEquals("v_aria", searchAria.get(0).getId());

        var searchCat = library.filter(input, "cat", false, false, null);
        assertTrue(searchCat.stream().anyMatch(t -> t.getId().equals("d_cat")));

        var searchCaseInsensitive = library.filter(input, "ARIA", false, false, null);
        assertEquals(1, searchCaseInsensitive.size());

        var searchById = library.filter(input, "v_danny", false, false, null);
        assertEquals(1, searchById.size());

        var noMatch = library.filter(input, "nonexistent_xyz", false, false, null);
        assertTrue(noMatch.isEmpty());

        var emptyQueryReturnsAll = library.filter(input, "", false, false, null);
        assertEquals(input.size(), emptyQueryReturnsAll.size());

        // source filter
        var vanillaOnly = library.filter(input, null, false, false, SourceType.VANILLA);
        assertTrue(vanillaOnly.stream().allMatch(t -> t.getSourceType() == SourceType.VANILLA));
    }

    @Test
    void testPersistedStateRoundTrip() {
        // Direct toPersistedState / applyPersistedState without Gson
        var local = localTrack("local.test1", "/tmp/test1.ogg");
        local.setFavorite(true);
        local.setAmbientEligible(false);
        local.setDurationSeconds(123);
        library.addOrReplaceTrack(local);
        library.addOrReplaceTrack(vanillaTrack("v1", "Calm"));

        // set flags on vanilla track
        library.getTrack("v1").orElseThrow().setEnabled(false);

        MusicPreset custom = new MusicPreset("custom1", "Custom One", false);
        custom.setDescription("My custom");
        custom.addTrack("local.test1");
        custom.addTrack("v1");
        library.addOrReplacePreset(custom);
        library.setActivePresetId("custom1");

        PersistedLibraryState state = library.toPersistedState();
        assertNotNull(state.activePresetId);
        assertEquals("custom1", state.activePresetId);
        assertFalse(state.tracks.isEmpty());
        assertTrue(state.presets.stream().anyMatch(p -> p.id.equals("custom1")));

        // Now create new library, add vanilla tracks as base, then apply persisted state
        MusicLibrary restored = new MusicLibrary();
        restored.addOrReplaceTrack(vanillaTrack("v1", "Calm")); // base vanilla
        restored.applyPersistedState(state);

        assertEquals("custom1", restored.getActivePresetId());
        var restoredLocal = restored.getTrack("local.test1").orElseThrow();
        assertEquals("/tmp/test1.ogg", restoredLocal.getLocalAudioPath());
        assertTrue(restoredLocal.isFavorite());
        assertFalse(restoredLocal.isAmbientEligible());
        assertEquals(123, restoredLocal.getDurationSeconds());

        var restoredVanilla = restored.getTrack("v1").orElseThrow();
        assertFalse(restoredVanilla.isEnabled()); // flag merged

        var restoredPreset = restored.getPreset("custom1").orElseThrow();
        assertTrue(restoredPreset.getTrackIds().contains("local.test1"));
        assertTrue(restoredPreset.getTrackIds().contains("v1"));
    }

    @Test
    void testMissingFileHandling() {
        // Model check
        var missing = localTrack("local.missing", null);
        assertTrue(missing.requiresLocalFile());
        assertFalse(missing.hasLocalPath());

        var blankPath = localTrack("local.blank", "   ");
        assertTrue(blankPath.requiresLocalFile());
        assertFalse(blankPath.hasLocalPath());

        var present = localTrack("local.present", "/tmp/some.ogg");
        assertTrue(present.hasLocalPath());

        var vanilla = vanillaTrack("v1", "Calm");
        assertFalse(vanilla.requiresLocalFile());

        // Library should still store missing tracks
        library.addOrReplaceTrack(missing);
        library.addOrReplaceTrack(vanilla);
        assertTrue(library.getTrack("local.missing").isPresent());
        assertTrue(library.getTrack("v1").isPresent());

        // Filtering doesn't remove missing by itself — director does
        var all = library.getAllTracks();
        assertEquals(2, all.size());

        // Simulate director filtering: missing local files should be excluded from eligible
        // We test the predicate that director would use
        long eligible = all.stream()
                .filter(t -> !t.requiresLocalFile() || t.hasLocalPath())
                .count();
        assertEquals(1, eligible);
        assertTrue(all.stream().filter(t -> !t.requiresLocalFile() || t.hasLocalPath()).anyMatch(t -> t.getId().equals("v1")));
    }
}
