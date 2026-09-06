package com.cuemymusic.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;

@Mixin(WeighedSoundEvents.class)
public interface WeighedSoundEventsAccessor {
    @Accessor("list")
    List<Weighted<Sound>> cueMyMusic$entries();
}
