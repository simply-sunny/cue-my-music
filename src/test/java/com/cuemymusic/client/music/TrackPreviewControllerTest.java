package com.cuemymusic.client.music;

import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.TrackPreviewController.State;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import static org.junit.jupiter.api.Assertions.*;

class TrackPreviewControllerTest {

    private Pool pool;
    private Track track;

    private static Sound dummySound(String path) {
        return new Sound(
                Identifier.parse(path),
                ConstantFloat.of(1.0F),
                ConstantFloat.of(1.0F),
                1,
                Sound.Type.FILE,
                true,
                false,
                16);
    }

    @BeforeEach
    void setUp() {
        Sound sound = dummySound("minecraft:music/game/sweden");
        Occurrence occurrence = new Occurrence("minecraft:music/game/sweden", sound, 1.0);
        track = new Track("minecraft:music/game/sweden", "Sweden", "C418", 1.0, List.of(occurrence));
        pool = new Pool("minecraft:music.game", List.of(track), List.of(occurrence));
    }

    static final class FakeBackend implements TrackPreviewController.Backend {
        boolean active;
        boolean paused;
        boolean backgroundPaused;
        boolean pauseBackgroundReturn = true;
        int playCount;
        int stopCount;
        int pauseBgCount;
        int resumeBgCount;
        long lastGeneration;
        double lastOffset;
        PinnedMusicInstance lastInstance;
        CompletableFuture<OptionalDouble> durationFuture = CompletableFuture.completedFuture(OptionalDouble.of(120.0));

        @Override
        public PinnedMusicInstance play(Pool pool, Track track, long generation, double offsetSeconds) {
            playCount++;
            lastGeneration = generation;
            lastOffset = offsetSeconds;
            Sound sound = (track != null && track.occurrences() != null && !track.occurrences().isEmpty())
                    ? track.occurrences().getFirst().sound()
                    : null;
            lastInstance = new PinnedMusicInstance(
                    Identifier.parse(pool.id()),
                    sound,
                    RandomSource.create(generation),
                    generation,
                    offsetSeconds);
            return lastInstance;
        }

        @Override
        public void stop(PinnedMusicInstance instance) {
            stopCount++;
            active = false;
        }

        @Override
        public boolean isActive(PinnedMusicInstance instance) {
            return active;
        }

        @Override
        public void setPaused(PinnedMusicInstance instance, boolean paused) {
            this.paused = paused;
        }

        @Override
        public CompletableFuture<OptionalDouble> duration(Track track, long generation) {
            return durationFuture;
        }

        @Override
        public boolean pauseBackground() {
            pauseBgCount++;
            backgroundPaused = pauseBackgroundReturn;
            return pauseBackgroundReturn;
        }

        @Override
        public void resumeBackground() {
            resumeBgCount++;
            backgroundPaused = false;
        }
    }

