package com.cuemymusic.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(net.minecraft.client.sounds.SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> cueMyMusic$getInstanceToChannel();

    @Accessor("soundManager")
    net.minecraft.client.sounds.SoundManager cueMyMusic$getSoundManager();
}
