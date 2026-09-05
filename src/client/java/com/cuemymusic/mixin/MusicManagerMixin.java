package com.cuemymusic.mixin;

import com.cuemymusic.CueMyMusic;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    // ponytail: suppress vanilla situational music when ambient replacement enabled — our MusicDirector owns playback
    @Inject(method = "startPlaying(Lnet/minecraft/sounds/Music;)V", at = @At("HEAD"), cancellable = true)
    private void cueMyMusic$suppress(net.minecraft.sounds.Music music, CallbackInfo ci){
        try {
            if (com.cuemymusic.client.playback.MusicDirector.getInstance().isPlaying()) {
                ci.cancel();
                return;
            }
            var inst = CueMyMusic.getInstance();
            if (inst != null && inst.getConfig() != null && inst.getConfig().isEnableAmbientReplacement()) {
                // only suppress if we have any eligible track to play instead
                var lib = inst.getLibrary();
                if (lib != null && !com.cuemymusic.client.playback.MusicDirector.getInstance().getEligibleCandidates().isEmpty()) {
                    ci.cancel();
                }
            }
        } catch (Exception ignored) {}
    }
}
