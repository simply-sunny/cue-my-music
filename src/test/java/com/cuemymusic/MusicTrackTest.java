package com.cuemymusic;

import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MusicTrackTest {

    @Test void ctorAndGetters() {
        var t = new MusicTrack("vanilla:sweden", "Sweden", "C418", SourceType.VANILLA);
        assertEquals("vanilla:sweden", t.getId());
        assertEquals("Sweden", t.getTitle());
        assertEquals(SourceType.VANILLA, t.getSourceType());
        assertTrue(t.isEnabled());
    }

    @Test void equalsByIdOnly() {
        var a = new MusicTrack("vanilla:sweden","Sweden","C418", SourceType.VANILLA);
        var b = new MusicTrack("vanilla:sweden","Different","Other", SourceType.MUSIC_DISC);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        var c = new MusicTrack("vanilla:clark","Clark","C418", SourceType.VANILLA);
        assertNotEquals(a, c);
    }

    @Test void copyCtorCopiesAllFields() {
        var orig = new MusicTrack("vanilla:sweden","Sweden","C418", SourceType.VANILLA);
        orig.setSourceId("minecraft:music/game/sweden");
        orig.setEnabled(false);
        orig.setAmbientEligible(false);
        var copy = new MusicTrack(orig);
        assertEquals(orig.getId(), copy.getId());
        assertEquals(orig.isEnabled(), copy.isEnabled());
        assertEquals(orig, copy);
        assertNotSame(orig, copy);
    }

    @Test void toStringContainsId() {
        var t = new MusicTrack("vanilla:sweden","Sweden","C418", SourceType.VANILLA);
        assertTrue(t.toString().contains("vanilla:sweden"));
    }
}
