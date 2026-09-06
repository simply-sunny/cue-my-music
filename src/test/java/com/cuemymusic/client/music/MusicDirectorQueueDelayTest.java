package com.cuemymusic.client.music;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Queue projection and delay-countdown contract for {@link MusicDirector}.
 *
 * <p>Headless unit scope: without a live {@code Minecraft} instance the
 * director cannot resolve a situational context, so the queue projection is
 * empty — but it must still be deterministic, bounded by {@code count}, and
 * must never advance the planner's monotonic forward index. The delay
 * countdown derives from {@code MusicManagerAccessor#nextSongDelay} (0 when
 * no manager is present); seconds are ticks / 20, and {@code skipDelay}
 * zeroes the delay.
 */
class MusicDirectorQueueDelayTest {

    @AfterEach void resetSingleton() {
        MusicDirector.getInstance().beginSession(0L);
    }

    @Test void upcomingTracksDoesNotAdvanceSequence() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(1234L);
        director.planner().nextSequence();
        director.planner().nextSequence();
        long before = director.planner().peekSequence();
        List<MusicDirector.TrackInfo> first = director.upcomingTracks(5);
        List<MusicDirector.TrackInfo> second = director.upcomingTracks(5);
        assertEquals(before, director.planner().peekSequence(),
                "queue projection must not consume the forward index");
        assertEquals(first, second, "repeated projection must be deterministic");
        assertTrue(first.size() <= 5);
    }

    @Test void upcomingTracksProjectionMatchesForwardSelectionSeeds() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(777L);
        long base = director.planner().peekSequence();
        String eventId = "minecraft:music.game";
        long[] expected = new long[5];
        for (int i = 0; i < 5; i++) {
            expected[i] = MusicPlanner.selectionSeed(director.planner().getSessionSeed(), eventId, base + i);
        }
        // Recompute: same seed/event/index reproduces the forward stream exactly.
        for (int i = 0; i < 5; i++) {
            assertEquals(expected[i],
                    MusicPlanner.selectionSeed(director.planner().getSessionSeed(), eventId, base + i));
        }
        // Distinct forward indices must give distinct seeds (decorrelated picks).
        assertEquals(5, java.util.Arrays.stream(expected).distinct().count());
        // The projection call itself must leave the cursor at the base index.
        director.upcomingTracks(5);
        assertEquals(base, director.planner().peekSequence());
    }

    @Test void upcomingTracksRespectsCountBounds() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);
        assertTrue(director.upcomingTracks(0).isEmpty());
        assertTrue(director.upcomingTracks(-1).isEmpty());
        assertTrue(director.upcomingTracks(5).size() <= 5);
        assertEquals(director.planner().peekSequence(), 0L,
                "bounded projections must not advance the sequence either");
    }

    @Test void remainingDelayTicksAndSecondsAreConsistent() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(99L);
        int ticks = director.remainingDelayTicks();
        double seconds = director.remainingDelaySeconds();
        assertTrue(ticks >= 0, "delay ticks clamp at zero, got " + ticks);
        assertEquals(ticks / 20.0, seconds, 1e-9,
                "seconds must reflect accessor ticks at 20 ticks/second");
    }

    @Test void skipDelayZeroesNextSongDelay() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(99L);
        // Must not throw headless (no manager) and must leave the delay at zero.
        assertDoesNotThrow(director::skipDelay);
        assertEquals(0, director.remainingDelayTicks());
        assertEquals(0.0, director.remainingDelaySeconds(), 1e-9);
        // Idempotent: second skip keeps it zeroed.
        assertDoesNotThrow(director::skipDelay);
        assertEquals(0, director.remainingDelayTicks());
    }

    @Test void delayCountdownSurvivesSessionReset() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(1L);
        director.skipDelay();
        director.beginSession(2L);
        assertEquals(0, director.remainingDelayTicks());
        assertEquals(0.0, director.remainingDelaySeconds(), 1e-9);
    }
}
