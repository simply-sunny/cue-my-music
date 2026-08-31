package com.cuemymusic;

import com.cuemymusic.data.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MusicDirector candidate selection logic.
 * Uses a lightweight standalone selector that mirrors MusicDirector.getEligibleCandidates
 * but operates directly on MusicLibrary so tests compile without MinecraftClient.
 */
public class PlaybackDirectorTest {

    private MusicLibrary library;

    @BeforeEach
    void setUp() {
        library = new MusicLibrary();
    }

    private MusicTrack vanilla(String id, String title, boolean c418) {
        MusicTrack t = new MusicTrack(id, title, "Composer", SourceType.VANILLA);
        t.setSourceId(c418 ? "minecraft:music.game." + id + ".c418" : "minecraft:music.game." + id);
        t.setEnabled(true);
        t.setAmbientEligible(true);
        return t;
    }

    private MusicTrack disc(String id, String title) {
        MusicTrack t = new MusicTrack(id, title, "C418", SourceType.MUSIC_DISC);
        t.setSourceId("minecraft:music_disc." + id);
        t.setEnabled(true);
        t.setAmbientEligible(false);
        return t;
    }

    private MusicTrack local(String id, String path) {
        MusicTrack t = new MusicTrack(id, titleFromId(id), "Local", SourceType.LOCAL_GENERIC);
        t.setLocalAudioPath(path);
        t.setSourceId(path);
        t.setEnabled(true);
        t.setAmbientEligible(true);
        return t;
    }

    private String titleFromId(String id) { return id.replace(".", " "); }

    // ---- minimal selector mirroring MusicDirector logic ----

    private List<MusicTrack> getEligibleCandidates(MusicLibrary lib, boolean forAmbient) {
        String presetId = lib.getActivePresetId();
        List<MusicTrack> base = lib.getTracksForPreset(presetId);
        if (base.isEmpty()) base = lib.getAllTracks();
        return base.stream()
                .filter(MusicTrack::isEnabled)
                .filter(t -> !forAmbient || t.isAmbientEligible())
                .filter(t -> !t.requiresLocalFile() || (t.getLocalAudioPath() != null && !t.getLocalAudioPath().isBlank() && !isAbsoluteMissing(t)))
                .toList();
    }

    private boolean isAbsoluteMissing(MusicTrack t) {
        String p = t.getLocalAudioPath();
        if (p == null || p.isBlank()) return true;
        try {
            java.nio.file.Path path = java.nio.file.Path.of(p);
            if (path.isAbsolute()) return !java.nio.file.Files.exists(path);
        } catch (Exception ignored) {}
        return false;
    }

    private Optional<MusicTrack> chooseNextTrack(MusicLibrary lib, boolean forAmbient) {
        var c = getEligibleCandidates(lib, forAmbient);
        if (c.isEmpty()) return Optional.empty();
        return Optional.of(c.get(0));
    }

