package com.cuemymusic.client.playback;

import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import com.cuemymusic.mixin.ChannelAccessor;
import com.cuemymusic.mixin.ChannelHandleAccessor;
import com.cuemymusic.mixin.SoundEngineAccessor;
import com.cuemymusic.mixin.SoundManagerAccessor;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.lwjgl.openal.AL10;

import java.util.Map;
import java.util.Optional;

/**
 * Vanilla built-in preview only. Uses real built-in Minecraft sound/music references
 * Goal2 registered, via MUSIC category, through SoundManager/SoundInstance.
 * No OGG extraction, no resource packs.
 * Separate from future LocalOggPlayback (file cache).
 * Single preview guarantee: playing B replaces A, rapid Play no duplicates, close stops.
 */
public final class NativeMinecraftPlayback {
    private static final NativeMinecraftPlayback INSTANCE = new NativeMinecraftPlayback();

    private final Object lock = new Object();
    private final PlaybackLifecycle lifecycle = new PlaybackLifecycle();
    private SoundInstance currentSound;
    private long playStartMs = 0;
    private long pausedElapsedMs = 0;

    private NativeMinecraftPlayback() {}

    public static NativeMinecraftPlayback getInstance() { return INSTANCE; }

    public boolean hasOwnership() {
        synchronized (lock) { return lifecycle.occupied(); }
    }

    public PlaybackState getState() {
        synchronized (lock) { return lifecycle.state(); }
    }

    public Optional<MusicTrack> getCurrentTrack() {
        synchronized (lock) { return lifecycle.track(); }
    }

    public Optional<String> getCurrentTrackId() {
        synchronized (lock) { return lifecycle.track().map(MusicTrack::getId); }
    }

    public SoundInstance getHandle() {
        synchronized (lock) { return currentSound; }
    }

    public boolean isPlaying() {
        synchronized (lock) { return lifecycle.state() == PlaybackState.PLAYING; }
    }

    public boolean isPlaying(Minecraft mc) {
        return isPlaying();
    }

    public boolean isPaused() {
        synchronized (lock) { return lifecycle.state() == PlaybackState.PAUSED; }
    }

    public long getElapsedMs(Minecraft mc) {
        synchronized (lock) {
            return getElapsedMsLocked(mc);
        }
    }

    private long getElapsedMsLocked(Minecraft mc) {
        var track = lifecycle.track().orElse(null);
        if (track == null) return 0;
        if (lifecycle.state() == PlaybackState.PAUSED) return pausedElapsedMs;
        if (lifecycle.state() == PlaybackState.STOPPED || playStartMs == 0) return 0;
        // Try real OpenAL position first
        float pos = getPositionSecondsReal(mc);
        if (pos >= 0) return (long)(pos * 1000);
        long elapsed = System.currentTimeMillis() - playStartMs;
        if (lifecycle.state() != PlaybackState.PLAYING) {
            Integer dur = track.getDurationSeconds();
            if (dur != null) return Math.min(elapsed, dur * 1000L);
        }
        return elapsed;
    }

    private ChannelAccess.ChannelHandle channelHandle(Minecraft mc, SoundInstance sound) {
        if (mc == null || sound == null) return null;
        try {
            SoundManager sm = mc.getSoundManager();
            if (sm == null) return null;
            SoundEngine se = ((SoundManagerAccessor) sm).cueMyMusic$getSoundEngine();
            if (se == null) return null;
            Map<SoundInstance, ChannelAccess.ChannelHandle> map = ((SoundEngineAccessor) se).cueMyMusic$getInstanceToChannel();
            if (map == null) return null;
            return map.get(sound);
        } catch (Exception e) {
            return null;
        }
    }

    /** Real playback position via OpenAL AL_SEC_OFFSET, or -1 if unavailable. */
    public float getPositionSecondsReal(Minecraft mc) {
        try {
            if (mc == null || currentSound == null) return -1;
            ChannelAccess.ChannelHandle handle = channelHandle(mc, currentSound);
            if (handle == null || handle.isStopped()) return -1;
            Channel ch = ((ChannelHandleAccessor) handle).cueMyMusic$getChannel();
            if (ch == null) return -1;
            int source = ((ChannelAccessor) ch).cueMyMusic$getSource();
            // 4132 = AL_SEC_OFFSET
            return AL10.alGetSourcef(source, 4132);
        } catch (Exception e) {
            return -1;
        }
    }

    public int getDurationSeconds() {
        synchronized (lock) {
            var track = lifecycle.track().orElse(null);
            if (track == null || track.getDurationSeconds() == null) return 0;
            return track.getDurationSeconds();
        }
    }

