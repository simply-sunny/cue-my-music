package mix.cuemymusic.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.CueStream;

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    @WrapOperation(
            method = "play",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> cuemymusic$wrapStream(SoundBufferLibrary library, Identifier sound, boolean looping,
            Operation<CompletableFuture<AudioStream>> original, @Local(argsOnly = true) SoundInstance instance) {
        // Async: in BPM mode, wrapping decodes ahead, which must not run on the client thread.
        return original.call(library, sound, looping).thenApplyAsync(stream -> CueStream.wrap(stream, instance.getSource(), sound));
    }
}
