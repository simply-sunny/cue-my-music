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
 * <p>Plain Java, so the rules can be checked without the game.
 */
public final class PlayOrder<T> {
    private final List<T> queue = new ArrayList<>();
    private List<T> list = List.of();
    private Object listKey;
    /** The list's song that's playing, or that was before the queue started. */
    private int cursor = -1;
    /** True if the song that's playing came from the queue. */
    private boolean fromQueue;

    /** Starts {@code list} at {@code index}, which plays through to the end. The queue is kept. */
    public T play(Object key, List<T> list, int index) {
        Objects.checkIndex(index, list.size());
        this.list = List.copyOf(list);
        this.listKey = key;
        cursor = index;
        fromQueue = false;
        return this.list.get(index);
    }

    /** The song after the current one: the queue's head, else the list's next. Null hands back to the game. */
    public T next() {
        if (!queue.isEmpty()) {
            fromQueue = true;
            return queue.removeFirst();
        }
        fromQueue = false;
        if (cursor + 1 < list.size()) return list.get(++cursor);
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
        return queue.isEmpty() && cursor + 1 < list.size() ? list.get(cursor + 1) : null;
    }

    /** The key of the list that's playing through, or null. */
    public Object listKey() {
        return listKey;
    }

    /** Stops playing through the list, such as when the game starts its own song. */
    public void endList() {
        list = List.of();
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
}
