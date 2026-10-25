package mix.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import net.minecraft.util.RandomSource;

@Mixin(MusicManager.MusicFrequency.class)
public interface MusicFrequencyInvoker {
    /** The game's roll for the break between songs, in ticks. */
    @Invoker("getNextSongDelay")
    int cuemymusic$nextSongDelay(Music music, RandomSource random);
}
