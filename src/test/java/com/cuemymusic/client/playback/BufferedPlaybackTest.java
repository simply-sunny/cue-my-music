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
}
