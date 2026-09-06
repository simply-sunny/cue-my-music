package com.cuemymusic.client.music;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cuemymusic.mixin.MusicManagerAccessor;
import com.cuemymusic.mixin.SoundEngineProvider;

/**
 * Vanilla music director (approved minimal design).
 *
 * <p>Vanilla {@link MusicManager} keeps owning the tick, current song,
 * fades, delays, streaming backend, replacement rules and toast. This
 * director only answers one question per start — <em>which</em> sound plays
 * for the given situational event — deterministically from the session seed,
 * the context event and a monotonic forward index, using vanilla weights
 * via {@code WeighedSoundEvents.getSound}. At most one materialized entry
 * (a pending Previous pin) exists at a time. Manual Next bypasses the delay
 * but still goes through vanilla loading, context, volume and replacement
 * rules. No standalone playback engine, downloads, cache or codecs.
 */
public final class MusicDirector {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/director");
    private static final MusicDirector INSTANCE = new MusicDirector();

    private final MusicPlanner planner = new MusicPlanner();
    /** The single materialized next entry: a pending Previous file pin. */
    private String pendingPreviousFile;

    /** Transport generation: every start/seek/skip bumps it; stale native work is closed. */
    private final AtomicLong transportGeneration = new AtomicLong(0L);
    private volatile PinnedMusicInstance transportInstance;
    private final TransportClock clock = new TransportClock();
    private volatile double durationSeconds = Double.NaN;
    private volatile boolean transportPaused;
    private final Map<Identifier, OffsetRequest> offsetRequests = new ConcurrentHashMap<>();
    /** Hard ceiling on decoded bytes dropped for one seek (far past any music track). */
    static final long SKIP_BUDGET_BYTES = 256L << 20;
    static final int SKIP_CHUNK_BYTES = 1 << 16;

    /** Pending offset reopen, captured by the play redirect at stream-open time. */
    public record OffsetRequest(long generation, double startSeconds) {
    }

    /**
     * Generation tag around the stream handed to the native channel, so the
     * audible-start/pause hooks correlate channel callbacks with the current
     * transport generation and ignore stale ones. Audio bytes pass through
     * untouched; offset discards happen before wrapping.
     */
    public static final class TaggedStream implements AudioStream {
        private final AudioStream delegate;
        private final long generation;

        public TaggedStream(AudioStream delegate, long generation) {
            this.delegate = delegate;
            this.generation = generation;
        }

        public long generation() {
            return generation;
        }

        @Override
        public AudioFormat getFormat() {
            return delegate.getFormat();
        }

        @Override
        public ByteBuffer read(int size) throws java.io.IOException {
            return delegate.read(size);
        }

        @Override
        public void close() throws java.io.IOException {
            delegate.close();
        }
    }

    private MusicDirector() {
    }

    public static MusicDirector getInstance() {
        return INSTANCE;
    }

    MusicPlanner planner() {
        return planner;
    }

    /**
     * Builds the deterministically pinned instance for a vanilla start.
     * Called from the {@code startPlaying} redirect; never throws.
     */
    public SimpleSoundInstance pinnedInstance(SoundEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        SoundManager sounds = minecraft.getSoundManager();
        Identifier eventId = event.location();
        pruneStaleReferences(sounds);
        WeighedSoundEvents weighed = sounds.getSoundEvent(eventId);
        if (weighed == null) {
            resetTransport();
            return SimpleSoundInstance.forMusic(event);
        }
        String pending = pendingPreviousFile;
        pendingPreviousFile = null;
        if (pending != null) {
            Optional<Sound> leaf = MusicGraph.resolveFile(weighed, pending, sounds::getSoundEvent,
                    MusicGraph::nestedEventId, MusicGraph::nestedDefinition);
            if (leaf.isPresent()) {
                long seed = MusicPlanner.selectionSeed(planner.getSessionSeed(), pending, 0L);
                return trackStart(event.location(), leaf.get(), seed);
            }
            LOGGER.debug("[Cue My Music] pending previous file {} no longer eligible under {}; "
                    + "falling back to fresh selection", pending, eventId);
        }
        long index = planner.nextSequence(eventId.toString());
        long seed = MusicPlanner.selectionSeed(planner.getSessionSeed(), eventId.toString(), index);
        Sound chosen = weighed.getSound(RandomSource.create(seed));
        // Pin exactly what vanilla selection returned, including intentional
        // silence (identifier-compared: delegates build copies, so reference
        // identity would miss nested silence). Never re-roll: a fresh random
        // factory would hide selection bugs behind nondeterminism.
        if (!MusicGraph.isSilence(chosen)) {
            planner.record(new MusicPlanner.Entry(eventId.toString(), chosen.getPath().toString(), index));
        }
        return trackStart(event.location(), chosen, seed);
    }

