package com.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(com.mojang.blaze3d.audio.Channel.class)
public interface ChannelAccessor {
    @Accessor("source")
    int cueMyMusic$getSource();
}
