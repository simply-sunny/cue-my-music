package com.cuemymusic;

import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JukeboxLibraryScreenLogicTest {

    static final int ROW_H = 18;
    static int clampScroll(int scroll, int displayedSize, int visibleH) {
        int total = displayedSize * ROW_H;
        int max = Math.max(0, total - visibleH);
        if (scroll < 0) scroll = 0;
        if (scroll > max) scroll = max;
        return scroll;
    }
    static int rowY(int tableTop, int scroll, int idx) {
        int first = scroll / ROW_H;
        int yOff = -(scroll % ROW_H);
        return tableTop + yOff + (idx - first) * ROW_H;
    }

    private MusicTrack mk(String id, String title, String artist, SourceType type) {
        return new MusicTrack(id, title, artist, type);
    }

    @Test void clampNoScrollWhenFits() {
        assertEquals(0, clampScroll(50, 5, 100));
    }

    @Test void clampWithOverflow() {
        assertEquals(260, clampScroll(1000, 20, 100));
    }

    @Test void rowYNoScroll() {
        assertEquals(50, rowY(50, 0, 0));
        assertEquals(68, rowY(50, 0, 1));
    }

    @Test void rowYWithScrollExactRow() {
        assertEquals(100, rowY(100, 18, 1));
        assertEquals(118, rowY(100, 18, 2));
    }

    @Test void rowYWithPartialScroll() {
        assertEquals(91, rowY(100, 9, 0));
        assertEquals(109, rowY(100, 9, 1));
    }
}
