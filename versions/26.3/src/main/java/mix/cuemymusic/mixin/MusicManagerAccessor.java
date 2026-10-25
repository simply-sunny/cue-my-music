package mix.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

@Mixin(MusicManager.class)
public interface MusicManagerAccessor {
    @Accessor("currentMusic")
    SoundInstance cuemymusic$currentMusic();

    @Accessor("currentMusic")
    void cuemymusic$setCurrentMusic(SoundInstance music);

    /** Ticks until the game starts its next song. Only counts down while no song is playing. */
    @Accessor("nextSongDelay")
    int cuemymusic$nextSongDelay();

    @Accessor("nextSongDelay")
    void cuemymusic$setNextSongDelay(int ticks);
}
