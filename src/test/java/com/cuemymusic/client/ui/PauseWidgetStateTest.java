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

    @Test void presentationDistinguishesNoTrackLoadingAndCooldown() {
        var none = PauseMusicWidget.presentation(
                MusicDirector.PlaybackStatus.NO_TRACK, Optional.empty(), false, 0);
        assertEquals("No music playing", none.title());
        assertNull(none.artist());
        assertFalse(none.scrubEnabled());
        assertFalse(none.playPauseEnabled());

        var loading = PauseMusicWidget.presentation(
                MusicDirector.PlaybackStatus.LOADING,
                Optional.of(new MusicDirector.TrackInfo("Sweden", "C418")), false, 0);
        assertEquals("Sweden", loading.title());
        assertEquals("C418", loading.artist());
        assertEquals("Loading…", loading.endText());
        assertFalse(loading.playPauseEnabled());

        var cooldown = PauseMusicWidget.presentation(
                MusicDirector.PlaybackStatus.COOLDOWN,
                Optional.of(new MusicDirector.TrackInfo("Sweden", "C418")), false, 80);
        assertEquals("No music playing", cooldown.title());
        assertNull(cooldown.artist(), "cooldown must not retain previous track's artist");
        assertEquals("0:04", cooldown.endText());
        assertEquals("Skip cooldown", cooldown.endTooltip());
    }

    @Test void presentationDistinguishesPlayingPausedAndUnknownDuration() {
        Optional<MusicDirector.TrackInfo> track = Optional.of(
                new MusicDirector.TrackInfo("Sweden", "C418"));
        var playing = PauseMusicWidget.presentation(
                MusicDirector.PlaybackStatus.PLAYING, track, true, 0);
        assertEquals("Sweden", playing.title());
        assertEquals("C418", playing.artist());
        assertEquals(PauseMusicWidget.PAUSE_TEXT, playing.playPauseText());
        assertTrue(playing.scrubEnabled());
        assertTrue(playing.playPauseEnabled());

        var paused = PauseMusicWidget.presentation(
                MusicDirector.PlaybackStatus.PAUSED, track, false, 0);
        assertEquals(PauseMusicWidget.PLAY_TEXT, paused.playPauseText());
        assertFalse(paused.scrubEnabled(), "unknown duration must disable scrubbing");
        assertTrue(paused.playPauseEnabled());
    }

    @Test void playbackRateCopyIsTechnicallyHonest() {
        assertEquals("Playback Rate", PauseMusicWidget.RATE_LABEL);
        assertTrue(PauseMusicWidget.RATE_TOOLTIP.toLowerCase().contains("speed"));
        assertTrue(PauseMusicWidget.RATE_TOOLTIP.toLowerCase().contains("pitch"));
        assertFalse(PauseMusicWidget.RATE_TOOLTIP.toLowerCase().contains("tempo"));
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

        // dedicated Mod Menu player true regardless
        assertTrue(PauseMusicWidget.isEligible(MusicPlayerScreen.class, false));
        assertTrue(PauseMusicWidget.isEligible(MusicPlayerScreen.class, true));

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

    @Test void playerWrapsNonOptionsOriginsWithVanillaOptions() {
        assertFalse(MusicPlayerScreen.requiresOptionsTarget(OptionsScreen.class));
        assertTrue(MusicPlayerScreen.requiresOptionsTarget(PauseScreen.class));
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
        assertEquals("×", PauseMusicWidget.CLOSE_TEXT);
        assertEquals("Open music player in Mod Menu", PauseMusicWidget.OPEN_PLAYER_TOOLTIP);
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
        assertEquals(PauseMusicWidget.MIN_WIDTH, layout.boxWidth());
        assertEquals(400 - PauseMusicWidget.MARGIN - PauseMusicWidget.MIN_WIDTH, layout.boxX());
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

    @Test void configureButtonIsLongAndBottomCentered() {
        TrackWeightScreen.Bounds bounds = MusicPlayerScreen.configureButtonBounds(692, 423);
        assertEquals(200, bounds.width());
        assertEquals(20, bounds.height());
        assertEquals((692 - 200) / 2, bounds.x());
        assertEquals(423 - 6 - 20, bounds.y());
    }

    @Test void playerConfigureButtonLabelAndNarrationMatchSpecification() {
        assertEquals("⚙ Configure Track Pools…", MusicPlayerScreen.CONFIGURE_LABEL);
        assertEquals("Configure track pools and selection chances", MusicPlayerScreen.CONFIGURE_NARRATION);
    }
}
