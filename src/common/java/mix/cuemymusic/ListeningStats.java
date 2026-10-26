package mix.cuemymusic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What's been listened to: time per song, hour of the day, calendar day,
 * dimension, and who picked it, plus plays and skips.
 *
 * <p>Nothing about a song is kept until {@link #MIN_SECONDS} of it have been
 * heard, so clicking through songs, or a song cut off as it starts, leaves no
 * trace. After that it's a play at {@link #PLAY_SECONDS}, or when it ends by
 * itself having been heard for half its length (so jumping to the last few
 * seconds isn't a play); moving on before either is a skip.
 *
 * <p>Plain Java, so the counting can be checked without the game. Saved by
 * {@code StatsFile}.
 */
public final class ListeningStats {
    public static final ListeningStats INSTANCE = new ListeningStats();
    public static final double PLAY_SECONDS = 30;
    public static final double MIN_SECONDS = 3;

    /** One song's totals. */
    public static final class Song {
        public int plays;
        public int skips;
        public double seconds;
        /** Epoch milliseconds. */
        public long firstPlayed;
        public long lastPlayed;
    }

    // Saved.
    private Map<String, Song> songs = new HashMap<>();
    private double[] hours = new double[24];
    /** Seconds per calendar day, "2026-10-26". */
    private Map<String, Double> days = new TreeMap<>();
    private Map<String, Double> dimensions = new HashMap<>();
    private double pickedSeconds;
    private double gameSeconds;
    /** Time heard with pitch, speed or EQ changed. */
    private double effectSeconds;
    /** When counting started, epoch milliseconds; 0 before anything was heard. */
    private long since;

    // The song being heard now.
    private transient String nowSong;
    private transient double nowSeconds;
    private transient boolean counted;
    /** True once {@link #MIN_SECONDS} of the song have been heard and it's being recorded. */
    private transient boolean kept;
    /** What was heard of the song before it was kept. */
    private transient List<Heard> held = new ArrayList<>();

    private record Heard(double seconds, LocalDateTime when, String dimension, boolean picked, boolean effects) {
    }

    public synchronized void started(String song) {
        nowSong = song;
        nowSeconds = 0;
        counted = false;
        kept = false;
        held.clear();
    }

    /** Adds {@code seconds} of {@code song} heard just now. */
    public synchronized void listened(String song, double seconds, LocalDateTime when, String dimension, boolean picked,
            boolean effects) {
        if (seconds <= 0) return;
        if (!song.equals(nowSong)) started(song);
        nowSeconds += seconds;
        Heard h = new Heard(seconds, when, dimension, picked, effects);
        if (kept) {
            add(song, h);
        } else {
            held.add(h);
            if (nowSeconds >= MIN_SECONDS) keep();
        }
        if (kept && !counted && nowSeconds >= PLAY_SECONDS) count(songs.get(song));
    }

    /** The song is long enough heard to record: everything held for it goes in. */
    private void keep() {
        kept = true;
        for (Heard h : held) add(nowSong, h);
        held.clear();
    }

    private void add(String song, Heard h) {
        long millis = h.when().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (since == 0) since = millis;
        Song s = songs.computeIfAbsent(song, k -> new Song());
        if (s.firstPlayed == 0) s.firstPlayed = millis;
        s.lastPlayed = millis;
        s.seconds += h.seconds();
        hours[h.when().getHour()] += h.seconds();
        days.merge(h.when().toLocalDate().toString(), h.seconds(), Double::sum);
        if (h.dimension() != null) dimensions.merge(h.dimension(), h.seconds(), Double::sum);
        if (h.picked()) pickedSeconds += h.seconds();
        else gameSeconds += h.seconds();
        if (h.effects()) effectSeconds += h.seconds();
    }

    /**
     * The song ended by itself. A play if at least half of its {@code length}
     * seconds were heard (any of it, for a song shorter than {@link #MIN_SECONDS}).
     * NaN for an unknown length counts it as a play.
     */
    public synchronized void finished(String song, double length) {
        if (!song.equals(nowSong) || counted || nowSeconds <= 0) return;
        if (!Double.isNaN(length) && nowSeconds < Math.min(PLAY_SECONDS, length / 2)) return;
        if (!kept) keep();
        count(songs.get(song));
    }

    /** Moved on from the song: a skip if it was heard long enough to keep, but not to count as played. */
    public synchronized void skipped(String song) {
        if (song.equals(nowSong) && kept && !counted) songs.get(song).skips++;
        nowSong = null;
        held.clear();
    }

    private void count(Song s) {
        s.plays++;
        counted = true;
    }

    // Reading.

    public synchronized boolean isEmpty() {
        return songs.isEmpty();
    }

    public synchronized double totalSeconds() {
        return pickedSeconds + gameSeconds;
    }

    public synchronized int totalPlays() {
        return songs.values().stream().mapToInt(s -> s.plays).sum();
    }

    public synchronized double pickedSeconds() {
        return pickedSeconds;
    }

    public synchronized double gameSeconds() {
        return gameSeconds;
    }

    public synchronized double effectSeconds() {
        return effectSeconds;
    }

    public synchronized long since() {
        return since;
    }

    public synchronized double[] hours() {
        return hours.clone();
    }

    public synchronized double day(LocalDate date) {
        return days.getOrDefault(date.toString(), 0.0);
    }

    public synchronized Map<String, Double> dimensions() {
        return Map.copyOf(dimensions);
    }

    /** Every song's totals, by song id. */
    public synchronized Map<String, Song> songs() {
        Map<String, Song> copy = new HashMap<>();
        songs.forEach((k, v) -> {
            Song s = new Song();
            s.plays = v.plays;
            s.skips = v.skips;
            s.seconds = v.seconds;
            s.firstPlayed = v.firstPlayed;
            s.lastPlayed = v.lastPlayed;
            copy.put(k, s);
        });
        return copy;
    }

    /** Song ids, most played first; ties go to more time listened. */
    public synchronized List<String> top(int n) {
        List<String> ids = new ArrayList<>(songs.keySet());
        ids.removeIf(id -> songs.get(id).plays == 0);
        ids.sort(Comparator.comparingInt((String id) -> songs.get(id).plays).reversed()
                .thenComparing(Comparator.comparingDouble((String id) -> songs.get(id).seconds).reversed()));
        return List.copyOf(ids.subList(0, Math.min(n, ids.size())));
    }

    /** The song skipped most, or null if none was. */
    public synchronized String mostSkipped() {
        return songs.entrySet().stream().filter(e -> e.getValue().skips > 0)
                .max(Comparator.comparingInt((Map.Entry<String, Song> e) -> e.getValue().skips))
                .map(Map.Entry::getKey).orElse(null);
    }

    /** The hour of the day with the most listening, or -1 with none. */
    public synchronized int peakHour() {
        int best = -1;
        for (int h = 0; h < 24; h++) {
            if (hours[h] > 0 && (best < 0 || hours[h] > hours[best])) best = h;
        }
        return best;
    }

    /** Days in a row with listening, ending today, or yesterday if today has none yet. */
    public synchronized int currentStreak(LocalDate today) {
        LocalDate d = days.containsKey(today.toString()) ? today : today.minusDays(1);
        int n = 0;
        while (days.containsKey(d.toString())) {
            n++;
            d = d.minusDays(1);
        }
        return n;
    }

    public synchronized int longestStreak() {
        int best = 0, run = 0;
        LocalDate prev = null;
        for (String key : days.keySet()) {
            LocalDate d = LocalDate.parse(key);
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        return best;
    }

    // Saving.

    public synchronized void reset() {
        copyFrom(new ListeningStats());
        nowSong = null;
        kept = false;
        held.clear();
    }

    /** Takes over everything saved in {@code o}. For loading. */
    synchronized void copyFrom(ListeningStats o) {
        songs = o.songs != null ? new HashMap<>(o.songs) : new HashMap<>();
        songs.values().removeIf(java.util.Objects::isNull);
        hours = o.hours != null && o.hours.length == 24 ? o.hours.clone() : new double[24];
        days = o.days != null ? new TreeMap<>(o.days) : new TreeMap<>();
        dimensions = o.dimensions != null ? new HashMap<>(o.dimensions) : new HashMap<>();
        pickedSeconds = o.pickedSeconds;
        gameSeconds = o.gameSeconds;
        effectSeconds = o.effectSeconds;
        since = o.since;
    }
}
