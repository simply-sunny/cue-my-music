package com.cuemymusic.client.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.TrackPreviewController.Snapshot;
import com.cuemymusic.client.music.TrackPreviewController.State;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;
import com.cuemymusic.client.ui.TrackWeightScreen.Bounds;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;

import static org.junit.jupiter.api.Assertions.*;

class TrackPreviewPlayerTest {

    private static Track track(String resourceId, String title) {
        Occurrence occ = new Occurrence(resourceId, null, 1.0);
        return new Track(resourceId, title, "C418", 1.0, List.of(occ));
    }

    private static Pool poolWithTracks() {
        Track a = track("minecraft:music/game/sweden", "Sweden");
        Track b = track("minecraft:music/game/unknown", "Unknown Song");
        return new Pool("minecraft:music.game", List.of(a, b),
                List.of(a.occurrences().getFirst(), b.occurrences().getFirst()));
    }

    private static String tooltipText(AbstractWidget widget) {
        try {
            java.lang.reflect.Field tooltipField = AbstractWidget.class.getDeclaredField("tooltip");
            tooltipField.setAccessible(true);
            net.minecraft.client.gui.components.WidgetTooltipHolder holder =
                    (net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(widget);
            net.minecraft.client.gui.components.Tooltip tooltip = holder != null ? holder.get() : null;
            if (tooltip == null) {
                return null;
            }
            java.lang.reflect.Field messageField = net.minecraft.client.gui.components.Tooltip.class.getDeclaredField("message");
            messageField.setAccessible(true);
            net.minecraft.network.chat.Component msg =
                    (net.minecraft.network.chat.Component) messageField.get(tooltip);
            return msg != null ? msg.getString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String narrationText(AbstractWidget widget) {
        try {
            java.lang.reflect.Method method = AbstractWidget.class.getDeclaredMethod("createNarrationMessage");
            method.setAccessible(true);
            net.minecraft.network.chat.Component comp =
                    (net.minecraft.network.chat.Component) method.invoke(widget);
            return comp != null ? comp.getString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test void playPauseIconMapping() {
        assertEquals("▶", TrackPreviewPlayer.playPauseIcon(State.PAUSED));
        assertEquals("⏸", TrackPreviewPlayer.playPauseIcon(State.PLAYING));
        assertEquals("▶", TrackPreviewPlayer.playPauseIcon(State.STARTING));
    }

    @Test void timeTextFormatting() {
        assertEquals("▶", TrackPreviewPlayer.playPauseIcon(State.PAUSED));
        assertEquals("⏸", TrackPreviewPlayer.playPauseIcon(State.PLAYING));
        assertEquals("1:05 / 3:20", TrackPreviewPlayer.timeText(65, 200));
        assertEquals("0:00 / --:--", TrackPreviewPlayer.timeText(0, Double.NaN));
    }

    @Test void idleScreenShowsOnlyIconPlayWithNarrationAndNoTooltip() {
        Pool pool = poolWithTracks();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        Button idle = screen.previewButton();
        assertNotNull(idle, "Idle preview control must exist");
        assertEquals("▶", idle.getMessage().getString(), "Idle preview must be icon-only play");
        assertNull(tooltipText(idle), "Idle preview must have no visual tooltip");
        assertEquals("Preview selected track", narrationText(idle), "Idle narration must match");
        assertNull(screen.previewPlayer(), "No active player widgets while idle");
    }

    @Test void activeStatesReplaceIdleWithCompactPlayer() {
        Pool pool = poolWithTracks();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(1200, 800);

        screen.previewButton().onPress(null);
        screen.tick();
        assertNull(screen.previewButton(), "STARTING must replace idle control");
        TrackPreviewPlayer player = screen.previewPlayer();
        assertNotNull(player, "STARTING must show compact player");
        List<AbstractWidget> widgets = player.widgets();
        assertEquals(4, widgets.size(), "Player must own title, scrub, play/pause, stop");
        boolean hasTitle = false;
        boolean hasPlayPause = false;
        boolean hasStop = false;
        for (AbstractWidget w : widgets) {
            String msg = w.getMessage().getString();
            if (msg.equals(pool.tracks().getFirst().title())) {
                hasTitle = true;
            }
            if (msg.equals("▶") || msg.equals("⏸")) {
                hasPlayPause = true;
            }
            if (msg.equals("■")) {
                hasStop = true;
            }
            assertTrue(screen.children().contains(w), "Player widget must be added through screen widget path");
        }
        assertTrue(hasTitle, "Player must show full track title");
        assertTrue(hasPlayPause, "Player must show play/pause control");
        assertTrue(hasStop, "Player must show stop control");
    }

    @Test void narrowBrowserOverlayHidesPlayerAndIdleButAudioContinues() {
        Pool pool = poolWithTracks();
        TrackWeightScreen screen = new TrackWeightScreen(null, TrackWeightConfig.defaults(), pool);
        screen.initForDimensions(640, 360);

        screen.previewButton().onPress(null);
        screen.tick();
        assertNotNull(screen.previewPlayer(), "Preview player must be showing before overlay");

        screen.toggleBrowser();
        assertTrue(screen.browserState().isOpen());
        assertNull(screen.previewButton(), "Overlay must hide idle control");
        assertNull(screen.previewPlayer(), "Overlay must hide active player");
        assertTrue(screen.children().stream()
                .filter(w -> w instanceof AbstractWidget)
                .map(w -> (AbstractWidget) w)
                .noneMatch(w ->
                w.getMessage() != null && (w.getMessage().getString().equals("▶")
                        || w.getMessage().getString().equals("⏸")
                        || w.getMessage().getString().equals("■"))),
                "Hidden player widgets must not remain focusable");
        assertTrue(screen.previewController().state() != State.IDLE, "Audio must continue while overlay is open");

        screen.toggleBrowser();
        assertNotNull(screen.previewPlayer(), "Closing overlay must restore player");
    }

    @Test void unknownDurationDisablesSeek() {
        AtomicReference<Snapshot> snap = new AtomicReference<>(
                new Snapshot(State.PLAYING, "id", "Sweden", 0.0, Double.NaN, false));
        TrackPreviewPlayer player = new TrackPreviewPlayer(null, snap::get, () -> {}, v -> {}, () -> {});
        player.setBounds(new Bounds(0, 0, 300, 60));
        player.tick();
        AbstractWidget scrub = player.widgets().get(1);
        assertFalse(scrub.active, "Scrub must be disabled when duration is unknown");
    }

    @Test void scrubSyncIsNonReentrantAndSeekDelegates() {
        AtomicReference<Snapshot> snap = new AtomicReference<>(
                new Snapshot(State.PLAYING, "id", "Sweden", 65.0, 200.0, true));
        AtomicBoolean seekCalled = new AtomicBoolean(false);
        TrackPreviewPlayer player = new TrackPreviewPlayer(null, snap::get, () -> {},
                v -> seekCalled.set(true), () -> {});
        player.setBounds(new Bounds(0, 0, 300, 60));
        player.tick();
        assertFalse(seekCalled.get(), "tick() sync must not invoke seek callbacks");
        AbstractWidget scrub = player.widgets().get(1);
        assertTrue(scrub.active, "Scrub must be enabled when duration is known");
    }

    @Test void activeNarrationIncludesTrackTitle() {
        AtomicReference<Snapshot> snap = new AtomicReference<>(
                new Snapshot(State.PLAYING, "id", "Sweden", 10.0, 200.0, true));
        TrackPreviewPlayer player = new TrackPreviewPlayer(null, snap::get, () -> {}, v -> {}, () -> {});
        player.setBounds(new Bounds(0, 0, 300, 60));
        player.tick();
        assertTrue(narrationText(player.widgets().get(2)).contains("Sweden"),
                "Play/pause narration must include the track title");
        assertTrue(narrationText(player.widgets().get(3)).contains("Sweden"),
                "Stop narration must include the track title");
    }

    private static Bounds widgetBounds(AbstractWidget widget) {
        try {
            java.lang.reflect.Field xField = AbstractWidget.class.getDeclaredField("x");
            java.lang.reflect.Field yField = AbstractWidget.class.getDeclaredField("y");
            java.lang.reflect.Field wField = AbstractWidget.class.getDeclaredField("width");
            java.lang.reflect.Field hField = AbstractWidget.class.getDeclaredField("height");
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

    @Test void compactBoundsKeepAllWidgetsInsidePreview() {
        AtomicReference<Snapshot> snap = new AtomicReference<>(
                new Snapshot(State.PLAYING, "id", "Sweden", 10.0, 200.0, true));
        TrackPreviewPlayer player = new TrackPreviewPlayer(null, snap::get, () -> {}, v -> {}, () -> {});
        Bounds preview = new Bounds(30, 136, 240, 18);
        player.setBounds(preview);
        player.tick();
        for (AbstractWidget w : player.widgets()) {
            Bounds extent = widgetBounds(w);
            assertTrue(preview.contains(extent),
                    "Widget " + w.getMessage().getString() + " extent " + extent
                            + " must stay inside compact preview " + preview);
        }
        assertTrue(narrationText(player.widgets().get(2)).contains("Sweden"),
                "Compact play/pause narration must retain the track title");
    }

    @Test void playerControlsTogglePauseAndStop() {
        AtomicBoolean toggled = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicReference<Snapshot> snap = new AtomicReference<>(
                new Snapshot(State.PLAYING, "id", "Sweden", 10.0, 200.0, true));
        TrackPreviewPlayer player = new TrackPreviewPlayer(null, snap::get,
                () -> toggled.set(true), v -> {}, () -> stopped.set(true));
        player.setBounds(new Bounds(0, 0, 300, 60));
        player.tick();
        Button playPause = (Button) player.widgets().get(2);
        Button stop = (Button) player.widgets().get(3);
        assertEquals("⏸", playPause.getMessage().getString());
        playPause.onPress(null);
        assertTrue(toggled.get(), "Play/pause must delegate to controller toggle");
        assertEquals("■", stop.getMessage().getString());
        stop.onPress(null);
        assertTrue(stopped.get(), "Stop must delegate to controller stop");

        snap.set(new Snapshot(State.PAUSED, "id", "Sweden", 10.0, 200.0, true));
        player.tick();
        assertEquals("▶", playPause.getMessage().getString(), "Paused must show play icon");
    }
}
