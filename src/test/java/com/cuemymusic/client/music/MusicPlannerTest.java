package com.cuemymusic.client.music;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core deterministic planner contract (approved minimal design):
 * session seed + context event + monotonic index determine choices;
 * bounded history of 8; previous scans by actual file membership and
 * never rewinds forward progress.
 */
class MusicPlannerTest {

    private MusicPlanner planner(long seed) {
        MusicPlanner planner = new MusicPlanner();
        planner.setSessionSeed(seed);
        return planner;
    }

    @Test void sameSeedEventAndIndexGiveSameSelectionSeed() {
        MusicPlanner a = planner(1234L);
        MusicPlanner b = planner(1234L);
        assertEquals(
                MusicPlanner.selectionSeed(a.getSessionSeed(), "minecraft:music.game", 7),
                MusicPlanner.selectionSeed(b.getSessionSeed(), "minecraft:music.game", 7));
    }

    @Test void thirtyForwardSelectionsAllDeterministicAndDistinct() {
        MusicPlanner a = planner(99L);
        MusicPlanner b = planner(99L);
        Set<Long> seeds = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            long indexA = a.nextSequence();
            long indexB = b.nextSequence();
            assertEquals(indexA, indexB);
            long seedA = MusicPlanner.selectionSeed(a.getSessionSeed(), "minecraft:music.game", indexA);
            long seedB = MusicPlanner.selectionSeed(b.getSessionSeed(), "minecraft:music.game", indexB);
            assertEquals(seedA, seedB);
            seeds.add(seedA);
        }
        assertEquals(30, seeds.size(), "30 monotonic indices must give 30 distinct selection seeds");
        assertEquals(30, a.peekSequence());
    }

    @Test void contextChangeGivesDifferentDeterministicStream() {
        MusicPlanner a = planner(7L);
        long overworld = MusicPlanner.selectionSeed(a.getSessionSeed(), "minecraft:music.game", 0);
        long nether = MusicPlanner.selectionSeed(a.getSessionSeed(), "minecraft:music.nether", 0);
        assertNotEquals(overworld, nether);
        // Returning to the first context at the same index reproduces its seed.
        assertEquals(overworld, MusicPlanner.selectionSeed(a.getSessionSeed(), "minecraft:music.game", 0));
    }

    @Test void historyIsBoundedToEightNewestFirst() {
        MusicPlanner planner = planner(1L);
        for (int i = 0; i < 10; i++) {
            planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/track" + i));
        }
        List<MusicPlanner.Entry> snapshot = planner.historySnapshot();
        assertEquals(8, snapshot.size());
        assertEquals("music/game/track9", snapshot.get(0).filePath());
        assertEquals("music/game/track2", snapshot.get(7).filePath());
    }

    @Test void previousScansNewestFirstByFileMembership() {
        MusicPlanner planner = planner(1L);
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/b"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/c"));
        // c is current (cursor 0); previous must skip it and return b.
        Optional<MusicPlanner.Entry> prev = planner.previousIn(Set.of("music/game/a", "music/game/b", "music/game/c"));
        assertEquals(Optional.of(new MusicPlanner.Entry("minecraft:music.game", "music/game/b")), prev);
    }

    @Test void previousSkipsInvalidFilesEvenAcrossEvents() {
        MusicPlanner planner = planner(1L);
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/old"));
        planner.record(new MusicPlanner.Entry("minecraft:music.nether", "music/nether/gone"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/current"));
        // Only the oldest file is eligible in the current context, even though it
        // was recorded under a different event; stale newer entries are skipped.
        Optional<MusicPlanner.Entry> prev = planner.previousIn(Set.of("music/game/old"));
        assertEquals(Optional.of(new MusicPlanner.Entry("minecraft:music.game", "music/game/old")), prev);
    }

    @Test void previousWithNoEligibleEntryIsEmptyAndKeepsCursor() {
        MusicPlanner planner = planner(1L);
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        assertTrue(planner.previousIn(Set.of("music/game/missing")).isEmpty());
        assertEquals(0, planner.getCursor());
    }

    @Test void previousDoesNotRewindForwardProgress() {
        MusicPlanner planner = planner(1L);
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/b"));
        planner.previousIn(Set.of("music/game/a", "music/game/b"));
        assertEquals(1, planner.getCursor());
        long next = planner.nextSequence();
        assertEquals(0, next, "previous must not consume the monotonic forward index");
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/c"));
        assertEquals(0, planner.getCursor(), "forward play resets the back-scan cursor");
        assertEquals(List.of(
                new MusicPlanner.Entry("minecraft:music.game", "music/game/c"),
                new MusicPlanner.Entry("minecraft:music.game", "music/game/b"),
                new MusicPlanner.Entry("minecraft:music.game", "music/game/a")),
                planner.historySnapshot());
    }

    @Test void consecutivePreviousWalksFurtherBack() {
        MusicPlanner planner = planner(1L);
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/b"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/c"));
        Set<String> all = Set.of("music/game/a", "music/game/b", "music/game/c");
        assertEquals("music/game/b", planner.previousIn(all).orElseThrow().filePath());
        assertEquals("music/game/a", planner.previousIn(all).orElseThrow().filePath());
        assertTrue(planner.previousIn(all).isEmpty());
    }

    @Test void pruneDropsStaleReferencesAndClampsCursor() {
        MusicPlanner planner = planner(1L);
        planner.record(new MusicPlanner.Entry("gone:event", "music/gone/x"));
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/a"));
        planner.record(new MusicPlanner.Entry("gone:event", "music/gone/y"));
        planner.prune(entry -> entry.eventId().equals("minecraft:music.game"));
        assertEquals(List.of(new MusicPlanner.Entry("minecraft:music.game", "music/game/a")),
                planner.historySnapshot());
    }
}
