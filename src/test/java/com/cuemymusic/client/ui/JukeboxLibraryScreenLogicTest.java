package com.cuemymusic.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JukeboxLibraryScreenLogicTest {

    @Test
    void normalLayoutIsCenteredAndKeepsScrubBesideSort() {
        var l = JukeboxLibraryScreen.computeLayout(854, 480);
        assertEquals(620, l.contentW());
        assertEquals(117, l.contentL());
        assertFalse(l.scrubWrapped());
        assertTrue(l.searchRight() <= l.sortX());
        assertTrue(l.sortRight() <= l.scrubX());
    }

    @Test
    void narrowLayoutWrapsScrubWithoutOverlap() {
        var l = JukeboxLibraryScreen.computeLayout(400, 300);
        assertEquals(360, l.contentW());
        assertTrue(l.scrubWrapped());
        assertTrue(l.searchRight() <= l.sortX());
        assertTrue(l.scrubY() > l.searchY());
        assertTrue(l.listTop() > l.scrubBottom());
    }

    @Test
    void rowsUseAccessibleTargets() {
        assertEquals(20, JukeboxLibraryScreen.ROW_H);
        assertEquals(20, JukeboxLibraryScreen.ROW_CONTROL_SIZE);
    }

    @Test
    void minimumGuiWidthLayoutDoesNotClip() {
        var l = JukeboxLibraryScreen.computeLayout(320, 240);
        assertEquals(280, l.contentW());
        assertEquals(20, l.contentL());
        assertTrue(l.scrubWrapped());
        assertTrue(l.searchRight() <= l.sortX());
        assertTrue(l.sortRight() <= l.contentL() + l.contentW());
        assertTrue(l.scrubRight() <= l.contentL() + l.contentW());
        assertTrue(l.listTop() > l.scrubBottom());
        assertTrue(l.listBottom() <= l.doneY());
    }

    @Test
    void listBoundsLeavePaddingAboveDoneButton() {
        var l = JukeboxLibraryScreen.computeLayout(854, 480);
        assertTrue(l.listBottom() < l.doneY());
        assertEquals(80, l.doneW());
        assertEquals((854 - 80) / 2, l.doneX());
    }

    @Test
    void playbackSliderFormatMath() {
        assertEquals("0:00", PlaybackSlider.format(0f));
        assertEquals("0:05", PlaybackSlider.format(5.3f));
        assertEquals("1:00", PlaybackSlider.format(60f));
        assertEquals("3:25", PlaybackSlider.format(205.9f));
        assertEquals("0:00", PlaybackSlider.format(-10f));
    }

    @Test
    void playbackSliderSyncVisibilityRules() {
        var slider = new PlaybackSlider(0, 0, 100);
        assertFalse(slider.visible);
        assertFalse(slider.active);

        // Stopped/paused/starting: seekable is false
        slider.sync(10f, 100f, false);
        assertFalse(slider.visible);
        assertFalse(slider.active);

        // Playing with invalid duration
        slider.sync(10f, 0f, true);
        assertFalse(slider.visible);
        assertFalse(slider.active);

        slider.sync(10f, -1f, true);
        assertFalse(slider.visible);
        assertFalse(slider.active);

        // Playing with valid seekable duration
        slider.sync(25f, 100f, true);
        assertTrue(slider.visible);
        assertTrue(slider.active);
        assertEquals(0.25, slider.getValue(), 0.001);
        assertEquals("0:25 / 1:40", slider.getMessage().getString());
    }

    @Test
    void playbackSliderSyncDoesNotOverwriteWhileDragging() {
        var slider = new PlaybackSlider(0, 0, 100);
        slider.sync(25f, 100f, true);
        assertEquals(0.25, slider.getValue(), 0.001);

        // User starts dragging
        slider.setUserDragging(true);
        // Audio ticks position to 30s
        slider.sync(30f, 100f, true);
        // Value must NOT be overwritten by audio position while dragging
        assertEquals(0.25, slider.getValue(), 0.001);

        // User releases drag
        slider.setUserDragging(false);
        slider.sync(30f, 100f, true);
        assertEquals(0.30, slider.getValue(), 0.001);
    }

    @Test
    void ellipsizeHandlesNullAndEmpty() {
        assertEquals("", JukeboxLibraryScreen.ellipsize(null, null, 100));
        assertEquals("", JukeboxLibraryScreen.ellipsize(null, "hello", 0));
        assertEquals("test", JukeboxLibraryScreen.ellipsize(null, "test", 50));
    }
}
