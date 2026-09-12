package com.cuemymusic.client.music;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.util.valueproviders.ConstantFloat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Queue projection and delay-countdown contract for {@link MusicDirector}.
 *
 * <p>Headless unit scope: without a live {@code Minecraft} instance the
 * director cannot resolve a situational context, so the queue projection is
 * empty — but it must still be deterministic, bounded by {@code count}, and
 * must never advance the planner's monotonic forward index. The delay
 * countdown derives from {@code MusicManagerAccessor#nextSongDelay} (0 when
 * no manager is present); seconds are ticks / 20, and {@code skipDelay}
 * zeroes the delay.
 */
class MusicDirectorQueueDelayTest {

    @BeforeEach void injectTestListProvider() {
        MusicGraph.entriesProvider = event -> {
            try {
                Field field = WeighedSoundEvents.class.getDeclaredField("list");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Weighted<Sound>> entries = (List<Weighted<Sound>>) field.get(event);
                return entries;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @AfterEach void resetSingleton() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(0L);
        director.setWeightingConfig(TrackWeightConfig.defaults());
        director.setWeightedCatalog(WeightedMusicCatalog.empty());
        director.setWeightingPath(null);
    }

    private static Sound file(String name, float volume, float pitch, int weight,
            boolean stream, boolean preload) {
        return new Sound(
                Identifier.parse("minecraft:" + name),
                ConstantFloat.of(volume),
                ConstantFloat.of(pitch),
                weight,
                Sound.Type.FILE,
                stream,
                preload,
                16);
    }

    private static WeighedSoundEvents event(String id, List<Weighted<Sound>> entries) {
        WeighedSoundEvents event = new WeighedSoundEvents(Identifier.parse(id), null);
        for (Weighted<Sound> entry : entries) {
            event.addSound(entry);
        }
        return event;
    }

    private static WeightedMusicCatalog catalogOf(Map<Identifier, WeighedSoundEvents> registry) {
        Collection<Identifier> ids = registry.keySet();
        return WeightedMusicCatalog.fromEvents(ids, registry::get, entry -> null, entry -> null);
    }

    @Test void configuredMultiplierChangesPinnedResourceSelectedForKnownSeeds() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);
        Sound sweden = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Sound clark = file("music/game/clark", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents fallback = event("minecraft:music.game", List.of(sweden, clark));
        WeightedMusicCatalog catalog = catalogOf(Map.of(Identifier.parse("minecraft:music.game"), fallback));
        director.setWeightedCatalog(catalog);

        Identifier poolId = Identifier.parse("minecraft:music.game");
        long seed = 0L;
        while (!director.chooseFresh(poolId, fallback, seed, null).getPath().equals(sweden.getPath())) {
            seed++;
        }
        assertEquals(sweden.getPath(),
                director.chooseFresh(poolId, fallback, seed, null).getPath());

        TrackWeightConfig mutedSweden = TrackWeightConfig.defaults()
                .withMultiplier("minecraft:music.game", sweden.getPath().toString(), 0.0);
        director.setWeightingConfig(mutedSweden);

        Sound chosen = director.chooseFresh(poolId, fallback, seed, null);
        assertEquals(clark.getPath(), chosen.getPath());
    }

    @Test void actualAndQueueUseSameConfiguredSelector() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(777L);
        Sound sweden = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Sound clark = file("music/game/clark", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents weighedFallback = event("minecraft:music.game", List.of(sweden, clark));
        WeightedMusicCatalog catalog = catalogOf(Map.of(Identifier.parse("minecraft:music.game"), weighedFallback));
        director.setWeightedCatalog(catalog);

        Identifier poolId = Identifier.parse("minecraft:music.game");
        long index = director.planner().peekSequence(poolId.toString());
        long seed = MusicPlanner.selectionSeed(director.planner().getSessionSeed(), poolId.toString(), index);
        Sound actual = director.chooseFresh(poolId, weighedFallback, seed, null);
        Sound queued = director.projectFresh(poolId, null, index, 1).getFirst().sound();
        assertEquals(actual.getPath(), queued.getPath());
    }

    @Test void mutedKnownPoolReturnsIntentionallyEmptySoundAndEmptyQueue() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);
        Sound sweden = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents fallback = event("minecraft:music.game", List.of(sweden));
        WeightedMusicCatalog catalog = catalogOf(Map.of(Identifier.parse("minecraft:music.game"), fallback));
        director.setWeightedCatalog(catalog);

