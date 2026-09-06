package com.cuemymusic.client.music;

import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * Interface injected onto SoundEngine by SoundEngineTransportMixin to allow
 * per-song pausing without casting to a Mixin class.
 */
public interface EngineTransport {
    void cueMyMusic$setInstancePaused(SoundInstance instance, boolean paused);
}
