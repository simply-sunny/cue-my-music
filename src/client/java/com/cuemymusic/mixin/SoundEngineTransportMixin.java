package com.cuemymusic.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.cuemymusic.client.music.EngineTransport;
import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import net.minecraft.client.sounds.SoundEngine;

/**
 * Per-song pause: pause or resume exactly one instance's channel via its own
 * handle. Never {@code pauseAll}: other sounds keep playing.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineTransportMixin implements EngineTransport {
    @Shadow
    @Final
    private Map<SoundInstance, ChannelHandle> instanceToChannel;

    /**
     * Pauses or resumes one instance's channel only. Missing or released
     * handles are a no-op: pausing during stream load still lands via the
     * sticky audible-start path.
     */
    @Unique
    @Override
    public void cueMyMusic$setInstancePaused(SoundInstance instance, boolean paused) {
        ChannelHandle handle = instanceToChannel.get(instance);
        if (handle == null || handle.isStopped()) {
            return;
        }
        if (paused) {
            handle.execute(Channel::pause);
        } else {
            handle.execute(Channel::unpause);
        }
    }
}
