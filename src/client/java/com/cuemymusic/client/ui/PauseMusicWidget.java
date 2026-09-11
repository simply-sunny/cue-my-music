package com.cuemymusic.client.ui;

import java.util.List;
import java.util.Optional;

import com.cuemymusic.client.music.MusicDirector;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Transport panel placed top-right at {@link #MARGIN}: holding a player card with
 * title/artist on the left and draggable scrub bar on the right, a minimize button ('−'),
 * a transport row (Previous, Play/Pause, Next, flexible End song, Queue toggle [≡]),
 * and an expandable read-only preview card showing the next 5 deterministic upcoming songs.
 *
 * <p>Attached via Fabric screen events only to {@link PauseScreen} and root
 * {@link OptionsScreen} whose last screen was {@link TitleScreen} (never TitleScreen
 * directly, never in-world Options, never Options sub-screens).
 *
 * <p>When minimized, all widgets are hidden and deactivated, leaving only a '+' restore
 * button at top-right, while preserving queue open state.
 *
 * <p>Card backgrounds and borders use vanilla {@link GuiGraphicsExtractor} fill and outline
 * primitives without custom textures or dependencies.
 */
public final class PauseMusicWidget {
    static final int MARGIN = 6;
    static final int BUTTON_SIZE = 20;
    static final int GAP = 4;
    static final int LINE_GAP = 2;
    static final int PAD = 4;
    static final int FIXED_WIDTH = 204;
    static final int PLAYER_SCREEN_WIDTH = FIXED_WIDTH * 3 / 2;
    static final int MIN_WIDTH = 160;
    static final int OPTIONS_HALF_WIDTH = 154;
    static final int PAUSE_HALF_WIDTH = 102;
    static final int QUEUE_WIDTH = 20;
    static final int SLIDER_HEIGHT = 20;
    static final String FALLBACK_TEXT = "No music playing";
    static final String PREVIOUS_LABEL = "Previous track";
    static final String NEXT_LABEL = "Next track";
    static final String PREVIOUS_TEXT = "<<";
    static final String NEXT_TEXT = ">>";
    static final String PLAY_TEXT = ">";
    static final String PAUSE_TEXT = "||";
    static final String END_TEXT = "End";
    static final String QUEUE_ICON = "≡";
    static final String MINIMIZE_TEXT = "−";
    static final String RESTORE_TEXT = "+";
    static final String CLOSE_TEXT = "×";
    static final String PLAY_PAUSE_TOOLTIP = "Play or pause the current song";
    static final String NEXT_TOOLTIP = "Next track (plays immediately)";
    static final String END_TOOLTIP = "End song (natural delay before the next song)";
    static final String QUEUE_TOOLTIP = "Upcoming tracks (next 5)";
    static final String SCRUB_TOOLTIP = "Seek (drag or arrow keys)";
    static final String MINIMIZE_TOOLTIP = "Minimize";
    static final String RESTORE_TOOLTIP = "Restore";
    static final String OPEN_PLAYER_TOOLTIP = "Open music player in Mod Menu";
    static final String CLOSE_TOOLTIP = "Back to Options";
    static final int CARD_BG_COLOR = 0xD0101010;
    static final int CARD_BORDER_COLOR = 0xFF505050;

    private static final java.util.Map<Screen, Panel> ATTACHED_PANELS = new java.util.WeakHashMap<>();

    private PauseMusicWidget() {
    }

    /** Pure panel geometry: player card, transport row, and upcoming queue card. */
    record PanelLayout(
            int boxX, int boxY, int boxWidth, int boxHeight,
            int playerCardHeight,
            int titleX, int titleY, int titleWidth,
            int artistX, int artistY, int artistWidth,
            int sliderX, int sliderY, int sliderWidth,
            int minimizeX, int minimizeY, int minimizeWidth, int minimizeHeight,
            int buttonsY, int previousX, int playPauseX, int nextX,
            int endX, int endWidth, int queueX,
            int queueCardX, int queueCardY, int queueCardWidth, int queueCardHeight,
            int queueHeaderY, int queueListY) {
    }

    static PanelLayout panelLayout(int screenWidth, int screenHeight, int titleTextWidth, int artistTextWidth,
            int lineHeight, boolean queueOpen, boolean optionsScreen, boolean centered) {
        int playerCardHeight = PAD * 2 + BUTTON_SIZE * 2 + GAP;
        int queueCardWidth;
        int boxWidth;
        int boxX;
        if (centered && queueOpen) {
            int available = Math.max(0, screenWidth - MARGIN * 2 - GAP);
            if (available >= PLAYER_SCREEN_WIDTH + FIXED_WIDTH) {
                boxWidth = PLAYER_SCREEN_WIDTH;
                queueCardWidth = FIXED_WIDTH;
            } else {
                boxWidth = Math.min(available, Math.max(MIN_WIDTH,
                        available * PLAYER_SCREEN_WIDTH / (PLAYER_SCREEN_WIDTH + FIXED_WIDTH)));
                queueCardWidth = available - boxWidth;
            }
            boxX = (screenWidth - boxWidth - GAP - queueCardWidth) / 2;
        } else {
            boxWidth = centered
                    ? Math.min(PLAYER_SCREEN_WIDTH, screenWidth - MARGIN * 2)
                    : Math.min(FIXED_WIDTH, Math.max(MIN_WIDTH, availablePanelWidth(screenWidth, optionsScreen)));
            boxX = centered ? (screenWidth - boxWidth) / 2 : Math.max(0, screenWidth - MARGIN - boxWidth);
            queueCardWidth = boxWidth;
        }
        int boxY = centered ? Math.max(MARGIN, (screenHeight - playerCardHeight) / 2) : MARGIN;

        int innerX = boxX + PAD;
        int innerY = boxY + PAD;
        int innerWidth = boxWidth - PAD * 2;

        int minimizeWidth = BUTTON_SIZE;
        int minimizeHeight = BUTTON_SIZE;
        int minimizeX = innerX + innerWidth - minimizeWidth;
        int minimizeY = innerY;

        int availableWidth = innerWidth - minimizeWidth - GAP;
        int content = availableWidth - GAP;
        int sliderWidth = Math.min(84, content / 2);
        int titleWidth = content - sliderWidth;

        int titleX = innerX;
        int titleY = innerY;
        int artistX = innerX;
        int artistY = innerY + lineHeight + LINE_GAP;
        int artistWidth = titleWidth;

        int sliderX = titleX + titleWidth + GAP;
        int sliderY = innerY;

        int buttonsY = innerY + BUTTON_SIZE + GAP;
        int previousX = innerX;
        int playPauseX = previousX + BUTTON_SIZE + GAP;
        int nextX = playPauseX + BUTTON_SIZE + GAP;
        int queueX = innerX + innerWidth - QUEUE_WIDTH;
        int endX = nextX + BUTTON_SIZE + GAP;
        int endWidth = Math.max(20, queueX - GAP - endX);

        int queueCardX = centered ? boxX + boxWidth + GAP : boxX;
        int queueCardY = centered ? boxY : boxY + playerCardHeight + GAP;
        int lineStep = lineHeight + LINE_GAP;
        int queueCardHeight = queueOpen ? (PAD * 2 + lineStep * 6) : 0;
        int queueHeaderY = queueCardY + PAD;
        int queueListY = queueHeaderY + lineStep;

        int boxHeight = centered
                ? Math.max(playerCardHeight, queueCardHeight)
                : playerCardHeight + (queueOpen ? GAP + queueCardHeight : 0);

        return new PanelLayout(
                boxX, boxY, boxWidth, boxHeight,
                playerCardHeight,
                titleX, titleY, titleWidth,
                artistX, artistY, artistWidth,
                sliderX, sliderY, sliderWidth,
                minimizeX, minimizeY, minimizeWidth, minimizeHeight,
                buttonsY, previousX, playPauseX, nextX,
                endX, endWidth, queueX,
                queueCardX, queueCardY, queueCardWidth, queueCardHeight,
                queueHeaderY, queueListY);
    }

    static PanelLayout panelLayout(int screenWidth, int screenHeight, int titleTextWidth, int artistTextWidth,
            int lineHeight, boolean queueOpen, boolean optionsScreen) {
        return panelLayout(screenWidth, screenHeight, titleTextWidth, artistTextWidth, lineHeight, queueOpen,
                optionsScreen, false);
    }

    static PanelLayout panelLayout(int screenWidth, int screenHeight, int titleTextWidth, int artistTextWidth,
            int lineHeight, boolean queueOpen) {
        return panelLayout(screenWidth, screenHeight, titleTextWidth, artistTextWidth, lineHeight, queueOpen, false);
    }

    static PanelLayout panelLayout(int screenWidth, int titleTextWidth, int artistTextWidth, int lineHeight) {
        return panelLayout(screenWidth, 400, titleTextWidth, artistTextWidth, lineHeight, false);
    }

    static int availablePanelWidth(int screenWidth, boolean optionsScreen) {
        return screenWidth / 2 - (optionsScreen ? OPTIONS_HALF_WIDTH : PAUSE_HALF_WIDTH) - MARGIN - GAP;
    }

    static boolean requiresMinimizedPanel(int screenWidth, boolean optionsScreen) {
        return availablePanelWidth(screenWidth, optionsScreen) < MIN_WIDTH;
    }

    /** Pure display lines: title/artist, title only, or the fallback. */
    static List<String> displayLines(Optional<MusicDirector.TrackInfo> nowPlaying) {
        if (nowPlaying.isEmpty()) {
            return List.of(FALLBACK_TEXT);
        }
        MusicDirector.TrackInfo info = nowPlaying.orElseThrow();
        if (info.artist() == null) {
            return List.of(info.title());
        }
        return List.of(info.title(), info.artist());
    }

    /** Pure upcoming item formatting: number + title + optional artist. */
    static String formatUpcoming(int index, MusicDirector.TrackInfo track) {
        if (track.artist() != null && !track.artist().isEmpty()) {
            return index + ". " + track.title() + " - " + track.artist();
        }
        return index + ". " + track.title();
    }

    /**
     * Draggable scrub bar. Every value change seeks live so the audio follows
     * the handle in real time; release and keyboard steps commit as well so
     * the final position always lands. While the user is dragging, per-tick
     * clock syncs are ignored so the handle and time label never jump back
     * to the old playback position mid-drag.
     */
    static final class ScrubSlider extends AbstractSliderButton {
        private double durationSeconds = Double.NaN;
        private double positionSeconds;
        private boolean scrubbing;

        ScrubSlider(int x, int y, int width) {
            super(x, y, width, SLIDER_HEIGHT, Component.literal(""), 0.0);
            setTooltip(Tooltip.create(Component.literal(SCRUB_TOOLTIP)));
        }

        /** Pure fraction mapping: unknown or non-positive durations pin to zero. */
        static double fractionFor(double positionSeconds, double durationSeconds) {
            if (Double.isNaN(durationSeconds) || Double.isInfinite(durationSeconds)
                    || durationSeconds <= 0.0) {
                return 0.0;
            }
            return Math.min(1.0, Math.max(0.0, positionSeconds / durationSeconds));
        }

        static double secondsFor(double value, double durationSeconds) {
            return Math.min(1.0, Math.max(0.0, value)) * durationSeconds;
        }

        /** Per-tick sync from the transport clock; never seeks (no applyValue path). */
        void sync(double positionSeconds, double durationSeconds) {
            this.durationSeconds = durationSeconds;
            this.active = canSeekDuration(durationSeconds);
            if (scrubbing) {
                // Mid-drag: keep the handle where the user put it and keep the
                // label following the handle instead of the stale audio clock.
                updateMessage();
                return;
            }
            this.positionSeconds = positionSeconds;
            this.value = fractionFor(positionSeconds, durationSeconds);
            updateMessage();
        }

        private static boolean canSeekDuration(double durationSeconds) {
            return !Double.isNaN(durationSeconds) && !Double.isInfinite(durationSeconds)
                    && durationSeconds > 0.0;
        }

        @Override
        protected void updateMessage() {
            if (canSeekDuration(durationSeconds)) {
                setMessage(Component.literal(MusicDirector.formatTime(secondsFor(value, durationSeconds))
                        + " / " + MusicDirector.formatTime(durationSeconds)));
            } else {
                setMessage(Component
                        .literal(MusicDirector.formatTime(positionSeconds) + " / --:--"));
            }
        }

        @Override
        protected void applyValue() {
            // Seek live on every change so the audio follows the handle in real
            // time. Each call bumps the transport generation, so rapid drag
            // events cleanly supersede prior seeks; the latest one wins.
            commit();
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean bl) {
            super.onClick(event, bl);
            scrubbing = true;
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) {
                scrubbing = false;
            }
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            scrubbing = false;
        }

        private void commit() {
            if (canSeekDuration(durationSeconds)) {
                MusicDirector.getInstance().seekToSeconds(secondsFor(value, durationSeconds));
            }
        }
    }

    static boolean isEligible(Class<?> screenClass, boolean inWorld) {
        if (screenClass == null) {
            return false;
        }
        if (PauseScreen.class.isAssignableFrom(screenClass)
                || MusicPlayerScreen.class.isAssignableFrom(screenClass)) {
            return true;
        }
        if (screenClass == OptionsScreen.class) {
            return !inWorld;
        }
        return false;
    }

    /**
     * Predicate controlling widget attachment: attaches to {@link PauseScreen} and
     * root {@link OptionsScreen} when not in a world.
     * Never TitleScreen directly, never in-world OptionsScreen, never Options sub-screens.
     */
    public static boolean shouldAttach(net.minecraft.client.Minecraft client, Screen screen) {
        if (client == null || screen == null) {
            return false;
        }
        return isEligible(screen.getClass(), client.level != null);
    }

    public static boolean shouldAttach(Screen screen) {
        return shouldAttach(net.minecraft.client.Minecraft.getInstance(), screen);
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (shouldAttach(client, screen)) {
                attach(client, screen);
            }
        });
    }

    /** Per-screen widget bundle closed over by the render and tick callbacks. */
    static final class Panel {
        final net.minecraft.client.Minecraft client;
        final Screen screen;
        final StringWidget title;
        final StringWidget artist;
        final ScrubSlider slider;
        final Button previous;
        final Button playPause;
        final Button next;
        final Button end;
        final Button queueButton;
        final Button minimizeButton;
        final StringWidget queueHeader;
        final StringWidget[] queueItems;
        boolean queueOpen;
        boolean minimized;

        Panel(net.minecraft.client.Minecraft client, Screen screen, StringWidget title, StringWidget artist,
                ScrubSlider slider, Button previous, Button playPause, Button next, Button end,
                Button queueButton, Button minimizeButton, StringWidget queueHeader, StringWidget[] queueItems) {
            this.client = client;
            this.screen = screen;
            this.title = title;
            this.artist = artist;
            this.slider = slider;
            this.previous = previous;
            this.playPause = playPause;
            this.next = next;
            this.end = end;
            this.queueButton = queueButton;
            this.minimizeButton = minimizeButton;
            this.queueHeader = queueHeader;
            this.queueItems = queueItems;
        }

        void addWidgets(List<net.minecraft.client.gui.components.AbstractWidget> widgets) {
            if (!widgets.contains(title)) {
                widgets.add(title);
                widgets.add(artist);
                widgets.add(slider);
                widgets.add(minimizeButton);
                widgets.add(previous);
                widgets.add(playPause);
                widgets.add(next);
                widgets.add(end);
                widgets.add(queueButton);
                widgets.add(queueHeader);
                for (StringWidget item : queueItems) {
                    widgets.add(item);
                }
            }
        }

        PanelLayout computeLayout() {
            var font = client.font;
            MusicDirector director = MusicDirector.getInstance();
            List<String> lines = displayLines(director.nowPlaying());
            int titleWidth = font.width(lines.get(0));
            int artistWidth = lines.size() > 1 ? font.width(lines.get(1)) : 0;
            return panelLayout(screen.width, screen.height, titleWidth, artistWidth, font.lineHeight, queueOpen,
                    screen instanceof OptionsScreen, screen instanceof MusicPlayerScreen);
        }

        void toggleMinimize() {
            this.minimized = !this.minimized;
            if (this.minimized && this.screen != null) {
                this.screen.clearFocus();
            }
            if (client != null && client.font != null) {
                refresh();
            }
        }

        void toggleQueue() {
            this.queueOpen = !this.queueOpen;
            if (client != null && client.font != null) {
                refresh();
            }
        }

        void extractBackground(GuiGraphicsExtractor extractor) {
            if (minimized) {
                return;
            }
            PanelLayout layout = computeLayout();
            extractor.fill(layout.boxX(), layout.boxY(),
                    layout.boxX() + layout.boxWidth(), layout.boxY() + layout.playerCardHeight(),
                    CARD_BG_COLOR);
            extractor.outline(layout.boxX(), layout.boxY(),
                    layout.boxWidth(), layout.playerCardHeight(),
                    CARD_BORDER_COLOR);

            if (queueOpen && layout.queueCardHeight() > 0) {
                extractor.fill(layout.queueCardX(), layout.queueCardY(),
                        layout.queueCardX() + layout.queueCardWidth(),
                        layout.queueCardY() + layout.queueCardHeight(),
                        CARD_BG_COLOR);
                extractor.outline(layout.queueCardX(), layout.queueCardY(),
                        layout.queueCardWidth(), layout.queueCardHeight(),
                        CARD_BORDER_COLOR);
            }
        }

        void refresh() {
            boolean playerScreen = screen instanceof MusicPlayerScreen;
            boolean forcedMinimized = !playerScreen
                    && requiresMinimizedPanel(screen.width, screen instanceof OptionsScreen);
            if (forcedMinimized) {
                minimized = true;
            }
            if (minimized) {
                title.visible = false;
                artist.visible = false;
                slider.visible = false;
                slider.active = false;
                previous.visible = false;
                previous.active = false;
                playPause.visible = false;
                playPause.active = false;
                next.visible = false;
                next.active = false;
                end.visible = false;
                end.active = false;
                queueButton.visible = false;
                queueButton.active = false;
                queueHeader.visible = false;
                for (StringWidget item : queueItems) {
                    item.visible = false;
                }
                minimizeButton.setMessage(Component.literal(RESTORE_TEXT));
                minimizeButton.setTooltip(Tooltip.create(Component.literal(
                        forcedMinimized ? OPEN_PLAYER_TOOLTIP : RESTORE_TOOLTIP)));
                minimizeButton.setX(screen.width - MARGIN - BUTTON_SIZE);
                minimizeButton.setY(MARGIN);
                minimizeButton.setWidth(BUTTON_SIZE);
                minimizeButton.setHeight(BUTTON_SIZE);
                minimizeButton.visible = true;
                minimizeButton.active = true;
                return;
            }

            MusicDirector director = MusicDirector.getInstance();
            List<String> lines = displayLines(director.nowPlaying());
            PanelLayout layout = computeLayout();
            var font = client.font;

            title.setMessage(Component.literal(lines.get(0)));
            title.setX(layout.titleX());
            title.setY(layout.titleY());
            title.setWidth(layout.titleWidth());
            title.setMaxWidth(layout.titleWidth());
            title.visible = true;

            if (lines.size() > 1) {
                artist.setMessage(Component.literal(lines.get(1)));
                artist.visible = true;
            } else {
                artist.visible = false;
            }
            artist.setX(layout.artistX());
            artist.setY(layout.artistY());
            artist.setWidth(layout.artistWidth());
            artist.setMaxWidth(layout.artistWidth());

            slider.setX(layout.sliderX());
            slider.setY(layout.sliderY());
            slider.setWidth(layout.sliderWidth());
            slider.visible = true;

            boolean live = director.canTogglePause();
            slider.sync(live ? director.transportPositionSeconds() : 0.0,
                    live ? director.transportDurationSeconds() : Double.NaN);

            minimizeButton.setMessage(Component.literal(playerScreen ? CLOSE_TEXT : MINIMIZE_TEXT));
            minimizeButton.setTooltip(Tooltip.create(Component.literal(
                    playerScreen ? CLOSE_TOOLTIP : MINIMIZE_TOOLTIP)));
            minimizeButton.setX(layout.minimizeX());
            minimizeButton.setY(layout.minimizeY());
            minimizeButton.setWidth(layout.minimizeWidth());
            minimizeButton.setHeight(layout.minimizeHeight());
            minimizeButton.visible = true;
            minimizeButton.active = true;

            previous.setX(layout.previousX());
            previous.setY(layout.buttonsY());
            previous.visible = true;

            playPause.setX(layout.playPauseX());
            playPause.setY(layout.buttonsY());
            playPause.visible = true;

            next.setX(layout.nextX());
            next.setY(layout.buttonsY());
            next.visible = true;

            end.setX(layout.endX());
            end.setY(layout.buttonsY());
            end.setWidth(layout.endWidth());
            end.visible = true;

            queueButton.setX(layout.queueX());
            queueButton.setY(layout.buttonsY());
            queueButton.visible = true;

            playPause.setMessage(
                    Component.literal(director.transportPaused() ? PLAY_TEXT : PAUSE_TEXT));

            previous.active = director.canGoPrevious();
            playPause.active = director.canTogglePause();
            next.active = director.canGoNext();
            queueButton.active = true;

            if (live) {
                end.setMessage(Component.literal(END_TEXT));
                end.setTooltip(Tooltip.create(Component.literal(END_TOOLTIP)));
                end.active = true;
            } else {
                int delayTicks = director.remainingDelayTicks();
                if (delayTicks > 0) {
                    end.setMessage(Component.literal(MusicDirector.formatTime(director.remainingDelaySeconds())));
                    end.setTooltip(Tooltip.create(Component.literal("Skip delay?")));
                    end.active = true;
                } else {
                    end.setMessage(Component.literal(END_TEXT));
                    end.setTooltip(Tooltip.create(Component.literal("No active music")));
                    end.active = false;
                }
            }

            // Queue display
            if (queueOpen) {
                queueHeader.setX(layout.queueCardX() + PAD);
                queueHeader.setY(layout.queueHeaderY());
                queueHeader.setWidth(layout.queueCardWidth() - PAD * 2);
                queueHeader.setMaxWidth(layout.queueCardWidth() - PAD * 2);
                queueHeader.visible = true;

                List<MusicDirector.TrackInfo> upcoming = director.upcomingTracks(5);
                int lineH = font.lineHeight + LINE_GAP;
                for (int i = 0; i < queueItems.length; i++) {
                    StringWidget item = queueItems[i];
                    if (i < upcoming.size()) {
                        item.setMessage(Component.literal(formatUpcoming(i + 1, upcoming.get(i))));
                        item.setX(layout.queueCardX() + PAD);
                        item.setY(layout.queueListY() + i * lineH);
                        item.setWidth(layout.queueCardWidth() - PAD * 2);
                        item.setMaxWidth(layout.queueCardWidth() - PAD * 2);
                        item.visible = true;
                    } else {
                        item.visible = false;
                    }
                }
            } else {
                queueHeader.visible = false;
                for (StringWidget item : queueItems) {
                    item.visible = false;
                }
            }
        }
    }

    private static void attach(net.minecraft.client.Minecraft client, Screen screen) {
        var widgets = Screens.getWidgets(screen);
        Panel existing = ATTACHED_PANELS.get(screen);
        if (existing != null) {
            existing.addWidgets(widgets);
            existing.refresh();
            return;
        }

        var font = client.font;
        PanelLayout layout = panelLayout(screen.width, screen.height, font.width(FALLBACK_TEXT), 0,
                font.lineHeight, false, screen instanceof OptionsScreen, screen instanceof MusicPlayerScreen);

        StringWidget title = new StringWidget(layout.titleX(), layout.titleY(), layout.titleWidth(),
                font.lineHeight, Component.literal(FALLBACK_TEXT), font);
        title.setMaxWidth(layout.titleWidth());

        StringWidget artist = new StringWidget(layout.artistX(), layout.artistY(), layout.artistWidth(),
                font.lineHeight, Component.empty(), font);
        artist.setMaxWidth(layout.artistWidth());
        artist.visible = false;

        ScrubSlider slider = new ScrubSlider(layout.sliderX(), layout.sliderY(), layout.sliderWidth());

        Panel[] panelHolder = new Panel[1];

        Button minimizeButton = Button.builder(Component.literal(MINIMIZE_TEXT),
                button -> {
                    Panel panel = panelHolder[0];
                    if (panel == null) {
                        return;
                    }
                    if (panel.screen instanceof MusicPlayerScreen playerScreen) {
                        playerScreen.openOptions();
                    } else if (requiresMinimizedPanel(panel.screen.width,
                            panel.screen instanceof OptionsScreen)) {
                        MusicPlayerScreen.openFromVanillaScreen(panel.client, panel.screen);
                    } else {
                        panel.toggleMinimize();
                    }
                })
                .bounds(layout.minimizeX(), layout.minimizeY(), layout.minimizeWidth(), layout.minimizeHeight())
                .tooltip(Tooltip.create(Component.literal(MINIMIZE_TOOLTIP)))
                .createNarration(narration -> {
                    Panel panel = panelHolder[0];
                    if (panel != null && panel.screen instanceof MusicPlayerScreen) {
                        return Component.literal(CLOSE_TOOLTIP);
                    }
                    if (panel != null && requiresMinimizedPanel(panel.screen.width,
                            panel.screen instanceof OptionsScreen)) {
                        return Component.literal(OPEN_PLAYER_TOOLTIP);
                    }
                    return Component.literal(panel != null && panel.minimized ? RESTORE_TOOLTIP : MINIMIZE_TOOLTIP);
                })
                .build();

        Button previous = Button.builder(Component.literal(PREVIOUS_TEXT),
                button -> MusicDirector.getInstance().requestPrevious())
                .bounds(layout.previousX(), layout.buttonsY(), BUTTON_SIZE, BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.literal(PREVIOUS_LABEL)))
                .createNarration(narration -> Component.literal(PREVIOUS_LABEL))
                .build();

        Button playPause = Button.builder(Component.literal(PAUSE_TEXT),
                button -> MusicDirector.getInstance().togglePause())
                .bounds(layout.playPauseX(), layout.buttonsY(), BUTTON_SIZE, BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.literal(PLAY_PAUSE_TOOLTIP)))
                .createNarration(narration -> Component.literal(
                        MusicDirector.getInstance().transportPaused() ? "Play track" : "Pause track"))
                .build();

        Button next = Button.builder(Component.literal(NEXT_TEXT),
                button -> MusicDirector.getInstance().requestNext())
                .bounds(layout.nextX(), layout.buttonsY(), BUTTON_SIZE, BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.literal(NEXT_TOOLTIP)))
                .createNarration(narration -> Component.literal(NEXT_LABEL))
                .build();

        Button end = Button.builder(Component.literal(END_TEXT),
                button -> {
                    MusicDirector dir = MusicDirector.getInstance();
                    if (dir.canTogglePause()) {
                        dir.requestEnd();
                    } else {
                        dir.skipDelay();
                    }
                })
                .bounds(layout.endX(), layout.buttonsY(), layout.endWidth(), BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.literal(END_TOOLTIP)))
                .createNarration(narration -> Component.literal("End song"))
                .build();

        Button queueButton = Button.builder(Component.literal(QUEUE_ICON),
                button -> {
                    if (panelHolder[0] != null) {
                        panelHolder[0].toggleQueue();
                    }
                })
                .bounds(layout.queueX(), layout.buttonsY(), QUEUE_WIDTH, BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.literal(QUEUE_TOOLTIP)))
                .createNarration(narration -> Component.literal(QUEUE_TOOLTIP))
                .build();

        StringWidget queueHeader = new StringWidget(layout.queueCardX() + PAD, layout.queueHeaderY(),
                layout.queueCardWidth() - PAD * 2, font.lineHeight, Component.literal("Upcoming (next 5):"), font);
        queueHeader.setMaxWidth(layout.queueCardWidth() - PAD * 2);
        queueHeader.visible = false;

        StringWidget[] queueItems = new StringWidget[5];
        for (int i = 0; i < 5; i++) {
            queueItems[i] = new StringWidget(layout.queueCardX() + PAD,
                    layout.queueListY() + i * (font.lineHeight + LINE_GAP),
                    layout.queueCardWidth() - PAD * 2, font.lineHeight, Component.empty(), font);
            queueItems[i].setMaxWidth(layout.queueCardWidth() - PAD * 2);
            queueItems[i].visible = false;
        }

        widgets.add(title);
        widgets.add(artist);
        widgets.add(slider);
        widgets.add(minimizeButton);
        widgets.add(previous);
        widgets.add(playPause);
        widgets.add(next);
        widgets.add(end);
        widgets.add(queueButton);
        widgets.add(queueHeader);
        for (StringWidget item : queueItems) {
            widgets.add(item);
        }

        Panel panel = new Panel(client, screen, title, artist, slider, previous, playPause, next, end,
                queueButton, minimizeButton, queueHeader, queueItems);
        panelHolder[0] = panel;
        ATTACHED_PANELS.put(screen, panel);

        ScreenEvents.beforeExtract(screen).register((scr, extractor, mouseX, mouseY, tickProgress) -> {
            panel.extractBackground(extractor);
        });

        ScreenEvents.afterTick(screen).register(ticked -> panel.refresh());
        panel.refresh();
    }
}
