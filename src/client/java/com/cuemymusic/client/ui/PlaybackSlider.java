package com.cuemymusic.client.ui;

import com.cuemymusic.client.playback.MusicDirector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

final class PlaybackSlider extends AbstractSliderButton {
    private final MusicDirector director = MusicDirector.getInstance();
    private float duration;
    private boolean userDragging;

    PlaybackSlider(int x, int y, int width) {
        super(x, y, width, 20, Component.empty(), 0.0);
        this.visible = false;
        this.active = false;
    }

    void sync(float position, float duration, boolean seekable) {
        this.duration = duration;
        this.visible = this.active = seekable && duration > 0;
        if (!userDragging && visible) {
            this.value = Math.clamp((double) position / (double) duration, 0.0, 1.0);
        }
        updateMessage();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (active) {
            userDragging = true;
        }
        super.onClick(event, doubleClick);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        userDragging = false;
        super.onRelease(event);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(format((float) (value * duration)) + " / " + format(duration)));
    }

    @Override
    protected void applyValue() {
        if (active) {
            director.seek(Minecraft.getInstance(), (float) (value * duration));
        }
    }

    static String format(float seconds) {
        int total = Math.max(0, (int) seconds);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    boolean isUserDragging() {
        return userDragging;
    }

    void setUserDragging(boolean dragging) {
        this.userDragging = dragging;
    }

    float getDuration() {
        return duration;
    }

    double getValue() {
        return value;
    }
}
