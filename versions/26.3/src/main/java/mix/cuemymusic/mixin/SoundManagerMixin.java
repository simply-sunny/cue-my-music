package mix.cuemymusic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

import mix.cuemymusic.CueConfig;
import mix.cuemymusic.CuePlayer;
import mix.cuemymusic.api.CueSounds;

@Mixin(SoundManager.class)
abstract class SoundManagerMixin {
    /**
     * Restarting the engine would stop every sound, the song too. An add-on's sound refresh only swaps the
     * sound list; a resource pack reload while a song plays keeps the engine too, clearing what it had loaded.
     */
    @WrapOperation(
            method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;reload()V"))
    private void cuemymusic$keepEngine(SoundEngine engine, Operation<Void> original) {
        if (CueSounds.keepEngine) return;
        if (!CueConfig.INSTANCE.vanilla && CuePlayer.INSTANCE.playThroughReload(engine)) return;
        original.call(engine);
    }
}
