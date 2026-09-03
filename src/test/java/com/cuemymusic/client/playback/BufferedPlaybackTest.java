package com.cuemymusic.client.playback;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BufferedPlaybackTest {

    @Test
    void exactFileSoundIsStaticNotStreamed() {
        var sound = new NativeMinecraftPlayback.BufferedFileSoundInstance(
                Identifier.fromNamespaceAndPath("minecraft", "music/game/sweden"), SoundSource.MUSIC);
        assertFalse(sound.getSound().shouldStream());
    }

    @Test
    void durationUsesPcmShape() {
        assertEquals(2.0f, NativeMinecraftPlayback.durationSeconds(352_800, 2, 16, 44_100), 0.001f);
        assertEquals(-1.0f, NativeMinecraftPlayback.durationSeconds(0, 2, 16, 44_100));
        assertEquals(-1.0f, NativeMinecraftPlayback.durationSeconds(-100, 2, 16, 44_100));
        assertEquals(-1.0f, NativeMinecraftPlayback.durationSeconds(352_800, 0, 16, 44_100));
        assertEquals(-1.0f, NativeMinecraftPlayback.durationSeconds(352_800, 2, 0, 44_100));
        assertEquals(-1.0f, NativeMinecraftPlayback.durationSeconds(352_800, 2, 16, 0));
    }

    @Test
    void seekClampUsesRealDuration() {
        assertEquals(0f, NativeMinecraftPlayback.clampSeek(-4f, 120f));
        assertEquals(60f, NativeMinecraftPlayback.clampSeek(60f, 120f));
        assertEquals(120f, NativeMinecraftPlayback.clampSeek(999f, 120f));
        assertEquals(0f, NativeMinecraftPlayback.clampSeek(0f, 120f));
    }

    @Test
    void canSeekRequiresPlayingState() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        assertFalse(playback.canSeek());
        var director = MusicDirector.getInstance();
        assertFalse(director.canSeek());
    }

    @Test
    void staleProbeCallbackIgnoredWhenStoppedOrPaused() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        long oldGen = playback.getPlaybackGeneration();
        // Simulate an in-flight probe callback from oldGen completing after stop
        boolean applied = playback.applyProbeResult(oldGen, 180.0f, 45.0f, false);
        assertFalse(applied);
        assertEquals(-1.0f, playback.durationSeconds());
        assertEquals(-1.0f, playback.positionSeconds());
        assertFalse(playback.canSeek());
    }

    @Test
    void staleProbeCallbackIgnoredAfterGenerationIncrement() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        long staleGen = playback.getPlaybackGeneration();
        playback.incrementGeneration(); // simulates track start/replacement
        boolean applied = playback.applyProbeResult(staleGen, 200.0f, 10.0f, false);
        assertFalse(applied);
        assertEquals(-1.0f, playback.durationSeconds());
        assertEquals(-1.0f, playback.positionSeconds());
    }

    @Test
    void staleSeekCallbackIgnoredWhenStoppedOrReplaced() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        long staleGen = playback.getPlaybackGeneration();
        playback.incrementGeneration();
        boolean applied = playback.applySeekResult(staleGen, 60.0f, true);
        assertFalse(applied);
        assertEquals(-1.0f, playback.positionSeconds());
    }

    @Test
    void seekFastFailsWhenNotSeekableOrHandleMissing() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        var future = playback.seek(null, 30.0f);
        assertTrue(future.isDone());
        assertFalse(future.join());
    }

    @Test
    void stopIncrementsPlaybackGeneration() {
        var playback = NativeMinecraftPlayback.getInstance();
        playback.stop(null);
        long g1 = playback.getPlaybackGeneration();
        playback.stop(null);
        long g2 = playback.getPlaybackGeneration();
        assertTrue(g2 > g1);
    }
}
