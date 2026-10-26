package mix.cuemymusic.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;

import mix.cuemymusic.CueConfig;
import mix.cuemymusic.CuePlayer;
import mix.cuemymusic.library.Track;

/**
 * Lets an add-on say something over the start of a song, as a radio DJ does:
 * hand over a clip for the song that plays next, and when that song starts
 * from the beginning (or is blended into), the music dips and the clip plays
 * over it. The clip goes out with the music, so it pauses with it, follows
 * the music volume, and stays out of recordings when the music does.
 */
public final class VoiceOver {
    /** A voice clip: mono samples from -1 to 1 at {@code rate}, said at {@code volume} (1 as recorded). */
    public record Clip(float[] samples, float rate, double volume) {
    }

    private static final Map<Identifier, Clip> WAITING = new ConcurrentHashMap<>();

    private VoiceOver() {
    }

    /** Says {@code clip} over {@code track} the next time it starts. Replaces a clip already waiting for it. */
    public static void before(Track track, Clip clip) {
        WAITING.put(track.location(), clip);
    }

    /** Drops the clip waiting for {@code track}, if any. */
    public static void cancel(Track track) {
        WAITING.remove(track.location());
    }

    /** The song playing, or null. */
    public static Track playing() {
        return CuePlayer.INSTANCE.current();
    }

    /** The song that plays after the one playing: the queue's first, the list's next, or the game's pick. Null if unknown. */
    public static Track following() {
        return CuePlayer.INSTANCE.following();
    }

    /** False while Cue My Music is off for vanilla music, when nothing is said. */
    public static boolean active() {
        return !CueConfig.INSTANCE.vanilla;
    }

    /** For the player: takes the clip waiting for the song with this id, or null. */
    public static Clip take(Identifier track) {
        return WAITING.remove(track);
    }
}
