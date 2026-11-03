package mix.cuemymusic;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import mix.cuemymusic.library.MusicLibrary;

/**
 * Plays one specific music file (rather than a random pick from an event), from a start offset.
 * Everything else matches the game's own music: the file's volume and pitch from sounds.json,
 * no attenuation, no looping. The event is the music it plays for ("music.game",
 * "music.menu"), so the game can tell whether it's still the right music for where the player is.
 *
 * <p>It ticks so the sound engine takes up its volume every tick, which lets
 * the player fade it out through a portal and back in, without restarting it.
 */
public final class TrackSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    static final Identifier GAME = Identifier.withDefaultNamespace("music.game");

    private final Sound entry;
    private final double startSeconds;
    /** How far it's faded, from 1 (full) to 0 (silent). */
    private volatile float fade = 1;

    /** {@code entry} is the file's sounds.json entry, picked the way the game picks one. */
    public TrackSoundInstance(Sound entry, double startSeconds, Identifier event) {
        super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.entry = entry;
        this.startSeconds = startSeconds;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
    }

    /** Plays {@code track} with its sounds.json entry, or plain settings if no music event lists it. */
    public static TrackSoundInstance of(Identifier track, double startSeconds, Identifier event) {
        Sound entry = MusicLibrary.sound(track);
        if (entry == null) entry = new Sound(track, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
        return new TrackSoundInstance(entry, startSeconds, event);
    }

    public Identifier track() {
        return entry.getLocation();
    }

    public double startSeconds() {
        return startSeconds;
    }

    public void setFade(float fade) {
        this.fade = fade;
    }

    @Override
    public float getVolume() {
        return super.getVolume() * fade;
    }

    @Override
    public boolean isStopped() {
        return false;
    }

    @Override
    public void tick() {
    }

    @Override
    public WeighedSoundEvents getOrResolve(SoundManager manager) {
        WeighedSoundEvents event = manager.getSoundEvent(getIdentifier());
        this.soundEvent = event != null ? event : new WeighedSoundEvents(getIdentifier(), null);
        // The game's empty sounds are kept as they are, so they fail to play just as they would for the game.
        // Anything else is always streamed: it sounds the same, and streams are what the player follows.
        boolean empty = entry == SoundManager.EMPTY_SOUND || entry == SoundManager.INTENTIONALLY_EMPTY_SOUND;
        this.sound = empty || entry.shouldStream() ? entry
                : new Sound(entry.getLocation(), entry.getVolume(), entry.getPitch(), entry.getWeight(), entry.getType(),
                        true, entry.shouldPreload(), entry.getAttenuationDistance());
        return this.soundEvent;
    }
}
