package com.cuemymusic.client.playback;

import com.cuemymusic.data.MusicTrack;

import java.util.Objects;
import java.util.Optional;

final class PlaybackLifecycle {
    static final long START_TIMEOUT_MS = 2_000;
    private PlaybackState state = PlaybackState.STOPPED;
    private MusicTrack track;
    private long startedAtMs;

    void start(MusicTrack next, long now) {
        track = Objects.requireNonNull(next);
        startedAtMs = now;
        state = PlaybackState.STARTING;
    }

    void attached() {
        if (state == PlaybackState.STARTING) state = PlaybackState.PLAYING;
    }

    void pause() {
        if (state == PlaybackState.PLAYING) state = PlaybackState.PAUSED;
    }

    void resume(long now) {
        if (state == PlaybackState.PAUSED) {
            startedAtMs = now;
            state = PlaybackState.STARTING;
        }
    }

    void stop() {
        track = null;
        startedAtMs = 0;
        state = PlaybackState.STOPPED;
    }

    boolean occupied() {
        return state != PlaybackState.STOPPED;
    }

    boolean startTimedOut(long now) {
        return state == PlaybackState.STARTING && now - startedAtMs >= START_TIMEOUT_MS;
    }

    PlaybackState state() {
        return state;
    }

    Optional<MusicTrack> track() {
        return Optional.ofNullable(track);
    }
}
