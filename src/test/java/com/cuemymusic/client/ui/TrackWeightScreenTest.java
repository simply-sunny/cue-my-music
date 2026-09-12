package com.cuemymusic.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.MusicPlanner;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.music.WeightedMusicCatalog;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;
import com.cuemymusic.client.ui.TrackWeightScreen.Bounds;
import com.cuemymusic.client.ui.TrackWeightScreen.BrowserState;
import com.cuemymusic.client.ui.TrackWeightScreen.PreviewState;
import com.cuemymusic.client.ui.TrackWeightScreen.ResponsiveLayout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.TabButton;

import static org.junit.jupiter.api.Assertions.*;

class TrackWeightScreenTest {

    private static Track track(String resourceId, String title, String composer) {
        Occurrence occ = new Occurrence(resourceId, null, 1.0);
        return new Track(resourceId, title, composer, 1.0, List.of(occ));
    }

    private static Track track(String resourceId) {
        return track(resourceId, resourceId, null);
    }

    private static Pool poolWithC418AndUnknown() {
        Track c418 = track("minecraft:music/game/sweden", "Sweden", "C418");
        Track unknown = track("minecraft:music/game/unknown", "Unknown Song", null);
        Track lena = track("minecraft:music/game/relic", "Relic", "Lena Raine");
        return new Pool("minecraft:music.game", List.of(c418, unknown, lena),
                List.of(c418.occurrences().getFirst(), unknown.occurrences().getFirst(), lena.occurrences().getFirst()));
    }

    private static TrackWeightScreen screenWithPools(int count) {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        return new TrackWeightScreen(null, TrackWeightConfig.defaults(), pools);
    }

