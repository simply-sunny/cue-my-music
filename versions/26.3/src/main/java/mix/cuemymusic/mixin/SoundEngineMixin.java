package mix.cuemymusic.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import mix.cuemymusic.CueConfig;
import mix.cuemymusic.CuePlayer;
import mix.cuemymusic.CueStream;
import mix.cuemymusic.TrackSoundInstance;

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    /** While the player holds the music, other music (the game's, or another mod's) doesn't start over it. */
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void cuemymusic$holdMusic(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (instance.getSource() == SoundSource.MUSIC && !CueConfig.INSTANCE.vanilla && CuePlayer.INSTANCE.holdsMusic()
                && !CuePlayer.INSTANCE.isOurs(instance)) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    /** Wraps background-music streams. Jukeboxes (the record category) are left alone. */
    @WrapOperation(
            method = "play",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> cuemymusic$wrapStream(SoundBufferLibrary library, Identifier sound, boolean looping,
            Operation<CompletableFuture<AudioStream>> original, @Local(argsOnly = true) SoundInstance instance) {
        CompletableFuture<AudioStream> stream = original.call(library, sound, looping);
        if (instance.getSource() != SoundSource.MUSIC || CueConfig.INSTANCE.vanilla) return stream;
        double start = instance instanceof TrackSoundInstance t ? t.startSeconds() : 0;
        // Async: seeking decodes ahead, which must not run on the client or sound thread.
        return stream.thenApplyAsync(s -> CueStream.wrap(s, sound, start, instance));
    }
}
