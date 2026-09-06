package com.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.util.RandomSource;

/**
 * Minimal access: read and maintain the current song (seek swaps the
 * instance without a duplicate start), read/write delay, and reseed delay RNG.
 */
@Mixin(MusicManager.class)
public interface MusicManagerAccessor {
    @Accessor("currentMusic")
    SoundInstance cueMyMusic$currentMusic();

    @Mutable
    @Accessor("currentMusic")
    void cueMyMusic$setCurrentMusic(SoundInstance music);

    @Accessor("nextSongDelay")
    int cueMyMusic$nextSongDelay();

    @Mutable
    @Accessor("nextSongDelay")
    void cueMyMusic$setNextSongDelay(int delay);

    @Mutable
    @Accessor("random")
    void cueMyMusic$setRandom(RandomSource random);
}
