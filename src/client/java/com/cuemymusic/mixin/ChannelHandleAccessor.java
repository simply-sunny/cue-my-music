package com.cuemymusic.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.client.sounds.ChannelAccess.ChannelHandle.class)
public interface ChannelHandleAccessor {
    @Accessor("channel")
    Channel cueMyMusic$getChannel();
}
