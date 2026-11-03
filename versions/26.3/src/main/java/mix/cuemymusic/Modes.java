package mix.cuemymusic;

import java.util.List;

import net.minecraft.client.Minecraft;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * The three modes: "vanilla" (pure vanilla, the mod off), "fx" (the game's
 * music, with pitch, speed, the equalizer and the time between songs) and
 * "addons" (songs from packs and add-ons, on the game's schedule).
 */
public final class Modes {
    public static final List<String> ALL = List.of("vanilla", "fx", "addons");

    private Modes() {
    }

    public static String current() {
        CueConfig cfg = CueConfig.INSTANCE;
        return cfg.vanilla ? "vanilla" : cfg.mode;
    }

    /**
     * Whether {@code t} belongs to the mode: only the game's songs in Vanilla + FX,
     * only add-on songs in Add-ons. The two never mix.
     */
    public static boolean allows(Track t) {
        String m = current();
        return m.equals("vanilla") || MusicLibrary.isGameMusic(t) == m.equals("fx");
    }

    /** The songs of {@code tracks} that belong to the mode, in order. */
    public static List<Track> only(List<Track> tracks) {
        return tracks.stream().filter(Modes::allows).toList();
    }

    /**
     * The mode the mode key goes to: Vanilla + FX and Add-ons in turn, or round all three.
     * From pure vanilla without going round, back to the mode it was turned on from.
     */
    public static String afterCurrent() {
        String now = current();
        if (CueConfig.INSTANCE.modeKeyCyclesAll) return ALL.get((ALL.indexOf(now) + 1) % ALL.size());
        if (now.equals("vanilla")) return CueConfig.INSTANCE.mode;
        return now.equals("addons") ? "fx" : "addons";
    }

    /**
     * Switches mode. Pure vanilla turns the mod off. The other two turn it on,
     * stop a song from the other mode's music, point Radio at the same music,
     * and have the game's next song picked again from it. Returns false if it was already in that mode.
     */
    public static boolean set(String mode) {
        CueConfig cfg = CueConfig.INSTANCE;
        if (mode.equals(current())) return false;
        if (mode.equals("vanilla")) {
            setVanilla(true);
        } else {
            cfg.mode = mode;
            cfg.radioSource = mode.equals("addons") ? "addons" : "game";
            if (cfg.vanilla) setVanilla(false);
            CuePlayer.INSTANCE.modeChanged();
            CuePlayer.INSTANCE.refreshRadio();
            CuePlayer.INSTANCE.songGapChanged();
            cfg.save();
        }
        return true;
    }

    /** Turns vanilla music on (the mod off: the player stops and the game takes over its music) or off. */
    public static void setVanilla(boolean on) {
        CueConfig cfg = CueConfig.INSTANCE;
        if (on) CuePlayer.INSTANCE.stopForVanilla();
        cfg.vanilla = on;
        cfg.save();
        // Back on: the game's own song stops so Cue My Music picks the next one.
        if (!on) Minecraft.getInstance().getMusicManager().stopPlaying();
    }
}
