package mix.cuemymusic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;

import mix.cuemymusic.CuePlayer;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    /** Every sound stops when the level changes; the player's own song carries on, or waits for the new level. */
    @WrapOperation(
            method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;stop()V"))
    private void cuemymusic$keepSong(SoundManager sounds, Operation<Void> original, @Local(argsOnly = true) ClientLevel level) {
        CuePlayer.INSTANCE.levelChanged(level != null, () -> original.call(sounds));
    }
}
