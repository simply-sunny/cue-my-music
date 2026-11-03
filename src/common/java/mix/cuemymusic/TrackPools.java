package mix.cuemymusic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Your changes to the game's track pools (the music it picks from in a
 * place, such as "music.overworld.forest"): songs taken out of a pool, and
 * songs put into one they aren't in. The game's own pools are never changed;
 * these are laid over them, and a pool's reset just drops its edit.
 */
public final class TrackPools {
    private TrackPools() {
    }

    /** A pool's changes: the songs taken out, and the songs put in, as sound file ids. */
    public record Edit(List<String> removed, List<String> added) {
        public static final Edit NONE = new Edit(List.of(), List.of());

        public Edit {
            removed = removed == null ? List.of() : removed.stream().filter(java.util.Objects::nonNull).distinct().toList();
            added = added == null ? List.of() : added.stream().filter(java.util.Objects::nonNull).distinct().toList();
        }

        public boolean isEmpty() {
            return removed.isEmpty() && added.isEmpty();
        }

        /** Takes {@code song} out of the pool: out of what was put in, or out of the game's own songs. */
        public Edit remove(String song, boolean inGamePool) {
            List<String> in = new ArrayList<>(added);
            boolean wasAdded = in.remove(song);
            List<String> out = new ArrayList<>(removed);
            if (inGamePool && !out.contains(song)) out.add(song);
            return wasAdded && !inGamePool ? new Edit(removed, in) : new Edit(out, in);
        }

        /** Puts {@code song} in the pool: back, if it was taken out of the game's own songs, else as an extra. */
        public Edit add(String song, boolean inGamePool) {
            List<String> out = new ArrayList<>(removed);
            out.remove(song);
            List<String> in = new ArrayList<>(added);
            if (!inGamePool && !in.contains(song)) in.add(song);
            return new Edit(out, in);
        }
    }

    /** The pool's songs with the edit laid over: the game's own minus the ones taken out, then the ones put in. */
    public static List<String> apply(List<String> gamePool, Edit edit) {
        Set<String> out = new LinkedHashSet<>(gamePool);
        out.removeAll(edit.removed());
        out.addAll(edit.added());
        return List.copyOf(out);
    }

    /**
     * Which of a pool's songs the game draws next, as the index of a song put
     * in, or -1 for one of the game's own (drawn by the game's weights, then
     * drawn again while it's one taken out). Each song put in is as likely as
     * one of the game's own that's left. {@code r} is uniform in [0, 1).
     */
    public static int choose(int gameLeft, int added, double r) {
        if (added <= 0) return -1;
        int all = gameLeft + added;
        int i = (int) Math.floor(r * all);
        return i < gameLeft ? -1 : Math.min(i - gameLeft, added - 1);
    }
}
