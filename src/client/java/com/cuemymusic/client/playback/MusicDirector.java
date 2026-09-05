package com.cuemymusic.client.playback;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.data.MusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import java.util.*;
import java.util.concurrent.CompletableFuture;

// ponytail: minimal director — eligible = enabled && ambientEligible, no local files, ambient timer
public final class MusicDirector {
    private static final MusicDirector INSTANCE = new MusicDirector();
    private final Random random = new Random();
    private com.cuemymusic.data.MusicLibrary library;
    private final NativeMinecraftPlayback nativePlayback = NativeMinecraftPlayback.getInstance();
    // ambient scheduling
    private long nextDelayMs = 0; // 0 = not scheduled
    private long trackEndMs = 0;
    private boolean skipRequested = false;

    private MusicDirector() {}

    public static MusicDirector getInstance() { return INSTANCE; }

    public void init(com.cuemymusic.data.MusicLibrary lib) { this.library = lib; }

    public static boolean blocksAutoStart(PlaybackState state) {
        return state != PlaybackState.STOPPED;
    }

    public boolean hasOwnership() {
        return nativePlayback.hasOwnership();
    }

    public synchronized boolean previewTrack(Minecraft mc, MusicTrack track) {
        if (track == null) return false;
        var current = getCurrentTrack().orElse(null);
        if (current != null && current.getId().equals(track.getId())) return togglePause(mc);
        if (mc != null) {
            if (mc.getMusicManager() != null) mc.getMusicManager().stopPlaying();
            if (mc.getSoundManager() != null) mc.getSoundManager().stop(null, SoundSource.MUSIC);
        }
        boolean started = nativePlayback.play(mc, track);
        if (started) { nextDelayMs = 0; trackEndMs = 0; skipRequested = false; }
        return started;
    }

    public Optional<MusicTrack> chooseNextTrack() {
        var c = getEligibleCandidates();
        if (c.isEmpty()) return Optional.empty();
        return Optional.of(c.get(random.nextInt(c.size())));
    }

    public List<MusicTrack> getEligibleCandidates() {
        var lib = this.library;
        if (lib == null) {
            var inst = CueMyMusic.getInstance();
            if (inst != null) lib = inst.getLibrary();
        }
        if (lib == null) return List.of();
        List<MusicTrack> eligible = new ArrayList<>();
        for (var t : lib.getAllTracks()) if (t.isEnabled() && t.isAmbientEligible()) eligible.add(t);
        return List.copyOf(eligible);
    }

    public boolean playNext(Minecraft mc) {
        var n = chooseNextTrack();
        return n.isPresent() && playTrack(mc, n.get());
    }

    public synchronized boolean playTrack(Minecraft mc, MusicTrack track) {
        if (track == null) return false;
        boolean ok = nativePlayback.play(mc, track);
        if (ok) { scheduleNextDelay(); trackEndMs = 0; skipRequested = false; }
        return ok;
    }

    public synchronized void stopCurrent(Minecraft mc) {
        nativePlayback.stop(mc);
        nextDelayMs = 0;
        trackEndMs = 0;
        skipRequested = false;
    }

    public boolean isPlaying(Minecraft mc) { return nativePlayback.isPlaying(mc); }
    public boolean isPlaying() { return nativePlayback.isPlaying(); }
    public boolean isPaused() { return nativePlayback.isPaused(); }
    public PlaybackState getState(Minecraft mc) { return nativePlayback.getState(); }
    public PlaybackState getState() { return nativePlayback.getState(); }
    public long getElapsedMs(Minecraft mc) { return nativePlayback.getElapsedMs(mc); }
    public boolean togglePause(Minecraft mc) { return nativePlayback.togglePause(mc); }
    public Optional<MusicTrack> skip(Minecraft mc) {
        stopCurrent(mc);
        if (playNext(mc)) return getCurrentTrack();
        return Optional.empty();
    }
    public Optional<MusicTrack> getCurrentTrack() { return nativePlayback.getCurrentTrack(); }
    public float getPositionSecondsReal(Minecraft mc) {
        return nativePlayback.positionSeconds();
    }
    public float getPositionSeconds() {
        return nativePlayback.positionSeconds();
    }
    public float positionSeconds() {
        return nativePlayback.positionSeconds();
    }
    public boolean canSeek() {
        return nativePlayback.canSeek();
    }
    public CompletableFuture<Boolean> seek(Minecraft mc, float sec) {
        return nativePlayback.seek(mc, sec);
    }
    public float getDurationSeconds() {
        return nativePlayback.durationSeconds();
    }
    public float durationSeconds() {
        return nativePlayback.durationSeconds();
    }

    // ambient timer — called from client tick or widget
    private void scheduleNextDelay() {
        try {
            var cfg = CueMyMusic.getInstance() != null ? CueMyMusic.getInstance().getConfig() : null;
            int base = cfg != null ? cfg.getNextTrackDelaySeconds() : 300;
            // frequency: constant=0, frequent=30, default=base
            // read Minecraft option if available; fallback to config
            try {
                var freq = Minecraft.getInstance().options.musicFrequency().get();
                String name = freq.name();
                if ("CONSTANT".equals(name)) base = 0;
                else if ("FREQUENT".equals(name)) base = Math.min(base, 60);
            } catch (Exception ignored) {}
            if (skipRequested) base = 0;
            nextDelayMs = base * 1000L;
        } catch (Exception e) {
            nextDelayMs = 300_000;
        }
    }

    public long getTimeUntilNextMs(Minecraft mc) {
        if (blocksAutoStart(nativePlayback.getState()) || nativePlayback.hasOwnership()) {
            return nextDelayMs;
        }
        if (trackEndMs == 0) return nextDelayMs;
        long sinceEnd = System.currentTimeMillis() - trackEndMs;
        return Math.max(0, nextDelayMs - sinceEnd);
    }

    public void requestSkipToNext() { skipRequested = true; nextDelayMs = 0; }
    public boolean isSkipRequested() { return skipRequested; }
    public void clearSkip() { skipRequested = false; scheduleNextDelay(); }

    // tick hook for auto-play next when delay elapsed
    public void tick(Minecraft mc) {
        nativePlayback.tick(mc, System.currentTimeMillis());
        if (blocksAutoStart(nativePlayback.getState())) return;
        var lib = this.library;
        if (lib == null) {
            var inst = CueMyMusic.getInstance();
            if (inst != null) lib = inst.getLibrary();
        }
        if (lib == null) return;
        if (nativePlayback.hasOwnership()) { trackEndMs = 0; return; }
        if (nextDelayMs == 0) scheduleNextDelay();
        if (trackEndMs == 0) trackEndMs = System.currentTimeMillis();
        if (System.currentTimeMillis() - trackEndMs >= nextDelayMs) playNext(mc);
    }
}
