package com.cuemymusic.client.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session-boundary contract: world join/disconnect resets the session seed,
 * forward index, history and any materialized pin. The delay stream derives
 * deterministically from the session seed under a reserved domain.
 */
class MusicDirectorSessionTest {

    @AfterEach void resetSingleton() {
        MusicDirector.getInstance().beginSession(0L);
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
}
