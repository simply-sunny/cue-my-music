package mix.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.sounds.MusicManager;

import mix.cuemymusic.CuePlayer;

/** Keeps the game from starting its own music while the player has a track playing or paused. */
@Mixin(MusicManager.class)
abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cuemymusic$holdWhilePlayerActive(CallbackInfo ci) {
        if (CuePlayer.INSTANCE.holdsMusic()) ci.cancel();
    }
}
