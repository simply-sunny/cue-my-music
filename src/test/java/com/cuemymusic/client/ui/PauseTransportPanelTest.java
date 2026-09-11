package com.cuemymusic.client.ui;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicDirector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boxed transport panel contract: compact dark-box geometry (six-pixel
 * margin, single transport row, bounded height), honest time labels, and
 * pure slider fraction mapping. Live playback stays a runtime concern.
 */
class PauseTransportPanelTest {

    @Test void timeLabelsFormatMinSec() {
        assertEquals("0:00", MusicDirector.formatTime(0.0));
        assertEquals("0:05", MusicDirector.formatTime(5.4));
        assertEquals("3:03", MusicDirector.formatTime(183.2));
        assertEquals("10:00", MusicDirector.formatTime(600.0));
    }

    @Test void timeLabelsStayHonestWhenUnknown() {
        assertEquals("--:--", MusicDirector.formatTime(Double.NaN));
        assertEquals("--:--", MusicDirector.formatTime(Double.POSITIVE_INFINITY));
        assertEquals("--:--", MusicDirector.formatTime(-1.0));
    }

    @Test void sliderFractionMapsPositionOverDuration() {
        assertEquals(0.5, PauseMusicWidget.ScrubSlider.fractionFor(91.5, 183.0), 1e-9);
        assertEquals(0.0, PauseMusicWidget.ScrubSlider.fractionFor(-5.0, 183.0), 1e-9);
        assertEquals(1.0, PauseMusicWidget.ScrubSlider.fractionFor(999.0, 183.0), 1e-9);
        assertEquals(0.0, PauseMusicWidget.ScrubSlider.fractionFor(50.0, Double.NaN), 1e-9);
    }

    @Test void sliderValueMapsBackToSeconds() {
        assertEquals(91.5, PauseMusicWidget.ScrubSlider.secondsFor(0.5, 183.0), 1e-9);
    }

