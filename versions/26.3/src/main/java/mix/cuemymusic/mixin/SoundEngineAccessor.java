package mix.cuemymusic.mixin;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;

/** What's playing and what's waiting to, so a level change or reload can stop every sound but the player's song. */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("instanceToChannel")
    Map<SoundInstance, ?> cuemymusic$playing();

    /** Sounds waiting to start after a delay. */
    @Accessor("queuedSounds")
    Map<SoundInstance, Integer> cuemymusic$delayed();

    @Accessor("queuedTickableSounds")
    List<TickableSoundInstance> cuemymusic$queuedTicking();

    /** The loaded sounds, cleared on a resource reload so packs' changed sounds are read again. */
    @Accessor("soundBuffers")
    SoundBufferLibrary cuemymusic$soundBuffers();
}