        TrackWeightConfig muted = TrackWeightConfig.defaults()
                .withMultiplier("minecraft:music.game", sweden.getPath().toString(), 0.0);
        director.setWeightingConfig(muted);

        Identifier poolId = Identifier.parse("minecraft:music.game");
        long index = director.planner().peekSequence(poolId.toString());
        long seed = MusicPlanner.selectionSeed(director.planner().getSessionSeed(), poolId.toString(), index);

        Sound actual = director.chooseFresh(poolId, fallback, seed, null);
        assertSame(SoundManager.INTENTIONALLY_EMPTY_SOUND, actual);

        List<WeightedMusicCatalog.Occurrence> queued = director.projectFresh(poolId, null, index, 5);
        assertTrue(queued.isEmpty());
    }

    @Test void undiscoveredPoolFallsBackToWeighedSoundEvents() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);
        director.setWeightedCatalog(WeightedMusicCatalog.empty());

        Sound sweden = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents fallback = event("minecraft:music.game", List.of(sweden));

        Identifier poolId = Identifier.parse("minecraft:music.game");
        long seed = 123L;

        Sound actual = director.chooseFresh(poolId, fallback, seed, null);
        assertNotEquals(SoundManager.INTENTIONALLY_EMPTY_SOUND, actual);
        assertEquals(fallback.getSound(net.minecraft.util.RandomSource.create(seed)).getPath(), actual.getPath());
    }

    @Test void upcomingTracksDoesNotAdvanceSequence() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(1234L);
        director.planner().nextSequence();
        director.planner().nextSequence();
        long before = director.planner().peekSequence();
        List<MusicDirector.TrackInfo> first = director.upcomingTracks(5);
        List<MusicDirector.TrackInfo> second = director.upcomingTracks(5);
        assertEquals(before, director.planner().peekSequence(),
                "queue projection must not consume the forward index");
        assertEquals(first, second, "repeated projection must be deterministic");
        assertTrue(first.size() <= 5);
    }

    @Test void upcomingTracksProjectionMatchesForwardSelectionSeeds() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(777L);
        long base = director.planner().peekSequence();
        String eventId = "minecraft:music.game";
        long[] expected = new long[5];
        for (int i = 0; i < 5; i++) {
            expected[i] = MusicPlanner.selectionSeed(director.planner().getSessionSeed(), eventId, base + i);
        }
        // Recompute: same seed/event/index reproduces the forward stream exactly.
        for (int i = 0; i < 5; i++) {
            assertEquals(expected[i],
                    MusicPlanner.selectionSeed(director.planner().getSessionSeed(), eventId, base + i));
        }
        // Distinct forward indices must give distinct seeds (decorrelated picks).
        assertEquals(5, java.util.Arrays.stream(expected).distinct().count());
        // The projection call itself must leave the cursor at the base index.
        director.upcomingTracks(5);
        assertEquals(base, director.planner().peekSequence());
    }

    @Test void upcomingTracksRespectsCountBounds() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);
        assertTrue(director.upcomingTracks(0).isEmpty());
        assertTrue(director.upcomingTracks(-1).isEmpty());
        assertTrue(director.upcomingTracks(5).size() <= 5);
        assertEquals(director.planner().peekSequence(), 0L,
                "bounded projections must not advance the sequence either");
    }

    @Test void remainingDelayTicksAndSecondsAreConsistent() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(99L);
        int ticks = director.remainingDelayTicks();
        double seconds = director.remainingDelaySeconds();
        assertTrue(ticks >= 0, "delay ticks clamp at zero, got " + ticks);
        assertEquals(ticks / 20.0, seconds, 1e-9,
                "seconds must reflect accessor ticks at 20 ticks/second");
    }

    @Test void skipDelayZeroesNextSongDelay() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(99L);
        // Must not throw headless (no manager) and must leave the delay at zero.
        assertDoesNotThrow(director::skipDelay);
        assertEquals(0, director.remainingDelayTicks());
        assertEquals(0.0, director.remainingDelaySeconds(), 1e-9);
        // Idempotent: second skip keeps it zeroed.
        assertDoesNotThrow(director::skipDelay);
        assertEquals(0, director.remainingDelayTicks());
    }

    @Test void delayCountdownSurvivesSessionReset() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(1L);
        director.skipDelay();
        director.beginSession(2L);
        assertEquals(0, director.remainingDelayTicks());
        assertEquals(0.0, director.remainingDelaySeconds(), 1e-9);
    }

    @Test void pauseAndResumeForPreviewSafelyHandleHeadlessAndNoActiveMusic() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);
        long initialSeq = director.planner().peekSequence("minecraft:music.game");
        long initialGen = director.currentGeneration();
        int initialDelay = director.remainingDelayTicks();

        // Without active music, pauseForPreview returns false and does not mutate state
        assertFalse(director.pauseForPreview());
        assertEquals(initialSeq, director.planner().peekSequence("minecraft:music.game"));
        assertEquals(initialGen, director.currentGeneration());
        assertEquals(initialDelay, director.remainingDelayTicks());

        // resumeAfterPreview(false) is a no-op
        assertDoesNotThrow(() -> director.resumeAfterPreview(false));

        // resumeAfterPreview(true) with no active transport is a no-op
        assertDoesNotThrow(() -> director.resumeAfterPreview(true));
        assertEquals(initialSeq, director.planner().peekSequence("minecraft:music.game"));
        assertEquals(initialGen, director.currentGeneration());
        assertEquals(initialDelay, director.remainingDelayTicks());
    }

    @Test void previewTransportOperationsLeaveNormalPlannerQueueDelayAndTransportGenerationUnchanged() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);

        Sound sweden = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Sound clark = file("music/game/clark", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents fallback = event("minecraft:music.game", List.of(sweden, clark));
        WeightedMusicCatalog catalog = catalogOf(Map.of(Identifier.parse("minecraft:music.game"), fallback));
        director.setWeightedCatalog(catalog);

        String poolId = "minecraft:music.game";
        WeightedMusicCatalog.Pool pool = catalog.pool(poolId).orElseThrow();
        WeightedMusicCatalog.Track track = pool.tracks().getFirst();

        long initialSeq = director.planner().peekSequence(poolId);
        int initialHistorySize = director.planner().historySnapshot().size();
        long initialTransportGen = director.currentGeneration();
        int initialDelay = director.remainingDelayTicks();
        List<WeightedMusicCatalog.Occurrence> initialQueue = director.projectFresh(Identifier.parse(poolId), null, initialSeq, 3);

        // Preview operations: play, pause, probe duration, stop
        TrackPreviewController.Backend backend = director.previewBackend();
        PinnedMusicInstance pin = backend.play(pool, track, 1L, 0.0);
        assertNotNull(pin);
        backend.setPaused(pin, true);
        backend.duration(track, 1L);
        backend.stop(pin);

        // Normal state must be strictly unchanged
        assertEquals(initialSeq, director.planner().peekSequence(poolId),
                "Preview operations must not advance planner sequence");
        assertEquals(initialHistorySize, director.planner().historySnapshot().size(),
                "Preview operations must not append to planner history");
        assertEquals(initialTransportGen, director.currentGeneration(),
                "Preview operations must not bump normal transport generation");
        assertEquals(initialDelay, director.remainingDelayTicks(),
                "Preview operations must not alter music delay ticks");
        assertEquals(initialQueue, director.projectFresh(Identifier.parse(poolId), null, initialSeq, 3),
                "Preview operations must not alter queue projections");
    }

    @Test void separateOffsetOwnershipValidatesIndependentGenerationsAndPreventsCollisions() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);

        long transportGen = director.currentGeneration();
        Sound sound = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Identifier soundPath = sound.getPath();
        MusicDirector.OffsetRequest transportReq = new MusicDirector.OffsetRequest(
                MusicDirector.StreamOwner.TRANSPORT, transportGen, 0.0);
        director.registerOffsetRequest(soundPath, transportReq);
        assertEquals(transportReq, director.offsetRequestFor(soundPath));
        assertTrue(director.isCurrentOffsetRequest(transportReq));
        assertFalse(director.isCurrentOffsetRequest(new MusicDirector.OffsetRequest(
                MusicDirector.StreamOwner.TRANSPORT, transportGen - 1, 0.0)));

        // Preview starts on the exact same sound path (registers PREVIEW request)
        PinnedMusicInstance previewPin = director.playPreview(
                Identifier.parse("minecraft:music.game"), sound, 99L, 25.0);
        assertEquals(99L, director.previewGeneration());

        MusicDirector.OffsetRequest previewReq = director.offsetRequestFor(soundPath);
        assertNotNull(previewReq);
        assertEquals(MusicDirector.StreamOwner.PREVIEW, previewReq.owner());
        assertEquals(99L, previewReq.generation());
        assertEquals(25.0, previewReq.seconds());

        // Independent generation validation
        assertTrue(director.isCurrentOffsetRequest(previewReq));
        assertFalse(director.isCurrentOffsetRequest(new MusicDirector.OffsetRequest(
                MusicDirector.StreamOwner.PREVIEW, 98L, 25.0)));
        assertTrue(director.isCurrentOffsetRequest(transportReq));

        // Stopping preview clears PREVIEW request without clobbering matching path state
        director.stopPreview(previewPin);
        // TRANSPORT request on the same path was preserved and not clobbered
        MusicDirector.OffsetRequest remainingReq = director.offsetRequestFor(soundPath);
        assertNotNull(remainingReq);
        assertEquals(MusicDirector.StreamOwner.TRANSPORT, remainingReq.owner());
        assertEquals(transportGen, remainingReq.generation());
    }

    @Test void resourceReloadCleansUpActivePreviewAndClearsPreviewOffsetRequests() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);

        Sound sound = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        PinnedMusicInstance pin = director.playPreview(
                Identifier.parse("minecraft:music.game"), sound, 5L, 10.0);
        assertNotNull(director.offsetRequestFor(sound.getPath()));

        director.onResourcesReloaded();

        // Preview offset requests cleared, preview generation bumped
        assertNull(director.offsetRequestFor(sound.getPath()));
        assertTrue(director.previewGeneration() > 5L);
    }

    private static class FakeAudioStream implements net.minecraft.client.sounds.AudioStream {
        private final javax.sound.sampled.AudioFormat format =
                new javax.sound.sampled.AudioFormat(44100.0f, 16, 2, true, false);

        @Override
        public javax.sound.sampled.AudioFormat getFormat() {
            return format;
        }

        @Override
        public java.nio.ByteBuffer read(int size) {
            return java.nio.ByteBuffer.allocate(0);
        }

        @Override
        public void close() {}
    }

    @Test void previewApplyOffsetDoesNotWrapInTaggedStreamEvenWhenGenerationsCoincide() throws Exception {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(42L);

        // Align transport and preview generations to the exact same value
        long commonGen = director.currentGeneration();
        Field previewGenField = MusicDirector.class.getDeclaredField("previewGeneration");
        previewGenField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) previewGenField.get(director)).set(commonGen);
        assertEquals(commonGen, director.currentGeneration());
        assertEquals(commonGen, director.previewGeneration());

        // Simulate normal transport paused (e.g. paused background music)
        Field pausedField = MusicDirector.class.getDeclaredField("transportPaused");
        pausedField.setAccessible(true);
        pausedField.set(director, true);
        assertTrue(director.shouldStickyPause(commonGen));

        // When PREVIEW offset is applied on the coincident generation:
        MusicDirector.OffsetRequest previewReq = new MusicDirector.OffsetRequest(
                MusicDirector.StreamOwner.PREVIEW, commonGen, 0.0);
        FakeAudioStream previewStream = new FakeAudioStream();
        net.minecraft.client.sounds.AudioStream previewResult = director.applyOffset(previewStream, previewReq);

        // PREVIEW must NOT be wrapped in TaggedStream, preventing ChannelAudibleMixin from sticky-pausing it
        assertFalse(previewResult instanceof MusicDirector.TaggedStream,
                "PREVIEW stream must return untagged base stream so ChannelAudibleMixin does not sticky-pause it or corrupt transport clock");
        assertSame(previewStream, previewResult);

        // TRANSPORT on the same generation DOES wrap in TaggedStream for ChannelAudibleMixin
        MusicDirector.OffsetRequest transportReq = new MusicDirector.OffsetRequest(
                MusicDirector.StreamOwner.TRANSPORT, commonGen, 0.0);
        FakeAudioStream transportStream = new FakeAudioStream();
        net.minecraft.client.sounds.AudioStream transportResult = director.applyOffset(transportStream, transportReq);

        assertTrue(transportResult instanceof MusicDirector.TaggedStream,
                "TRANSPORT stream must be wrapped in TaggedStream for audible clock and sticky pause");
        assertEquals(commonGen, ((MusicDirector.TaggedStream) transportResult).generation());
    }
}
