package mix.cuemymusic.mixin;

import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.sounds.AudioStream;

import mix.cuemymusic.CueStream;

/**
 * Music goes straight to the left and right speakers. With Directional Audio
 * on, OpenAL otherwise plays a stereo source through two virtual speakers in
 * front of the head (HRTF), which makes music sound hollow on headphones.
 * Sound effects keep their 3D sound.
 */
@Mixin(Channel.class)
abstract class ChannelMixin {
    // AL_SOFT_direct_channels and AL_SOFT_direct_channels_remix.
    private static final int AL_DIRECT_CHANNELS_SOFT = 0x1033;
    private static final int AL_REMIX_UNMATCHED_SOFT = 0x0002;

    @Shadow @Final private int source;

    @Inject(method = "attachBufferStream", at = @At("TAIL"))
    private void cuemymusic$directChannels(AudioStream stream, CallbackInfo ci) {
        if (!(stream instanceof CueStream) || stream.getFormat().getChannels() != 2) return;
        if (!AL10.alIsExtensionPresent("AL_SOFT_direct_channels")) return;
        // Remix, where there's an extension for it, mixes channels the output lacks (a mono device) instead of dropping them.
        int mode = AL10.alIsExtensionPresent("AL_SOFT_direct_channels_remix") ? AL_REMIX_UNMATCHED_SOFT : AL10.AL_TRUE;
        AL10.alSourcei(source, AL_DIRECT_CHANNELS_SOFT, mode);
    }
}
