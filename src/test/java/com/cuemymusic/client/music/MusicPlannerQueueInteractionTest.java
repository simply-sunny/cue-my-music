package com.cuemymusic.client.music;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies how Previous, Next, and Context changes update the upcoming queue
 * sequence deterministically.
 */
class MusicPlannerQueueInteractionTest {

    @Test
    void forwardPlaysAdvanceUpcomingQueuePointer() {
        MusicPlanner planner = new MusicPlanner();
        planner.setSessionSeed(12345L);

        assertEquals(0, planner.peekSequence("overworld"));

        // Play 0
        long idx0 = planner.nextSequence("overworld");
        assertEquals(0, idx0);
        planner.record(new MusicPlanner.Entry("overworld", "track0.ogg", idx0));
        assertEquals(1, planner.peekSequence("overworld"));

        // Play 1
        long idx1 = planner.nextSequence("overworld");
        assertEquals(1, idx1);
        planner.record(new MusicPlanner.Entry("overworld", "track1.ogg", idx1));
        assertEquals(2, planner.peekSequence("overworld"));

        // Play 2
        long idx2 = planner.nextSequence("overworld");
        assertEquals(2, idx2);
        planner.record(new MusicPlanner.Entry("overworld", "track2.ogg", idx2));
        assertEquals(3, planner.peekSequence("overworld"));
    }

    @Test
    void previousUpdatesUpcomingQueueToFollowHistoricalTrack() {
        MusicPlanner planner = new MusicPlanner();
        planner.setSessionSeed(42L);

        // Play 4 tracks: 0, 1, 2, 3
        for (int i = 0; i < 4; i++) {
            long idx = planner.nextSequence("overworld");
            planner.record(new MusicPlanner.Entry("overworld", "track" + i + ".ogg", idx));
        }

        assertEquals(4, planner.peekSequence("overworld"));

        Set<String> eligible = Set.of("track0.ogg", "track1.ogg", "track2.ogg", "track3.ogg");

        // 1st Previous -> Track 2 (index 2)
        Optional<MusicPlanner.Entry> prev1 = planner.previousIn(eligible);
        assertTrue(prev1.isPresent());
        assertEquals("track2.ogg", prev1.get().filePath());
        assertEquals(2, prev1.get().index());
        // Upcoming queue now points to index 3 (the track after track 2)
        assertEquals(3, planner.peekSequence("overworld"));

        // 2nd Previous -> Track 1 (index 1)
        Optional<MusicPlanner.Entry> prev2 = planner.previousIn(eligible);
        assertTrue(prev2.isPresent());
        assertEquals("track1.ogg", prev2.get().filePath());
        assertEquals(1, prev2.get().index());
        // Upcoming queue now points to index 2 (the track after track 1)
        assertEquals(2, planner.peekSequence("overworld"));

        // 3rd Previous -> Track 0 (index 0)
        Optional<MusicPlanner.Entry> prev3 = planner.previousIn(eligible);
        assertTrue(prev3.isPresent());
        assertEquals("track0.ogg", prev3.get().filePath());
        assertEquals(0, prev3.get().index());
        // Upcoming queue now points to index 1 (the track after track 0)
        assertEquals(1, planner.peekSequence("overworld"));

        // No more history
        Optional<MusicPlanner.Entry> prev4 = planner.previousIn(eligible);
        assertTrue(prev4.isEmpty());

        // Stepping Next from Track 0 claims index 1 (Track 1)
        long nextIdx = planner.nextSequence("overworld");
        assertEquals(1, nextIdx);
        // Sequence advances to 2
        assertEquals(2, planner.peekSequence("overworld"));
    }

    @Test
    void contextSwitchIsolatesUpcomingSequencesAndHistory() {
        MusicPlanner planner = new MusicPlanner();
        planner.setSessionSeed(999L);

        String overworld = "minecraft:music.game";
        String nether = "minecraft:music.nether.nether_wastes";

        // Play 3 Overworld tracks
        for (int i = 0; i < 3; i++) {
            long idx = planner.nextSequence(overworld);
            planner.record(new MusicPlanner.Entry(overworld, "overworld_" + i + ".ogg", idx));
        }
        assertEquals(3, planner.peekSequence(overworld));
        assertEquals(0, planner.peekSequence(nether));

        // Player enters Nether: previous Overworld tracks are not eligible in Nether
        Set<String> netherEligible = Set.of("rubedo.ogg", "chrysopoeia.ogg");
        assertFalse(planner.hasPreviousIn(netherEligible));
        assertTrue(planner.previousIn(netherEligible).isEmpty());

        // Play 2 Nether tracks
        long n0 = planner.nextSequence(nether);
        planner.record(new MusicPlanner.Entry(nether, "rubedo.ogg", n0));
        long n1 = planner.nextSequence(nether);
        planner.record(new MusicPlanner.Entry(nether, "chrysopoeia.ogg", n1));

        assertEquals(2, planner.peekSequence(nether));
        assertEquals(3, planner.peekSequence(overworld));

        // Now in Nether, Previous rewinds to rubedo.ogg (index 0)
        Optional<MusicPlanner.Entry> netherPrev = planner.previousIn(netherEligible);
        assertTrue(netherPrev.isPresent());
        assertEquals("rubedo.ogg", netherPrev.get().filePath());
        assertEquals(0, netherPrev.get().index());
        assertEquals(1, planner.peekSequence(nether));

        // Overworld sequence is completely intact
        assertEquals(3, planner.peekSequence(overworld));
    }
}
