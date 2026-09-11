package com.cuemymusic.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicDirector;

import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Display-state contract for the Pause-screen widget: pure credit
 * formatting and geometry. Live Minecraft state (playback, history) is
 * covered by parent runtime checks instead.
 */
class PauseWidgetStateTest {

    @Test void nativeCreditSplitsIntoTitleAndArtist() {
        assertEquals(new MusicDirector.TrackInfo("Sweden", "C418"),
                MusicDirector.splitCredit("C418 - Sweden"));
    }

    @Test void creditWithoutSeparatorIsTitleOnly() {
        assertEquals(new MusicDirector.TrackInfo("sweden", null),
                MusicDirector.splitCredit("sweden"));
    }

    @Test void creditSplitsOnFirstSeparator() {
        assertEquals(new MusicDirector.TrackInfo("B - C", "A"),
                MusicDirector.splitCredit("A - B - C"));
    }

    @Test void displayLinesShowTitleThenArtist() {
        assertEquals(List.of("Sweden", "C418"), PauseMusicWidget.displayLines(
                Optional.of(new MusicDirector.TrackInfo("Sweden", "C418"))));
    }

    @Test void displayLinesShowTitleOnlyWithoutArtist() {
        assertEquals(List.of("sweden"),
                PauseMusicWidget.displayLines(Optional.of(new MusicDirector.TrackInfo("sweden", null))));
    }

    @Test void displayLinesFallBackWhenNothingPlays() {
        assertEquals(List.of(PauseMusicWidget.FALLBACK_TEXT), PauseMusicWidget.displayLines(Optional.empty()));
    }

    @Test void eligibilityAllowsPauseScreenAndMenuOptions() {
        // pause true regardless of inWorld
        assertTrue(PauseMusicWidget.isEligible(PauseScreen.class, true));
        assertTrue(PauseMusicWidget.isEligible(PauseScreen.class, false));

        // pause subclass true
        class SubPauseScreen extends PauseScreen {
            SubPauseScreen() { super(false); }
        }
        assertTrue(PauseMusicWidget.isEligible(SubPauseScreen.class, true));
        assertTrue(PauseMusicWidget.isEligible(SubPauseScreen.class, false));

        // options only when not in world
        assertTrue(PauseMusicWidget.isEligible(OptionsScreen.class, false));
        assertFalse(PauseMusicWidget.isEligible(OptionsScreen.class, true));

        // options subclass excluded
        class SubOptionsScreen extends OptionsScreen {
            SubOptionsScreen() { super(null, null, false); }
        }
        assertFalse(PauseMusicWidget.isEligible(SubOptionsScreen.class, false));
        assertFalse(PauseMusicWidget.isEligible(SubOptionsScreen.class, true));

        // title false regardless
        assertFalse(PauseMusicWidget.isEligible(TitleScreen.class, false));
        assertFalse(PauseMusicWidget.isEligible(TitleScreen.class, true));

        // generic screen false
        assertFalse(PauseMusicWidget.isEligible(Screen.class, false));
        assertFalse(PauseMusicWidget.isEligible(Screen.class, true));

        // null screen false
        assertFalse(PauseMusicWidget.isEligible(null, false));
        assertFalse(PauseMusicWidget.isEligible(null, true));
    }

    @Test void toggleMinimizeClearsFocusWhenNewlyMinimized() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java"));
        assertTrue(source.contains("screen.clearFocus()"),
                "Panel.toggleMinimize must call screen.clearFocus() when newly minimized");
    }

    @Test void minimizeAndRestoreConstantsMatchSpecification() {
        assertEquals("−", PauseMusicWidget.MINIMIZE_TEXT);
        assertEquals("+", PauseMusicWidget.RESTORE_TEXT);
        assertEquals("Minimize", PauseMusicWidget.MINIMIZE_TOOLTIP);
        assertEquals("Restore", PauseMusicWidget.RESTORE_TOOLTIP);
    }

    @Test void minimizeTogglesStateWhilePreservingQueueOpen() {
        PauseMusicWidget.Panel panel = new PauseMusicWidget.Panel(null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(panel.minimized);
        assertFalse(panel.queueOpen);

        panel.queueOpen = true;
        panel.toggleMinimize();
        assertTrue(panel.minimized);
        assertTrue(panel.queueOpen, "queueOpen must be preserved while minimized");

        panel.toggleMinimize();
        assertFalse(panel.minimized);
        assertTrue(panel.queueOpen, "queueOpen must remain preserved after restoring");
    }

    @Test void layoutKeepsSixPixelMarginWithCompactButtons() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(400, 60, 40, 9);
        // Label box right edge and top edge honor the margin.
        assertEquals(400 - PauseMusicWidget.MARGIN, layout.boxX() + layout.boxWidth());
        assertEquals(PauseMusicWidget.MARGIN, layout.boxY());
        assertEquals(layout.titleX(), layout.artistX());
        assertEquals(layout.titleWidth(), layout.artistWidth());
        assertEquals(20, PauseMusicWidget.BUTTON_SIZE);
        assertEquals(layout.previousX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.playPauseX());
        assertTrue(layout.buttonsY() > layout.artistY());
    }

    @Test void layoutClipsWideTextToBoundedCardWidth() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(400, 10_000, 9_000, 9);
        assertEquals(200, layout.boxWidth());
        assertEquals(400 - PauseMusicWidget.MARGIN - 200, layout.boxX());
    }

    @Test void layoutAlwaysFitsTheButtonRow() {
        PauseMusicWidget.PanelLayout layout = PauseMusicWidget.panelLayout(200, 0, 0, 9);
        assertTrue(layout.previousX() >= 0);
        assertTrue(layout.queueX() + PauseMusicWidget.QUEUE_WIDTH <= layout.boxX() + layout.boxWidth());
    }

    @Test void addWidgetsDoesNotDuplicateOnMultipleCalls() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/PauseMusicWidget.java"));
        assertTrue(source.contains("ATTACHED_PANELS"),
                "PauseMusicWidget must track ATTACHED_PANELS per screen to prevent duplication on resize");
        assertTrue(source.contains("!widgets.contains(title)"),
                "addWidgets must check for existing widgets before adding");
    }
}
