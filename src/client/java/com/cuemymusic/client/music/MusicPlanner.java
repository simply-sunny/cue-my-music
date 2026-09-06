package com.cuemymusic.client.music;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Deterministic session planner for vanilla background music.
 *
 * <p>One session seed; the context event id plus a monotonic forward index
 * determine each selection seed. A separate stream is never shared with
 * vanilla delay logic (vanilla keeps its own delay RNG; this planner only
 * feeds sound selection). History holds at most 8 entries, newest first.
 * Previous walks through history and updates the upcoming queue to reflect
 * the timeline following the historical song.
 */
public final class MusicPlanner {
    static final int HISTORY_LIMIT = 8;

    /** A played track: the context event it started under, actual file, and selection index. */
    public record Entry(String eventId, String filePath, long index) {
        public Entry(String eventId, String filePath) {
            this(eventId, filePath, -1L);
        }
    }

    private long sessionSeed = ThreadLocalRandom.current().nextLong();
    private long sequence;
    private final Map<String, Long> contextSequences = new HashMap<>();
    private final ArrayList<Entry> history = new ArrayList<>();
    private int cursor;

    public long getSessionSeed() {
        return sessionSeed;
    }

    public void setSessionSeed(long sessionSeed) {
        this.sessionSeed = sessionSeed;
    }

    /** Claims the next monotonic forward index for a context event. */
    public long nextSequence(String eventId) {
        long seq = contextSequences.getOrDefault(eventId, 0L);
        contextSequences.put(eventId, seq + 1);
        this.sequence = seq + 1;
        return seq;
    }

    public long nextSequence() {
        return sequence++;
    }

    public long peekSequence(String eventId) {
        return contextSequences.getOrDefault(eventId, 0L);
    }

    public long peekSequence() {
        return sequence;
    }

    public void setSequence(String eventId, long seq) {
        contextSequences.put(eventId, seq);
        this.sequence = seq;
    }

    /**
     * Pure deterministic selection seed for a context event and forward index.
     * Returning to a context at the same index reproduces its seed.
     */
    public static long selectionSeed(long sessionSeed, String eventId, long index) {
        long hash = sessionSeed
                ^ (Integer.toUnsignedLong(eventId.hashCode()) * 0x9E3779B97F4A7C15L)
                ^ (index + 0x9E3779B97F4A7C15L);
        hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        return hash ^ (hash >>> 31);
    }

    /** Records a forward play; resets the back-scan cursor. */
    public void record(Entry entry) {
        history.add(0, entry);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
        cursor = 0;
    }

    /**
     * Scans history behind the cursor for the newest entry whose actual file
     * is eligible in the current context. Updates cursor and aligns upcoming
     * sequence to the track following the historical song.
     */
    public Optional<Entry> previousIn(Set<String> eligibleFiles) {
        for (int i = cursor + 1; i < history.size(); i++) {
            Entry entry = history.get(i);
            if (eligibleFiles.contains(entry.filePath())) {
                cursor = i;
                if (entry.index() >= 0) {
                    setSequence(entry.eventId(), entry.index() + 1);
                }
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean hasPreviousIn(Set<String> eligibleFiles) {
        for (int i = cursor + 1; i < history.size(); i++) {
            if (eligibleFiles.contains(history.get(i).filePath())) {
                return true;
            }
        }
        return false;
    }

    public int getCursor() {
        return cursor;
    }

    public List<Entry> historySnapshot() {
        return List.copyOf(history);
    }

    /** Drops stale references (e.g. events gone after a resource reload). */
    public void prune(Predicate<Entry> keep) {
        history.removeIf(keep.negate());
        if (cursor >= history.size()) {
            cursor = Math.max(0, history.size() - 1);
        }
    }

    /** Clears history, cursor and forward index; the session seed is kept. */
    public void reset() {
        history.clear();
        contextSequences.clear();
        cursor = 0;
        sequence = 0;
    }
}
