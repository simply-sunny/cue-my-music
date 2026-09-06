package com.cuemymusic.client.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audible-playback clock contract: position reflects wall-clock time between
 * the real channel play/pause transitions, never the decoder's queued
 * read-ahead. Pause freezes the position; resume continues from it; a seek
 * retargets the offset while preserving the paused state.
 */
class TransportClockTest {

    @Test void positionAdvancesFromAudibleStart() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(0.0, 1_000_000_000L);
        assertEquals(5.0, clock.positionSeconds(6_000_000_000L, Double.NaN), 1e-9);
    }

    @Test void positionIncludesStartOffset() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(30.0, 0L);
        assertEquals(32.5, clock.positionSeconds(2_500_000_000L, Double.NaN), 1e-9);
    }

    @Test void pauseFreezesPosition() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(10.0, 0L);
        clock.notePaused(4_000_000_000L);
        assertTrue(clock.isPaused());
        assertEquals(14.0, clock.positionSeconds(4_000_000_000L, Double.NaN), 1e-9);
        // Much later wall-clock: still frozen.
        assertEquals(14.0, clock.positionSeconds(400_000_000_000L, Double.NaN), 1e-9);
    }

    @Test void resumeContinuesFromFrozenPosition() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(10.0, 0L);
        clock.notePaused(4_000_000_000L);
        clock.noteResumed(100_000_000_000L);
        assertFalse(clock.isPaused());
        assertEquals(15.0, clock.positionSeconds(101_000_000_000L, Double.NaN), 1e-9);
    }

    @Test void pauseWithoutStartStaysAtOffset() {
        TransportClock clock = new TransportClock();
        clock.notePaused(5_000_000_000L);
        assertEquals(0.0, clock.positionSeconds(9_000_000_000L, Double.NaN), 1e-9);
    }

    @Test void retargetPreservesPausedState() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(10.0, 0L);
        clock.notePaused(4_000_000_000L);
        clock.retarget(60.0, 200_000_000_000L);
        assertTrue(clock.isPaused(), "seek must not resume a user-paused song");
        assertEquals(60.0, clock.positionSeconds(999_000_000_000L, Double.NaN), 1e-9);
    }

    @Test void retargetWhileRunningKeepsRunning() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(10.0, 0L);
        clock.retarget(60.0, 4_000_000_000L);
        assertFalse(clock.isPaused());
        assertEquals(62.0, clock.positionSeconds(6_000_000_000L, Double.NaN), 1e-9);
    }

    @Test void positionClampsToKnownDuration() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(170.0, 0L);
        assertEquals(183.0, clock.positionSeconds(20_000_000_000L, 183.0), 1e-9);
    }

    @Test void positionNeverNegative() {
        TransportClock clock = new TransportClock();
        clock.noteStarted(-5.0, 0L);
        assertEquals(0.0, clock.positionSeconds(0L, Double.NaN), 1e-9);
    }

    @Test void clampSeekTargetKeepsFiniteValuesInsideRange() {
        assertEquals(42.5, TransportClock.clampSeekTarget(42.5, 10.0, 183.0), 1e-9);
        assertEquals(0.0, TransportClock.clampSeekTarget(-3.0, 10.0, 183.0), 1e-9);
        assertEquals(183.0, TransportClock.clampSeekTarget(999.0, 10.0, 183.0), 1e-9);
    }

    @Test void clampSeekTargetWithoutDurationHasNoUpperBound() {
        assertEquals(999.0, TransportClock.clampSeekTarget(999.0, 10.0, Double.NaN), 1e-9);
    }

    @Test void clampSeekTargetRejectsNonFiniteRequests() {
        assertEquals(10.0, TransportClock.clampSeekTarget(Double.NaN, 10.0, 183.0), 1e-9);
        assertEquals(10.0, TransportClock.clampSeekTarget(Double.POSITIVE_INFINITY, 10.0, 183.0), 1e-9);
    }
}