    /**
     * Tracks a fresh vanilla start as a new transport generation: unpaused,
     * zero offset, unknown duration (probed off-thread), and a registered
     * zero-offset request so the play redirect tags the stream for the
     * audible-start hook even when no seek is involved.
     */
    private PinnedMusicInstance trackStart(Identifier eventId, Sound chosen, long seed) {
        long generation = transportGeneration.incrementAndGet();
        PinnedMusicInstance pin =
                new PinnedMusicInstance(eventId, chosen, RandomSource.create(seed), generation, 0.0);
        transportInstance = pin;
        transportPaused = false;
        durationSeconds = Double.NaN;
        clock.reset();
        offsetRequests.clear();
        offsetRequests.put(chosen.getPath(), new OffsetRequest(generation, 0.0));
        launchDurationScan(generation, chosen.getPath());
        return pin;
    }

    /** Reserved selection domain for the vanilla delay stream, decorrelated from track picks. */
    public static long delaySeed(long sessionSeed) {
        return MusicPlanner.selectionSeed(sessionSeed, "cue_my_music:delay", 0L);
    }

    /**
     * World-session boundary (join): fresh session seed, cleared forward
     * index/history and no materialized pin. Caller also installs
     * {@link #delaySeed(long)} into vanilla via {@link #seedDelayRandom}.
     */
    public void beginSession(long sessionSeed) {
        planner.setSessionSeed(sessionSeed);
        planner.reset();
        pendingPreviousFile = null;
        resetTransport();
    }

    /** World-session boundary (disconnect): drops all session state. */
    public void endSession() {
        planner.setSessionSeed(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        planner.reset();
        pendingPreviousFile = null;
        resetTransport();
    }

    /**
     * Seeds vanilla's own delay RNG from the session, preserving every
     * vanilla delay calculation while decorrelating it from track picks.
     */
    public void seedDelayRandom(MusicManager manager, long sessionSeed) {
        ((MusicManagerAccessor) manager).cueMyMusic$setRandom(RandomSource.create(delaySeed(sessionSeed)));
    }

    /**
     * Resource reload: clears the resource-dependent future (pending pin);
     * stale history references are pruned against the live graph.
     */
    public void onResourcesReloaded() {
        pendingPreviousFile = null;
        resetTransport();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getSoundManager() != null) {
            pruneStaleReferences(minecraft.getSoundManager());
        }
    }

    /**
     * Manual Next: drops any materialized Previous pin, stops the current
     * song and clears the vanilla delay so the next tick starts fresh music
     * for whatever context is current then.
     */
    public boolean requestNext() {
        MusicManager manager = musicManager();
        if (manager == null) {
            return false;
        }
        pendingPreviousFile = null;
        resetTransport();
        manager.stopPlaying();
        ((MusicManagerAccessor) manager).cueMyMusic$setNextSongDelay(0);
        return true;
    }

