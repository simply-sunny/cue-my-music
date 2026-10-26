package mix.cuemymusic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.locale.Language;
import net.minecraft.sounds.Music;
import net.minecraft.util.RandomSource;

import mix.cuemymusic.CuePlayer;
import mix.cuemymusic.TrackSoundInstance;
import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * The game keeps deciding when music plays (the break between songs, music
 * that changes with where the player is), but the songs themselves are
 * played by the player, so its queue can show what's coming.
 */
@Mixin(MusicManager.class)
abstract class MusicManagerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private SoundInstance currentMusic;
    @Shadow private int nextSongDelay;
    @Shadow private boolean toastShown;

    /** Paused: the game doesn't count down to another song. */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cuemymusic$holdWhilePaused(CallbackInfo ci) {
        if (CuePlayer.INSTANCE.isPaused()) ci.cancel();
    }

    /** The break after a song is the one the queue has been showing. */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/MusicManager$MusicFrequency;getNextSongDelay(Lnet/minecraft/sounds/Music;Lnet/minecraft/util/RandomSource;)I"))
    private int cuemymusic$plannedBreak(MusicManager.MusicFrequency frequency, Music music, RandomSource random,
            Operation<Integer> original) {
        return CuePlayer.INSTANCE.takeBreak(music, frequency, () -> original.call(frequency, music, random));
    }

    /** Music that replaces the current song (the boss fight, the credits) stops it on purpose: it didn't end. */
    @Inject(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"))
    private void cuemymusic$replaced(CallbackInfo ci) {
        if (CuePlayer.INSTANCE.isOurs(currentMusic)) CuePlayer.INSTANCE.stoppedByGame();
    }

    /** Songs picked in the player aren't replaced. */
    @Inject(method = "canReplace", at = @At("HEAD"), cancellable = true)
    private static void cuemymusic$keepPicked(Music music, SoundInstance current, CallbackInfoReturnable<Boolean> cir) {
        if (CuePlayer.INSTANCE.isPicked(current)) cir.setReturnValue(false);
    }

    /** The game's next song is played by the player: the one it planned. */
    @Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true)
    private void cuemymusic$playPlanned(Music music, CallbackInfo ci) {
        SoundEngine.PlayResult result = CuePlayer.INSTANCE.playForGame(music);
        if (result == null) return;
        switch (result) {
            case STARTED -> {
                minecraft.gui.toastManager().showNowPlayingToast();
                toastShown = true;
            }
            case STARTED_SILENTLY -> toastShown = false;
            default -> {
            }
        }
        nextSongDelay = Integer.MAX_VALUE;
        ci.cancel();
    }

    /** Changing dimension stops the game's songs, but not ones picked in the player. */
    @Inject(method = "stopPlaying()V", at = @At("HEAD"), cancellable = true)
    private void cuemymusic$stopPlaying(CallbackInfo ci) {
        CuePlayer player = CuePlayer.INSTANCE;
        if (player.isPicked(currentMusic)) {
            ci.cancel();
        } else if (player.isOurs(currentMusic)) {
            player.stoppedByGame();
        }
    }

    /** The "Now playing" toast: the song's name in the game's language if it has one, otherwise the player's credits. */
    @Inject(method = "getCurrentMusicTranslationKey", at = @At("HEAD"), cancellable = true)
    private void cuemymusic$songName(CallbackInfoReturnable<String> cir) {
        if (!(currentMusic instanceof TrackSoundInstance t)) return;
        String key = t.track().toShortLanguageKey();
        if (Language.getInstance().has(key.replace("/", "."))) return;
        Track track = MusicLibrary.get(t.track());
        cir.setReturnValue((track.artist() + " - " + track.title()).replace("/", " "));
    }
}
