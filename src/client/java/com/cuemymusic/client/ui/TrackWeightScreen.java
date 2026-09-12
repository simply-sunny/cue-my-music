package com.cuemymusic.client.ui;

import java.io.IOException;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.TrackWeightConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Native Minecraft screen for configuring track pools and track selection multipliers.
 * Modifications are transactional: Done persists and applies the draft, Esc discards.
 */
public final class TrackWeightScreen extends Screen {

    public record Bounds(int x, int y, int width, int height) {}

    private final MusicPlayerScreen parent;
    private final TrackWeightConfig saved;
    private TrackWeightConfig draft;
    private Component errorMessage;

    public TrackWeightScreen(MusicPlayerScreen parent) {
        this(parent, MusicDirector.getInstance().weightingConfig());
    }

    TrackWeightScreen(MusicPlayerScreen parent, TrackWeightConfig saved) {
        super(Component.literal("Configure Track Pools"));
        this.parent = parent;
        this.saved = saved != null ? saved : TrackWeightConfig.defaults();
        this.draft = this.saved;
    }

    static boolean usesWideLayout(int width) {
        return width >= 640;
    }

    static TrackWeightConfig updateWeight(TrackWeightConfig base, String poolId, String resourceId, double multiplier) {
        return base.withMultiplier(poolId, resourceId, multiplier);
    }

    void saveAndClose() {
        try {
            MusicDirector.getInstance().saveWeightingConfig(draft);
            if (minecraft != null) {
                minecraft.setScreenAndShow(parent);
            }
        } catch (IOException e) {
            this.errorMessage = Component.literal("Could not save cue-my-music.json");
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    protected void init() {
        super.init();
        // Wide and narrow layouts both initially render title and Done in Task 5 shell.
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds((width - 200) / 2, height - 28, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
        super.extractRenderState(extractor, mouseX, mouseY, tickProgress);
        extractor.centeredText(font, title, width / 2, 15, 0xFFFFFF);
        if (errorMessage != null) {
            extractor.centeredText(font, errorMessage, width / 2, height - 40, 0xFF5555);
        }
    }

    TrackWeightConfig draft() {
        return draft;
    }

    TrackWeightConfig saved() {
        return saved;
    }

    Component errorMessage() {
        return errorMessage;
    }

    MusicPlayerScreen parent() {
        return parent;
    }
}
