package com.cuemymusic.client.music;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * A music instance pinned to one deterministically chosen {@link Sound}.
 *
 * <p>The event identifier is preserved (same as {@code forMusic}), so
 * vanilla replacement rules ({@code canReplace}), toasts and volume logic
 * behave exactly as usual. Only sound resolution is pinned: instead of
 * re-rolling from the instance random, {@link #resolve} installs the pinned
 * sound already selected by {@link MusicDirector} from the current loaded
 * graph. The seeded instance random is still used for pitch sampling, so
 * playback stays deterministic per selection.
 *
 * <p>Each transport action (fresh start, seek reopen) mints a new
 * generation on a new instance object: asynchronous native work (stream
 * futures, channel callbacks) is tagged by generation, so stale completions
 * from a skipped or re-sought song can be recognised and closed instead of
 * attached. The start offset is advisory metadata for the director's
 * offset-stream path; vanilla sees a normal music instance.
 */
public final class PinnedMusicInstance extends SimpleSoundInstance {
    private final Sound pinned;
    private final long generation;
    private final double startOffsetSeconds;

    public PinnedMusicInstance(SoundEvent event, Sound pinned, RandomSource random) {
        this(event.location(), pinned, random, 0L, 0.0);
    }

    public PinnedMusicInstance(Identifier eventId, Sound pinned, RandomSource random, long generation,
            double startOffsetSeconds) {
        super(eventId, SoundSource.MUSIC, 1.0F, 1.0F, random, false, 0,
                SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true);
        this.pinned = pinned;
        this.generation = generation;
        this.startOffsetSeconds = startOffsetSeconds;
    }

    public PinnedMusicInstance(SoundEvent event, Sound pinned, RandomSource random, long generation,
            double startOffsetSeconds) {
        this(event.location(), pinned, random, generation, startOffsetSeconds);
    }

    /** Transport generation of this instance; stale async work carries older values. */
    public long generation() {
        return generation;
    }

    /** Advisory stream offset in seconds; 0 for fresh starts. */
    public double startOffsetSeconds() {
        return startOffsetSeconds;
    }

    /** The deterministically pinned sound this instance always resolves to. */
    public Sound pinnedSound() {
        return pinned;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents event = manager.getSoundEvent(getIdentifier());
        if (event == null) {
            this.sound = SoundManager.EMPTY_SOUND;
            return null;
        }
        this.sound = pinned;
        return event;
    }
}
