package com.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/** Narrow lookup: reach the engine behind the manager for per-song pause. */
@Mixin(SoundManager.class)
public interface SoundEngineProvider {
    @Accessor("soundEngine")
    SoundEngine cueMyMusic$engine();
}
