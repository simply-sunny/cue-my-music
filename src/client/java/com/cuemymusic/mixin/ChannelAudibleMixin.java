package com.cuemymusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cuemymusic.client.music.MusicDirector;
import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.sounds.AudioStream;

/**
 * Audible-transition hooks for the transport clock. Only channels carrying
 * our generation-tagged stream report; every other sound is untouched.
 *
 * <ul>
 * <li>{@code play} anchors the clock at the stream offset — position
 * reflects audible playback, not the seconds of PCM queued ahead.</li>
 * <li>{@code pause}/{@code unpause} freeze/continue the clock. A denied
 * unpause keeps the user's pause sticky across vanilla global resumes;
 * an allowed one continues the clock.</li>
 * </ul>
 */
@Mixin(Channel.class)
public abstract class ChannelAudibleMixin {
    @Shadow
    private AudioStream stream;

    private long cueMyMusic$taggedGeneration() {
        if (stream instanceof MusicDirector.TaggedStream tagged) {
            return tagged.generation();
        }
        return Long.MIN_VALUE;
    }

    @Inject(method = "play()V", at = @At("TAIL"))
    private void cueMyMusic$noteAudibleStart(CallbackInfo info) {
        long generation = cueMyMusic$taggedGeneration();
        if (generation == Long.MIN_VALUE) {
            return;
        }
        MusicDirector director = MusicDirector.getInstance();
        // Anchor first, then enforce a held pause directly on this (sound
        // thread) channel: the pause hook below freezes the clock at the
        // offset, so seek-while-paused never blips audible.
        director.noteAudibleStart(generation);
        if (director.shouldStickyPause(generation)) {
            ((Channel) (Object) this).pause();
        }
    }

    @Inject(method = "pause()V", at = @At("HEAD"))
    private void cueMyMusic$noteAudiblePause(CallbackInfo info) {
        long generation = cueMyMusic$taggedGeneration();
        if (generation == Long.MIN_VALUE) {
            return;
        }
        // Only a genuinely playing channel transitions; a no-op pause must
        // not freeze the clock.
        if (((Channel) (Object) this).playing()) {
            MusicDirector.getInstance().noteAudiblePause(generation);
        }
    }

    @Inject(method = "unpause()V", at = @At("HEAD"), cancellable = true)
    private void cueMyMusic$guardAudibleResume(CallbackInfo info) {
        long generation = cueMyMusic$taggedGeneration();
        if (generation == Long.MIN_VALUE) {
            return;
        }
        MusicDirector director = MusicDirector.getInstance();
        if (director.shouldStickyPause(generation)) {
            // Our own resume clears the flag before executing, so only
            // foreign (vanilla global) resumes are denied here.
            info.cancel();
            return;
        }
        director.noteAudibleResume(generation);
    }
}
