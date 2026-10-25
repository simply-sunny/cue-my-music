package mix.cuemymusic;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * Plays one specific music file (rather than a random pick from an event), from a start offset.
 * The event is the music it plays for ("music.game", "music.menu"), so the game can tell
 * whether it's still the right music for where the player is.
 */
public final class TrackSoundInstance extends AbstractSoundInstance {
    static final Identifier GAME = Identifier.withDefaultNamespace("music.game");

    private final Identifier track;
    private final double startSeconds;

    public TrackSoundInstance(Identifier track, double startSeconds, Identifier event) {
        super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.track = track;
        this.startSeconds = startSeconds;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
    }

    public Identifier track() {
        return track;
    }

    public double startSeconds() {
        return startSeconds;
    }

    @Override
    public WeighedSoundEvents getOrResolve(SoundManager manager) {
        WeighedSoundEvents event = manager.getSoundEvent(getIdentifier());
        this.soundEvent = event != null ? event : new WeighedSoundEvents(getIdentifier(), null);
        this.sound = new Sound(track, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
        return this.soundEvent;
    }
}
