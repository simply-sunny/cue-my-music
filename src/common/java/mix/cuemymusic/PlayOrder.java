package mix.cuemymusic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which song plays next: the queue, then the list the player is playing
 * through, then nothing (the game's schedule takes over).
 *
 * <p>The list is the page a song was started from, in its on-screen order. It
 * isn't copied into the queue. Queued songs play before the list carries on,
 * and the list then resumes after the song that was playing before the queue
 * started. Previous only walks the list.
 *
 * <p>Repeat all starts the list over at its end, and repeat one plays the
 * song that just ended again. Next and previous still move on either way.
 *
 * <p>Shuffle, as on Spotify, plays the song picked first and every other
 * song of the list in a random order, each once; turning it off goes back to
 * the list's own order after the song playing.
 *
 * <p>Plain Java, so the rules can be checked without the game.
 */
public final class PlayOrder<T> {
    public enum Repeat {
        OFF, ALL, ONE;

        /** Off, all, one, then off again: the order the repeat button steps through. */
        public Repeat cycle() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final List<T> queue = new ArrayList<>();
    private List<T> list = List.of();
    private Object listKey;
    /** The list's song that's playing, or that was before the queue started. */
    private int cursor = -1;
    /** True if the song that's playing came from the queue. */
    private boolean fromQueue;
    private Repeat repeat = Repeat.OFF;
    private boolean shuffle;
    /** While shuffled, the list in its own order, so turning shuffle off can go back to it. */
    private List<T> unshuffled = List.of();
    private final java.util.Random random;

    public PlayOrder() {
        this(new java.util.Random());
    }

    /** With {@code random} picking the shuffled order, so tests can fix it. */
    public PlayOrder(java.util.Random random) {
        this.random = random;
    }

    public boolean shuffle() {
        return shuffle;
    }

    /** Mixes the list's other songs to come after the one playing, or puts the list back in its own order around it. */
    public void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        if (on) {
            // The whole list but the song playing, mixed, as on Spotify: songs already heard come round again.
            unshuffled = list;
            List<T> rest = new ArrayList<>(list);
            T playing = cursor >= 0 && cursor < list.size() ? rest.remove(cursor) : null;
            java.util.Collections.shuffle(rest, random);
            if (playing != null) rest.addFirst(playing);
            list = List.copyOf(rest);
            cursor = playing == null ? -1 : 0;
        } else {
            T playing = cursor >= 0 && cursor < list.size() ? list.get(cursor) : null;
            list = unshuffled;
            unshuffled = List.of();
            cursor = playing == null ? -1 : list.indexOf(playing);
        }
    }

    public Repeat repeat() {
        return repeat;
    }

    public void setRepeat(Repeat repeat) {
        this.repeat = repeat;
    }

    /** What plays after {@code ended} finishes by itself: the same song again on repeat one, otherwise {@link #next}. */
    public T finished(T ended) {
        return repeat == Repeat.ONE && ended != null ? ended : next();
    }

    /** Starts {@code list} at {@code index}, which plays through to the end. The queue is kept. */
    public T play(Object key, List<T> list, int index) {
        Objects.checkIndex(index, list.size());
        this.list = List.copyOf(list);
        this.listKey = key;
        cursor = index;
        fromQueue = false;
        if (shuffle) {
            // The song picked, then the rest of the list mixed.
            T picked = this.list.get(index);
            List<T> rest = new ArrayList<>(this.list);
            rest.remove(index);
            java.util.Collections.shuffle(rest, random);
            rest.addFirst(picked);
            unshuffled = this.list;
            this.list = List.copyOf(rest);
            cursor = 0;
        }
        return this.list.get(cursor);
    }

    /** The song after the current one: the queue's head, else the list's next. Null hands back to the game. */
    public T next() {
        if (!queue.isEmpty()) {
            fromQueue = true;
            return queue.removeFirst();
        }
        fromQueue = false;
        if (cursor + 1 < list.size()) return list.get(++cursor);
        if (repeat != Repeat.OFF && !list.isEmpty()) {
            cursor = 0;
            return list.getFirst();
        }
        endList();
        return null;
    }

    /**
     * The list's previous song. After a queued song that's the list song
     * the queue interrupted. Null at the start of the list, or with no list.
     */
    public T previous() {
        if (fromQueue && cursor >= 0 && cursor < list.size()) {
            fromQueue = false;
            return list.get(cursor);
        }
        if (cursor <= 0) return null;
        fromQueue = false;
        return list.get(--cursor);
    }

    /** What {@link #next} would play from the list, if the queue is empty. Null otherwise. */
    public T listNext() {
        if (!queue.isEmpty() || list.isEmpty()) return null;
        if (cursor + 1 < list.size()) return list.get(cursor + 1);
        return repeat != Repeat.OFF ? list.getFirst() : null;
    }

    /** Songs left in the list after the one playing (or the one the queue interrupted). */
    public int remaining() {
        return Math.max(0, list.size() - 1 - cursor);
    }

    /** Adds songs to the end of the list that's playing through, as Radio does to keep going. */
    public void extendList(List<T> more) {
        List<T> next = new ArrayList<>(list);
        next.addAll(more);
        list = List.copyOf(next);
        if (shuffle) {
            List<T> own = new ArrayList<>(unshuffled);
            own.addAll(more);
            unshuffled = List.copyOf(own);
        }
    }

    /**
     * Replaces the songs after the one playing, keeping that one and the songs
     * before it, as when Radio's choice of music changes.
     */
    public void replaceRest(List<T> rest) {
        List<T> next = new ArrayList<>(list.subList(0, Math.min(list.size(), cursor + 1)));
        next.addAll(rest);
        list = List.copyOf(next);
        if (shuffle) unshuffled = list;
    }

    /**
     * Takes out every song, queued or in the list, that {@code keep} turns
     * down, as when the mode changes. Songs still to come play in the same
     * order; if the one playing is taken out, the list carries on after it.
     */
    public void retain(java.util.function.Predicate<T> keep) {
        queue.removeIf(keep.negate());
        if (list.isEmpty()) return;
        List<T> kept = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            if (keep.test(list.get(i))) kept.add(list.get(i));
            if (i == cursor) at = kept.size() - 1;
        }
        if (kept.isEmpty()) {
            endList();
            return;
        }
        list = List.copyOf(kept);
        cursor = at;
        if (shuffle) unshuffled = unshuffled.stream().filter(keep).toList();
    }

    /** Skips the list ahead to its song {@code k} places after the one playing, as clicking it under Next from does. */
    public T jump(int k) {
        int to = cursor + 1 + k;
        if (k < 0 || to >= list.size()) return null;
        cursor = to;
        fromQueue = false;
        return list.get(cursor);
    }

    /**
     * Moves the list's song {@code from} places after the one playing so it
     * ends up {@code to} places after it, as dragging under Next from does.
     * Only the order it plays in changes, not the list it came from.
     */
    public void moveUpNext(int from, int to) {
        int base = Math.max(0, cursor + 1);
        if (from < 0 || base + from >= list.size()) return;
        List<T> next = new ArrayList<>(list);
        T song = next.remove(base + from);
        next.add(Math.clamp(base + (long) to, base, next.size()), song);
        list = List.copyOf(next);
    }

    /** Up to {@code n} of the list's songs still to come after the one playing, in the order they'll play. */
    public List<T> upNext(int n) {
        int from = Math.min(list.size(), Math.max(0, cursor + 1));
        return list.subList(from, Math.min(list.size(), from + n));
    }

    /** The list playing through, in order. */
    public List<T> list() {
        return list;
    }

    /** The key of the list that's playing through, or null. */
    public Object listKey() {
        return listKey;
    }

    /** Stops playing through the list, such as when the game starts its own song. */
    public void endList() {
        list = List.of();
        unshuffled = List.of();
        listKey = null;
        cursor = -1;
        fromQueue = false;
    }

    // Queue.

    public List<T> queue() {
        return List.copyOf(queue);
    }

    public void enqueue(T song) {
        queue.add(song);
    }

    /** Appends every song of {@code songs}, in order. */
    public void enqueueAll(List<T> songs) {
        queue.addAll(songs);
    }

    public void clearQueue() {
        queue.clear();
    }

    public void unqueue(int index) {
        if (index >= 0 && index < queue.size()) queue.remove(index);
    }

    /** Moves the queued song at {@code from} so it ends up at {@code to}. */
    public void moveQueued(int from, int to) {
        if (from < 0 || from >= queue.size()) return;
        T song = queue.remove(from);
        queue.add(Math.clamp(to, 0, queue.size()), song);
    }

    /** Takes the queued song at {@code index} to play now. The list resumes after it as after any queued song. */
    public T playQueued(int index) {
        if (index < 0 || index >= queue.size()) return null;
        fromQueue = true;
        return queue.remove(index);
    }

    /**
     * {@code list} with the items in {@code moving} taken out and put back,
     * in their order in the list, just before {@code before}, or at the end
     * if that's null. Dropped on one of themselves, nothing moves. For
     * dragging songs in a playlist.
     */
    public static <E> java.util.List<E> moved(java.util.List<E> list, java.util.Collection<E> moving, E before) {
        if (before != null && moving.contains(before)) return list;
        java.util.List<E> taken = list.stream().filter(moving::contains).toList();
        java.util.List<E> rest = new java.util.ArrayList<>(list.stream().filter(e -> !moving.contains(e)).toList());
        int at = before == null ? rest.size() : rest.indexOf(before);
        rest.addAll(at < 0 ? rest.size() : at, taken);
        return rest;
    }
}
