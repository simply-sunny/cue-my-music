package mix.cuemymusic.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;

import mix.cuemymusic.AutomixProfiles;
import mix.cuemymusic.CuePlayer;
import mix.cuemymusic.library.MusicLibrary;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    /** Every sound stops when the level changes; the player's own song carries on, or waits for the new level. */
    @WrapOperation(
            method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;stop()V"))
    private void cuemymusic$keepSong(SoundManager sounds, Operation<Void> original, @Local(argsOnly = true) ClientLevel level) {
        CuePlayer.INSTANCE.levelChanged(level != null, () -> original.call(sounds));
    }

    /** Picks up songs a pack added or removed once a resource reload finishes, such as from the pack screen or F3+T. */
    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void cuemymusic$refreshLibrary(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        cir.getReturnValue().thenRun(() -> mc.execute(() -> {
            AutomixProfiles.clear();
            MusicLibrary.refresh();
        }));
    }
}
