package com.cuemymusic.mixin;

import com.cuemymusic.CueMyMusic;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
    private void cueMyMusic$logPlayedSound(SoundInstance sound, CallbackInfo ci) {
        CueMyMusic.LOGGER.debug("[Cue My Music] SoundManager playing {}", sound.getIdentifier());
    }
}
