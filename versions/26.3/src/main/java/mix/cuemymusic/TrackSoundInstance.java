package mix.cuemymusic;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/** Plays one specific music file (rather than a random pick from an event), from a start offset. */
public final class TrackSoundInstance extends AbstractSoundInstance {
    private static final Identifier EVENT = Identifier.withDefaultNamespace("music.game");

    private final Identifier track;
    private final double startSeconds;

    public TrackSoundInstance(Identifier track, double startSeconds) {
        super(EVENT, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.track = track;
        this.startSeconds = startSeconds;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
    }

    public double startSeconds() {
        return startSeconds;
    }

    @Override
    public WeighedSoundEvents getOrResolve(SoundManager manager) {
        WeighedSoundEvents event = manager.getSoundEvent(EVENT);
        this.soundEvent = event != null ? event : new WeighedSoundEvents(EVENT, null);
        this.sound = new Sound(track, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
        return this.soundEvent;
    }
}
