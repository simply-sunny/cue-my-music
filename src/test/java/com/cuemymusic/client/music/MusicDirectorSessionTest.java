package com.cuemymusic.client.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session-boundary contract: world join/disconnect resets the session seed,
 * forward index, history and any materialized pin. The delay stream derives
 * deterministically from the session seed under a reserved domain.
 */
class MusicDirectorSessionTest {

    @AfterEach void resetSingleton() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(0L);
        director.setWeightingConfig(TrackWeightConfig.defaults());
        director.setWeightedCatalog(WeightedMusicCatalog.empty());
        director.setWeightingPath(null);
    }

    @Test void saveFailureLeavesActiveConfigUnchanged(@TempDir Path tempDir) throws IOException {
        MusicDirector director = MusicDirector.getInstance();
        TrackWeightConfig active = TrackWeightConfig.defaults().withAntiRepeat(false);
        director.setWeightingConfig(active);

        Path existingFile = tempDir.resolve("not_a_dir");
        Files.writeString(existingFile, "blocking_file");
        Path impossiblePath = existingFile.resolve("nested/cue-my-music.json");
        director.initializeWeighting(impossiblePath);
        director.setWeightingConfig(active);

        TrackWeightConfig draft = TrackWeightConfig.defaults().withAntiRepeat(true);
        assertThrows(IOException.class, () -> director.saveWeightingConfig(draft));
        assertSame(active, director.weightingConfig());
    }

    @Test void sessionResetClearsHistoryUsedByAntiRepeatButRetainsConfig() {
        MusicDirector director = MusicDirector.getInstance();
        TrackWeightConfig custom = TrackWeightConfig.defaults().withAntiRepeat(true)
                .withMultiplier("minecraft:music.game", "minecraft:music/game/sweden", 2.0);
        director.setWeightingConfig(custom);

        director.beginSession(111L);
        director.planner().nextSequence();
        director.planner().record(new MusicPlanner.Entry("minecraft:music.game", "minecraft:music/game/sweden", 0L));
        assertFalse(director.planner().historySnapshot().isEmpty());

        director.beginSession(222L);
        assertEquals(0, director.planner().peekSequence());
        assertTrue(director.planner().historySnapshot().isEmpty());
        assertSame(custom, director.weightingConfig());

        director.planner().record(new MusicPlanner.Entry("minecraft:music.game", "minecraft:music/game/sweden", 0L));
        director.endSession();
        assertTrue(director.planner().historySnapshot().isEmpty());
        assertSame(custom, director.weightingConfig());
    }

    @Test void reloadReplacesCatalogAndPreservesConfig() {
        MusicDirector director = MusicDirector.getInstance();
        TrackWeightConfig custom = TrackWeightConfig.defaults().withAntiRepeat(false);
        director.setWeightingConfig(custom);

        WeightedMusicCatalog catalogA = WeightedMusicCatalog.empty();
        director.setWeightedCatalog(catalogA);
        assertSame(catalogA, director.weightedCatalog());

        director.reloadWeightedCatalog(null);
        assertNotSame(catalogA, director.weightedCatalog());
        assertTrue(director.weightedCatalog().pools().isEmpty());
        assertSame(custom, director.weightingConfig());
    }

    @Test void beginSessionResetsIndexAndHistory() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(111L);
        director.planner().nextSequence();
        director.planner().nextSequence();
        director.planner().record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        director.beginSession(222L);
        assertEquals(0, director.planner().peekSequence());
        assertTrue(director.planner().historySnapshot().isEmpty());
        assertEquals(0, director.planner().getCursor());
    }

    @Test void sessionSeedIsAdopted() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(555L);
        assertEquals(555L, director.planner().getSessionSeed());
    }

    @Test void endSessionClearsForwardProgress() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(1L);
        director.planner().nextSequence();
        director.planner().record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        director.endSession();
        assertEquals(0, director.planner().peekSequence());
        assertTrue(director.planner().historySnapshot().isEmpty());
    }

    @Test void delaySeedIsDeterministicAndSeparateFromSelection() {
        assertEquals(MusicDirector.delaySeed(42L), MusicDirector.delaySeed(42L));
        assertNotEquals(MusicDirector.delaySeed(42L), MusicDirector.delaySeed(43L));
        long selection = MusicPlanner.selectionSeed(42L, "minecraft:music.game", 0);
        assertNotEquals(selection, MusicDirector.delaySeed(42L));
    }

    @Test void transportStatusDistinguishesLifecycleStates() {
        assertEquals(MusicDirector.PlaybackStatus.NO_TRACK,
                MusicDirector.statusFor(false, false, false, false, 0));
        assertEquals(MusicDirector.PlaybackStatus.COOLDOWN,
                MusicDirector.statusFor(false, false, false, false, 20));
        assertEquals(MusicDirector.PlaybackStatus.LOADING,
                MusicDirector.statusFor(true, false, false, false, 0));
        assertEquals(MusicDirector.PlaybackStatus.PLAYING,
                MusicDirector.statusFor(true, true, true, false, 0));
        assertEquals(MusicDirector.PlaybackStatus.PAUSED,
                MusicDirector.statusFor(true, true, true, true, 0));
        assertEquals(MusicDirector.PlaybackStatus.COOLDOWN,
                MusicDirector.statusFor(true, true, false, false, 20));
    }
}
