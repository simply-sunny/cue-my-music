package com.cuemymusic;

import com.cuemymusic.client.music.VanillaTrackRegistry;
import com.cuemymusic.client.playback.NativeMinecraftPlayback;
import com.cuemymusic.client.playback.PlaybackState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PlaybackStateTest {

    private NativeMinecraftPlayback playback;

    @BeforeEach void setUp() { playback = NativeMinecraftPlayback.getInstance(); playback.stop(null); }
    @AfterEach void tearDown() { playback.stop(null); }

    @Test void statesExist() {
        assertEquals(3, PlaybackState.values().length);
        assertNotNull(PlaybackState.valueOf("STOPPED"));
    }

    @Test void initialIsStopped() {
        assertEquals(PlaybackState.STOPPED, playback.getState());
        assertTrue(playback.getCurrentTrack().isEmpty());
        assertFalse(playback.isPaused());
    }

    @Test void isValidIdAndCounts() {
        assertEquals(70, VanillaTrackRegistry.vanillaCount());
        assertEquals(22, VanillaTrackRegistry.discCount());
        assertTrue(VanillaTrackRegistry.isValidId("vanilla:sweden"));
        assertFalse(VanillaTrackRegistry.isValidId("vanilla:game"));
        assertFalse(VanillaTrackRegistry.isValidId(null));
    }

    @Test void registry92() {
        var lib = new com.cuemymusic.data.MusicLibrary();
        VanillaTrackRegistry.registerAll(lib);
        assertEquals(92, lib.getAllTracks().size());
    }
}