    /** Seek to seconds, clamped 0..duration, no duplicate instances. */
    public boolean seek(Minecraft mc, float seconds) {
        synchronized (lock) {
            if (currentSound == null || lifecycle.track().isEmpty()) return false;
            int dur = getDurationSeconds();
            if (dur > 0) seconds = Math.max(0, Math.min(seconds, dur));
            else seconds = Math.max(0, seconds);
            boolean wasPlaying = lifecycle.state() == PlaybackState.PLAYING;
            boolean wasPaused = lifecycle.state() == PlaybackState.PAUSED;
            try {
                ChannelAccess.ChannelHandle handle = channelHandle(mc, currentSound);
                if (handle != null && !handle.isStopped()) {
                    Channel ch = ((ChannelHandleAccessor) handle).cueMyMusic$getChannel();
                    if (ch != null) {
                        int source = ((ChannelAccessor) ch).cueMyMusic$getSource();
                        AL10.alSourcef(source, 4132, seconds);
                        // keep wall-clock in sync
                        if (wasPlaying) playStartMs = System.currentTimeMillis() - (long)(seconds * 1000);
                        else if (wasPaused) pausedElapsedMs = (long)(seconds * 1000);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            // Fallback wall-clock seek (no real OpenAL seek, but keep state consistent)
            if (wasPlaying) playStartMs = System.currentTimeMillis() - (long)(seconds * 1000);
            else if (wasPaused) pausedElapsedMs = (long)(seconds * 1000);
            return false;
        }
    }

    public void tick(Minecraft mc, long now) {
        synchronized (lock) {
            if (lifecycle.state() == PlaybackState.STARTING) {
                var handle = channelHandle(mc, currentSound);
                if (handle != null && !handle.isStopped()) lifecycle.attached();
                else if (lifecycle.startTimedOut(now)) stopLocked(mc);
            } else if (lifecycle.state() == PlaybackState.PLAYING
                    && currentSound != null
                    && mc != null
                    && mc.getSoundManager() != null
                    && !mc.getSoundManager().isActive(currentSound)) {
                stopLocked(mc); // natural completion
            }
        }
    }

    /**
     * Play a vanilla/disc track via MUSIC category. Granular fileId like
     * minecraft:music/game/sweden is streamed via FILE Sound (exact track).
     * Returns true if audibly started.
     */
    public boolean play(Minecraft mc, MusicTrack track) {
        if (mc == null || track == null) return false;
        SoundInstance sound = createSound(track);
        if (sound == null) return false;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            // single preview: stop previous before playing new (prevents duplicates, rapid play)
            stopLocked(mc);
            lifecycle.start(track, now);
            currentSound = sound;
            playStartMs = now;
            pausedElapsedMs = 0;
            try {
                mc.getSoundManager().play(sound);
                return true;
            } catch (Exception e) {
                stopLocked(mc);
                return false;
            }
        }
    }

    public void stop(Minecraft mc) {
        synchronized (lock) { stopLocked(mc); }
    }

    private void stopLocked(Minecraft mc) {
        if (currentSound != null) {
            try { if (mc != null && mc.getSoundManager() != null) mc.getSoundManager().stop(currentSound); } catch (Exception ignored) {}
        }
        currentSound = null;
        lifecycle.stop();
        playStartMs = 0;
        pausedElapsedMs = 0;
    }

    public boolean pause(Minecraft mc) {
        synchronized (lock) {
            return pauseLocked(mc);
        }
    }

    private boolean pauseLocked(Minecraft mc) {
        if (lifecycle.state() != PlaybackState.PLAYING || currentSound == null) return false;
        pausedElapsedMs = getElapsedMsLocked(mc);
        try { if (mc != null && mc.getSoundManager() != null) mc.getSoundManager().stop(currentSound); } catch (Exception ignored) {}
        currentSound = null;
        lifecycle.pause();
        return true;
    }

    public boolean resume(Minecraft mc) {
        synchronized (lock) {
            return resumeLocked(mc);
        }
    }

    private boolean resumeLocked(Minecraft mc) {
        var track = lifecycle.track().orElse(null);
        if (lifecycle.state() != PlaybackState.PAUSED || track == null) return false;
        SoundInstance sound = createSound(track);
        if (sound == null) return false;
        long now = System.currentTimeMillis();
        lifecycle.resume(now);
        currentSound = sound;
        playStartMs = now - pausedElapsedMs;
        try {
            if (mc != null && mc.getSoundManager() != null) {
                mc.getSoundManager().play(sound);
            }
            return true;
        } catch (Exception e) {
            stopLocked(mc);
            return false;
        }
    }

    public boolean togglePause(Minecraft mc) {
        synchronized (lock) {
            if (lifecycle.state() == PlaybackState.PLAYING) return pauseLocked(mc);
            if (lifecycle.state() == PlaybackState.PAUSED) return resumeLocked(mc);
            return false;
        }
    }

    private SoundInstance createSound(MusicTrack track) {
        if (track.getSourceId() == null) return null;
        Identifier id = Identifier.tryParse(track.getSourceId());
        if (id == null) return null;
        // Granular vanilla fileId: minecraft:music/game/sweden -> FILE streamed via MUSIC category, exact track
        if (track.getSourceType() == SourceType.VANILLA && id.getPath().contains("/")) {
            // Use VanillaFileSoundInstance (FILE type, streamed) so exact OGG plays, not random event
            return new VanillaFileSoundInstance(id, SoundSource.MUSIC);
        }
        // Disc or coarse vanilla: resolve SoundEvent from registry, play via MUSIC category
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (event == null) return null;
        // Preview uses MUSIC category per spec via SoundManager MUSIC
        // For discs, SimpleSoundInstance.forMusic gives MUSIC category
        return net.minecraft.client.resources.sounds.SimpleSoundInstance.forMusic(event);
    }

    /** Inner FILE-streamed instance for granular vanilla tracks. MUSIC category, streamed exact file. */
    public static final class VanillaFileSoundInstance extends AbstractSoundInstance {
        private final Sound fileSound;

        public VanillaFileSoundInstance(Identifier fileId, SoundSource source) {
            super(fileId, source, SoundInstance.createUnseededRandom());
            this.fileSound = new Sound(fileId, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE, true, false, 16);
            this.volume = 1.0f;
            this.pitch = 1.0f;
            this.looping = false;
            this.sound = fileSound;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            WeighedSoundEvents events = new WeighedSoundEvents(getIdentifier(), null);
            events.addSound(fileSound);
            return events;
        }
    }
}