    private static net.minecraft.client.gui.components.Tooltip getWidgetTooltip(net.minecraft.client.gui.components.AbstractWidget widget) {
        try {
            java.lang.reflect.Field tooltipField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
            tooltipField.setAccessible(true);
            net.minecraft.client.gui.components.WidgetTooltipHolder holder =
                    (net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(widget);
            return holder != null ? holder.get() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String getTooltipText(net.minecraft.client.gui.components.AbstractWidget widget) {
        net.minecraft.client.gui.components.Tooltip tooltip = getWidgetTooltip(widget);
        if (tooltip == null) {
            return null;
        }
        try {
            java.lang.reflect.Field messageField = net.minecraft.client.gui.components.Tooltip.class.getDeclaredField("message");
            messageField.setAccessible(true);
            net.minecraft.network.chat.Component msg = (net.minecraft.network.chat.Component) messageField.get(tooltip);
            return msg != null ? msg.getString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Bounds widgetFieldBounds(net.minecraft.client.gui.components.AbstractWidget widget) {
        try {
            java.lang.reflect.Field xField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("x");
            java.lang.reflect.Field yField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("y");
            java.lang.reflect.Field wField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("width");
            java.lang.reflect.Field hField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("height");
            xField.setAccessible(true);
            yField.setAccessible(true);
            wField.setAccessible(true);
            hField.setAccessible(true);
            return new Bounds(xField.getInt(widget), yField.getInt(widget),
                    wField.getInt(widget), hField.getInt(widget));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String getWidgetNarration(net.minecraft.client.gui.components.AbstractWidget widget) {
        try {
            java.lang.reflect.Method method = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredMethod("createNarrationMessage");
            method.setAccessible(true);
            net.minecraft.network.chat.Component comp = (net.minecraft.network.chat.Component) method.invoke(widget);
            return comp != null ? comp.getString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertIcon(net.minecraft.client.gui.components.AbstractWidget widget, String expectedMessage, String expectedTooltip) {
        assertNotNull(widget, "Widget must not be null");
        assertEquals(expectedMessage, widget.getMessage().getString(), "Icon message must match");
        assertEquals(expectedTooltip, getTooltipText(widget), "Tooltip must match");
        assertEquals(expectedTooltip, getWidgetNarration(widget), "Narration must match");
    }

    @Test
    void workspaceIsCenteredInnerEightyPercent() {
        assertEquals(new Bounds(100, 60, 800, 480), TrackWeightScreen.workspaceBounds(1000, 600));
        assertEquals(new Bounds(30, 21, 240, 167), TrackWeightScreen.workspaceBounds(300, 209));
    }

    @Test
    void poolTabsFillWorkspaceAndHaveNoArrowButtons() {
        TrackWeightScreen screen = screenWithPools(30);
        screen.width = 1000;
        screen.height = 600;
        screen.init();
        Bounds workspace = TrackWeightScreen.workspaceBounds(1000, 600);
        assertEquals(workspace.x(), screen.tabNavigationBar().getX());
        assertEquals(workspace.width(), screen.tabNavigationBar().getWidth());
        assertTrue(screen.tabNavigationBar().maxScroll() > 0);
        assertTrue(screen.children().stream().noneMatch(child -> child instanceof Button button
                && (button.getMessage().getString().equals("<") || button.getMessage().getString().equals(">"))));
    }

    @Test
    void poolTabsUseCleanTitlesAndClampedWheelScrollingAndReveal() {
        TrackWeightScreen screen = screenWithPools(30);
        screen.width = 1000;
        screen.height = 600;
        screen.init();
        TrackWeightScreen.ScrollablePoolTabBar bar = screen.tabNavigationBar();
        Bounds workspace = TrackWeightScreen.workspaceBounds(1000, 600);

        // Clean category titles on tab buttons
        List<String> expectedTitles = screen.orderedPools().stream()
                .map(p -> TrackWeightScreen.poolDisplayName(p.id()))
                .toList();
        for (int i = 0; i < 30; i++) {
            TabButton tabBtn = bar.tabButtons().get(i);
            assertEquals(expectedTitles.get(i), tabBtn.getMessage().getString());
        }

        // Wheel scrolling clamps inside maxScroll()
        int maxScroll = bar.maxScroll();
        assertTrue(maxScroll > 0);

        int initialOffset = bar.scrollOffset();
        screen.mouseScrolled(workspace.x() + 10, workspace.y() + 10, 0.0, -1.0);
        assertTrue(bar.scrollOffset() > initialOffset, "Wheel scroll down must increase scroll offset");

        // Scroll past max clamps to maxScroll
        bar.scrollBy(maxScroll + 1000);
        assertEquals(maxScroll, bar.scrollOffset(), "Scroll offset must clamp to maxScroll()");

        // Scrolling further down stays clamped at maxScroll
        screen.mouseScrolled(workspace.x() + 10, workspace.y() + 10, 0.0, -1.0);
        assertEquals(maxScroll, bar.scrollOffset(), "Wheel scroll down must stay clamped to maxScroll()");

        // Scroll past 0 clamps to 0
        bar.scrollBy(-maxScroll - 1000);
        assertEquals(0, bar.scrollOffset(), "Scroll offset must clamp to 0");

        // Scrolling further up stays clamped at 0
        screen.mouseScrolled(workspace.x() + 10, workspace.y() + 10, 0.0, 1.0);
        assertEquals(0, bar.scrollOffset(), "Wheel scroll up must stay clamped to 0");

        // Selected-tab reveal clamps inside maxScroll()
        bar.revealTab(29);
        assertTrue(bar.scrollOffset() <= maxScroll, "Reveal last tab must clamp inside maxScroll()");
        assertTrue(bar.scrollOffset() > 0, "Reveal last tab must increase offset");

        bar.revealTab(0);
        assertEquals(0, bar.scrollOffset(), "Reveal first tab must clamp to 0");
    }

    @Test
    void wideLayoutPlacesBrowserBesideMainAndEditorBelowWheel() {
        ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(1200, 800, true);
        assertTrue(layout.browserFits());
        assertTrue(layout.browser().right() < layout.main().x());
        assertTrue(layout.wheel().bottom() <= layout.editor().y());
        assertTrue(layout.workspace().contains(layout.bottomToolbar()));
    }

    @Test
    void narrowOpenBrowserFillsContentAndHidesMain() {
        ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(640, 360, true);
        assertFalse(layout.browserFits());
        assertEquals(layout.content(), layout.browser());
        assertNull(layout.wheel());
        assertNull(layout.editor());
    }

    @Test
    void browserStateTransitionsAndPreservation() {
        BrowserState state = new BrowserState();
        // First wide init opens
        state.updateForFit(true);
        assertTrue(state.isOpen(), "First wide init must open browser");

        // Wide -> narrow auto-closes
        state.updateForFit(false);
        assertFalse(state.isOpen(), "Wide -> narrow resize must auto-close browser");

        // Manual narrow open persists through same-size rebuild
        state.setOpen(true);
        state.updateForFit(false);
        assertTrue(state.isOpen(), "Manual narrow open must persist through same-size rebuild");

        // Narrow -> wide restores side-by-side
        state.updateForFit(true);
        assertTrue(state.isOpen(), "Narrow -> wide resize must restore open browser");
    }

    @Test
    void screenBrowserStateTogglesPreserveDraftAndSelection() {
        Pool pool1 = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool1);

        // First wide init opens
        screen.initForDimensions(1200, 800);
        assertTrue(screen.browserState().isOpen(), "Wide screen must initialize browser open");

        // Wide -> narrow auto-closes
        screen.initForDimensions(640, 360);
        assertFalse(screen.browserState().isOpen(), "Resizing to narrow must auto-close browser");

        // Manual narrow open persists through same-size rebuild
        screen.toggleBrowser();
        assertTrue(screen.browserState().isOpen(), "Toggling browser must open it in narrow overlay");
        screen.initForDimensions(640, 360);
        assertTrue(screen.browserState().isOpen(), "Manual narrow open must persist through rebuild at same size");

        // Narrow -> wide restores side-by-side
        screen.initForDimensions(1200, 800);
        assertTrue(screen.browserState().isOpen(), "Resizing to wide must restore open browser");

        // Toggling does not alter draft or selection
        TrackWeightConfig draftBefore = screen.draft();
        Track selectedBefore = screen.selectedTrack();
        screen.toggleBrowser();
        assertEquals(draftBefore, screen.draft(), "Toggling browser must not mutate draft");
        assertEquals(selectedBefore, screen.selectedTrack(), "Toggling browser must not mutate selected track");
    }

    @Test
    void runtimeAcceptanceNoTabOverlapAndTogglePresent() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        ResponsiveLayout layout = screen.responsiveLayout();
        assertNotNull(layout.browser());

        // Browser, search box, and track list start strictly below tab header within workspace
        assertTrue(layout.browser().y() >= layout.tabs().bottom(),
                "Browser top must start below tab header bottom");
        assertNotNull(screen.searchBox());
        assertNotNull(screen.trackList());
        assertTrue(screen.searchBox().getY() >= layout.tabs().bottom(),
                "Search box Y must be below tab header bottom");
        assertTrue(screen.trackList().getY() >= screen.searchBox().getBottom(),
                "Track list Y must be below search box bottom");
        assertTrue(layout.workspace().contains(layout.browser()),
                "Workspace must contain browser bounds");

        // Toggle button exists with ☰/× toggle, tooltip, and narration
        Button toggleBtn = screen.browserToggleButton();
        assertNotNull(toggleBtn, "Toggle button must exist");
        assertEquals("×", toggleBtn.getMessage().getString(), "Open browser toggle message must be ×");
        assertEquals("Hide track list", getTooltipText(toggleBtn), "Open toggle button tooltip must be Hide track list");
        assertEquals("Hide track list", getWidgetNarration(toggleBtn), "Open toggle button narration must be Hide track list");

        // Toggle closes browser
        screen.toggleBrowser();
        assertNull(screen.responsiveLayout().browser(), "Closing browser must collapse it");
        assertEquals("☰", screen.browserToggleButton().getMessage().getString(), "Collapsed browser toggle message must be ☰");
        assertEquals("Show track list", getTooltipText(screen.browserToggleButton()), "Collapsed toggle button tooltip must be Show track list");
        assertEquals("Show track list", getWidgetNarration(screen.browserToggleButton()), "Collapsed toggle button narration must be Show track list");
    }

    @Test
    void wheelShrinksTo16pxAtShortestSupportedWindow() {
        ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(300, 209, false);
        assertNotNull(layout.wheel(), "Wheel must exist in collapsed mode even at 300x209");
        assertTrue(layout.wheel().width() >= 16, "Wheel width must be at least 16px");
        assertTrue(layout.wheel().height() >= 16, "Wheel height must be at least 16px");
        assertTrue(layout.wheel().bottom() <= layout.editor().y(), "Wheel bottom must not overlap editor y");
        assertTrue(layout.editor().bottom() <= layout.preview().y(), "Editor bottom must not overlap preview y");
        assertTrue(layout.preview().bottom() <= layout.main().bottom(), "Preview bottom must stay inside main panel");
        assertTrue(layout.workspace().contains(layout.main()), "Workspace must contain main panel");
    }

    @Test
    void narrowOpenedBrowserHidesMainWidgetsWhilePreviewAudioContinues() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(640, 360);

        // Initially collapsed in narrow mode: wheel and idle preview exist
        assertNotNull(screen.radialWheel(), "Wheel should exist in narrow collapsed mode");
        assertNotNull(screen.previewButton(), "Idle preview should exist in narrow collapsed mode");
        assertNull(screen.previewPlayer(), "No active player while idle");

        // Start preview -> compact player replaces idle control
        screen.previewButton().onPress(null);
        screen.tick();
        assertTrue(screen.previewState().isPlaying(), "Preview must be playing");
        assertNull(screen.previewButton(), "Idle control must be replaced while preview is active");
        assertNotNull(screen.previewPlayer(), "Compact player must appear while preview is active");

        // Open browser in narrow mode
        screen.toggleBrowser();
        assertTrue(screen.browserState().isOpen());
        assertNull(screen.radialWheel(), "Radial wheel must be hidden (null) in narrow open browser overlay");
        assertNull(screen.previewButton(), "Idle preview must be hidden (null) in narrow open browser overlay");
        assertNull(screen.previewPlayer(), "Active player must be hidden (null) in narrow open browser overlay");
        assertTrue(screen.previewState().isPlaying(), "Audio preview must continue playing while browser overlay is open");

        // Close browser in narrow mode: restores main widgets and same preview playing state
        screen.toggleBrowser();
        assertFalse(screen.browserState().isOpen());
        assertNotNull(screen.radialWheel(), "Radial wheel must be restored when browser is closed");
        assertNotNull(screen.previewPlayer(), "Active player must be restored when browser is closed");
        assertNull(screen.previewButton(), "Idle control must stay replaced while preview is active");
        assertTrue(screen.previewState().isPlaying(), "Preview must still be playing after closing browser");
    }

    @Test
    void activePreviewConsumesLowerMainPanelRegionInsideWorkspace() {
        int[][] dims = {
                {427, 254},
                {800, 600},
                {1200, 800},
                {1920, 1080}
        };
        for (int[] dim : dims) {
            int w = dim[0];
            int h = dim[1];
            Pool pool = poolWithC418AndUnknown();
            TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
            screen.initForDimensions(w, h);
            screen.previewButton().onPress(null);
            screen.tick();
            assertNotNull(screen.previewPlayer(), "Player must appear at " + w + "x" + h);
            ResponsiveLayout layout = screen.responsiveLayout();
            assertNotNull(layout.main(), "Main panel must exist at " + w + "x" + h);
            assertNotNull(layout.preview(), "Preview bounds must exist at " + w + "x" + h);
            assertTrue(layout.main().contains(layout.preview()),
                    "Main must contain preview at " + w + "x" + h);
            assertTrue(layout.workspace().contains(layout.preview()),
                    "Workspace must contain preview at " + w + "x" + h);
            assertTrue(layout.wheel().bottom() <= layout.editor().y(),
                    "Wheel must stay above editor at " + w + "x" + h);
            assertTrue(layout.editor().bottom() <= layout.preview().y(),
                    "Editor must stay above preview at " + w + "x" + h);
            assertTrue(layout.preview().bottom() <= layout.main().bottom(),
                    "Preview must stay inside main at " + w + "x" + h);
            assertTrue(layout.preview().width() >= layout.main().width() / 2,
                    "Active preview must consume available lower main-panel width at " + w + "x" + h);
            assertEquals(layout.main().bottom(), layout.preview().bottom(),
                    "Active preview must extend to main-panel bottom at " + w + "x" + h);
        }
    }

    @Test
    void steadyIdleTicksRetainIdleButtonInChildren() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        Button idle = screen.previewButton();
        assertNotNull(idle, "Idle preview must exist");
        for (int i = 0; i < 3; i++) {
            screen.tick();
        }
        assertSame(idle, screen.previewButton(), "Steady idle ticks must retain the same button instance");
        assertTrue(screen.children().contains(idle), "Idle button must stay in children after steady ticks");
        assertEquals("▶", screen.previewButton().getMessage().getString());
    }

    @Test
    void stopToIdlePlusExtraTickRetainsIdleButton() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        screen.previewButton().onPress(null);
        screen.tick();
        assertNotNull(screen.previewPlayer());

        screen.previewPlayer().stopButton().onPress(null);
        screen.tick();
        assertNull(screen.previewPlayer());
        assertNotNull(screen.previewButton(), "Idle control must return after stop");
        screen.tick();
        assertNotNull(screen.previewButton(), "Extra idle tick must retain the button");
        assertTrue(screen.children().contains(screen.previewButton()),
                "Idle button must stay in children after stop plus extra tick");
    }

    @Test
    void naturalCompletionSwapsPlayerBackToIdle() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        screen.previewButton().onPress(null);
        screen.tick();
        assertNotNull(screen.previewPlayer(), "Player must appear after start");

        for (int i = 0; i < 60; i++) {
            screen.tick();
        }
        assertNull(screen.previewPlayer(), "Natural completion must remove the player");
        assertNotNull(screen.previewButton(), "Natural completion must restore the idle control");
        assertTrue(screen.children().contains(screen.previewButton()),
                "Restored idle button must be in children");
        assertEquals("▶", screen.previewButton().getMessage().getString());
    }

    @Test
    void activePlayerWidgetsStayInsidePreviewAt300x209() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(300, 209);
        assertNotNull(screen.previewButton(), "Idle preview must exist at 300x209");

        screen.previewButton().onPress(null);
        screen.tick();
        assertNotNull(screen.previewPlayer(), "Player must appear at 300x209");
        Bounds preview = screen.responsiveLayout().preview();
        for (net.minecraft.client.gui.components.AbstractWidget w : screen.previewPlayer().widgets()) {
            Bounds extent = widgetFieldBounds(w);
            assertTrue(preview.contains(extent),
                    "Widget " + w.getMessage().getString() + " extent " + extent
                            + " must stay inside preview " + preview + " at 300x209");
        }
        int errorY = screen.responsiveLayout().bottomToolbar().y() - 12;
        assertTrue(preview.bottom() <= errorY,
                "Preview bottom must stay above the save-error row at 300x209");
    }

    @Test void configureButtonIsLongAndBottomCentered() {
        TrackWeightScreen.Bounds bounds = MusicPlayerScreen.configureButtonBounds(692, 423);
        assertEquals(200, bounds.width());
        assertEquals(20, bounds.height());
        assertEquals((692 - 200) / 2, bounds.x());
        assertEquals(423 - 6 - 20, bounds.y());
    }

    @Test void settingsDraftIsIndependentFromSavedConfig() {
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightConfig draft = TrackWeightScreen.updateWeight(saved, "p", "t", 5.0);
        assertEquals(1.0, saved.multiplier("p", "t"));
        assertEquals(5.0, draft.multiplier("p", "t"));
    }

    @Test
    void toolbarActionButtonsUseIconOnlyLabelsTooltipsAndNarration() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        assertIcon(screen.allButton(), "↺", "Reset pool to native weights");
        assertIcon(screen.c418Button(), "♫", "Double C418 tracks");
        assertIcon(screen.muteButton(), "∅", "Mute selected pool");
        assertIcon(screen.testRollButton(), "⚄", "Test weighted selection");
        assertIcon(screen.jsonButton(), "{}", "View and copy JSON");
        assertIcon(screen.doneButton(), "✓", "Done");
    }

    @Test
    void antiRepeatButtonUsesIconOnlyAndTogglesStateWithNarration() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        // Initial state is true (on)
        assertTrue(screen.draft().antiRepeat());
        assertEquals("⟳", screen.antiRepeatButton().getMessage().getString());
        assertEquals("Anti-Repeat: On", getTooltipText(screen.antiRepeatButton()));
        assertEquals("Anti-Repeat: On", getWidgetNarration(screen.antiRepeatButton()));

        // Press to toggle off
        screen.antiRepeatButton().onPress(null);
        assertFalse(screen.draft().antiRepeat());
        assertEquals("⟳", screen.antiRepeatButton().getMessage().getString());
        assertEquals("Anti-Repeat: Off", getTooltipText(screen.antiRepeatButton()));
        assertEquals("Anti-Repeat: Off", getWidgetNarration(screen.antiRepeatButton()));

        // Press to toggle on
        screen.antiRepeatButton().onPress(null);
        assertTrue(screen.draft().antiRepeat());
        assertEquals("⟳", screen.antiRepeatButton().getMessage().getString());
        assertEquals("Anti-Repeat: On", getTooltipText(screen.antiRepeatButton()));
        assertEquals("Anti-Repeat: On", getWidgetNarration(screen.antiRepeatButton()));
    }

