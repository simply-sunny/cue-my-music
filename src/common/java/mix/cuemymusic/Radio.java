package mix.cuemymusic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Picks Radio's songs, one after another without end. Each pick is drawn at
 * random, weighted towards songs you like (liked, or played through more
 * than skipped), songs like the one it started from (same artist or album),
 * and songs that follow on well from the last pick (a close tempo and a key
 * that mixes). Disliked songs never play, and a song doesn't come round
 * again until most of the others have.
 *
 * <p>Plain Java, so the picking can be checked without the game.
 */
public final class Radio {
    /** What Radio knows of a song. {@code bpm} is 0 and {@code key} empty when they aren't known. */
    public record Song(String id, String artist, String album, double bpm, String key, boolean liked, boolean disliked,
            int plays, int skips) {
    }

    private final Random random;

    public Radio(long seed) {
        random = new Random(seed);
    }

    /**
     * {@code count} songs from {@code pool} to play after {@code played}
     * (oldest first). {@code seed} is the song the station started from, or
     * null for one started from everything.
     */
    public List<Song> next(List<Song> pool, Song seed, List<String> played, int count) {
        List<Song> candidates = pool.stream().filter(s -> !s.disliked()).toList();
        List<Song> picks = new ArrayList<>();
        if (candidates.isEmpty()) return picks;
        List<String> history = new ArrayList<>(played);
        Song last = seed;
        for (int n = 0; n < count; n++) {
            // Nothing again until about two thirds of the others have played.
            int window = Math.max(1, candidates.size() * 2 / 3);
            Set<String> recent = new HashSet<>(history.subList(Math.max(0, history.size() - window), history.size()));
            double total = 0;
            double[] weights = new double[candidates.size()];
            for (int i = 0; i < weights.length; i++) {
                Song c = candidates.get(i);
                if (recent.contains(c.id()) || (last != null && c.id().equals(last.id()) && candidates.size() > 1)) continue;
                weights[i] = Math.exp(score(c, seed, last));
                total += weights[i];
            }
            if (total <= 0) {
                // Everything played recently: start the round again. Only the last song is still left out.
                history.clear();
                n--;
                continue;
            }
            double r = random.nextDouble() * total;
            int chosen = weights.length - 1;
            for (int i = 0; i < weights.length; i++) {
                r -= weights[i];
                if (r < 0 && weights[i] > 0) {
                    chosen = i;
                    break;
                }
            }
            while (weights[chosen] <= 0) chosen--;
            Song pick = candidates.get(chosen);
            picks.add(pick);
            history.add(pick.id());
            last = pick;
        }
        return picks;
    }

    /** How strongly to lean towards {@code c}, on a log scale: 0 is an ordinary song. */
    static double score(Song c, Song seed, Song last) {
        double s = 0;
        if (c.liked()) s += 1.2;
        int heard = c.plays() + c.skips();
        // Played through or skipped: how often, once there's enough to go on.
        if (heard >= 3) s += 1.5 * (c.plays() / (double) heard - 0.5);
        else if (heard == 0) s += 0.2;
        if (seed != null) {
            if (!c.artist().isBlank() && c.artist().equals(seed.artist())) s += 0.8;
            if (!c.album().isBlank() && c.album().equals(seed.album())) s += 0.4;
        }
        if (last != null) {
            if (c.bpm() > 0 && last.bpm() > 0) s += 0.8 * (1 - Math.min(1, Automix.tempoGap(c.bpm(), last.bpm()) / 0.12));
            if (!c.key().isEmpty() && !last.key().isEmpty() && Automix.keysMix(c.key(), last.key())) s += 0.4;
        }
        return s;
    }
}
