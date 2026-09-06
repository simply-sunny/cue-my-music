package com.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.cuemymusic.client.music.MusicDirector;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.SoundEvent;

/**
 * Substitutes only the {@code SimpleSoundInstance.forMusic} call inside
 * {@code MusicManager.startPlaying} with our deterministically pinned
 * instance. The tick, fade, toast, delay and replacement lifecycle is
 * untouched: this redirect never cancels, and any failure falls back to
 * the vanilla factory so playback can never break.
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Redirect(
        method = "startPlaying",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;forMusic(Lnet/minecraft/sounds/SoundEvent;)Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;"
        )
    )
    private SimpleSoundInstance cueMyMusic$pinMusic(SoundEvent event) {
        // No random fallback: pinnedInstance is total (it pins exactly what
        // vanilla selection returns), and silently re-rolling on failure
        // would hide invariant-breaking bugs behind nondeterministic tracks.
        return MusicDirector.getInstance().pinnedInstance(event);
    }
}
