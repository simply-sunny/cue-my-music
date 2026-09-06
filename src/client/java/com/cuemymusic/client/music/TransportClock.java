package com.cuemymusic.client.music;

/**
 * Audible-playback clock for the Pause-screen transport.
 *
 * <p>Position is wall-clock time between the real native channel
 * transitions ({@code play} anchors it, {@code pause} freezes it,
 * {@code unpause} resumes it), so it reflects what is actually heard —
 * never the several seconds of PCM the native layer has already queued
 * ahead via {@code pumpBuffers}. All times are monotonic nanoseconds from
 * the same source; the class itself takes them as arguments and is pure.
 */
public final class TransportClock {
    private double offsetSeconds;
    private long anchorNanos;
    private boolean anchored;
    private boolean paused;
    private double frozenPosition;

    public TransportClock() {
        this.offsetSeconds = 0.0;
        this.anchorNanos = 0L;
        this.anchored = false;
        this.paused = false;
        this.frozenPosition = 0.0;
    }

    /** Audible start on the native channel at the given stream offset. */
    public synchronized void noteStarted(double offsetSeconds, long nowNanos) {
        this.offsetSeconds = Math.max(0.0, offsetSeconds);
        this.anchorNanos = nowNanos;
        this.anchored = true;
        this.paused = false;
    }

    /** Native channel paused: freeze the position at this instant. */
    public synchronized void notePaused(long nowNanos) {
        if (paused) {
            return;
        }
        frozenPosition = rawPosition(nowNanos, Double.NaN);
        paused = true;
    }

    /** Native channel resumed: continue from the frozen position. */
    public synchronized void noteResumed(long nowNanos) {
        if (!paused) {
            return;
        }
        offsetSeconds = frozenPosition;
        anchorNanos = nowNanos;
        anchored = true;
        paused = false;
    }

    /**
     * Seek retarget: move the offset without touching the paused state, so
     * a user-paused song stays paused through the reopen. A running song
     * keeps running from the new offset.
     */
    public synchronized void retarget(double offsetSeconds, long nowNanos) {
        double clamped = Math.max(0.0, offsetSeconds);
        if (paused) {
            frozenPosition = clamped;
        } else {
            this.offsetSeconds = clamped;
            this.anchorNanos = nowNanos;
            this.anchored = true;
        }
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /**
     * Current audible position in seconds, clamped to {@code [0, duration]}
     * when the duration is known, or {@code [0, +inf)} when unknown.
     */
    public synchronized double positionSeconds(long nowNanos, double durationSeconds) {
        return clamp(rawPosition(nowNanos, durationSeconds), durationSeconds);
    }

    private double rawPosition(long nowNanos, double durationSeconds) {
        if (paused) {
            return clamp(frozenPosition, durationSeconds);
        }
        if (!anchored) {
            return clamp(offsetSeconds, durationSeconds);
        }
        double elapsed = (nowNanos - anchorNanos) / 1_000_000_000.0;
        return clamp(offsetSeconds + Math.max(0.0, elapsed), durationSeconds);
    }

    private static double clamp(double value, double durationSeconds) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        double floored = Math.max(0.0, value);
        if (!Double.isNaN(durationSeconds) && !Double.isInfinite(durationSeconds)) {
            return Math.min(floored, Math.max(0.0, durationSeconds));
        }
        return floored;
    }

    /**
     * Fresh start: no anchor, no offset, running. The sound-thread hooks
     * and the client thread both touch the clock, so every accessor is
     * synchronized and values stay coherent.
     */
    public synchronized void reset() {
        offsetSeconds = 0.0;
        anchorNanos = 0L;
        anchored = false;
        paused = false;
        frozenPosition = 0.0;
    }

    /**
     * Clamps a raw seek request to finite values inside the playable range.
     * Non-finite requests keep the current position (no jump); unknown
     * duration ({@code NaN}) leaves the upper end open instead of guessing.
     */
    public static double clampSeekTarget(double requestedSeconds, double currentSeconds, double durationSeconds) {
        if (Double.isNaN(requestedSeconds) || Double.isInfinite(requestedSeconds)) {
            return currentSeconds;
        }
        double floored = Math.max(0.0, requestedSeconds);
        if (!Double.isNaN(durationSeconds) && !Double.isInfinite(durationSeconds)) {
            return Math.min(floored, Math.max(0.0, durationSeconds));
        }
        return floored;
    }
}
