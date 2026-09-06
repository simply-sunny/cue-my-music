package com.cuemymusic.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.cuemymusic.client.music.MusicDirector;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Intercepts stream acquisition to supply offset streams for our pinned music.
 * Avoids any conflict with mods/fabric-api targeting SoundEngine.play.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
    @Inject(method = "getStream", at = @At("RETURN"), cancellable = true)
    private void cueMyMusic$offsetStream(Identifier path, boolean loop,
            CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        MusicDirector director = MusicDirector.getInstance();
        MusicDirector.OffsetRequest request = director.offsetRequestFor(path);
        if (request == null || !director.isCurrentGeneration(request.generation())) {
            return;
        }
        CompletableFuture<AudioStream> base = cir.getReturnValue();
        CompletableFuture<AudioStream> offset = base.thenApplyAsync(
                stream -> director.applyOffset(stream, request), Util.nonCriticalIoPool());
        cir.setReturnValue(offset);
    }
}
