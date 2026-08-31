package com.cuemymusic.client.ui;

import com.cuemymusic.data.MusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class TrackDetailWidget {
    private final Minecraft client;

    public TrackDetailWidget(Minecraft client) { this.client = client; }

    public void render(GuiGraphicsExtractor gfx, int x, int y, int width, int height, MusicTrack track) {
        gfx.fill(x, y, x + width, y + height, 0x55333333);
        gfx.fill(x, y, x + width, y + 1, 0x44FFFFFF);
        gfx.fill(x, y + height - 1, x + width, y + height, 0x22000000);
        if (track == null) {
            String empty = "No track selected";
            int tw = client.font.width(empty);
            gfx.text(client.font, empty, x + (width - tw) / 2, y + (height - 8) / 2, 0xFFAAAAAA);
            return;
        }
        String title = track.getTitle() != null ? track.getTitle() : track.getId();
        String artist = track.getArtist() != null ? track.getArtist() : "Unknown Artist";
        String source = track.getSourceType() != null ? track.getSourceType().name() : "?";
        String duration = track.getDurationSeconds() != null ? format(track.getDurationSeconds()) : "--:--";
        gfx.text(client.font, Component.literal(title), x + 6, y + 3, 0xFFFFFFFF);
        String meta = artist + "  ·  " + source + "  ·  " + duration + (track.isFavorite() ? "  ♥" : "") + (!track.isEnabled() ? "  [Disabled]" : (!track.isAmbientEligible() ? "  [Not ambient]" : ""));
        // truncate
        int maxW = width - 12;
        while (client.font.width(meta) > maxW && meta.length() > 3) meta = meta.substring(0, meta.length() - 1);
        if (client.font.width(meta) > maxW) meta = meta.substring(0, Math.max(0, meta.length() - 3)) + "...";
        gfx.text(client.font, Component.literal(meta), x + 6, y + 12, 0xFFAAAAAA);
    }

    private static String format(int s) { return String.format("%d:%02d", s/60, s%60); }
}
