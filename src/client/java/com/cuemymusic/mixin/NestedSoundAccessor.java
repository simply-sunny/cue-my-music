package com.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.resources.Identifier;

/**
 * Narrow accessor for the runtime nested-event delegate
 * ({@code SoundManager$Preparations$1}). Field names verified against
 * mapped 26.2 bytecode: {@code val$soundLocation} is the nested event id.
 */
@Mixin(targets = "net.minecraft.client.sounds.SoundManager$Preparations$1")
public interface NestedSoundAccessor {
    @Accessor("val$soundLocation")
    Identifier cueMyMusic$nestedEventId();

    @Accessor("val$sound")
    Sound cueMyMusic$nestedDefinition();
}