    @Test
    void toolbarActionButtonsPreserveExistingBehavior() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        // Mute: sets 0x
        screen.muteButton().onPress(null);
        assertEquals(0.0, screen.draft().multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(0.0, screen.draft().multiplier(pool.id(), "minecraft:music/game/unknown"));

        // All: sets 1x
        screen.allButton().onPress(null);
        assertEquals(1.0, screen.draft().multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, screen.draft().multiplier(pool.id(), "minecraft:music/game/unknown"));

        // C418: sets C418 to 2x, other tracks 1x
        screen.c418Button().onPress(null);
        assertEquals(2.0, screen.draft().multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, screen.draft().multiplier(pool.id(), "minecraft:music/game/unknown"));

        // Test Roll: selects a track in the pool
        screen.testRollButton().onPress(null);
        assertNotNull(screen.selectedTrack());
    }

    @Test
    void toolbarButtonsContainedInBottomToolbarAndDoNotOverlapAcrossResolutions() {
        int[][] dims = {
                {300, 209},
                {427, 254},
                {640, 360},
                {800, 600},
                {1920, 1080}
        };
        Pool pool = poolWithC418AndUnknown();
        for (int[] dim : dims) {
            int w = dim[0];
            int h = dim[1];
            TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
            screen.initForDimensions(w, h);

            Bounds tb = screen.responsiveLayout().bottomToolbar();
            List<AbstractWidget> buttons = List.of(
                    screen.allButton(),
                    screen.c418Button(),
                    screen.muteButton(),
                    screen.antiRepeatButton(),
                    screen.testRollButton(),
                    screen.jsonButton(),
                    screen.doneButton()
            );

            for (int i = 0; i < buttons.size(); i++) {
                AbstractWidget b1 = buttons.get(i);
                assertNotNull(b1, "Button " + i + " must exist at " + w + "x" + h);
                Bounds r1 = new Bounds(b1.getX(), b1.getY(), b1.getWidth(), b1.getHeight());
                assertTrue(tb.contains(r1),
                        "Button " + b1.getMessage().getString() + " bounds " + r1 + " must be inside toolbar " + tb + " at " + w + "x" + h);

                for (int j = i + 1; j < buttons.size(); j++) {
                    AbstractWidget b2 = buttons.get(j);
                    Bounds r2 = new Bounds(b2.getX(), b2.getY(), b2.getWidth(), b2.getHeight());
                    assertFalse(r1.overlaps(r2),
                            "Button " + b1.getMessage().getString() + " and " + b2.getMessage().getString() + " must not overlap at " + w + "x" + h);
                }
            }
        }
    }

    @Test
    void saveErrorRowDoesNotOverlapPreviewAtShortestWindow() {
        int[][] dims = {
                {300, 209},
                {427, 254},
                {640, 360},
                {800, 600},
                {1920, 1080}
        };
        for (int[] dim : dims) {
            int w = dim[0];
            int h = dim[1];
            ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(w, h, false);
            assertNotNull(layout.preview(), "Preview must exist at " + w + "x" + h);
            int errorY = layout.bottomToolbar().y() - 12;
            assertTrue(layout.preview().bottom() <= errorY,
                    "Preview bottom (" + layout.preview().bottom() + ") must be <= save error Y (" + errorY + ") at " + w + "x" + h);
            assertTrue(errorY + 9 <= layout.bottomToolbar().y(),
                    "Save error text bottom (" + (errorY + 9) + ") must be <= bottom toolbar Y (" + layout.bottomToolbar().y() + ") at " + w + "x" + h);
        }
    }