    @Test void boxKeepsSixPixelMarginAndTopRight() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(400, 400, 60, 40, 9, false);
        assertEquals(400 - PauseMusicWidget.MARGIN, layout.boxX() + layout.boxWidth());
        assertEquals(PauseMusicWidget.MARGIN, layout.boxY());
        assertTrue(layout.boxHeight() <= 72, "closed box must stay compact: " + layout.boxHeight());
        assertTrue(layout.buttonsY() + PauseMusicWidget.BUTTON_SIZE <= layout.boxY() + layout.playerCardHeight());
    }

    @Test void transportRowHoldsFiveControlsWithFlexibleEnd() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(400, 60, 40, 9);
        assertEquals(20, PauseMusicWidget.BUTTON_SIZE);
        assertEquals(layout.previousX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.playPauseX());
        assertEquals(layout.playPauseX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.nextX());
        assertEquals(layout.nextX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.endX());
        assertEquals(layout.endX() + layout.endWidth() + PauseMusicWidget.GAP,
                layout.queueX());
        assertTrue(layout.endWidth() > 0, "flexible END must have positive width");
    }

    @Test void formatUpcomingFormatsNumberTitleArtist() {
        assertEquals("1. Sweden - C418",
                PauseMusicWidget.formatUpcoming(1, new MusicDirector.TrackInfo("Sweden", "C418")));
        assertEquals("2. Chrysopoeia",
                PauseMusicWidget.formatUpcoming(2, new MusicDirector.TrackInfo("Chrysopoeia", null)));
    }

    @Test void playerCardPutsTitleArtistLeftScrubberRight() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(400, 60, 40, 9);
        assertEquals(layout.boxX() + PauseMusicWidget.PAD, layout.titleX());
        assertEquals(layout.titleX(), layout.artistX());
        assertTrue(layout.sliderX() >= layout.titleX() + layout.titleWidth());
        assertTrue(layout.sliderX() + layout.sliderWidth() <= layout.minimizeX());
        assertEquals(layout.boxX() + layout.boxWidth() - PauseMusicWidget.PAD,
                layout.minimizeX() + layout.minimizeWidth());
        assertTrue(layout.buttonsY() > layout.sliderY());
    }

    @Test void expandedQueueCardGeometryWhenOpenVsClosed() {
        PauseMusicWidget.PanelLayout closed = PauseMusicWidget.panelLayout(400, 400, 60, 40, 9, false);
        assertEquals(0, closed.queueCardHeight());

        PauseMusicWidget.PanelLayout open = PauseMusicWidget.panelLayout(400, 400, 60, 40, 9, true);
        assertTrue(open.queueCardHeight() > 0);
        assertEquals(open.boxY() + open.playerCardHeight() + PauseMusicWidget.GAP, open.queueCardY());
        assertEquals(open.boxX(), open.queueCardX());
        assertEquals(open.boxWidth(), open.queueCardWidth());
        assertTrue(open.queueHeaderY() >= open.queueCardY() + PauseMusicWidget.PAD);
        assertTrue(open.queueListY() > open.queueHeaderY());
    }

    @Test void wideTextClipsToBoundedCardWidth() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(854, 10_000, 9_000, 9);
        assertEquals(204, layout.boxWidth());
        assertEquals(854 - PauseMusicWidget.MARGIN - 204, layout.boxX());
    }

    @Test void optionsPanelClearsCenteredHeaderAtGuiScaleFive() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(692, 423, 60, 40, 9, false, true);
        assertEquals(182, layout.boxWidth());
        assertEquals(504, layout.boxX());
        assertTrue(layout.boxX() >= 500 + PauseMusicWidget.GAP,
                "panel must clear the Options header by one gap");
    }

    @Test void pausePanelKeepsFullWidthWhenItsRightGutterFits() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(692, 423, 60, 40, 9, false, false);
        assertEquals(204, layout.boxWidth());
        assertEquals(482, layout.boxX());
    }

    @Test void panelsForceMinimizedStateBelowStructuralWidth() {
        assertFalse(PauseMusicWidget.requiresMinimizedPanel(692, true));
        assertTrue(PauseMusicWidget.requiresMinimizedPanel(620, true));
        assertTrue(PauseMusicWidget.requiresMinimizedPanel(520, false));
    }

    @Test void modMenuPlayerIsFiftyPercentWiderWithQueueOnRight() {
        PauseMusicWidget.PanelLayout closed =
                PauseMusicWidget.panelLayout(692, 423, 60, 40, 9, false, false, true);
        PauseMusicWidget.PanelLayout open =
                PauseMusicWidget.panelLayout(692, 423, 60, 40, 9, true, false, true);
        assertEquals(306, closed.boxWidth());
        assertEquals(193, closed.boxX());
        assertEquals(185, closed.boxY());
        assertEquals(open.boxX() + open.boxWidth() + PauseMusicWidget.GAP, open.queueCardX());
        assertEquals(open.boxY(), open.queueCardY());
        assertEquals(204, open.queueCardWidth());
        assertEquals((692 - open.boxWidth() - PauseMusicWidget.GAP - open.queueCardWidth()) / 2,
                open.boxX());
    }

    @Test void narrowModMenuPlayerAndRightQueueFitScreen() {
        PauseMusicWidget.PanelLayout open =
                PauseMusicWidget.panelLayout(300, 209, 60, 40, 9, true, false, true);
        assertTrue(open.boxWidth() >= PauseMusicWidget.MIN_WIDTH);
        assertTrue(open.boxX() >= PauseMusicWidget.MARGIN);
        assertEquals(open.boxX() + open.boxWidth() + PauseMusicWidget.GAP, open.queueCardX());
        assertTrue(open.queueCardWidth() > 0);
        assertTrue(open.queueCardX() + open.queueCardWidth() <= 300 - PauseMusicWidget.MARGIN);
    }

    @Test void narrowScreensStillFitTransportRow() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(220, 0, 0, 9);
        assertTrue(layout.previousX() >= 0, "transport row must stay on-screen");
        assertTrue(layout.boxHeight() <= 72);
    }

    @Test void endTooltipDescribesDelayVsImmediate() {
        assertTrue(PauseMusicWidget.END_TOOLTIP.toLowerCase().contains("delay"),
                "End tooltip must describe the natural delay");
        assertTrue(PauseMusicWidget.NEXT_TOOLTIP.toLowerCase().contains("immediat"),
                "Next tooltip must describe immediate start");
    }
}