    @Test
    void inactiveBeforeFirstActiveDoesNotCompleteAsynchronousPreview() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);
        backend.active = false;
        controller.tick(1L);
        assertEquals(State.STARTING, controller.snapshot(1L).state());
        backend.active = true;
        controller.tick(2L);
        assertEquals(State.PLAYING, controller.snapshot(2L).state());
        backend.active = false;
        controller.tick(3L);
        assertEquals(State.IDLE, controller.snapshot(3L).state());
    }

    @Test
    void startupTimeoutAfter40TicksCleansUpAndResumesBackground() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);
        backend.active = false;

        assertTrue(backend.backgroundPaused);
        assertEquals(1, backend.pauseBgCount);

        for (long i = 1; i <= 40; i++) {
            controller.tick(i);
            assertEquals(State.STARTING, controller.snapshot(i).state(), "Tick " + i + " should remain in STARTING");
        }

        // 41st tick exceeds 40 startup ticks -> timeout failure
        controller.tick(41L);
        assertEquals(State.IDLE, controller.snapshot(41L).state(), "After 40 ticks, timeout should transition to IDLE");
        assertEquals(1, backend.resumeBgCount, "Background music should be resumed once upon timeout");
        assertFalse(backend.backgroundPaused);
    }

    @Test
    void actualOccurrenceSoundReachesPinnedInstanceAndResolve() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);

        assertNotNull(backend.lastInstance);
        assertSame(track.occurrences().getFirst().sound(), backend.lastInstance.pinnedSound());
        assertEquals("minecraft:music.game", backend.lastInstance.getIdentifier().toString());
    }

    @Test
    void onlyOnePreviewAndReplacementCleanup() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);

        assertEquals(1, backend.playCount);
        assertEquals(0, backend.stopCount);
        assertEquals(1, backend.pauseBgCount);
        assertEquals(0, backend.resumeBgCount);
        PinnedMusicInstance instance1 = backend.lastInstance;

        Sound sound2 = dummySound("minecraft:music/game/clark");
        Occurrence occ2 = new Occurrence("minecraft:music/game/clark", sound2, 1.0);
        Track track2 = new Track("minecraft:music/game/clark", "Clark", "C418", 1.0, List.of(occ2));

        // Start replacement track
        controller.start(pool, track2);
        assertEquals(2, backend.playCount);
        assertEquals(1, backend.stopCount);
        // Background pause was NOT requested again (already owned)
        assertEquals(1, backend.pauseBgCount);
        assertEquals(0, backend.resumeBgCount);
        assertNotSame(instance1, backend.lastInstance);
        assertSame(sound2, backend.lastInstance.pinnedSound());

        // Stop preview cleans up and resumes background exactly once
        controller.stop();
        assertEquals(2, backend.stopCount);
        assertEquals(1, backend.resumeBgCount);
        assertEquals(State.IDLE, controller.state());
    }

    @Test
    void startingToPlayingToNaturalCompletion() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);

        assertEquals(State.STARTING, controller.state());
        assertTrue(controller.isPlaying());

        // Sound becomes active
        backend.active = true;
        controller.tick(1_000_000_000L);
        assertEquals(State.PLAYING, controller.state());
        assertTrue(controller.isPlaying());

        // Sound completes naturally
        backend.active = false;
        controller.tick(2_000_000_000L);
        assertEquals(State.IDLE, controller.state());
        assertFalse(controller.isPlaying());
        assertEquals(1, backend.resumeBgCount);
    }

    @Test
    void pauseAndResumeClock() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);

        // Sound becomes active at 1.0s
        backend.active = true;
        controller.tick(1_000_000_000L);
        assertEquals(State.PLAYING, controller.state());

        // Tick at 3.0s -> position is 2.0s
        TrackPreviewController.Snapshot snap1 = controller.snapshot(3_000_000_000L);
        assertEquals(2.0, snap1.positionSeconds(), 1e-6);

        // Pause at 3.0s
        controller.togglePause(3_000_000_000L);
        assertEquals(State.PAUSED, controller.state());
        assertTrue(backend.paused);

        // Time elapses to 6.0s while paused -> position remains frozen at 2.0s
        TrackPreviewController.Snapshot snap2 = controller.snapshot(6_000_000_000L);
        assertEquals(2.0, snap2.positionSeconds(), 1e-6);
        assertEquals(State.PAUSED, snap2.state());

        // Resume at 6.0s
        controller.togglePause(6_000_000_000L);
        assertEquals(State.PLAYING, controller.state());
        assertFalse(backend.paused);

        // Tick at 8.0s -> position is 4.0s
        TrackPreviewController.Snapshot snap3 = controller.snapshot(8_000_000_000L);
        assertEquals(4.0, snap3.positionSeconds(), 1e-6);
    }

    @Test
    void seekStopsAndReopensPreviewAtRequestedOffset() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);
        backend.active = true;
        controller.tick(1_000_000_000L);

        // Duration is 120s (from FakeBackend durationFuture)
        TrackPreviewController.Snapshot snap = controller.snapshot(1_000_000_000L);
        assertTrue(snap.canSeek());
        assertEquals(120.0, snap.durationSeconds(), 1e-6);

        // Seek to 45.0s
        controller.seek(45.0, 2_000_000_000L);
        assertEquals(1, backend.stopCount);
        assertEquals(2, backend.playCount);
        assertEquals(45.0, backend.lastOffset, 1e-6);
        assertEquals(State.STARTING, controller.state());

        // When active at 3.0s, position advances from 45.0s
        backend.active = true;
        controller.tick(3_000_000_000L);
        assertEquals(State.PLAYING, controller.state());
        TrackPreviewController.Snapshot snapAfterSeek = controller.snapshot(5_000_000_000L);
        assertEquals(47.0, snapAfterSeek.positionSeconds(), 1e-6);
    }

    @Test
    void seekWhilePausedPreservesPauseState() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);
        backend.active = true;
        controller.tick(1_000_000_000L);

        controller.togglePause(2_000_000_000L);
        assertEquals(State.PAUSED, controller.state());

        // Seek while paused
        controller.seek(50.0, 3_000_000_000L);
        assertEquals(State.PAUSED, controller.state());
        assertEquals(50.0, backend.lastOffset, 1e-6);

        // Active notification keeps state paused and instructs backend
        backend.active = true;
        controller.tick(4_000_000_000L);
        assertEquals(State.PAUSED, controller.state());
        assertTrue(backend.paused);
        assertEquals(50.0, controller.snapshot(10_000_000_000L).positionSeconds(), 1e-6);
    }

    @Test
    void unknownDurationDisablesSeek() {
        FakeBackend backend = new FakeBackend();
        backend.durationFuture = CompletableFuture.completedFuture(OptionalDouble.empty());
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track);
        backend.active = true;
        controller.tick(1_000_000_000L);

        TrackPreviewController.Snapshot snap = controller.snapshot(1_000_000_000L);
        assertFalse(snap.canSeek());
        assertTrue(Double.isNaN(snap.durationSeconds()));

        int initialPlayCount = backend.playCount;
        controller.seek(30.0, 2_000_000_000L);
        assertEquals(initialPlayCount, backend.playCount, "Seek must be a no-op when duration is unknown");
    }

    @Test
    void backgroundPauseAcquiredOnceAndResumedOnce() {
        FakeBackend backend = new FakeBackend();
        TrackPreviewController controller = new TrackPreviewController(backend);

        // Normal case: background music was playing
        controller.start(pool, track);
        assertTrue(controller.ownsBackgroundPause());
        assertEquals(1, backend.pauseBgCount);

        // Multiple seeks/track switches keep ownership without extra calls
        controller.seek(10.0, 1L);
        assertEquals(1, backend.pauseBgCount);
        assertEquals(0, backend.resumeBgCount);

        controller.stop();
        assertFalse(controller.ownsBackgroundPause());
        assertEquals(1, backend.resumeBgCount);

        // Second stop is idempotent
        controller.stop();
        assertEquals(1, backend.resumeBgCount);

        // Case where background music was NOT active (pauseBackground returned false)
        backend.pauseBackgroundReturn = false;
        controller.start(pool, track);
        assertFalse(controller.ownsBackgroundPause());
        assertEquals(2, backend.pauseBgCount);

        controller.stop();
        // Since it did not own the pause, resumeBackground is NOT called
        assertEquals(1, backend.resumeBgCount);
    }

    @Test
    void asyncDurationThreadVisibilityAndStaleGenerationSafelyIgnored() {
        FakeBackend backend = new FakeBackend();
        CompletableFuture<OptionalDouble> futureA = new CompletableFuture<>();
        CompletableFuture<OptionalDouble> futureB = new CompletableFuture<>();

        backend.durationFuture = futureA;
        TrackPreviewController controller = new TrackPreviewController(backend);
        controller.start(pool, track); // gen 1

        Sound sound2 = dummySound("minecraft:music/game/clark");
        Occurrence occ2 = new Occurrence("minecraft:music/game/clark", sound2, 1.0);
        Track track2 = new Track("minecraft:music/game/clark", "Clark", "C418", 1.0, List.of(occ2));

        backend.durationFuture = futureB;
        controller.start(pool, track2); // gen 2

        // Future A completes with 100.0s for the stale generation 1
        futureA.complete(OptionalDouble.of(100.0));

        // On render tick, stale duration result must be ignored
        controller.tick(1_000_000_000L);
        assertTrue(Double.isNaN(controller.snapshot(1_000_000_000L).durationSeconds()),
                "Stale callback from gen 1 must not set duration on replacement preview");

        // Future B completes with 250.0s for current generation 2
        futureB.complete(OptionalDouble.of(250.0));
        controller.tick(2_000_000_000L);
        assertEquals(250.0, controller.snapshot(2_000_000_000L).durationSeconds(), 1e-6);
    }

    @Test
    void playPreviewConstructsPinnedInstanceWithActualSound() {
        MusicDirector director = MusicDirector.getInstance();
        Sound sound = dummySound("minecraft:music/game/sweden");
        PinnedMusicInstance pin = director.playPreview(
                Identifier.parse("minecraft:music.game"), sound, 10L, 5.5);
        assertNotNull(pin);
        assertSame(sound, pin.pinnedSound());
        assertEquals(10L, pin.generation());
        assertEquals(5.5, pin.startOffsetSeconds());
        assertEquals(10L, director.previewGeneration());
        assertNotNull(director.offsetRequestFor(sound.getPath()));
        assertEquals(MusicDirector.StreamOwner.PREVIEW, director.offsetRequestFor(sound.getPath()).owner());
        assertEquals(10L, director.offsetRequestFor(sound.getPath()).generation());
        assertEquals(5.5, director.offsetRequestFor(sound.getPath()).seconds());

        director.stopPreview(pin);
        assertNull(director.offsetRequestFor(sound.getPath()));
    }
}
