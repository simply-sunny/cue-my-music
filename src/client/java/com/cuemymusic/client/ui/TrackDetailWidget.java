package com.cuemymusic.client.ui;

import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Bottom detail panel — matches reference: large 32x32 cover left, middle labels,
 * right transport + progress bar (static visual, playback handled by screen).
 */
public final class TrackDetailWidget {
    private final Minecraft client;

    public TrackDetailWidget(Minecraft client) { this.client = client; }

    public void render(GuiGraphicsExtractor gfx, int x, int y, int width, int height, MusicTrack track) {
        // Outer panel background
        gfx.fill(x, y, x + width, y + height, 0xCC333333);
        gfx.fill(x, y, x + width, y + 1, 0x44FFFFFF);
        gfx.fill(x, y + height - 1, x + width, y + height, 0x22000000);
        gfx.fill(x, y, x + 1, y + height, 0x22000000);
        gfx.fill(x + width - 1, y, x + width, y + height, 0x22000000);

        if (track == null) {
            String empty = "No track selected";
            int tw = client.font.width(empty);
            gfx.text(client.font, Component.literal(empty), x + (width - tw) / 2, y + (height - 8) / 2, 0xFFAAAAAA);
            return;
        }

        int textLeft = x + 8;
        int lineH = 11;
        int labelW = client.font.width("Artist: ");
        String title = track.getTitle() != null ? track.getTitle() : track.getId();
        String artist = track.getArtist() != null ? track.getArtist() : "Unknown";
        String sourceLabel = sourceLabel(track.getSourceType());
        String length = track.getDurationSeconds() != null ? format(track.getDurationSeconds()) : "--:--";

        int sourceColor = colorFor(track.getSourceType());

        // Reserve right side for transport + slider
        int rightReserve = 140;
        int textMax = width - (textLeft - x) - rightReserve - 8;
        if (textMax < 40) textMax = 40;

        // Row 1 Title
        gfx.text(client.font, Component.literal("Title:"), textLeft, y + 8, 0xFFAAAAAA);
        drawValue(gfx, textLeft + labelW, y + 8, title, textMax - labelW, 0xFFFFFFFF);
        // Row 2 Artist
        gfx.text(client.font, Component.literal("Artist:"), textLeft, y + 8 + lineH, 0xFFAAAAAA);
        drawValue(gfx, textLeft + labelW, y + 8 + lineH, artist, textMax - labelW, 0xFFFFFFFF);
        // Row 3 Source + Length combined? Reference has Title/Artist/Source/Length separate lines
        gfx.text(client.font, Component.literal("Source:"), textLeft, y + 8 + lineH * 2, 0xFFAAAAAA);
        // source value colored
        String srcTrunc = truncate(sourceLabel, textMax - labelW - 40);
        gfx.text(client.font, Component.literal(srcTrunc), textLeft + labelW, y + 8 + lineH * 2, sourceColor);
        // Length after source on same line or next
        String lenLabel = "Length:";
        int lenLabelW = client.font.width(lenLabel);
        int lenX = textLeft + labelW + client.font.width(srcTrunc) + 12;
        // if not enough space, wrap to next line
        if (lenX + lenLabelW + client.font.width(length) > x + width - rightReserve) {
            gfx.text(client.font, Component.literal("Length:"), textLeft, y + 8 + lineH * 3, 0xFFAAAAAA);
            gfx.text(client.font, Component.literal(length), textLeft + labelW, y + 8 + lineH * 3, 0xFFE8E8E8);
        } else {
            gfx.text(client.font, Component.literal(lenLabel), lenX, y + 8 + lineH * 2, 0xFFAAAAAA);
            gfx.text(client.font, Component.literal(length), lenX + lenLabelW + 2, y + 8 + lineH * 2, 0xFFE8E8E8);
        }

        // Right side: transport controls - render from playback service state
        var director = com.cuemymusic.client.playback.MusicDirector.getInstance();
        var playback = com.cuemymusic.client.playback.NativeMinecraftPlayback.getInstance();
        com.cuemymusic.client.playback.PlaybackState state = com.cuemymusic.client.playback.PlaybackState.STOPPED;
        boolean isCurrent = false;
        try {
            var cur = director.getCurrentTrack().orElse(null);
            isCurrent = cur != null && cur.getId().equals(track.getId());
            if (isCurrent) state = playback.getState();
        } catch (Exception ignored) {}
        // Spec: Play when STOPPED, Pause when PLAYING (if supported else Stop)
        String playSym = ">";
        if (isCurrent && state == com.cuemymusic.client.playback.PlaybackState.PLAYING) playSym = "||";
        else if (isCurrent && state == com.cuemymusic.client.playback.PlaybackState.PAUSED) playSym = ">";
        boolean isPlaying = state == com.cuemymusic.client.playback.PlaybackState.PLAYING;
        int ctrlW = 18;
        int ctrlH = 18;
        int gap = 4;
        int totalCtrlW = ctrlW * 3 + gap * 2;
        int ctrlX = x + width - rightReserve + (rightReserve - totalCtrlW) / 2;
        int ctrlY = y + 8;

        // prev
        drawControl(gfx, ctrlX, ctrlY, ctrlW, ctrlH, "<|");
        // play/pause (dynamic)
        drawControl(gfx, ctrlX + ctrlW + gap, ctrlY, ctrlW, ctrlH, playSym);
        // next
        drawControl(gfx, ctrlX + ctrlW * 2 + gap * 2, ctrlY, ctrlW, ctrlH, "|>");

        // Progress bar (functional)
        int barX = x + width - rightReserve + 6;
        int barY = ctrlY + ctrlH + 8;
        int barW = rightReserve - 12;
        int barH = 4;
        gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF1A1A1A);
        long elapsedMs = 0;
        Integer durSec = track.getDurationSeconds();
        int totalSec = durSec != null ? durSec : 0;
        try { if (isCurrent) elapsedMs = director.getElapsedMs(client); } catch (Exception ignored) {}
        int elapsedSec = (int)(elapsedMs / 1000);
        int fillW = 0;
        if (totalSec > 0) fillW = (int)(barW * Math.min(1.0, elapsedMs / (totalSec * 1000.0)));
        // when duration unavailable, keep 0 (do not lie); thin read-only bar hidden as empty
        gfx.fill(barX, barY, barX + fillW, barY + barH, 0xFF7ED321);
        // knob
        int knobX = barX + Math.max(0, Math.min(fillW, barW));
        gfx.fill(knobX - 2, barY - 2, knobX + 2, barY + barH + 2, 0xFFFFFFFF);
        // times
        String leftTime = String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60);
        String rightTime = length;
        gfx.text(client.font, Component.literal(leftTime), barX, barY + barH + 4, 0xFFAAAAAA);
        int rtW = client.font.width(rightTime);
        gfx.text(client.font, Component.literal(rightTime), barX + barW - rtW, barY + barH + 4, 0xFFAAAAAA);
    }

    private void drawValue(GuiGraphicsExtractor gfx, int x, int y, String value, int maxW, int color) {
        String draw = truncate(value, maxW);
        gfx.text(client.font, Component.literal(draw), x, y, color);
    }

    private void drawControl(GuiGraphicsExtractor gfx, int x, int y, int w, int h, String sym) {
        gfx.fill(x, y, x + w, y + h, 0xFF2A2A2A);
        gfx.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        gfx.fill(x, y + h - 1, x + w, y + h, 0x22000000);
        int tw = client.font.width(sym);
        gfx.text(client.font, Component.literal(sym), x + (w - tw) / 2, y + (h - 7) / 2, 0xFFFFFFFF);
    }

    private String truncate(String s, int maxW) {
        if (client.font.width(s) <= maxW) return s;
        String ell = "...";
        int ellW = client.font.width(ell);
        String out = s;
        while (out.length() > 0 && client.font.width(out) + ellW > maxW) out = out.substring(0, out.length() - 1);
        return out + ell;
    }

    private static int colorFor(SourceType t) {
        if (t == null) return 0xFFAAAAAA;
        return switch (t) {
            case VANILLA -> 0xFF7ED321;
            case MUSIC_DISC -> 0xFF5AA9FF;
            case YOUTUBE -> 0xFFFF5555;
        };
    }

    private static String sourceLabel(SourceType t) {
        if (t == null) return "Unknown";
        return switch (t) {
            case VANILLA -> "Vanilla Music";
            case MUSIC_DISC -> "Music Discs";
            case YOUTUBE -> "YouTube";
        };
    }

    private static String format(int s) { return String.format("%d:%02d", s / 60, s % 60); }
}