    /**
     * End song: simulate natural completion. Only the sound is stopped —
     * the vanilla delay is left alone so the next tick recomputes it via
     * the normal {@code min(stored, frequency)} path. Never
     * {@code stopPlaying + 0}: that bypass is Next's behavior.
     */
    public boolean requestEnd() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        PinnedMusicInstance current = transportInstance;
        if (current == null || !minecraft.getSoundManager().isActive(current)) {
            return false;
        }
        resetTransport();
        minecraft.getSoundManager().stop(current);
        return true;
    }

    /**
     * Play/Pause toggle: pauses or resumes only this song's native channel.
     * The clock anchors on the real sound-thread pause/unpause callbacks,
     * so pausing during stream load still lands correctly at audible start.
     */
    public boolean togglePause() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        PinnedMusicInstance current = transportInstance;
        if (current == null || !minecraft.getSoundManager().isActive(current)) {
            return false;
        }
        SoundEngine engine = engineOf(minecraft);
        if (engine == null) {
            return false;
        }
        // Flip first: the sticky-unpause guard reads this flag, and the
        // clock itself anchors on the real sound-thread callbacks.
        transportPaused = !transportPaused;
        ((EngineTransport) engine).cueMyMusic$setInstancePaused(current, transportPaused);
        return true;
    }

    /**
     * Seek within the current song: reopens the same pinned sound at a new
     * offset on a new generation, preserving pause state, duration, planner
     * index and history. Stale generations are closed, never attached.
     */
    public boolean seekToSeconds(double requestedSeconds) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        PinnedMusicInstance current = transportInstance;
        if (current == null || !minecraft.getSoundManager().isActive(current)) {
            return false;
        }
        double target = TransportClock.clampSeekTarget(requestedSeconds,
                clock.positionSeconds(System.nanoTime(), durationSeconds), durationSeconds);
        long generation = transportGeneration.incrementAndGet();
        PinnedMusicInstance next = new PinnedMusicInstance(current.getIdentifier(), current.pinnedSound(),
                RandomSource.create(generation), generation, target);
        offsetRequests.clear();
        offsetRequests.put(current.pinnedSound().getPath(), new OffsetRequest(generation, target));
        minecraft.getSoundManager().stop(current);
        minecraft.getSoundManager().play(next);
        MusicManager manager = minecraft.getMusicManager();
        // Maintain vanilla's current song exactly: no duplicate playback,
        // no extra toast, no delay touch. The old instance is already gone.
        // A mismatch cannot happen between ticks on this thread, but unwind
        // instead of doubling playback if it ever does.
        if (!(manager instanceof MusicManagerAccessor accessor)
                || accessor.cueMyMusic$currentMusic() != current) {
            minecraft.getSoundManager().stop(next);
            resetTransport();
            return false;
        }
        accessor.cueMyMusic$setCurrentMusic(next);
        transportInstance = next;
        clock.retarget(target, System.nanoTime());
        // Deliberately no planner.nextSequence/record: a seek replays the
        // same selection, it is not a forward play or a history event.
        return true;
    }

    /**
     * Manual Previous: samples the current situational context now, scans up
     * to eight history entries by actual file membership in that context,
     * and materializes at most one pin. Skips invalid files, including ones
     * from other events whose files are not eligible here.
     */
    public boolean requestPrevious() {
        Minecraft minecraft = Minecraft.getInstance();
        MusicManager manager = musicManager();
        if (minecraft == null || manager == null) {
            return false;
        }
        Music situational = minecraft.getSituationalMusic();
        if (situational == null) {
            return false;
        }
        SoundManager sounds = minecraft.getSoundManager();
        pruneStaleReferences(sounds);
        Identifier eventId = situational.sound().value().location();
        WeighedSoundEvents weighed = sounds.getSoundEvent(eventId);
        if (weighed == null) {
            return false;
        }
        Set<String> eligible = MusicGraph.eligibleFiles(weighed, sounds::getSoundEvent,
                MusicGraph::nestedEventId);
        Optional<MusicPlanner.Entry> target = planner.previousIn(eligible);
        if (target.isEmpty()) {
            return false;
        }
        pendingPreviousFile = target.get().filePath();
        resetTransport();
        manager.stopPlaying();
        ((MusicManagerAccessor) manager).cueMyMusic$setNextSongDelay(0);
        return true;
    }

    /** Current track credit for the Pause-screen widget; artist is null when unknown. */
    public record TrackInfo(String title, String artist) {
    }

    /**
     * Splits a native credit ({@code C418 - Sweden}) into title/artist.
     * Pure formatting helper shared with the widget.
     */
    public static TrackInfo splitCredit(String resolved) {
        int separator = resolved.indexOf(" - ");
        if (separator < 0) {
            return new TrackInfo(resolved, null);
        }
        return new TrackInfo(resolved.substring(separator + 3), resolved.substring(0, separator));
    }

    /**
     * Current track for the Pause-screen widget: the native translation
     * ({@code music.game.sweden} = {@code C418 - Sweden}) when present,
     * otherwise the file name fallback. Empty unless music is really active.
     * The key transform mirrors vanilla {@code NowPlayingToast}.
     */
    public Optional<TrackInfo> nowPlaying() {
        Sound sound = activeSound();
        if (sound == null) {
            return Optional.empty();
        }
        String key = sound.getLocation().toShortLanguageKey().replace('/', '.');
        Language language = Language.getInstance();
        if (language.has(key)) {
            return Optional.of(splitCredit(language.getOrDefault(key)));
        }
        String path = sound.getPath().getPath();
        int slash = path.lastIndexOf('/');
        return Optional.of(new TrackInfo(slash >= 0 ? path.substring(slash + 1) : path, null));
    }

    /**
     * Real playback state: a current song that the sound engine still
     * holds active. Read-only; used to gate the widget display.
     */
    public boolean isActive() {
        return activeSound() != null;
    }

    /**
     * Read-only Previous availability: whether the history scan behind the
     * cursor would find an eligible file in the current context. Never
     * advances the planner (no cursor or sequence mutation).
     */
    public boolean canGoPrevious() {
        Set<String> eligible = currentEligibleFiles();
        if (eligible == null) {
            return false;
        }
        return planner.hasPreviousIn(eligible);
    }

    /** Remaining delay ticks before next song starts. */
    public int remainingDelayTicks() {
        MusicManager manager = musicManager();
        if (manager instanceof MusicManagerAccessor accessor) {
            return Math.max(0, accessor.cueMyMusic$nextSongDelay());
        }
        return 0;
    }

    /** Remaining delay seconds before next song starts. */
    public double remainingDelaySeconds() {
        return remainingDelayTicks() / 20.0;
    }

    /** Skips the remaining delay to start music immediately. */
    public void skipDelay() {
        MusicManager manager = musicManager();
        if (manager instanceof MusicManagerAccessor accessor) {
            accessor.cueMyMusic$setNextSongDelay(0);
        }
    }

    /**
     * Read-only Next availability: a fresh deterministic selection exists
     * for the current context. Read-only.
     */
    public boolean canGoNext() {
        Set<String> eligible = currentEligibleFiles();
        return eligible != null && !eligible.isEmpty();
    }

    /**
     * Read-only projection of the next N deterministic upcoming tracks for
     * the current situational context.
     */
    public List<TrackInfo> upcomingTracks(int count) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return List.of();
        }
        Music situational = minecraft.getSituationalMusic();
        if (situational == null) {
            return List.of();
        }
        SoundManager sounds = minecraft.getSoundManager();
        if (sounds == null) {
            return List.of();
        }
        Identifier eventId = situational.sound().value().location();
        WeighedSoundEvents weighed = sounds.getSoundEvent(eventId);
        if (weighed == null) {
            return List.of();
        }
        Language language = Language.getInstance();
        List<TrackInfo> list = new java.util.ArrayList<>();
        long baseIndex = planner.peekSequence(eventId.toString());
        long sessionSeed = planner.getSessionSeed();
        for (int i = 0; i < count; i++) {
            long index = baseIndex + i;
            long seed = MusicPlanner.selectionSeed(sessionSeed, eventId.toString(), index);
            Sound chosen = weighed.getSound(RandomSource.create(seed));
            if (chosen != null && !MusicGraph.isSilence(chosen)) {
                String key = chosen.getLocation().toShortLanguageKey().replace('/', '.');
                if (language.has(key)) {
                    list.add(splitCredit(language.getOrDefault(key)));
                } else {
                    String path = chosen.getPath().getPath();
                    int slash = path.lastIndexOf('/');
                    list.add(new TrackInfo(slash >= 0 ? path.substring(slash + 1) : path, null));
                }
            }
        }
        return list;
    }

    /** Drops transport state and cancels pending native work via a generation bump. */
    private void resetTransport() {
        transportGeneration.incrementAndGet();
        transportInstance = null;
        transportPaused = false;
        durationSeconds = Double.NaN;
        clock.reset();
        offsetRequests.clear();
    }

    public long currentGeneration() {
        return transportGeneration.get();
    }

    public boolean isCurrentGeneration(long generation) {
        return generation == transportGeneration.get();
    }

    /** Pending offset for a sound path, captured by the play redirect. No consumption. */
    public OffsetRequest offsetRequestFor(Identifier path) {
        return offsetRequests.get(path);
    }

    /**
     * Applies a pending offset reopen on the native IO pool: drops decoded
     * PCM up to the target, then tags the stream for the audible-start
     * hook. Stale generations and failures close the stream and complete
     * without attaching, so a superseded song can never start late.
     */
    public AudioStream applyOffset(AudioStream stream, OffsetRequest request) {
        if (!isCurrentGeneration(request.generation())) {
            closeQuietly(stream);
            throw new CancellationException("stale transport generation");
        }
        try {
            AudioFormat format = stream.getFormat();
            double bytesPerSecond =
                    format.getSampleRate() * (format.getSampleSizeInBits() / 8.0) * format.getChannels();
            long targetBytes =
                    bytesPerSecond <= 0.0 ? 0L : (long) (request.startSeconds() * bytesPerSecond);
            StreamSkipper.ByteSource source = new StreamSkipper.ByteSource() {
                @Override
                public ByteBuffer read(int size) throws java.io.IOException {
                    return stream.read(size);
                }

                @Override
                public void close() throws java.io.IOException {
                    stream.close();
                }
            };
            StreamSkipper.discard(source, targetBytes, SKIP_CHUNK_BYTES, SKIP_BUDGET_BYTES);
            if (!isCurrentGeneration(request.generation())) {
                closeQuietly(stream);
                throw new CancellationException("superseded during discard");
            }
            return new TaggedStream(stream, request.generation());
        } catch (java.io.IOException failure) {
            closeQuietly(stream);
            throw new CompletionException(failure);
        }
    }

    private static void closeQuietly(AudioStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Best effort: the reopen already failed or went stale.
        }
    }

    private double currentOffsetSeconds() {
        PinnedMusicInstance current = transportInstance;
        return current == null ? 0.0 : Math.max(0.0, current.startOffsetSeconds());
    }

    /** Narrow hook: the native channel started audible playback of a tagged stream. */
    public void noteAudibleStart(long generation) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        clock.noteStarted(currentOffsetSeconds(), System.nanoTime());
    }

    /** Narrow hook: the native channel paused a tagged stream. */
    public void noteAudiblePause(long generation) {
        if (isCurrentGeneration(generation)) {
            clock.notePaused(System.nanoTime());
        }
    }

    /** Narrow hook: the native channel resumed a tagged stream. */
    public void noteAudibleResume(long generation) {
        if (isCurrentGeneration(generation)) {
            clock.noteResumed(System.nanoTime());
        }
    }

    /**
     * Narrow hook: deny a channel unpause while the user holds this
     * generation paused, so a vanilla global resume cannot undo it.
     */
    public boolean shouldStickyPause(long generation) {
        return isCurrentGeneration(generation) && transportPaused;
    }

    /** Audible position for the widget; 0 when no transport instance is tracked. */
    public double transportPositionSeconds() {
        if (transportInstance == null) {
            return 0.0;
        }
        return clock.positionSeconds(System.nanoTime(), durationSeconds);
    }

    /** Known track length in seconds, or NaN when honestly unknown. */
    public double transportDurationSeconds() {
        return durationSeconds;
    }

    public boolean transportPaused() {
        return transportPaused && transportInstance != null;
    }

    /** Whether Play/Pause can act: a live pinned song is held by the engine. */
    public boolean canTogglePause() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && transportInstance != null
                && minecraft.getSoundManager().isActive(transportInstance);
    }

    /** Whether seeking is honest: a live song with a known positive duration. */
    public boolean canSeek() {
        if (!canTogglePause()) {
            return false;
        }
        double duration = durationSeconds;
        return !Double.isNaN(duration) && !Double.isInfinite(duration) && duration > 0.0;
    }

    /** Elapsed-style time label; unknown renders as {@code --:--}, never a guess. */
    public static String formatTime(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0.0) {
            return "--:--";
        }
        long total = (long) seconds;
        long minutes = total / 60;
        long rest = total % 60;
        return minutes + ":" + (rest < 10 ? "0" + rest : Long.toString(rest));
    }

    /** Compressed OGG duration probe, off the render thread, generation-guarded. */
    private void launchDurationScan(long generation, Identifier path) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return;
        }
        var resources = minecraft.getResourceManager();
        CompletableFuture.supplyAsync(() -> {
            try (InputStream open = resources.open(path)) {
                return OggDuration.scan(open);
            } catch (Exception failure) {
                LOGGER.debug("[Cue My Music] duration probe failed for {}", path, failure);
                return OptionalDouble.empty();
            }
        }).thenAccept(found -> {
            if (isCurrentGeneration(generation)) {
                durationSeconds = found.orElse(Double.NaN);
            }
        });
    }

    private static SoundEngine engineOf(Minecraft minecraft) {
        try {
            return ((SoundEngineProvider) minecraft.getSoundManager()).cueMyMusic$engine();
        } catch (Exception missing) {
            LOGGER.debug("[Cue My Music] sound engine unavailable", missing);
            return null;
        }
    }

    private Sound activeSound() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        MusicManager manager = minecraft.getMusicManager();
        if (!(manager instanceof MusicManagerAccessor accessor)) {
            return null;
        }
        var current = accessor.cueMyMusic$currentMusic();
        if (current == null || !minecraft.getSoundManager().isActive(current)) {
            return null;
        }
        return current.getSound();
    }

    private Set<String> currentEligibleFiles() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        Music situational = minecraft.getSituationalMusic();
        if (situational == null) {
            return null;
        }
        SoundManager sounds = minecraft.getSoundManager();
        WeighedSoundEvents weighed =
                sounds.getSoundEvent(situational.sound().value().location());
        if (weighed == null) {
            return null;
        }
        return MusicGraph.eligibleFiles(weighed, sounds::getSoundEvent, MusicGraph::nestedEventId);
    }

    private MusicManager musicManager() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        return minecraft.getMusicManager();
    }

    /**
     * Invalidates stale history references (e.g. events removed by a
     * resource reload). Only string references are ever stored, so nothing
     * else can go stale; the pending pin is revalidated against the live
     * graph at playback time.
     */
    private void pruneStaleReferences(SoundManager sounds) {
        planner.prune(entry -> {
            try {
                return sounds.getSoundEvent(Identifier.parse(entry.eventId())) != null;
            } catch (Exception stale) {
                return false;
            }
        });
    }
}