    @Test
    void testSetupLibraryWithVanillaAndLocalPresets() {
        library.addOrReplaceTrack(vanilla("v1", "Calm", true));
        library.addOrReplaceTrack(vanilla("v2", "Aerie", false));
        library.addOrReplaceTrack(local("local.a", "local_a.ogg"));
        library.addOrReplaceTrack(local("local.b", "local_b.ogg"));

        // add to my_mix
        var preset = library.getPreset("my_mix").orElseThrow();
        preset.addTrack("v1");
        preset.addTrack("local.a");
        library.setActivePresetId("my_mix");

        var candidates = getEligibleCandidates(library, false);
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(t -> t.getId().equals("v1")));
        assertTrue(candidates.stream().anyMatch(t -> t.getId().equals("local.a")));
        assertFalse(candidates.stream().anyMatch(t -> t.getId().equals("v2")));
        assertFalse(candidates.stream().anyMatch(t -> t.getId().equals("local.b")));
    }

    @Test
    void testDisabledTracksNotChosen() {
        library.addOrReplaceTrack(vanilla("v1", "Calm", true));
        library.addOrReplaceTrack(vanilla("v2", "Hal", true));
        var disabled = vanilla("v3", "Danny", true);
        disabled.setEnabled(false);
        library.addOrReplaceTrack(disabled);

        // preset contains all
        var preset = library.getPreset("my_mix").orElseThrow();
        preset.addTrack("v1"); preset.addTrack("v2"); preset.addTrack("v3");
        library.setActivePresetId("my_mix");

        var candidates = getEligibleCandidates(library, false);
        assertEquals(2, candidates.size());
        assertFalse(candidates.stream().anyMatch(t -> t.getId().equals("v3")));

        // choose should never pick disabled
        for (int i = 0; i < 10; i++) {
            var chosen = chooseNextTrack(library, false);
            assertTrue(chosen.isPresent());
            assertNotEquals("v3", chosen.get().getId());
        }
    }

    @Test
    void testAmbientIneligibleNotChosenForAmbient() {
        var ambientOk = vanilla("v1", "Calm", true);
        ambientOk.setAmbientEligible(true);
        var notAmbient = vanilla("v2", "NotAmbient", true);
        notAmbient.setAmbientEligible(false);
        var discTrack = disc("d1", "13"); // discs not ambient by default
        library.addOrReplaceTrack(ambientOk);
        library.addOrReplaceTrack(notAmbient);
        library.addOrReplaceTrack(discTrack);

        var preset = library.getPreset("my_mix").orElseThrow();
        preset.addTrack("v1"); preset.addTrack("v2"); preset.addTrack("d1");
        library.setActivePresetId("my_mix");

        var ambientCandidates = getEligibleCandidates(library, true);
        assertEquals(1, ambientCandidates.size());
        assertEquals("v1", ambientCandidates.get(0).getId());

        var anyCandidates = getEligibleCandidates(library, false);
        assertEquals(3, anyCandidates.size());
    }

    @Test
    void testFallbackWhenPresetEmpty() {
        library.addOrReplaceTrack(vanilla("v1", "Calm", true));
        library.addOrReplaceTrack(disc("d1", "13"));
        // my_mix is empty by default (we cleared preset)
        var preset = library.getPreset("my_mix").orElseThrow();
        preset.getTrackIds().clear();
        library.setActivePresetId("my_mix");

        var candidates = getEligibleCandidates(library, false);
        // fallback to all tracks
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(t -> t.getId().equals("v1")));
        assertTrue(candidates.stream().anyMatch(t -> t.getId().equals("d1")));

        // also test built-in fallback: if active preset is custom empty preset without tracks
        MusicPreset emptyCustom = new MusicPreset("empty_custom", "Empty", false);
        library.addOrReplacePreset(emptyCustom);
        library.setActivePresetId("empty_custom");
        var fallback2 = getEligibleCandidates(library, false);
        assertEquals(2, fallback2.size());
    }

    @Test
    void testMissingFileSkip() {
        var goodLocal = local("local.good", "/tmp/good.ogg"); // relative-like? actually absolute but file may not exist - use relative to pass
        goodLocal.setLocalAudioPath("relative/good.ogg");
        goodLocal.setSourceId("relative/good.ogg");
        var missingLocal = local("local.missing", null);
        missingLocal.setLocalAudioPath(null);
        var blankLocal = local("local.blank", "   ");
        blankLocal.setLocalAudioPath("   ");
        var absoluteMissing = local("local.absmissing", "/tmp/does_not_exist_cue_my_music_test_12345.ogg");

        library.addOrReplaceTrack(goodLocal);
        library.addOrReplaceTrack(missingLocal);
        library.addOrReplaceTrack(blankLocal);
        library.addOrReplaceTrack(absoluteMissing);
        library.addOrReplaceTrack(vanilla("v1", "Calm", true));

        var preset = library.getPreset("my_mix").orElseThrow();
        preset.addTrack("local.good");
        preset.addTrack("local.missing");
        preset.addTrack("local.blank");
        preset.addTrack("local.absmissing");
        preset.addTrack("v1");
        library.setActivePresetId("my_mix");

        var candidates = getEligibleCandidates(library, false);
        // only goodLocal and vanilla should remain
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(t -> t.getId().equals("local.good")));
        assertTrue(candidates.stream().anyMatch(t -> t.getId().equals("v1")));
        assertFalse(candidates.stream().anyMatch(t -> t.getId().equals("local.missing")));
        assertFalse(candidates.stream().anyMatch(t -> t.getId().equals("local.blank")));
        assertFalse(candidates.stream().anyMatch(t -> t.getId().equals("local.absmissing")));
    }

    @Test
    void testChooseNextTrackEmptyReturnsEmpty() {
        MusicPreset empty = new MusicPreset("empty", "Empty", false);
        library.addOrReplacePreset(empty);
        library.setActivePresetId("empty");
        // no tracks at all
        assertTrue(getEligibleCandidates(library, false).isEmpty());
        assertTrue(chooseNextTrack(library, false).isEmpty());
    }
}
