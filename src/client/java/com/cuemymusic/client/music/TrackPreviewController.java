package com.cuemymusic.client.music;

import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;

/**
 * Owns track preview playback transport, state machine, duration probing,
 * clock position, seek, and background music pause ownership.
 */
public final class TrackPreviewController implements AutoCloseable {

    public enum State {
        IDLE,
        STARTING,
        PLAYING,
        PAUSED
    }

    public record Snapshot(
            State state,
            String resourceId,
            String title,
            double positionSeconds,
            double durationSeconds,
            boolean canSeek) {
    }

    public interface Backend {
        PinnedMusicInstance play(Pool pool, Track track, long generation, double offsetSeconds);
        void stop(PinnedMusicInstance instance);
        boolean isActive(PinnedMusicInstance instance);
        void setPaused(PinnedMusicInstance instance, boolean paused);
        CompletableFuture<OptionalDouble> duration(Track track, long generation);
        boolean pauseBackground();
        void resumeBackground();
    }

    private record DurationResult(long generation, double durationSeconds) {}

    private final Backend backend;
    private final TransportClock clock = new TransportClock();
    private final AtomicReference<DurationResult> pendingDuration = new AtomicReference<>();

    private State state = State.IDLE;
    private Pool currentPool;
    private Track currentTrack;
    private PinnedMusicInstance currentInstance;
    private long generation = 0L;
    private double currentOffsetSeconds = 0.0;
    private double durationSeconds = Double.NaN;
    private boolean seenActive = false;
    private int startingTicks = 0;
    private boolean pendingPause = false;
    private boolean ownsBackgroundPause = false;

    public TrackPreviewController(Backend backend) {
        this.backend = backend != null ? backend : MusicDirector.getInstance().previewBackend();
    }

    public TrackPreviewController() {
        this(MusicDirector.getInstance().previewBackend());
    }

    public void start(Pool pool, Track track) {
        if (pool == null || track == null) {
            stop();
            return;
        }
        if (currentInstance != null) {
            backend.stop(currentInstance);
            currentInstance = null;
        }
        if (!ownsBackgroundPause) {
            ownsBackgroundPause = backend.pauseBackground();
        }
        generation++;
        long gen = generation;
        currentPool = pool;
        currentTrack = track;
        currentOffsetSeconds = 0.0;
        durationSeconds = Double.NaN;
        pendingDuration.set(null);
        seenActive = false;
        startingTicks = 0;
        pendingPause = false;
        clock.reset();
        state = State.STARTING;

        MusicDirector.getInstance().registerActivePreviewController(this);

        currentInstance = backend.play(pool, track, gen, 0.0);
        backend.duration(track, gen).thenAccept(opt -> {
            if (opt.isPresent()) {
                pendingDuration.set(new DurationResult(gen, opt.getAsDouble()));
            }
        });
    }

    private void pollPendingDuration() {
        DurationResult pending = pendingDuration.get();
        if (pending != null) {
            if (pending.generation() == this.generation) {
                this.durationSeconds = pending.durationSeconds();
            }
            pendingDuration.compareAndSet(pending, null);
        }
    }

    public void tick(long nowNanos) {
        pollPendingDuration();
        if (state == State.IDLE) {
            return;
        }
        boolean active = currentInstance != null && backend.isActive(currentInstance);
        if (!seenActive) {
            startingTicks++;
            if (active) {
                seenActive = true;
                if (pendingPause) {
                    if (currentInstance != null) {
                        backend.setPaused(currentInstance, true);
                    }
                    clock.noteStarted(currentOffsetSeconds, nowNanos);
                    clock.notePaused(nowNanos);
                    state = State.PAUSED;
                } else {
                    clock.noteStarted(currentOffsetSeconds, nowNanos);
                    state = State.PLAYING;
                }
            } else if (startingTicks > 40) {
                stop();
            }
        } else {
            if (!active) {
                stop();
            }
        }
    }

    public void tick() {
        tick(System.nanoTime());
    }

    public void togglePause(long nowNanos) {
        if (state == State.PLAYING) {
            if (currentInstance != null) {
                backend.setPaused(currentInstance, true);
            }
            clock.notePaused(nowNanos);
            state = State.PAUSED;
        } else if (state == State.PAUSED) {
            if (currentInstance != null && seenActive) {
                backend.setPaused(currentInstance, false);
            }
            clock.noteResumed(nowNanos);
            state = seenActive ? State.PLAYING : State.STARTING;
            pendingPause = false;
        } else if (state == State.STARTING) {
            pendingPause = true;
            state = State.PAUSED;
        }
    }

    public void togglePause() {
        togglePause(System.nanoTime());
    }

    public void seek(double seconds, long nowNanos) {
        if (state == State.IDLE || Double.isNaN(durationSeconds) || durationSeconds <= 0.0) {
            return;
        }
        double currentPos = clock.positionSeconds(nowNanos, durationSeconds);
        double target = TransportClock.clampSeekTarget(seconds, currentPos, durationSeconds);

        if (currentInstance != null) {
            backend.stop(currentInstance);
            currentInstance = null;
        }
        generation++;
        long gen = generation;
        pendingDuration.set(null);
        currentOffsetSeconds = target;
        seenActive = false;
        startingTicks = 0;
        boolean wasPaused = (state == State.PAUSED || pendingPause);
        pendingPause = wasPaused;
        clock.retarget(target, nowNanos);
        state = wasPaused ? State.PAUSED : State.STARTING;
        currentInstance = backend.play(currentPool, currentTrack, gen, target);
    }

    public void seek(double seconds) {
        seek(seconds, System.nanoTime());
    }

    public void stop() {
        if (currentInstance != null) {
            backend.stop(currentInstance);
            currentInstance = null;
        }
        MusicDirector.getInstance().unregisterActivePreviewController(this);
        generation++;
        state = State.IDLE;
        currentPool = null;
        currentTrack = null;
        durationSeconds = Double.NaN;
        pendingDuration.set(null);
        currentOffsetSeconds = 0.0;
        seenActive = false;
        startingTicks = 0;
        pendingPause = false;
        clock.reset();
        if (ownsBackgroundPause) {
            ownsBackgroundPause = false;
            backend.resumeBackground();
        }
    }

    public Snapshot snapshot(long nowNanos) {
        pollPendingDuration();
        if (state == State.IDLE) {
            return new Snapshot(State.IDLE, "", "", 0.0, Double.NaN, false);
        }
        String resourceId = currentTrack != null ? currentTrack.resourceId() : "";
        String title = currentTrack != null ? currentTrack.title() : "";
        double pos = clock.positionSeconds(nowNanos, durationSeconds);
        boolean canSeek = !Double.isNaN(durationSeconds) && durationSeconds > 0.0;
        return new Snapshot(state, resourceId, title, pos, durationSeconds, canSeek);
    }

    public Snapshot snapshot() {
        return snapshot(System.nanoTime());
    }

    public boolean isPlaying() {
        return state == State.PLAYING || state == State.STARTING;
    }

    public State state() {
        return state;
    }

    public Track currentTrack() {
        return currentTrack;
    }

    public Pool currentPool() {
        return currentPool;
    }

    public long currentGeneration() {
        return generation;
    }

    public boolean ownsBackgroundPause() {
        return ownsBackgroundPause;
    }

    @Override
    public void close() {
        stop();
    }
}