    @Test void playerScreenConfiguresButtonAndOpensSettingsScreen() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java"));
        assertTrue(source.contains("CONFIGURE_LABEL"), "Must define CONFIGURE_LABEL");
        assertTrue(source.contains("\"⚙ Configure Track Pools…\""), "Configure button label must match exact copy");
        assertTrue(source.contains("CONFIGURE_NARRATION"), "Must define CONFIGURE_NARRATION");
        assertTrue(source.contains("\"Configure track pools and selection chances\""), "Narration must match exact copy");
        assertTrue(source.contains("openTrackSettings"), "Must provide openTrackSettings()");
        assertTrue(source.contains("new TrackWeightScreen(this)"), "openTrackSettings must construct TrackWeightScreen with this as parent");
        assertTrue(source.contains("minecraft.setScreenAndShow("), "openTrackSettings must show settings screen");
    }

    @Test void trackWeightScreenSourceGuaranteesTransactionalLifecycle() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("class TrackWeightScreen extends Screen"),
                "TrackWeightScreen must extend Screen");
        assertTrue(source.contains("parent"), "Must store parent screen");
        assertTrue(source.contains("saved"), "Must store saved config");
        assertTrue(source.contains("draft"), "Must store draft config");
        assertTrue(source.contains("onClose()"), "Must override onClose()");
        assertTrue(source.contains("Could not save cue-my-music.json"),
                "Save failure must set error component");

        // Save sequencing: verify strictly within saveAndClose's try-block before catch
        int saveStart = source.indexOf("void saveAndClose()");
        assertTrue(saveStart >= 0, "Must provide saveAndClose()");
        int catchIndex = source.indexOf("catch (IOException", saveStart);
        assertTrue(catchIndex > saveStart, "saveAndClose must catch IOException");
        String saveSuccessBlock = source.substring(saveStart, catchIndex);
        int saveIndex = saveSuccessBlock.indexOf("MusicDirector.getInstance().saveWeightingConfig(draft)");
        int showParentIndex = saveSuccessBlock.indexOf("minecraft.setScreenAndShow(parent)", saveIndex);
        assertTrue(saveIndex >= 0, "saveAndClose must persist draft to MusicDirector");
        assertTrue(showParentIndex > saveIndex,
                "Save success must return to parent only after saveWeightingConfig returns");

        // Discard: verify onClose returns to parent without saving
        int onCloseStart = source.indexOf("void onClose()");
        assertTrue(onCloseStart >= 0, "Must provide onClose()");
        int onCloseEnd = source.indexOf('}', onCloseStart);
        String onCloseBlock = source.substring(onCloseStart, onCloseEnd);
        assertTrue(onCloseBlock.contains("minecraft.setScreenAndShow(parent)"),
                "onClose must return to parent");
        assertFalse(onCloseBlock.contains("saveWeightingConfig"),
                "onClose must discard without saving");
    }

    @Test void searchMatchesTitleComposerAndResource() {
        List<Track> tracks = List.of(track("minecraft:sounds/music/game/sweden.ogg", "Sweden", "C418"));
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "swed").size());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "c418").size());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "music/game").size());
        assertTrue(TrackWeightScreen.filterTracks(tracks, "raine").isEmpty());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "").size());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, null).size());
    }

    @Test void sliderEndpointsAndHalfSteps() {
        assertEquals(0.0, TrackWeightScreen.sliderToMultiplier(0.0), 1e-9);
        assertEquals(0.5, TrackWeightScreen.sliderToMultiplier(0.05), 1e-9);
        assertEquals(1.0, TrackWeightScreen.sliderToMultiplier(0.10), 1e-9);
        assertEquals(2.0, TrackWeightScreen.sliderToMultiplier(0.20), 1e-9);
        assertEquals(2.5, TrackWeightScreen.sliderToMultiplier(0.24), 1e-9);
        assertEquals(2.5, TrackWeightScreen.sliderToMultiplier(0.26), 1e-9);
        assertEquals(5.0, TrackWeightScreen.sliderToMultiplier(0.50), 1e-9);
        assertEquals(10.0, TrackWeightScreen.sliderToMultiplier(1.00), 1e-9);

        assertEquals(0.0, TrackWeightScreen.multiplierToSlider(0.0), 1e-9);
        assertEquals(0.05, TrackWeightScreen.multiplierToSlider(0.5), 1e-9);
        assertEquals(0.10, TrackWeightScreen.multiplierToSlider(1.0), 1e-9);
        assertEquals(0.20, TrackWeightScreen.multiplierToSlider(2.0), 1e-9);
        assertEquals(0.50, TrackWeightScreen.multiplierToSlider(5.0), 1e-9);
        assertEquals(1.00, TrackWeightScreen.multiplierToSlider(10.0), 1e-9);
    }

    @Test void c418ActionChangesOnlyCreditedC418Tracks() {
        TrackWeightConfig draft = TrackWeightConfig.defaults();
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig changed = TrackWeightScreen.applyC418(draft, pool);
        assertEquals(2.0, changed.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/relic"));
    }

    @Test void allActionSetsEveryLoadedTrackToOne() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig draft = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 0.0)
                .withMultiplier(pool.id(), "minecraft:music/game/relic", 5.0)
                .withMultiplier("other:pool", "other:track", 3.0);
        TrackWeightConfig changed = TrackWeightScreen.applyAll(draft, pool);
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/relic"));
        assertEquals(3.0, changed.multiplier("other:pool", "other:track"));
    }

    @Test void muteActionSetsEveryLoadedTrackToZero() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig draft = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.0)
                .withMultiplier("other:pool", "other:track", 4.0);
        TrackWeightConfig changed = TrackWeightScreen.mute(draft, pool);
        assertEquals(0.0, changed.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(0.0, changed.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(0.0, changed.multiplier(pool.id(), "minecraft:music/game/relic"));
        assertEquals(4.0, changed.multiplier("other:pool", "other:track"));
    }

    @Test void testRollProducesDeterministicSelectionWithoutMutatingConfigOrPlanner() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig config = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.0);
        MusicPlanner planner = new MusicPlanner();
        long initialPlannerSeq = planner.peekSequence(pool.id());

        long testSeed = 987654321L;
        Optional<Occurrence> roll1 = TrackWeightScreen.testRoll(pool, config, testSeed);
        Optional<Occurrence> roll2 = TrackWeightScreen.testRoll(pool, config, testSeed);

        assertTrue(roll1.isPresent());
        assertEquals(roll1.get().resourceId(), roll2.get().resourceId());
        assertEquals(WeightedMusicCatalog.select(pool, config, null, testSeed).get().resourceId(),
                roll1.get().resourceId());

        // Assert input config was not mutated
        assertEquals(2.0, config.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, config.multiplier(pool.id(), "minecraft:music/game/unknown"));
        // Assert planner sequence was not mutated
        assertEquals(initialPlannerSeq, planner.peekSequence(pool.id()));
    }

    @Test void chanceRefreshReflectsDraftChanges() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig initial = TrackWeightConfig.defaults();
        Map<String, Double> initialChances = WeightedMusicCatalog.chances(pool, initial, null);
        assertEquals(1.0 / 3.0, initialChances.get("minecraft:music/game/sweden"), 1e-9);

        TrackWeightConfig doubled = TrackWeightScreen.applyC418(initial, pool);
        Map<String, Double> newChances = WeightedMusicCatalog.chances(pool, doubled, null);
        // Total weight: 2.0 + 1.0 + 1.0 = 4.0 -> sweden chance = 2/4 = 0.5
        assertEquals(0.5, newChances.get("minecraft:music/game/sweden"), 1e-9);
        assertEquals(0.25, newChances.get("minecraft:music/game/unknown"), 1e-9);
        assertEquals(0.25, newChances.get("minecraft:music/game/relic"), 1e-9);
    }

    @Test void completeDraftJsonContainsRetainedAndDraftWeights() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig draft = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.5)
                .withMultiplier("custom:pool", "custom:track", 4.0);
        String json = draft.toJson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertTrue(root.get("antiRepeat").getAsBoolean());
        JsonObject weights = root.getAsJsonObject("weights");
        assertEquals(2.5, weights.getAsJsonObject(pool.id()).get("minecraft:music/game/sweden").getAsDouble());
        assertEquals(4.0, weights.getAsJsonObject("custom:pool").get("custom:track").getAsDouble());
    }

    @Test void narrationFormattingBehavior() {
        assertEquals("Sweden, C418, 2×, 50%",
                TrackWeightScreen.formatNarration("Sweden", "C418", 2.0, 0.50));
        assertEquals("Unknown Song, Unknown composer, 1×, 25%",
                TrackWeightScreen.formatNarration("Unknown Song", null, 1.0, 0.25));
        assertEquals("Relic, Lena Raine, 0.5×, 10%",
                TrackWeightScreen.formatNarration("Relic", "Lena Raine", 0.5, 0.10));
    }

    @Test void modelInitialStateAndAccessors() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.0);
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));

        assertEquals(pool, model.selectedPool());
        assertEquals(pool.tracks().getFirst(), model.selectedTrack());
        assertEquals(saved, model.draft());
        assertEquals("", model.searchQuery());
        assertTrue(model.currentChances().containsKey("minecraft:music/game/sweden"));

        TrackWeightScreen.Model emptyModel = new TrackWeightScreen.Model(saved, List.of());
        assertNull(emptyModel.selectedPool());
        assertNull(emptyModel.selectedTrack());
        assertTrue(emptyModel.currentChances().isEmpty());
    }

    @Test void filteredSelectionBehavior() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));

        model.setSearchQuery("unknown");
        assertNotNull(model.selectedTrack());
        assertEquals("Unknown Song", model.selectedTrack().title());
        assertEquals(1, model.filteredTracks().size());

        model.setSearchQuery("nonexistent");
        assertNull(model.selectedTrack());
        assertTrue(model.filteredTracks().isEmpty());

        model.setSearchQuery("");
        assertNotNull(model.selectedTrack());
        assertEquals("Sweden", model.selectedTrack().title());
        assertEquals(3, model.filteredTracks().size());
    }

    @Test void poolSwitchClearsSearchAndSynchronizesEditorWithList() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool1, pool2));

        model.setSearchQuery("relic");
        assertEquals("Relic", model.selectedTrack().title());
        assertEquals(1, model.filteredTracks().size());

        model.switchPool(pool2);
        assertEquals("", model.searchQuery());
        assertEquals(pool2, model.selectedPool());
        assertEquals(netherTrack, model.selectedTrack());
        assertEquals(List.of(netherTrack), model.filteredTracks());
        assertEquals(model.selectedTrack(), model.filteredTracks().getFirst());
    }

    @Test void sliderSynchronizationGuardPreventsReentrantCallbacks() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));
        AtomicInteger callbacks = new AtomicInteger(0);

        TrackWeightScreen.WeightSlider slider =
                new TrackWeightScreen.WeightSlider(0, 0, 200, 20, model, callbacks::incrementAndGet);

        // User changes slider value directly
        slider.applyValue();
        assertEquals(1, callbacks.get(), "User slider update must trigger exactly one draft callback");

        // External model update syncing to slider must not re-trigger applyValue / callback
        model.setMultiplier(5.0);
        slider.syncFromModel(5.0);
        assertEquals(1, callbacks.get(), "syncFromModel must not cause re-entrant draft callbacks");
        assertEquals(TrackWeightScreen.multiplierToSlider(5.0), slider.sliderValue(), 1e-6);
    }

    @Test void jsonScreenValueAndClipboardSemantics() {
        String original = "{\"version\":1,\"antiRepeat\":true}";
        assertEquals(original, TrackWeightScreen.JsonScreen.enforceReadOnly(original, "mutated string"));
        assertEquals("", TrackWeightScreen.JsonScreen.enforceReadOnly(null, "incoming"));

        AtomicReference<String> clipboard = new AtomicReference<>();
        TrackWeightScreen.JsonScreen.copyToClipboard(original, clipboard::set);
        assertEquals(original, clipboard.get());
    }

    @Test void responsiveLayoutBoundsGuaranteeFitAcrossBoundaryWidths() {
        int[] widths = {640, 679, 680, 800, 1920};
        for (int w : widths) {
            ResponsiveLayout layout = TrackWeightScreen.responsiveLayout(w, 400, true);
            assertTrue(layout.workspace().x() >= 0, "Workspace x must be >= 0 at width " + w);
            assertTrue(layout.workspace().right() <= w, "Workspace right must fit width " + w);
            assertTrue(layout.workspace().contains(layout.tabs()));
            assertTrue(layout.workspace().contains(layout.content()));
            assertTrue(layout.workspace().contains(layout.bottomToolbar()));
            if (layout.main() != null) {
                assertTrue(layout.main().right() <= layout.content().right());
                assertTrue(layout.main().contains(layout.wheel()));
                assertTrue(layout.main().contains(layout.editor()));
                assertTrue(layout.main().contains(layout.preview()));
            }
        }
    }

    @Test void responsiveLayoutGeometryGuaranteesNoOverlapAcrossAllSupportedResolutions() {
        int[][] dims = {
                {300, 209},
                {427, 254},
                {640, 360},
                {800, 600},
                {1920, 1080}
        };
        for (int[] dim : dims) {
            int w = dim[0];
            int h = dim[1];
            for (boolean open : List.of(true, false)) {
                ResponsiveLayout l = TrackWeightScreen.responsiveLayout(w, h, open);
                assertTrue(l.workspace().contains(l.tabs()), "Workspace must contain tabs at " + w + "x" + h);
                assertTrue(l.workspace().contains(l.content()), "Workspace must contain content at " + w + "x" + h);
                assertTrue(l.workspace().contains(l.bottomToolbar()), "Workspace must contain bottomToolbar at " + w + "x" + h);

                assertTrue(l.tabs().bottom() <= l.content().y(), "Tabs must be above content at " + w + "x" + h);
                assertTrue(l.content().bottom() <= l.bottomToolbar().y(), "Content must be above bottomToolbar at " + w + "x" + h);

                if (l.browser() != null) {
                    assertTrue(l.content().contains(l.browser()), "Content must contain browser at " + w + "x" + h);
                }
                if (l.main() != null) {
                    assertTrue(l.content().contains(l.main()), "Content must contain main at " + w + "x" + h);
                    if (l.browser() != null) {
                        assertTrue(l.browser().right() < l.main().x(), "Browser right must be strictly < main x at " + w + "x" + h);
                    }
                    assertNotNull(l.wheel());
                    assertNotNull(l.editor());
                    assertNotNull(l.preview());
                    assertTrue(l.main().contains(l.wheel()), "Main must contain wheel at " + w + "x" + h);
                    assertTrue(l.main().contains(l.editor()), "Main must contain editor at " + w + "x" + h);
                    assertTrue(l.main().contains(l.preview()), "Main must contain preview at " + w + "x" + h);

                    assertTrue(l.wheel().bottom() <= l.editor().y(), "Wheel bottom must be <= editor y at " + w + "x" + h);
                    assertTrue(l.editor().bottom() <= l.preview().y(), "Editor bottom must be <= preview y at " + w + "x" + h);
                }
            }
        }
    }

    @Test void listNavigationUpdatesSelectedTrackWithoutRecursion() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));
        AtomicInteger selectionCallbacks = new AtomicInteger(0);

        TrackWeightScreen.TrackList list =
                new TrackWeightScreen.TrackList(null, 200, 200, 0, 20, model, selectionCallbacks::incrementAndGet);
        list.populate(pool.tracks());

        Track firstTrack = pool.tracks().getFirst();
        assertEquals(firstTrack, model.selectedTrack());

        Track secondTrack = pool.tracks().get(1);
        list.selectTrackEntry(secondTrack.resourceId());

        assertEquals(secondTrack, model.selectedTrack(), "selectTrackEntry must update model selected track");
        assertEquals(1, selectionCallbacks.get(), "Updating track selection must invoke callback once without recursion");

        Track thirdTrack = pool.tracks().get(2);
        TrackWeightScreen.TrackEntry thirdEntry = list.children().get(2);
        // Simulate native keyboard/controller arrow navigation directly calling setSelected
        list.setSelected(thirdEntry);
        assertEquals(thirdTrack, model.selectedTrack(), "Native keyboard navigation via setSelected must update model selected track");
        assertEquals(2, selectionCallbacks.get(), "Keyboard navigation must trigger exactly one additional callback");

        // Selecting already selected track should not trigger recursive callbacks
        list.selectTrackEntry(thirdTrack.resourceId());
        assertEquals(2, selectionCallbacks.get(), "Selecting already selected track must be a no-op");
    }

    @Test void radialWheelAppearsInWideAndCollapsedNarrowLayouts() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen wideScreen = new TrackWeightScreen(null, saved, pool);
        wideScreen.initForDimensions(800, 400);
        assertNotNull(wideScreen.radialWheel(), "Wheel must appear in wide layout");
        assertEquals(wideScreen.radialWheel().getWidth(), wideScreen.radialWheel().getHeight());

        TrackWeightScreen narrowScreen = new TrackWeightScreen(null, saved, pool);
        narrowScreen.initForDimensions(400, 300);
        assertNotNull(narrowScreen.radialWheel(), "Wheel must appear in collapsed narrow layout");

        narrowScreen.toggleBrowser();
        assertNull(narrowScreen.radialWheel(), "Wheel must not appear in narrow open browser overlay");

        narrowScreen.toggleBrowser();
        assertNotNull(narrowScreen.radialWheel(), "Wheel must be restored when closing browser in narrow layout");
    }

    @Test void wheelModelSynchronizesWithCatalogChancesAndPreservesOrder() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool);
        screen.initForDimensions(800, 400);

        RadialWeightWidget wheel = screen.radialWheel();
        assertNotNull(wheel);
        List<RadialWeightWidget.Slice> slices = wheel.slices();
        assertEquals(3, slices.size(), "Wheel must receive all tracks in the pool");

        // Preserves track-list order
        assertEquals(pool.tracks().get(0).resourceId(), slices.get(0).resourceId());
        assertEquals(pool.tracks().get(1).resourceId(), slices.get(1).resourceId());
        assertEquals(pool.tracks().get(2).resourceId(), slices.get(2).resourceId());

        // Normalized chances
        assertEquals(1.0 / 3.0, slices.get(0).chance(), 1e-6);
        assertEquals(1.0 / 3.0, slices.get(1).chance(), 1e-6);
        assertEquals(1.0 / 3.0, slices.get(2).chance(), 1e-6);

        // Initial selected track
        assertEquals(pool.tracks().getFirst().resourceId(), wheel.selectedResourceId());

        // Searching tracks in the search box does NOT change the wheel slices (search-independent)
        screen.model().setSearchQuery("relic");
        screen.initForDimensions(800, 400);
        assertEquals(3, screen.radialWheel().slices().size(), "Wheel slices must remain search-independent");
        assertEquals("minecraft:music/game/relic", screen.radialWheel().selectedResourceId(),
                "Search selecting new track must update wheel selected resource ID");

        // Changing track selection via selectTrack updates wheel highlight
        screen.selectTrack("minecraft:music/game/sweden");
        assertEquals("minecraft:music/game/sweden", screen.radialWheel().selectedResourceId());

        // Selecting track updates model and wheel
        screen.selectTrack("minecraft:music/game/unknown");
        assertEquals("Unknown Song", screen.model().selectedTrack().title());
        assertEquals("minecraft:music/game/unknown", screen.radialWheel().selectedResourceId());

        // Draft mutation (applyAll 1x, mute 0x) updates wheel chances
        screen.model().mute();
        screen.selectTrack("minecraft:music/game/unknown");
        for (RadialWeightWidget.Slice s : screen.radialWheel().slices()) {
            assertEquals(0.0, s.chance(), 1e-6, "Muted pool must result in 0 chance for all wheel slices");
        }
    }

    @Test void previewStopsPreviousAndResumesOnlyOwnedPause() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return true; },
                () -> calls.add("resume"));
        state.toggle(track("a"));
        state.toggle(track("b"));
        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a", "play:b", "stop:b", "resume"), calls);
    }

    @Test void previewSecondPressStopsAndResumes() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return true; },
                () -> calls.add("resume"));
        Track trackA = track("a");
        state.toggle(trackA);
        assertTrue(state.isPlaying());
        assertEquals(trackA, state.playingTrack());
        assertEquals(List.of("pause", "play:a"), calls);

        state.toggle(trackA);
        assertFalse(state.isPlaying());
        assertNull(state.playingTrack());
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);

        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);
    }

    @Test void naturalInactiveDetectionResumesOnce() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return true; },
                () -> calls.add("resume"));
        state.toggle(track("a"));
        assertTrue(state.isPlaying());
        assertEquals(List.of("pause", "play:a"), calls);

        state.tick(true);
        assertTrue(state.isPlaying());
        assertEquals(List.of("pause", "play:a"), calls);

        state.tick(false);
        assertFalse(state.isPlaying());
        assertNull(state.playingTrack());
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);

        state.tick(false);
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);

        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);
    }

    @Test void previewWithNoCurrentMusicDoesNotIssueResume() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return false; },
                () -> calls.add("resume"));
        state.toggle(track("a"));
        assertTrue(state.isPlaying());
        assertFalse(state.ownsPause());
        assertEquals(List.of("pause", "play:a"), calls);

        state.toggle(track("a"));
        assertFalse(state.isPlaying());
        assertEquals(List.of("pause", "play:a", "stop:a"), calls);

        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a"), calls);
    }

    @Test void previewOperationsDoNotMutateConfigOrPlannerSequence() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig config = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 3.0);
        MusicPlanner planner = new MusicPlanner();
        long initialSeq = planner.peekSequence(pool.id());

        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> true,
                () -> {});

        Track sweden = pool.tracks().getFirst();
        state.toggle(sweden);
        state.tick(true);
        state.toggle(pool.tracks().get(1));
        state.close();

        assertEquals(3.0, config.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, config.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(initialSeq, planner.peekSequence(pool.id()));
    }

    @Test void screenTickWhileInactiveDoesNotTearDownAsynchronousPreview() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool);
        screen.initForDimensions(800, 400);

        screen.previewButton().onPress(null);
        assertTrue(screen.previewState().isPlaying(), "Preview should be playing immediately after press");

        // Tick occurs before sound engine channel becomes active (asynchronous start)
        screen.tick();

        // Under root-cause bug, tick(false) immediately tore down the preview, setting isPlaying to false
        assertTrue(screen.previewState().isPlaying(), "Preview must remain active while starting asynchronously");
    }

    @Test void previewButtonLabelAndLifecycleInScreen() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool);
        screen.initForDimensions(800, 400);

        Button previewBtn = screen.previewButton();
        assertNotNull(previewBtn);
        assertTrue(previewBtn.active);
        assertEquals("▶", previewBtn.getMessage().getString());

        // Pressing idle preview starts preview and swaps it for the compact player
        previewBtn.onPress(null);
        screen.tick();
        assertTrue(screen.previewState().isPlaying());
        assertNull(screen.previewButton(), "Idle control must be replaced while active");
        assertNotNull(screen.previewPlayer(), "Compact player must appear while active");

        // Stop via player stop returns to idle
        screen.previewPlayer().stopButton().onPress(null);
        screen.tick();
        assertFalse(screen.previewState().isPlaying());
        assertNotNull(screen.previewButton(), "Idle control must return after stop");
        assertNull(screen.previewPlayer(), "Player must be removed after stop");
        assertEquals("▶", screen.previewButton().getMessage().getString());

        // Start preview again
        screen.previewButton().onPress(null);
        screen.tick();
        assertTrue(screen.previewState().isPlaying());

        // Selecting a different track stops preview and returns idle control
        screen.selectTrack("minecraft:music/game/unknown");
        screen.tick();
        assertFalse(screen.previewState().isPlaying());
        assertNotNull(screen.previewButton());
        assertEquals("▶", screen.previewButton().getMessage().getString());

        // Start preview again
        screen.previewButton().onPress(null);
        screen.tick();
        assertTrue(screen.previewState().isPlaying());

        // Screen removal stops preview
        screen.removed();
        assertFalse(screen.previewState().isPlaying());

        // Start preview again
        screen.initForDimensions(800, 400);
        screen.previewButton().onPress(null);
        screen.tick();
        assertTrue(screen.previewState().isPlaying());

        // Screen onClose (Escape) stops preview
        screen.onClose();
        assertFalse(screen.previewState().isPlaying());
    }

    @Test void previewStopsOnSaveAndCloseAndResourceReload() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool);
        screen.initForDimensions(800, 400);

        Button previewBtn = screen.previewButton();
        previewBtn.onPress(null);
        screen.tick();
        assertTrue(screen.previewState().isPlaying());

        Path tempConfig = Path.of("build/tmp/test-config.json");
        MusicDirector.getInstance().initializeWeighting(tempConfig);
        try {
            screen.saveAndClose();
            assertFalse(screen.previewState().isPlaying(), "saveAndClose must stop preview");

            screen.initForDimensions(800, 400);
            screen.previewButton().onPress(null);
            screen.tick();
            assertTrue(screen.previewState().isPlaying());

            MusicDirector.getInstance().onResourcesReloaded();
            assertFalse(screen.previewState().isPlaying(), "onResourcesReloaded must stop preview");
        } finally {
            try {
                Files.deleteIfExists(tempConfig);
            } catch (Exception ignored) {}
        }
    }

    @Test
    void trackEntryTextColorsAreOpaqueArgb() {
        assertEquals(0xFF, (TrackWeightScreen.TrackEntry.TITLE_COLOR >>> 24) & 0xFF, "Title color must have full alpha");
        assertEquals(0xFF, (TrackWeightScreen.TrackEntry.DETAIL_COLOR >>> 24) & 0xFF, "Detail color must have full alpha");
    }

    @Test
    void nativeMenuTabBarExactApiContract() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("TabNavigationBar"), "Must use TabNavigationBar");
        assertTrue(source.contains("MenuTabBar.MenuTabButton"), "Must use native MenuTabBar.MenuTabButton");
        assertTrue(source.contains("TabManager"), "Must use TabManager");
        assertTrue(source.contains("GridLayoutTab"), "Must use GridLayoutTab");
        assertTrue(source.contains("tabNavigationBar.keyPressed"), "Must delegate keyPressed to tabNavigationBar");
        assertTrue(source.contains("CreateWorldScreen.TAB_HEADER_BACKGROUND"), "Must use CreateWorldScreen.TAB_HEADER_BACKGROUND");
        assertTrue(source.contains("HEADER_SEPARATOR"), "Must draw HEADER_SEPARATOR");
        assertTrue(source.contains("extractMenuBackground"), "Must override extractMenuBackground for split background");
        assertTrue(source.contains("RenderPipelines.GUI_TEXTURED"), "Must use RenderPipelines.GUI_TEXTURED for header background blit");
        assertFalse(source.contains("CycleButton<Pool>"), "Must not use CycleButton<Pool>");
    }

    @Test
    void nativeMenuTabBarCreatedInBothWideAndNarrowLayoutsWithDynamicPools() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen wideScreen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        wideScreen.initForDimensions(800, 400);

        assertNotNull(wideScreen.tabNavigationBar(), "Tab bar must exist in wide layout");
        assertEquals(2, wideScreen.tabNavigationBar().getTabs().size(), "Tab bar must have one tab per pool");
        assertEquals("Survival", wideScreen.tabNavigationBar().getTabs().get(0).getTabTitle().getString());
        assertEquals("Nether", wideScreen.tabNavigationBar().getTabs().get(1).getTabTitle().getString());
        assertEquals(pool1, wideScreen.selectedPool(), "First pool must be selected initially");

        // Verify no CycleButton for Pool exists in wide layout
        boolean hasPoolCycleButtonWide = wideScreen.children().stream()
                .filter(w -> w instanceof net.minecraft.client.gui.components.CycleButton)
                .map(w -> ((net.minecraft.client.gui.components.CycleButton<?>) w).getMessage().getString())
                .anyMatch(msg -> msg.contains("Pool") || msg.contains(pool1.id()));
        assertFalse(hasPoolCycleButtonWide, "Wide layout must not contain a CycleButton for pool selection");

        TrackWeightScreen narrowScreen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        narrowScreen.initForDimensions(400, 300);

        assertNotNull(narrowScreen.tabNavigationBar(), "Tab bar must exist in narrow layout");
        assertEquals(2, narrowScreen.tabNavigationBar().getTabs().size(), "Tab bar must have one tab per pool");
        assertEquals("Survival", narrowScreen.tabNavigationBar().getTabs().get(0).getTabTitle().getString());
        assertEquals("Nether", narrowScreen.tabNavigationBar().getTabs().get(1).getTabTitle().getString());

        // Verify no CycleButton for Pool exists in narrow layout
        boolean hasPoolCycleButtonNarrow = narrowScreen.children().stream()
                .filter(w -> w instanceof net.minecraft.client.gui.components.CycleButton)
                .map(w -> ((net.minecraft.client.gui.components.CycleButton<?>) w).getMessage().getString())
                .anyMatch(msg -> msg.contains("Pool") || msg.contains(pool1.id()));
        assertFalse(hasPoolCycleButtonNarrow, "Narrow layout must not contain a CycleButton for pool selection");
    }

    @Test
    void emptyPoolsYieldsNoTabBar() {
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen wideScreen = new TrackWeightScreen(null, saved, List.of());
        wideScreen.initForDimensions(800, 400);
        assertNull(wideScreen.tabNavigationBar(), "Empty pools must yield no tab bar in wide layout");
        assertNull(wideScreen.selectedPool());

        TrackWeightScreen narrowScreen = new TrackWeightScreen(null, saved, List.of());
        narrowScreen.initForDimensions(400, 300);
        assertNull(narrowScreen.tabNavigationBar(), "Empty pools must yield no tab bar in narrow layout");
        assertNull(narrowScreen.selectedPool());
    }

    @Test
    void tabSelectionRoutesThroughSinglePathSwitchingPoolAndPreservingState() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        screen.initForDimensions(800, 400);

        screen.model().setSearchQuery("swed");
        assertEquals("Sweden", screen.model().selectedTrack().title());

        screen.previewState().toggle(screen.model().selectedTrack());
        assertTrue(screen.previewState().isPlaying(), "Preview should be playing before pool switch");

        // Select tab 1
        screen.tabNavigationBar().selectTab(1, false);

        assertEquals(pool2, screen.selectedPool(), "Selected pool must update to pool 2");
        assertEquals(netherTrack, screen.selectedTrack(), "Selected track must update to pool 2's first track");
        assertFalse(screen.previewState().isPlaying(), "Preview must be stopped on pool switch");
        assertEquals("", screen.model().searchQuery(), "Search query must be cleared on pool switch");
        assertEquals(1, screen.radialWheel().slices().size(), "Radial wheel must be synced to pool 2");
        assertEquals(netherTrack.resourceId(), screen.radialWheel().selectedResourceId());
    }

    @Test
    void resizePreservesSelectedPoolAndDoesNotFireUnintendedReset() {
        Pool pool1 = poolWithC418AndUnknown();
        Track nether1 = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Track nether2 = track("minecraft:music/nether/chrysopoeia", "Chrysopoeia", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(nether1, nether2),
                List.of(nether1.occurrences().getFirst(), nether2.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        screen.initForDimensions(800, 400);

        // Switch to pool 2
        screen.tabNavigationBar().selectTab(1, false);
        assertEquals(pool2, screen.selectedPool());

        // Select nether2 specifically
        screen.selectTrack(nether2.resourceId());
        assertEquals(nether2, screen.selectedTrack());

        // Start preview on nether2
        screen.previewState().toggle(nether2);
        assertTrue(screen.previewState().isPlaying());

        // Simulate resize to narrow layout
        screen.initForDimensions(400, 300);

        assertEquals(pool2, screen.selectedPool(), "Selected pool must be preserved across resize");
        assertNotNull(screen.tabNavigationBar());
        assertEquals("Nether", screen.tabManager().getCurrentTab().getTabTitle().getString(),
                "Tab manager must have pool 2's tab selected after resize");
        assertEquals(nether2, screen.selectedTrack(), "Selected track within pool must not reset to first track on resize");
        assertTrue(screen.previewState().isPlaying(), "Active preview must not be stopped on resize");
    }

    @Test
    void cleanCategoryNamesInTabTitlesAndButtons() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        Track customTrack = track("custom_mod:sound/ambient", "Cave", "Composer");
        Pool pool3 = new Pool("custom_mod:ambient.cave", List.of(customTrack),
                List.of(customTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2, pool3));
        screen.initForDimensions(800, 400);

        TrackWeightScreen.ScrollablePoolTabBar tabBar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertNotNull(tabBar);
        assertEquals(3, tabBar.getTabs().size());

        assertEquals("Survival", tabBar.getTabs().get(0).getTabTitle().getString());
        assertEquals("Nether", tabBar.getTabs().get(1).getTabTitle().getString());
        assertEquals("custom_mod: Ambient Cave", tabBar.getTabs().get(2).getTabTitle().getString());

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = tabBar.children();
        assertEquals(3, children.size(), "Tab bar should contain only tab buttons, no arrow buttons");
        assertTrue(children.stream().noneMatch(c -> c instanceof Button b && (b.getMessage().getString().equals("<") || b.getMessage().getString().equals(">"))));

        net.minecraft.client.gui.components.AbstractWidget btn0 = (net.minecraft.client.gui.components.AbstractWidget) children.get(0);
        net.minecraft.client.gui.components.AbstractWidget btn1 = (net.minecraft.client.gui.components.AbstractWidget) children.get(1);
        net.minecraft.client.gui.components.AbstractWidget btn2 = (net.minecraft.client.gui.components.AbstractWidget) children.get(2);

        assertEquals("Survival", btn0.getMessage().getString());
        assertEquals("Nether", btn1.getMessage().getString());
        assertEquals("custom_mod: Ambient Cave", btn2.getMessage().getString());
    }

    @Test
    void calculateTabWidthDerivedFromFontMetricsAndPaddingWithoutTruncation() {
        // Fallback font metrics (null font): text length * 6 + 16, min 40
        assertEquals(Math.max(40, "Survival".length() * 6 + 16),
                TrackWeightScreen.calculateTabWidth("minecraft:music.game"));
        assertEquals(Math.max(40, "Credits".length() * 6 + 16),
                TrackWeightScreen.calculateTabWidth("credits"));
        assertEquals(40, TrackWeightScreen.calculateTabWidth(""));
        assertEquals(40, TrackWeightScreen.calculateTabWidth(null));

        // Derived width must accommodate clean display name without truncation
        int widthGame = TrackWeightScreen.calculateTabWidth("minecraft:music.game");
        assertTrue(widthGame >= "Survival".length() * 6 + 16);
    }

    @Test
    void contentWidthAndOffsetClamping() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertNotNull(bar);

        int expectedContentW = 0;
        for (Pool p : pools) {
            expectedContentW += TrackWeightScreen.calculateTabWidth(p.id());
        }
        assertEquals(expectedContentW, bar.contentWidth(), "Content width must match sum of tab button widths");

        Bounds workspace = TrackWeightScreen.workspaceBounds(600, 400);
        assertEquals(workspace.width(), bar.viewportWidth());
        int expectedMaxScroll = Math.max(0, expectedContentW - workspace.width());
        assertEquals(expectedMaxScroll, bar.maxScroll());

        // Clamping below 0
        bar.setScrollOffset(-100);
        assertEquals(0, bar.scrollOffset());

        // Clamping above max
        bar.setScrollOffset(expectedMaxScroll + 500);
        assertEquals(expectedMaxScroll, bar.scrollOffset());

        // Setting within range
        bar.setScrollOffset(100);
        assertEquals(100, bar.scrollOffset());
    }

    @Test
    void wheelDirectionScrollsHorizontally() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        bar.setScrollOffset(100);

        int tabY = bar.getY() + 12;
        int tabX = bar.getX() + 50;

        // Vertical wheel down (scrollY < 0) scrolls content right (increases offset)
        int before = bar.scrollOffset();
        boolean handled = screen.mouseScrolled(tabX, tabY, 0.0, -1.0);
        assertTrue(handled, "Mouse scroll over tab bar must be handled");
        assertTrue(bar.scrollOffset() > before, "Wheel down must increase scroll offset (scroll content right)");

        // Vertical wheel up (scrollY > 0) scrolls content left (decreases offset)
        before = bar.scrollOffset();
        handled = screen.mouseScrolled(tabX, tabY, 0.0, 1.0);
        assertTrue(handled);
        assertTrue(bar.scrollOffset() < before, "Wheel up must decrease scroll offset (scroll content left)");

        // Horizontal wheel right (scrollX < 0) increases offset
        before = bar.scrollOffset();
        handled = screen.mouseScrolled(tabX, tabY, -1.0, 0.0);
        assertTrue(handled);
        assertTrue(bar.scrollOffset() > before, "Horizontal scroll right must increase scroll offset");

        // Horizontal wheel left (scrollX > 0) decreases offset
        before = bar.scrollOffset();
        handled = screen.mouseScrolled(tabX, tabY, 1.0, 0.0);
        assertTrue(handled);
        assertTrue(bar.scrollOffset() < before, "Horizontal scroll left must decrease scroll offset");

        // Wheel outside bar (e.g. y = 10) must not scroll bar
        before = bar.scrollOffset();
        bar.mouseScrolled(tabX, 10, 0.0, 1.0);
        assertEquals(before, bar.scrollOffset(), "Mouse scroll outside bar bounds must not alter offset");
    }

    @Test
    void calculateRevealOffsetPureBoundsBehavior() {
        int viewportW = 200;
        int maxScroll = 500;

        // 1. Tab already fully in view [100, 160] with offset=50, visible=[50, 250]
        assertEquals(50, TrackWeightScreen.calculateRevealOffset(50, 100, 60, viewportW, maxScroll));

        // 2. Tab to right of viewport: tab [300, 360] with offset=50 (visible=[50, 250])
        // Target offset should align right edge: 360 - 200 = 160
        assertEquals(160, TrackWeightScreen.calculateRevealOffset(50, 300, 60, viewportW, maxScroll));

        // 3. Tab to left of viewport: tab [20, 80] with offset=100 (visible=[100, 300])
        // Target offset should align left edge: 20
        assertEquals(20, TrackWeightScreen.calculateRevealOffset(100, 20, 60, viewportW, maxScroll));

        // 4. Tab wider than viewport: tab [100, 350] (width 250 > 200) with offset=0
        // Target offset should align left edge: 100
        assertEquals(100, TrackWeightScreen.calculateRevealOffset(0, 100, 250, viewportW, maxScroll));

        // 5. Clamping to [0, maxScroll]
        assertEquals(0, TrackWeightScreen.calculateRevealOffset(100, 0, 50, viewportW, maxScroll));
        assertEquals(maxScroll, TrackWeightScreen.calculateRevealOffset(0, 800, 50, viewportW, maxScroll));

        // 6. Max scroll <= 0
        assertEquals(0, TrackWeightScreen.calculateRevealOffset(50, 20, 50, viewportW, 0));
    }

    @Test
    void autoRevealSelectedOnTabSelectionAndKeyboard() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertEquals(0, bar.scrollOffset());

        // Select tab 15 (way off-viewport initially)
        bar.selectTab(15, false);
        assertTrue(bar.scrollOffset() > 0, "Selecting tab 15 must scroll bar to reveal it");

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = bar.children();
        net.minecraft.client.gui.components.AbstractWidget btn15 =
                (net.minecraft.client.gui.components.AbstractWidget) children.get(15);
        assertTrue(btn15.getX() >= bar.viewportX(), "Revealed tab 15 X must be >= viewportX");
        assertTrue(btn15.getRight() <= bar.viewportX() + bar.viewportWidth(), "Revealed tab 15 right must be <= viewport right");

        // Select tab 0: must scroll back to start
        bar.selectTab(0, false);
        assertEquals(0, bar.scrollOffset(), "Selecting tab 0 must scroll back to 0");
        net.minecraft.client.gui.components.AbstractWidget btn0 =
                (net.minecraft.client.gui.components.AbstractWidget) children.get(0);
        assertEquals(bar.viewportX(), btn0.getX(), "Tab 0 must be aligned to viewportX");
    }

    @Test
    void resizingClampsScrollOffsetAndKeepsSelectedVisible() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(1728, 1080);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        // Select pool at tab 20
        bar.selectTab(20, false);
        Pool selected = screen.selectedPool();
        assertEquals(screen.orderedPools().get(20), selected);

        // Resize to 800 width
        screen.initForDimensions(800, 600);
        TrackWeightScreen.ScrollablePoolTabBar bar800 = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertEquals(selected, screen.selectedPool(), "Selected pool preserved on resize");
        assertTrue(bar800.scrollOffset() <= bar800.maxScroll(), "Offset clamped to new maxScroll");

        net.minecraft.client.gui.components.AbstractWidget btn20 =
                (net.minecraft.client.gui.components.AbstractWidget) bar800.children().get(20);
        assertTrue(btn20.getX() >= bar800.viewportX(), "Selected tab must remain visible in viewport after resize");
        assertTrue(btn20.getRight() <= bar800.viewportX() + bar800.viewportWidth());

        // Resize to 400 narrow
        screen.initForDimensions(400, 300);
        TrackWeightScreen.ScrollablePoolTabBar bar400 = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertEquals(selected, screen.selectedPool(), "Selected pool preserved on narrow resize");
        assertTrue(bar400.scrollOffset() <= bar400.maxScroll());
    }

    @Test
    void thirtyPoolsNoOverlapAndContentExceedsViewport() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(800, 600);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertNotNull(bar);
        assertTrue(bar.contentWidth() > bar.viewportWidth(), "Content width must exceed 800px viewport");

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = bar.children();
        assertEquals(30, children.size(), "30 tabs = 30 children without arrow buttons");

        // Verify adjacency: button[i].right == button[i+1].x for all tabs
        for (int i = 0; i < 29; i++) {
            net.minecraft.client.gui.components.AbstractWidget curr = (net.minecraft.client.gui.components.AbstractWidget) children.get(i);
            net.minecraft.client.gui.components.AbstractWidget next = (net.minecraft.client.gui.components.AbstractWidget) children.get(i + 1);
            assertEquals(curr.getRight(), next.getX(), "Adjacent tab buttons must touch with zero overlap and zero gap at tab " + i);
            assertEquals(24, curr.getHeight());
            assertEquals(bar.getY(), curr.getY());
        }
    }

    @Test
    void offViewportButtonsCannotReceiveMouseClicks() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        bar.setScrollOffset(0);

        // Tab button 0 is in viewport (x = viewportX..): mouse click inside visible tab
        Optional<net.minecraft.client.gui.components.events.GuiEventListener> child =
                bar.getChildAt(bar.viewportX() + 10, bar.getY() + 10);
        assertTrue(child.isPresent());
        assertEquals(bar.children().get(0), child.get(), "Mouse over visible tab must return that tab button");

        // Mouse click to the left of the workspace tab bar (e.g. x = bar.viewportX() - 10) must return empty
        child = bar.getChildAt(bar.viewportX() - 10, bar.getY() + 10);
        assertTrue(child.isEmpty(), "Clicks left of viewport must not hit tab buttons");

        // Now scroll to 200: button 0 is off to the left (x < bar.viewportX())
        bar.setScrollOffset(200);
        child = bar.getChildAt(bar.viewportX() - 10, bar.getY() + 10);
        assertTrue(child.isEmpty(), "Clicks left of viewport must not hit scrolled off tab buttons");

        // Mouse click outside bar vertically (e.g. y = bar.getY() - 10 or y = bar.getY() + 30) must return empty
        child = bar.getChildAt(bar.viewportX() + 10, bar.getY() - 10);
        assertTrue(child.isEmpty());
        child = bar.getChildAt(bar.viewportX() + 10, bar.getY() + 30);
        assertTrue(child.isEmpty());
    }

    @Test
    void everyTabButtonReceivesContextualTooltipAndNarration() throws Exception {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        screen.initForDimensions(800, 400);

        java.lang.reflect.Field tooltipField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
        tooltipField.setAccessible(true);

        List<? extends net.minecraft.client.gui.components.TabButton> buttons =
                screen.tabNavigationBar().tabButtons();
        assertEquals(2, buttons.size());

        for (int i = 0; i < buttons.size(); i++) {
            net.minecraft.client.gui.components.AbstractWidget btn = (net.minecraft.client.gui.components.AbstractWidget) buttons.get(i);
            net.minecraft.client.gui.components.WidgetTooltipHolder holder =
                    (net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(btn);
            assertNotNull(holder, "WidgetTooltipHolder must exist on tab button " + i);
            net.minecraft.client.gui.components.Tooltip tooltip = holder.get();
            assertNotNull(tooltip, "Tooltip must be set on tab button " + i);

            java.lang.reflect.Field messageField = net.minecraft.client.gui.components.Tooltip.class.getDeclaredField("message");
            messageField.setAccessible(true);
            net.minecraft.network.chat.Component msg = (net.minecraft.network.chat.Component) messageField.get(tooltip);
            String expectedTooltip = i == 0 ? "Plays in Survival mode" : "Plays in the Nether";
            assertEquals(expectedTooltip, msg.getString(),
                    "Tooltip message must match contextual copy for tab " + i);

            assertEquals(expectedTooltip,
                    screen.tabNavigationBar().getTabs().get(i).getTabExtraNarration().getString(),
                    "Tab extra narration must supply contextual copy for tab " + i);
        }
    }

    @Test
    void poolDisplayNameMapsKnownVanillaTopLevel() {
        assertEquals("Creative", TrackWeightScreen.poolDisplayName("minecraft:music.creative"));
        assertEquals("Credits", TrackWeightScreen.poolDisplayName("minecraft:music.credits"));
        assertEquals("Ender Dragon", TrackWeightScreen.poolDisplayName("minecraft:music.dragon"));
        assertEquals("The End", TrackWeightScreen.poolDisplayName("minecraft:music.end"));
        assertEquals("Survival", TrackWeightScreen.poolDisplayName("minecraft:music.game"));
        assertEquals("Main Menu", TrackWeightScreen.poolDisplayName("minecraft:music.menu"));
        assertEquals("Underwater", TrackWeightScreen.poolDisplayName("minecraft:music.underwater"));
        assertEquals("Underwater", TrackWeightScreen.poolDisplayName("minecraft:music.under_water"));
        assertEquals("Nether", TrackWeightScreen.poolDisplayName("minecraft:music.nether"));
        assertEquals("Overworld", TrackWeightScreen.poolDisplayName("minecraft:music.overworld"));

        // Unprefixed / short forms
        assertEquals("Survival", TrackWeightScreen.poolDisplayName("music.game"));
        assertEquals("Creative", TrackWeightScreen.poolDisplayName("creative"));
    }

    @Test
    void poolDisplayNameMapsOverworldAndNetherPlaces() {
        assertEquals("Cherry Grove", TrackWeightScreen.poolDisplayName("minecraft:music.overworld.cherry_grove"));
        assertEquals("Deep Dark", TrackWeightScreen.poolDisplayName("minecraft:music.overworld.deep_dark"));
        assertEquals("Dripstone Caves", TrackWeightScreen.poolDisplayName("minecraft:music.overworld.dripstone_caves"));
        assertEquals("Old Growth Taiga", TrackWeightScreen.poolDisplayName("minecraft:music.overworld.old_growth_taiga"));
        assertEquals("Crimson Forest", TrackWeightScreen.poolDisplayName("minecraft:music.nether.crimson_forest"));
        assertEquals("Basalt Deltas", TrackWeightScreen.poolDisplayName("minecraft:music.nether.basalt_deltas"));
        assertEquals("Nether Wastes", TrackWeightScreen.poolDisplayName("minecraft:music.nether.nether_wastes"));
    }

    @Test
    void poolDisplayNameMapsCustomAndUnknownPools() {
        assertEquals("Pool 0", TrackWeightScreen.poolDisplayName("minecraft:music.pool_0"));
        assertEquals("Pool 29", TrackWeightScreen.poolDisplayName("minecraft:music.pool_29"));
        assertEquals("custom_mod: Ambient Cave", TrackWeightScreen.poolDisplayName("custom_mod:ambient.cave"));
        assertEquals("other: Pool", TrackWeightScreen.poolDisplayName("other:pool"));
        assertEquals("mod: Battle", TrackWeightScreen.poolDisplayName("mod:music.battle"));
    }

    @Test
    void poolTooltipMapsKnownContextsAndCustomPools() {
        assertEquals("Plays in Creative mode", TrackWeightScreen.poolTooltip("minecraft:music.creative"));
        assertEquals("Plays during the end credits", TrackWeightScreen.poolTooltip("minecraft:music.credits"));
        assertEquals("Plays during the Ender Dragon fight", TrackWeightScreen.poolTooltip("minecraft:music.dragon"));
        assertEquals("Plays in The End", TrackWeightScreen.poolTooltip("minecraft:music.end"));
        assertEquals("Plays in Survival mode", TrackWeightScreen.poolTooltip("minecraft:music.game"));
        assertEquals("Plays on the main menu", TrackWeightScreen.poolTooltip("minecraft:music.menu"));
        assertEquals("Plays while underwater", TrackWeightScreen.poolTooltip("minecraft:music.underwater"));
        assertEquals("Plays while underwater", TrackWeightScreen.poolTooltip("minecraft:music.under_water"));
        assertEquals("Plays in the Nether", TrackWeightScreen.poolTooltip("minecraft:music.nether"));
        assertEquals("Plays in the Overworld", TrackWeightScreen.poolTooltip("minecraft:music.overworld"));

        assertEquals("Plays in the Cherry Grove Overworld biome",
                TrackWeightScreen.poolTooltip("minecraft:music.overworld.cherry_grove"));
        assertEquals("Plays in the Deep Dark Overworld biome",
                TrackWeightScreen.poolTooltip("minecraft:music.overworld.deep_dark"));
        assertEquals("Plays in the Crimson Forest Nether biome",
                TrackWeightScreen.poolTooltip("minecraft:music.nether.crimson_forest"));

        assertEquals("Custom music pool: minecraft:music.pool_0",
                TrackWeightScreen.poolTooltip("minecraft:music.pool_0"));
        assertEquals("Custom music pool: custom_mod:ambient.cave",
                TrackWeightScreen.poolTooltip("custom_mod:ambient.cave"));
        assertEquals("Custom music pool: other:pool",
                TrackWeightScreen.poolTooltip("other:pool"));
    }

    @Test
    void poolCategoryOrderMapsAllElevenTiers() {
        assertEquals(0, TrackWeightScreen.poolCategoryOrder("minecraft:music.menu"));
        assertEquals(0, TrackWeightScreen.poolCategoryOrder("music.menu"));
        assertEquals(0, TrackWeightScreen.poolCategoryOrder("menu"));

        assertEquals(1, TrackWeightScreen.poolCategoryOrder("minecraft:music.game"));
        assertEquals(1, TrackWeightScreen.poolCategoryOrder("music.game"));
        assertEquals(1, TrackWeightScreen.poolCategoryOrder("game"));

        assertEquals(2, TrackWeightScreen.poolCategoryOrder("minecraft:music.creative"));
        assertEquals(2, TrackWeightScreen.poolCategoryOrder("music.creative"));
        assertEquals(2, TrackWeightScreen.poolCategoryOrder("creative"));

        assertEquals(3, TrackWeightScreen.poolCategoryOrder("minecraft:music.overworld.cherry_grove"));
        assertEquals(3, TrackWeightScreen.poolCategoryOrder("minecraft:music.overworld.deep_dark"));
        assertEquals(3, TrackWeightScreen.poolCategoryOrder("minecraft:music.overworld"));

        assertEquals(4, TrackWeightScreen.poolCategoryOrder("minecraft:music.under_water"));
        assertEquals(4, TrackWeightScreen.poolCategoryOrder("minecraft:music.underwater"));
        assertEquals(4, TrackWeightScreen.poolCategoryOrder("music.under_water"));
        assertEquals(4, TrackWeightScreen.poolCategoryOrder("underwater"));

        assertEquals(5, TrackWeightScreen.poolCategoryOrder("minecraft:music.nether.crimson_forest"));
        assertEquals(5, TrackWeightScreen.poolCategoryOrder("minecraft:music.nether.basalt_deltas"));
        assertEquals(5, TrackWeightScreen.poolCategoryOrder("minecraft:music.nether"));

        assertEquals(6, TrackWeightScreen.poolCategoryOrder("minecraft:music.dragon"));
        assertEquals(6, TrackWeightScreen.poolCategoryOrder("dragon"));

        assertEquals(7, TrackWeightScreen.poolCategoryOrder("minecraft:music.end"));
        assertEquals(7, TrackWeightScreen.poolCategoryOrder("end"));

        assertEquals(8, TrackWeightScreen.poolCategoryOrder("minecraft:music.credits"));
        assertEquals(8, TrackWeightScreen.poolCategoryOrder("credits"));

        assertEquals(9, TrackWeightScreen.poolCategoryOrder("minecraft:music.pool_0"));
        assertEquals(9, TrackWeightScreen.poolCategoryOrder("minecraft:custom_vanilla"));

        assertEquals(10, TrackWeightScreen.poolCategoryOrder("custom_mod:ambient.cave"));
        assertEquals(10, TrackWeightScreen.poolCategoryOrder("other_pack:music.theme"));
    }

    @Test
    void orderPoolTabsFollowsExactUserApprovedProgression() {
        Pool creative = new Pool("minecraft:music.creative", List.of(), List.of());
        Pool custom1 = new Pool("custom_mod:ambient.cave", List.of(), List.of());
        Pool credits = new Pool("minecraft:music.credits", List.of(), List.of());
        Pool deepDark = new Pool("minecraft:music.overworld.deep_dark", List.of(), List.of());
        Pool cherryGrove = new Pool("minecraft:music.overworld.cherry_grove", List.of(), List.of());
        Pool crimsonForest = new Pool("minecraft:music.nether.crimson_forest", List.of(), List.of());
        Pool menu = new Pool("minecraft:music.menu", List.of(), List.of());
        Pool end = new Pool("minecraft:music.end", List.of(), List.of());
        Pool underwaterLegacy = new Pool("minecraft:music.underwater", List.of(), List.of());
        Pool pool0 = new Pool("minecraft:music.pool_0", List.of(), List.of());
        Pool game = new Pool("minecraft:music.game", List.of(), List.of());
        Pool dragon = new Pool("minecraft:music.dragon", List.of(), List.of());
        Pool basaltDeltas = new Pool("minecraft:music.nether.basalt_deltas", List.of(), List.of());
        Pool underwaterModern = new Pool("minecraft:music.under_water", List.of(), List.of());
        Pool custom2 = new Pool("other_mod:music.theme", List.of(), List.of());

        List<Pool> scrambled = List.of(
                creative, custom1, credits, deepDark, cherryGrove,
                crimsonForest, menu, end, underwaterLegacy, pool0,
                game, dragon, basaltDeltas, underwaterModern, custom2
        );

        List<Pool> ordered = TrackWeightScreen.orderPoolTabs(scrambled);
        List<String> orderedIds = ordered.stream().map(Pool::id).toList();

        List<String> expectedIds = List.of(
                "minecraft:music.menu",
                "minecraft:music.game",
                "minecraft:music.creative",
                "minecraft:music.overworld.cherry_grove",
                "minecraft:music.overworld.deep_dark",
                "minecraft:music.under_water",
                "minecraft:music.underwater",
                "minecraft:music.nether.basalt_deltas",
                "minecraft:music.nether.crimson_forest",
                "minecraft:music.dragon",
                "minecraft:music.end",
                "minecraft:music.credits",
                "minecraft:music.pool_0",
                "custom_mod:ambient.cave",
                "other_mod:music.theme"
        );

        assertEquals(expectedIds, orderedIds);
    }

    @Test
    void overworldCategoriesSortAlphabeticallyByCleanDisplayName() {
        Pool oldGrowthTaiga = new Pool("minecraft:music.overworld.old_growth_taiga", List.of(), List.of());
        Pool cherryGrove = new Pool("minecraft:music.overworld.cherry_grove", List.of(), List.of());
        Pool deepDark = new Pool("minecraft:music.overworld.deep_dark", List.of(), List.of());
        Pool dripstoneCaves = new Pool("minecraft:music.overworld.dripstone_caves", List.of(), List.of());
        Pool overworld = new Pool("minecraft:music.overworld", List.of(), List.of());

        List<Pool> pools = List.of(oldGrowthTaiga, cherryGrove, deepDark, dripstoneCaves, overworld);
        List<String> sortedNames = TrackWeightScreen.orderPoolTabs(pools).stream()
                .map(p -> TrackWeightScreen.poolDisplayName(p.id()))
                .toList();

        assertEquals(List.of("Cherry Grove", "Deep Dark", "Dripstone Caves", "Old Growth Taiga", "Overworld"), sortedNames);
    }

    @Test
    void customAndUnknownPoolsDeterministicTieBreakByFullId() {
        Pool customB = new Pool("mod_b:ambient.forest", List.of(), List.of());
        Pool customA = new Pool("mod_a:ambient.forest", List.of(), List.of());
        Pool customZ = new Pool("mod:z_theme", List.of(), List.of());
        Pool customAT = new Pool("mod:a_theme", List.of(), List.of());

        List<Pool> pools = List.of(customB, customA, customZ, customAT);
        List<String> sortedIds = TrackWeightScreen.orderPoolTabs(pools).stream()
                .map(Pool::id)
                .toList();

        assertEquals(List.of("mod:a_theme", "mod:z_theme", "mod_a:ambient.forest", "mod_b:ambient.forest"), sortedIds);
    }

    @Test
    void screenPoolTabsUIOnlyOrderDoesNotReorderModelPoolsAndPreservesIdentity() throws Exception {
        Pool creative = new Pool("minecraft:music.creative", List.of(track("c", "C", "A")), List.of());
        Pool menu = new Pool("minecraft:music.menu", List.of(track("m", "M", "A")), List.of());
        Pool game = new Pool("minecraft:music.game", List.of(track("g", "G", "A")), List.of());
        Pool custom = new Pool("custom:pool", List.of(track("x", "X", "A")), List.of());

        List<Pool> scrambled = List.of(custom, creative, menu, game);
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, scrambled);
        screen.initForDimensions(1000, 600);

        // 1. Model.pools must NOT be reordered
        assertEquals(scrambled, screen.model().pools(), "Model.pools must retain exact original order");

        // 2. Tab buttons must follow user-approved UI order: Menu, Survival, Creative, Custom
        List<? extends net.minecraft.client.gui.components.TabButton> tabButtons =
                screen.tabNavigationBar().tabButtons();
        assertEquals(4, tabButtons.size());
        assertEquals("Main Menu", tabButtons.get(0).getMessage().getString());
        assertEquals("Survival", tabButtons.get(1).getMessage().getString());
        assertEquals("Creative", tabButtons.get(2).getMessage().getString());
        assertEquals("custom: Pool", tabButtons.get(3).getMessage().getString());

        // 3. Tab tooltips must match each sorted tab
        java.lang.reflect.Field tooltipField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
        tooltipField.setAccessible(true);
        java.lang.reflect.Field messageField = net.minecraft.client.gui.components.Tooltip.class.getDeclaredField("message");
        messageField.setAccessible(true);

        String[] expectedTooltips = {
                "Plays on the main menu",
                "Plays in Survival mode",
                "Plays in Creative mode",
                "Custom music pool: custom:pool"
        };
        for (int i = 0; i < 4; i++) {
            net.minecraft.client.gui.components.AbstractWidget btn =
                    (net.minecraft.client.gui.components.AbstractWidget) tabButtons.get(i);
            net.minecraft.client.gui.components.WidgetTooltipHolder holder =
                    (net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(btn);
            assertNotNull(holder);
            net.minecraft.client.gui.components.Tooltip tooltip = holder.get();
            assertNotNull(tooltip);
            net.minecraft.network.chat.Component msg = (net.minecraft.network.chat.Component) messageField.get(tooltip);
            assertEquals(expectedTooltips[i], msg.getString(), "Tooltip message must match for tab " + i);
        }

        // 4. Tab selection callback identity: selecting tab 2 (Creative) selects creative pool
        screen.tabNavigationBar().selectTab(2, false);
        assertEquals(creative, screen.selectedPool(), "Selecting tab 2 must switch to Creative pool");

        // 5. Selecting tab 0 (Main Menu) selects menu pool
        screen.tabNavigationBar().selectTab(0, false);
        assertEquals(menu, screen.selectedPool(), "Selecting tab 0 must switch to Menu pool");
    }
}
