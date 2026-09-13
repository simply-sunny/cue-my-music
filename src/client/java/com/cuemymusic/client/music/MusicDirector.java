package com.cuemymusic.client.music;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

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
    private final AtomicLong previewGeneration = new AtomicLong(0L);
    private volatile PinnedMusicInstance transportInstance;
    private volatile PinnedMusicInstance previewInstance;
    private volatile TrackPreviewController activePreviewController;
    private final TransportClock clock = new TransportClock();
    private volatile double durationSeconds = Double.NaN;
    private volatile boolean transportPaused;
    private volatile boolean audibleStarted;
    private final Map<Identifier, List<OffsetRequest>> offsetRequests = new ConcurrentHashMap<>();
    /** Hard ceiling on decoded bytes dropped for one seek (far past any music track). */
    static final long SKIP_BUDGET_BYTES = 256L << 20;
    static final int SKIP_CHUNK_BYTES = 1 << 16;

    private volatile TrackWeightConfig weightingConfig = TrackWeightConfig.defaults();
    private volatile WeightedMusicCatalog weightedCatalog = WeightedMusicCatalog.empty();
    private Path weightingPath;

    public enum StreamOwner {
        TRANSPORT,
        PREVIEW
    }

    public enum PlaybackStatus {
        NO_TRACK,
        LOADING,
        PLAYING,
        PAUSED,
        COOLDOWN
    }

    static PlaybackStatus statusFor(
            boolean tracked, boolean audibleStarted, boolean active, boolean paused, int delayTicks) {
        if (tracked && !audibleStarted) {
            return PlaybackStatus.LOADING;
        }
        if (tracked && active) {
            return paused ? PlaybackStatus.PAUSED : PlaybackStatus.PLAYING;
        }
        return delayTicks > 0 ? PlaybackStatus.COOLDOWN : PlaybackStatus.NO_TRACK;
    }

    /** Pending offset reopen, captured by the play redirect at stream-open time. */
    public record OffsetRequest(StreamOwner owner, long generation, double seconds) {
        public double startSeconds() {
            return seconds;
        }
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

    public void initializeWeighting(Path path) {
        this.weightingPath = path;
        this.weightingConfig = path != null ? TrackWeightConfig.load(path) : TrackWeightConfig.defaults();
    }

    public TrackWeightConfig weightingConfig() {
        return weightingConfig;
    }

    public WeightedMusicCatalog weightedCatalog() {
        return weightedCatalog;
    }

    public void saveWeightingConfig(TrackWeightConfig draft) throws IOException {
        Path path = this.weightingPath != null ? this.weightingPath : TrackWeightConfig.defaultPath();
        draft.save(path);
        this.weightingConfig = draft;
    }

    public void reloadWeightedCatalog(SoundManager sounds) {
        this.weightedCatalog = sounds != null ? WeightedMusicCatalog.discover(sounds) : WeightedMusicCatalog.empty();
    }

    Sound chooseFresh(Identifier eventId, WeighedSoundEvents fallback, long seed, String previousResourceId) {
        Optional<WeightedMusicCatalog.Pool> poolOpt = weightedCatalog.pool(eventId.toString());
        if (poolOpt.isPresent()) {
            Optional<WeightedMusicCatalog.Occurrence> selected =
                    WeightedMusicCatalog.select(poolOpt.get(), weightingConfig, previousResourceId, seed);
            return selected.map(WeightedMusicCatalog.Occurrence::sound)
                    .orElse(SoundManager.INTENTIONALLY_EMPTY_SOUND);
        }
        return fallback != null ? fallback.getSound(RandomSource.create(seed)) : SoundManager.INTENTIONALLY_EMPTY_SOUND;
    }

    List<WeightedMusicCatalog.Occurrence> projectFresh(
            Identifier eventId, String previousResourceId, long startIndex, int count) {
        if (count <= 0) {
            return List.of();
        }
        Optional<WeightedMusicCatalog.Pool> poolOpt = weightedCatalog.pool(eventId.toString());
        if (poolOpt.isPresent()) {
            return WeightedMusicCatalog.project(poolOpt.get(), weightingConfig, previousResourceId,
                    planner.getSessionSeed(), startIndex, count);
        }
        return List.of();
    }

    void setWeightingConfig(TrackWeightConfig config) {
        this.weightingConfig = config != null ? config : TrackWeightConfig.defaults();
    }

    void setWeightedCatalog(WeightedMusicCatalog catalog) {
        this.weightedCatalog = catalog != null ? catalog : WeightedMusicCatalog.empty();
    }

    void setWeightingPath(Path path) {
        this.weightingPath = path;
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
        List<MusicPlanner.Entry> history = planner.historySnapshot();
        String previousResourceId = history.isEmpty() ? null : history.get(0).filePath();
        Sound chosen = chooseFresh(eventId, weighed, seed, previousResourceId);
        // Pin exactly what selection returned, including intentional
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
        audibleStarted = false;
        durationSeconds = Double.NaN;
        clock.reset();
        clearOffsetRequests(StreamOwner.TRANSPORT);
        registerOffsetRequest(chosen.getPath(), new OffsetRequest(StreamOwner.TRANSPORT, generation, 0.0));
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
        if (activePreviewController != null) {
            activePreviewController.stop();
        }
        if (previewInstance != null) {
            stopPreview(previewInstance);
        }
        previewGeneration.incrementAndGet();
        clearOffsetRequests(StreamOwner.PREVIEW);
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
     * Pauses active tracked music exclusively for a preview session.
     * Returns true only if tracked music was active and unpaused, and was successfully paused.
     */
    public boolean pauseForPreview() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        PinnedMusicInstance current = transportInstance;
        if (current == null || !minecraft.getSoundManager().isActive(current) || transportPaused) {
            return false;
        }
        SoundEngine engine = engineOf(minecraft);
        if (engine == null) {
            return false;
        }
        transportPaused = true;
        ((EngineTransport) engine).cueMyMusic$setInstancePaused(current, true);
        return true;
    }

    /**
     * Resumes tracked music after a preview session only if the preview session owned the pause
     * and the transport instance still exists and remains paused.
     */
    public void resumeAfterPreview(boolean pausedByPreview) {
        if (!pausedByPreview) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        PinnedMusicInstance current = transportInstance;
        if (current == null || !minecraft.getSoundManager().isActive(current) || !transportPaused) {
            return;
        }
        SoundEngine engine = engineOf(minecraft);
        if (engine == null) {
            return;
        }
        transportPaused = false;
        ((EngineTransport) engine).cueMyMusic$setInstancePaused(current, false);
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
        clearOffsetRequests(StreamOwner.TRANSPORT);
        registerOffsetRequest(current.pinnedSound().getPath(), new OffsetRequest(StreamOwner.TRANSPORT, generation, target));
        audibleStarted = false;
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

    static TrackInfo trackInfo(Sound sound) {
        if (sound == null) {
            return null;
        }
        String key = sound.getLocation().toShortLanguageKey().replace('/', '.');
        Language language = Language.getInstance();
        if (language.has(key)) {
            return splitCredit(language.getOrDefault(key));
        }
        String path = sound.getPath().getPath();
        int slash = path.lastIndexOf('/');
        return new TrackInfo(slash >= 0 ? path.substring(slash + 1) : path, null);
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
        return Optional.ofNullable(trackInfo(sound));
    }

    /** Selected track metadata remains available while its channel is loading. */
    public Optional<TrackInfo> currentTrack() {
        PinnedMusicInstance current = transportInstance;
        return current == null ? Optional.empty() : Optional.ofNullable(trackInfo(current.pinnedSound()));
    }

    public PlaybackStatus playbackStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        PinnedMusicInstance current = transportInstance;
        boolean active = minecraft != null && current != null
                && minecraft.getSoundManager().isActive(current);
        return statusFor(current != null, audibleStarted, active, transportPaused, remainingDelayTicks());
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
        if (count <= 0) {
            return List.of();
        }
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
        Optional<WeightedMusicCatalog.Pool> poolOpt = weightedCatalog.pool(eventId.toString());
        if (poolOpt.isPresent()) {
            List<MusicPlanner.Entry> history = planner.historySnapshot();
            String previousResourceId = history.isEmpty() ? null : history.get(0).filePath();
            long baseIndex = planner.peekSequence(eventId.toString());
            List<WeightedMusicCatalog.Occurrence> occurrences =
                    projectFresh(eventId, previousResourceId, baseIndex, count);
            List<TrackInfo> list = new java.util.ArrayList<>(occurrences.size());
            for (WeightedMusicCatalog.Occurrence occ : occurrences) {
                TrackInfo info = trackInfo(occ.sound());
                if (info != null) {
                    list.add(info);
                }
            }
            return list;
        }
        WeighedSoundEvents weighed = sounds.getSoundEvent(eventId);
        if (weighed == null) {
            return List.of();
        }
        List<TrackInfo> list = new java.util.ArrayList<>();
        long baseIndex = planner.peekSequence(eventId.toString());
        long sessionSeed = planner.getSessionSeed();
        for (int i = 0; i < count; i++) {
            long index = baseIndex + i;
            long seed = MusicPlanner.selectionSeed(sessionSeed, eventId.toString(), index);
            Sound chosen = weighed.getSound(RandomSource.create(seed));
            if (chosen != null && !MusicGraph.isSilence(chosen)) {
                TrackInfo info = trackInfo(chosen);
                if (info != null) {
                    list.add(info);
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
        audibleStarted = false;
        durationSeconds = Double.NaN;
        clock.reset();
        clearOffsetRequests(StreamOwner.TRANSPORT);
    }

    void registerOffsetRequest(Identifier path, OffsetRequest request) {
        offsetRequests.compute(path, (k, list) -> {
            List<OffsetRequest> next = (list == null) ? new java.util.concurrent.CopyOnWriteArrayList<>() : list;
            next.removeIf(existing -> existing.owner() == request.owner());
            next.add(request);
            return next;
        });
    }

    private void clearOffsetRequests(StreamOwner owner) {
        for (var entry : offsetRequests.entrySet()) {
            entry.getValue().removeIf(req -> req.owner() == owner);
        }
    }

    private void clearOffsetRequest(StreamOwner owner, long generation) {
        for (var entry : offsetRequests.entrySet()) {
            entry.getValue().removeIf(req -> req.owner() == owner && req.generation() == generation);
        }
    }

    public long currentGeneration() {
        return transportGeneration.get();
    }

    public boolean isCurrentGeneration(long generation) {
        return generation == transportGeneration.get();
    }

    public long previewGeneration() {
        return previewGeneration.get();
    }

    public boolean isCurrentOffsetRequest(OffsetRequest request) {
        if (request == null) {
            return false;
        }
        if (request.owner() == StreamOwner.PREVIEW) {
            return request.generation() == previewGeneration.get();
        }
        return request.generation() == transportGeneration.get();
    }

    /** Pending offset for a sound path, captured by the play redirect. No consumption. */
    public OffsetRequest offsetRequestFor(Identifier path) {
        List<OffsetRequest> list = offsetRequests.get(path);
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (OffsetRequest req : list) {
            if (req.owner() == StreamOwner.PREVIEW && isCurrentOffsetRequest(req)) {
                return req;
            }
        }
        for (OffsetRequest req : list) {
            if (req.owner() == StreamOwner.TRANSPORT && isCurrentOffsetRequest(req)) {
                return req;
            }
        }
        return null;
    }

    /**
     * Applies a pending offset reopen on the native IO pool: drops decoded
     * PCM up to the target, then tags the stream for the audible-start
     * hook. Stale generations and failures close the stream and complete
     * without attaching, so a superseded song can never start late.
     */
    public AudioStream applyOffset(AudioStream stream, OffsetRequest request) {
        if (!isCurrentOffsetRequest(request)) {
            closeQuietly(stream);
            throw new CancellationException("stale transport generation");
        }
        try {
            AudioFormat format = stream.getFormat();
            double bytesPerSecond =
                    format.getSampleRate() * (format.getSampleSizeInBits() / 8.0) * format.getChannels();
            long targetBytes =
                    bytesPerSecond <= 0.0 ? 0L : (long) (request.seconds() * bytesPerSecond);
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
            if (!isCurrentOffsetRequest(request)) {
                closeQuietly(stream);
                throw new CancellationException("superseded during discard");
            }
            if (request.owner() == StreamOwner.TRANSPORT) {
                return new TaggedStream(stream, request.generation());
            }
            return stream;
        } catch (java.io.IOException failure) {
            closeQuietly(stream);
            throw new CompletionException(failure);
        }
    }

    public void registerActivePreviewController(TrackPreviewController controller) {
        this.activePreviewController = controller;
    }

    public void unregisterActivePreviewController(TrackPreviewController controller) {
        if (this.activePreviewController == controller) {
            this.activePreviewController = null;
        }
    }

    public PinnedMusicInstance playPreview(Identifier eventId, Sound sound, long generation, double offsetSeconds) {
        if (eventId == null || sound == null) {
            return null;
        }
        PinnedMusicInstance old = this.previewInstance;
        if (old != null) {
            stopPreview(old);
        }
        previewGeneration.set(generation);
        registerOffsetRequest(sound.getPath(), new OffsetRequest(StreamOwner.PREVIEW, generation, offsetSeconds));
        PinnedMusicInstance pin = new PinnedMusicInstance(
                eventId,
                sound,
                RandomSource.create(generation),
                generation,
                offsetSeconds);
        this.previewInstance = pin;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(pin);
        }
        return pin;
    }

    public void stopPreview(PinnedMusicInstance instance) {
        if (instance != null) {
            clearOffsetRequest(StreamOwner.PREVIEW, instance.generation());
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getSoundManager() != null) {
                minecraft.getSoundManager().stop(instance);
            }
            if (this.previewInstance == instance) {
                this.previewInstance = null;
            }
        }
    }

    public boolean previewActive(PinnedMusicInstance instance) {
        if (instance == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return false;
        }
        return minecraft.getSoundManager().isActive(instance);
    }

    public boolean setPreviewPaused(PinnedMusicInstance instance, boolean paused) {
        if (instance == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        SoundEngine engine = engineOf(minecraft);
        if (engine == null) {
            return false;
        }
        ((EngineTransport) engine).cueMyMusic$setInstancePaused(instance, paused);
        return true;
    }

    public CompletableFuture<OptionalDouble> probeDuration(Sound sound, long generation, StreamOwner owner) {
        if (sound == null || sound.getPath() == null) {
            return CompletableFuture.completedFuture(OptionalDouble.empty());
        }
        Identifier path = sound.getPath();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return CompletableFuture.completedFuture(OptionalDouble.empty());
        }
        var resources = minecraft.getResourceManager();
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream open = resources.open(path)) {
                return OggDuration.scan(open);
            } catch (Exception failure) {
                LOGGER.debug("[Cue My Music] duration probe failed for {}", path, failure);
                return OptionalDouble.empty();
            }
        }, Util.nonCriticalIoPool()).thenApply(opt -> {
            long currentGen = (owner == StreamOwner.PREVIEW) ? previewGeneration.get() : transportGeneration.get();
            if (generation != currentGen) {
                return OptionalDouble.empty();
            }
            return opt;
        });
    }

    public TrackPreviewController.Backend previewBackend() {
        return new TrackPreviewController.Backend() {
            @Override
            public PinnedMusicInstance play(WeightedMusicCatalog.Pool pool, WeightedMusicCatalog.Track track,
                    long generation, double offsetSeconds) {
                if (pool == null || track == null) {
                    return null;
                }
                WeightedMusicCatalog.Occurrence occ = (track.occurrences() != null && !track.occurrences().isEmpty())
                        ? track.occurrences().getFirst()
                        : null;
                if (occ == null || occ.sound() == null) {
                    return null;
                }
                return playPreview(Identifier.parse(pool.id()), occ.sound(), generation, offsetSeconds);
            }

            @Override
            public void stop(PinnedMusicInstance instance) {
                stopPreview(instance);
            }

            @Override
            public boolean isActive(PinnedMusicInstance instance) {
                return previewActive(instance);
            }

            @Override
            public void setPaused(PinnedMusicInstance instance, boolean paused) {
                setPreviewPaused(instance, paused);
            }

            @Override
            public CompletableFuture<OptionalDouble> duration(WeightedMusicCatalog.Track track, long generation) {
                if (track == null || track.occurrences() == null || track.occurrences().isEmpty()) {
                    return CompletableFuture.completedFuture(OptionalDouble.empty());
                }
                WeightedMusicCatalog.Occurrence occ = track.occurrences().getFirst();
                if (occ == null || occ.sound() == null) {
                    return CompletableFuture.completedFuture(OptionalDouble.empty());
                }
                return probeDuration(occ.sound(), generation, StreamOwner.PREVIEW);
            }

            @Override
            public boolean pauseBackground() {
                return pauseForPreview();
            }

            @Override
            public void resumeBackground() {
                resumeAfterPreview(true);
            }
        };
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
        audibleStarted = true;
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

    public double playbackRate() {
        return clock.playbackRate();
    }

    public void setPlaybackRate(double rate) {
        clock.setPlaybackRate(rate, System.nanoTime());
        Minecraft minecraft = Minecraft.getInstance();
        PinnedMusicInstance current = transportInstance;
        SoundEngine engine = minecraft == null || current == null ? null : engineOf(minecraft);
        if (engine != null) {
            ((EngineTransport) engine).cueMyMusic$setInstancePitch(current, (float) clock.playbackRate());
        }
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
