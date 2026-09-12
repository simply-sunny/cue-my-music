package com.cuemymusic.client.ui;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.TrackPreviewController.Snapshot;
import com.cuemymusic.client.music.TrackPreviewController.State;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Focused settings preview player widget bundle.
 * Idle state is handled by the screen's icon-only play control; this bundle
 * owns the active STARTING/PLAYING/PAUSED player beneath the wheel/editor.
 */
final class TrackPreviewPlayer {
    private final Supplier<Snapshot> snapshot;
    private final StringWidget title;
    private final PreviewScrubSlider scrub;
    private final Button playPause;
    private final Button stop;
    private final List<AbstractWidget> widgets;

    TrackPreviewPlayer(Font font, Supplier<Snapshot> snapshot,
            Runnable togglePause, DoubleConsumer seek, Runnable stopAction) {
        this.snapshot = snapshot != null ? snapshot : () ->
                new Snapshot(State.IDLE, "", "", 0.0, Double.NaN, false);
        Runnable onToggle = togglePause != null ? togglePause : () -> {};
        DoubleConsumer onSeek = seek != null ? seek : v -> {};
        Runnable onStop = stopAction != null ? stopAction : () -> {};

        Snapshot initial = this.snapshot.get();
        String initialTitle = initial != null && initial.title() != null ? initial.title() : "";

        this.title = new StringWidget(0, 0, 100, 9, Component.literal(initialTitle), font);
        this.scrub = new PreviewScrubSlider(0, 0, 100, onSeek, this.snapshot);
        this.playPause = Button.builder(Component.literal(playPauseIcon(initial != null ? initial.state() : State.PAUSED)),
                b -> onToggle.run())
                .bounds(0, 0, 20, 20)
                .createNarration(supplier -> Component.literal("Pause or resume preview"))
                .build();
        this.stop = Button.builder(Component.literal("■"), b -> onStop.run())
                .bounds(0, 0, 20, 20)
                .createNarration(supplier -> Component.literal("Stop preview"))
                .build();
        this.widgets = List.of(this.title, this.scrub, this.playPause, this.stop);
        tick();
    }

    static String playPauseIcon(State state) {
        return state == State.PLAYING ? "⏸" : "▶";
    }

    static String timeText(double positionSeconds, double durationSeconds) {
        return MusicDirector.formatTime(positionSeconds) + " / " + MusicDirector.formatTime(durationSeconds);
    }

    List<AbstractWidget> widgets() {
        return widgets;
    }

    StringWidget title() {
        return title;
    }

    PreviewScrubSlider scrubSlider() {
        return scrub;
    }

    Button playPauseButton() {
        return playPause;
    }

    Button stopButton() {
        return stop;
    }

    void setBounds(TrackWeightScreen.Bounds bounds) {
        if (bounds == null) {
            return;
        }
        int titleHeight = 9;
        title.setX(bounds.x());
        title.setY(bounds.y());
        title.setWidth(bounds.width());
        title.setHeight(titleHeight);
        try {
            title.setMaxWidth(Math.max(0, bounds.width()));
        } catch (Exception ignored) {}

        int rowY = bounds.y() + titleHeight + 2;
        int rowHeight = Math.max(18, bounds.bottom() - rowY);
        if (rowHeight > 20) {
            rowHeight = 20;
        }
        playPause.setX(bounds.x());
        playPause.setY(rowY);
        playPause.setWidth(20);
        playPause.setHeight(rowHeight);
        stop.setX(bounds.x() + 22);
        stop.setY(rowY);
        stop.setWidth(20);
        stop.setHeight(rowHeight);
        int sliderX = bounds.x() + 44;
        int sliderWidth = Math.max(40, bounds.right() - sliderX);
        scrub.setX(sliderX);
        scrub.setY(rowY);
        scrub.setWidth(sliderWidth);
        scrub.setHeight(rowHeight);
    }

    void tick() {
        Snapshot snap = snapshot.get();
        if (snap == null) {
            return;
        }
        String text = snap.title() != null ? snap.title() : "";
        if (!title.getMessage().getString().equals(text)) {
            title.setMessage(Component.literal(text));
        }
        String icon = playPauseIcon(snap.state());
        if (!playPause.getMessage().getString().equals(icon)) {
            playPause.setMessage(Component.literal(icon));
        }
        scrub.sync(snap.positionSeconds(), snap.durationSeconds(), snap.canSeek());
    }

    static final class PreviewScrubSlider extends AbstractSliderButton {
        private final DoubleConsumer onSeek;
        private final Supplier<Snapshot> snapshot;
        private double durationSeconds = Double.NaN;
        private boolean updating;

        PreviewScrubSlider(int x, int y, int width, DoubleConsumer onSeek, Supplier<Snapshot> snapshot) {
            super(x, y, width, 20, Component.literal("0:00 / --:--"), 0.0);
            this.onSeek = onSeek != null ? onSeek : v -> {};
            this.snapshot = snapshot;
        }

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

        void sync(double positionSeconds, double durationSeconds, boolean canSeek) {
            this.durationSeconds = durationSeconds;
            this.active = canSeek;
            double target = fractionFor(positionSeconds, durationSeconds);
            if (Math.abs(this.value - target) > 1e-6) {
                boolean wasUpdating = updating;
                updating = true;
                try {
                    setValue(target);
                } finally {
                    updating = wasUpdating;
                }
            }
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            Snapshot snap = snapshot != null ? snapshot.get() : null;
            double pos = snap != null ? snap.positionSeconds() : 0.0;
            setMessage(Component.literal(timeText(pos, durationSeconds)));
        }

        @Override
        protected void applyValue() {
            if (updating) {
                return;
            }
            updating = true;
            try {
                if (!Double.isNaN(durationSeconds) && !Double.isInfinite(durationSeconds)
                        && durationSeconds > 0.0) {
                    onSeek.accept(secondsFor(this.value, durationSeconds));
                }
            } finally {
                updating = false;
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean bl) {
            if (!this.active) {
                return;
            }
            super.onClick(event, bl);
        }
    }
}
