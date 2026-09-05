package com.cuemymusic.client.playback;

import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackLifecycleTest {

    private MusicTrack track(String id) {
        return new MusicTrack(id, id, "Artist", SourceType.VANILLA);
    }

    @Test void startingOwnsPlaybackAndTimesOutAtTwoSeconds() {
        var life = new PlaybackLifecycle();
        life.start(track("a"), 1_000);
        assertEquals(PlaybackState.STARTING, life.state());
        assertTrue(life.occupied());
        assertFalse(life.startTimedOut(2_999));
        assertTrue(life.startTimedOut(3_000));
    }

    @Test void attachmentPauseAndStopHaveExplicitStates() {
        var life = new PlaybackLifecycle();
        life.start(track("a"), 0);
        life.attached();
        assertEquals(PlaybackState.PLAYING, life.state());
        life.pause();
        assertEquals(PlaybackState.PAUSED, life.state());
        assertTrue(life.occupied());
        life.stop();
        assertEquals(PlaybackState.STOPPED, life.state());
        assertFalse(life.occupied());
    }

    @Test void rapidReplacementLeavesOnlyNewestTrackOwned() {
        var life = new PlaybackLifecycle();
        life.start(track("a"), 0);
        life.start(track("b"), 10);
        assertEquals("b", life.track().orElseThrow().getId());
        assertEquals(PlaybackState.STARTING, life.state());
    }

    @Test void directorBlocksAutoStartForEveryOwnedState() {
        assertFalse(MusicDirector.blocksAutoStart(PlaybackState.STOPPED));
        assertTrue(MusicDirector.blocksAutoStart(PlaybackState.STARTING));
        assertTrue(MusicDirector.blocksAutoStart(PlaybackState.PLAYING));
        assertTrue(MusicDirector.blocksAutoStart(PlaybackState.PAUSED));
    }

    @Test void resumeFromPausedTransitionsToStartingWithTimeout() {
        var life = new PlaybackLifecycle();
        life.start(track("a"), 1_000);
        life.attached();
        assertEquals(PlaybackState.PLAYING, life.state());
        life.pause();
        assertEquals(PlaybackState.PAUSED, life.state());

        life.resume(5_000);
        assertEquals(PlaybackState.STARTING, life.state());
        assertTrue(life.occupied());
        assertFalse(life.startTimedOut(6_999));
        assertTrue(life.startTimedOut(7_000));
    }

    @Test void invalidStateTransitionsAreIgnored() {
        var life = new PlaybackLifecycle();
        life.attached(); // not starting
        assertEquals(PlaybackState.STOPPED, life.state());
        life.pause(); // not playing
        assertEquals(PlaybackState.STOPPED, life.state());
        life.resume(1_000); // not paused
        assertEquals(PlaybackState.STOPPED, life.state());
    }

    @Test void nativePlaybackHasOwnershipReflectsLifecycle() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        assertFalse(playback.hasOwnership());
        assertEquals(PlaybackState.STOPPED, playback.getState());
    }

    @Test void directorStopCurrentStopsPlayback() {
        var director = MusicDirector.getInstance();
        director.stopCurrent(null);
        assertFalse(director.isPlaying());
        assertFalse(director.hasOwnership());
    }

    @Test void directorEligibleCandidatesWithLibrary() {
        var director = MusicDirector.getInstance();
        var lib = new com.cuemymusic.data.MusicLibrary();
        var t1 = new MusicTrack("t1", "Title 1", "Artist", SourceType.VANILLA);
        t1.setEnabled(true);
        t1.setAmbientEligible(true);
        var t2 = new MusicTrack("t2", "Title 2", "Artist", SourceType.YOUTUBE);
        t2.setEnabled(true);
        t2.setAmbientEligible(false);
        lib.addTracks(java.util.List.of(t1, t2));

        director.init(lib);
        var eligible = director.getEligibleCandidates();
        assertEquals(1, eligible.size());
        assertEquals("t1", eligible.get(0).getId());
    }
}
