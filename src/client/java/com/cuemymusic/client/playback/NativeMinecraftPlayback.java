package com.cuemymusic.client.playback;

import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import com.cuemymusic.mixin.ChannelAccessor;
import com.cuemymusic.mixin.SoundEngineAccessor;
import com.cuemymusic.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Vanilla built-in preview only. Uses real built-in Minecraft sound/music references
 * Goal2 registered, via MUSIC category, through SoundManager/SoundInstance.
 * Active track is loaded as a non-streamed OpenAL buffer for truthful seeking.
 * Runtime OpenAL calls must occur only inside ChannelHandle.execute callbacks.
 */
public final class NativeMinecraftPlayback {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/playback");
    private static final NativeMinecraftPlayback INSTANCE = new NativeMinecraftPlayback();

    private final Object lock = new Object();
    private final PlaybackLifecycle lifecycle = new PlaybackLifecycle();
    private SoundInstance currentSound;

    private volatile float positionSeconds = -1;
    private volatile float durationSeconds = -1;
    private volatile boolean seekCapable;
    private volatile boolean probePending;
    private float pausedPositionSeconds = -1;
    private float pendingResumeSeek = -1;

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

    public float positionSeconds() {
        return positionSeconds;
    }

    public float getPositionSeconds() {
        return positionSeconds;
    }

    public float getPositionSecondsReal(Minecraft mc) {
        return positionSeconds;
    }

    public float durationSeconds() {
        return durationSeconds;
    }

    public float getDurationSeconds() {
        return durationSeconds;
    }

    public boolean canSeek() {
        return seekCapable;
    }

    public long getElapsedMs(Minecraft mc) {
        float pos = positionSeconds;
        if (pos >= 0) return (long) (pos * 1000f);
        return 0L;
    }

    static float durationSeconds(int bytes, int channels, int bits, int frequency) {
        if (bytes <= 0 || channels <= 0 || bits <= 0 || frequency <= 0) return -1;
        return bytes / (channels * frequency * (bits / 8f));
    }

    static float clampSeek(float requested, float duration) {
        return Math.max(0, Math.min(requested, duration));
    }

    private static void clearAlErrors() {
        for (int i = 0; i < 10 && AL10.alGetError() != AL10.AL_NO_ERROR; i++) {}
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

    private void probe(ChannelAccess.ChannelHandle handle) {
        if (probePending) return;
        probePending = true;
        handle.execute(channel -> {
            try {
                clearAlErrors();
                int source = ((ChannelAccessor) channel).cueMyMusic$getSource();
                int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
                int size = AL10.alGetBufferi(buffer, AL10.AL_SIZE);
                int channels = AL10.alGetBufferi(buffer, AL10.AL_CHANNELS);
                int bits = AL10.alGetBufferi(buffer, AL10.AL_BITS);
                int frequency = AL10.alGetBufferi(buffer, AL10.AL_FREQUENCY);
                int error = AL10.alGetError();
                if (error == AL10.AL_NO_ERROR) {
                    durationSeconds = durationSeconds(size, channels, bits, frequency);
                    float pos = AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET);
                    int posError = AL10.alGetError();
                    if (posError == AL10.AL_NO_ERROR) {
                        positionSeconds = pos;
                        seekCapable = durationSeconds > 0;
                    } else {
                        seekCapable = false;
                    }
                } else {
                    seekCapable = false;
                }
            } finally {
                probePending = false;
            }
        });
    }

    public CompletableFuture<Boolean> seek(Minecraft mc, float requested) {
        ChannelAccess.ChannelHandle handle;
        float duration;
        synchronized (lock) {
            handle = channelHandle(mc, currentSound);
            duration = durationSeconds;
        }
        if (handle == null || !seekCapable || duration <= 0)
            return CompletableFuture.completedFuture(false);
        float target = clampSeek(requested, duration);
        var result = new CompletableFuture<Boolean>();
        handle.execute(channel -> {
            int source = ((ChannelAccessor) channel).cueMyMusic$getSource();
            clearAlErrors();
            AL10.alSourcef(source, AL11.AL_SEC_OFFSET, target);
            boolean ok = AL10.alGetError() == AL10.AL_NO_ERROR;
            if (ok) positionSeconds = target;
            result.complete(ok);
        });
        return result.completeOnTimeout(false, 1, TimeUnit.SECONDS);
    }

    public void tick(Minecraft mc, long now) {
        synchronized (lock) {
            if (lifecycle.state() == PlaybackState.STARTING) {
                var handle = channelHandle(mc, currentSound);
                if (handle != null && !handle.isStopped()) {
                    lifecycle.attached();
                    if (pendingResumeSeek > 0) {
                        float target = pendingResumeSeek;
                        pendingResumeSeek = -1;
                        float duration = durationSeconds;
                        float clamped = duration > 0 ? clampSeek(target, duration) : target;
                        handle.execute(channel -> {
                            int source = ((ChannelAccessor) channel).cueMyMusic$getSource();
                            clearAlErrors();
                            AL10.alSourcef(source, AL11.AL_SEC_OFFSET, clamped);
                            boolean ok = AL10.alGetError() == AL10.AL_NO_ERROR;
                            if (ok) positionSeconds = clamped;
                        });
                    } else {
                        pendingResumeSeek = -1;
                    }
                    probe(handle);
                } else if (lifecycle.startTimedOut(now)) {
                    stopLocked(mc);
                }
            } else if (lifecycle.state() == PlaybackState.PLAYING) {
                if (currentSound != null
                        && mc != null
                        && mc.getSoundManager() != null
                        && !mc.getSoundManager().isActive(currentSound)) {
                    stopLocked(mc); // natural completion
                } else {
                    var handle = channelHandle(mc, currentSound);
                    if (handle != null && !handle.isStopped()) {
                        probe(handle);
                    }
                }
            }
        }
    }

    public boolean play(Minecraft mc, MusicTrack track) {
        if (mc == null || track == null) return false;
        SoundInstance sound = createSound(mc, track);
        if (sound == null) return false;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            stopLocked(mc);
            lifecycle.start(track, now);
            currentSound = sound;
            positionSeconds = 0;
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
        positionSeconds = -1;
        durationSeconds = -1;
        seekCapable = false;
        probePending = false;
        pausedPositionSeconds = -1;
        pendingResumeSeek = -1;
    }

    public boolean pause(Minecraft mc) {
        synchronized (lock) {
            return pauseLocked(mc);
        }
    }

    private boolean pauseLocked(Minecraft mc) {
        if (lifecycle.state() != PlaybackState.PLAYING || currentSound == null) return false;
        pausedPositionSeconds = positionSeconds >= 0 ? positionSeconds : 0;
        if (currentSound != null) {
            try { if (mc != null && mc.getSoundManager() != null) mc.getSoundManager().stop(currentSound); } catch (Exception ignored) {}
        }
        currentSound = null;
        seekCapable = false;
        probePending = false;
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
        SoundInstance sound = createSound(mc, track);
        if (sound == null) return false;
        long now = System.currentTimeMillis();
        pendingResumeSeek = pausedPositionSeconds;
        lifecycle.resume(now);
        currentSound = sound;
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

    private SoundInstance createSound(Minecraft mc, MusicTrack track) {
        if (track == null || track.getSourceId() == null) return null;
        Identifier id = Identifier.tryParse(track.getSourceId());
        if (id == null) {
            LOGGER.warn("Invalid source ID: {}", track.getSourceId());
            return null;
        }
        Identifier fileId = id;
        if (track.getSourceType() == SourceType.MUSIC_DISC) {
            if (mc == null || mc.getSoundManager() == null) {
                LOGGER.warn("SoundManager not available to resolve music disc {}", track.getSourceId());
                return null;
            }
            WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(id);
            if (event == null) {
                LOGGER.warn("SoundEvent not found for music disc {}", track.getSourceId());
                return null;
            }
            Sound chosen = event.getSound(SoundInstance.createUnseededRandom());
            if (chosen == null) {
                LOGGER.warn("No sound chosen for music disc {}", track.getSourceId());
                return null;
            }
            fileId = chosen.getLocation();
        }
        return new BufferedFileSoundInstance(fileId, SoundSource.MUSIC);
    }

    /** Inner FILE-buffered instance for active tracks. MUSIC category, static exact file. */
    public static final class BufferedFileSoundInstance extends AbstractSoundInstance {
        private final Sound fileSound;

        public BufferedFileSoundInstance(Identifier fileId, SoundSource source) {
            super(fileId, source, SoundInstance.createUnseededRandom());
            this.fileSound = new Sound(fileId, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f),
                    1, Sound.Type.FILE, false, false, 16);
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
