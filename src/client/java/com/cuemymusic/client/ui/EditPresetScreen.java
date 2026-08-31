package com.cuemymusic.client.ui;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicPreset;
import com.cuemymusic.data.MusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EditPresetScreen extends Screen {
    private static final int ROW_HEIGHT = 18;

    private final Screen parent;
    private final MusicPreset preset;
    private final MusicLibrary library;
    private final Set<String> includedIds = new LinkedHashSet<>();
    private List<MusicTrack> allTracks;
    private int scrollOffset = 0;
    private String searchText = "";
    private EditBox nameField, descField, searchField;
    private int listTop, listBottom, listLeft, listRight;

    public EditPresetScreen(Screen parent, MusicPreset preset) {
        super(Component.literal("Edit Preset — " + preset.getName()));
        this.parent = parent;
        this.preset = preset;
        MusicLibrary lib = null;
        try { var inst = CueMyMusic.getInstance(); if (inst != null) lib = inst.getLibrary(); } catch (Exception ignored) {}
        this.library = lib != null ? lib : new MusicLibrary();
        this.includedIds.addAll(preset.getTrackIds());
        this.allTracks = this.library.getAllTracks();
    }

    public EditPresetScreen(Screen parent, String presetId) { this(parent, resolvePreset(presetId)); }

    private static MusicPreset resolvePreset(String presetId) {
        try { var inst = CueMyMusic.getInstance(); if (inst != null) { var opt = inst.getLibrary().getPreset(presetId); if (opt.isPresent()) return opt.get(); } } catch (Exception ignored) {}
        MusicPreset p = new MusicPreset(presetId != null ? presetId : "my_mix", "My Mix", false);
        p.setDescription("Your custom mix");
        return p;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int topMargin = 24;
        this.nameField = new EditBox(font, width / 2 - 130, topMargin + 14, 260, 18, Component.literal("Preset Name"));
        nameField.setMaxLength(64);
        nameField.setValue(preset.getName() != null ? preset.getName() : preset.getId());
        addRenderableWidget(nameField);

        this.descField = new EditBox(font, width / 2 - 130, topMargin + 34, 260, 18, Component.literal("Description"));
        descField.setMaxLength(128);
        descField.setValue(preset.getDescription() != null ? preset.getDescription() : "");
        addRenderableWidget(descField);

        this.searchField = new EditBox(font, width / 2 - 130, topMargin + 56, 260, 16, Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setValue(searchText);
        searchField.setResponder(v -> { searchText = v; scrollOffset = 0; });
        addRenderableWidget(searchField);

        listLeft = width / 2 - 150;
        listRight = width / 2 + 150;
        listTop = topMargin + 78;
        listBottom = height - 36;
        if (listBottom - listTop < 40) listBottom = listTop + 40;

        int btnW = 72;
        int btnY = height - 26;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(centerX - btnW - 40, btnY, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAndClose()).bounds(centerX + 40, btnY, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Select All"), b -> { for (var t : getFiltered()) includedIds.add(t.getId()); }).bounds(listLeft, btnY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> { for (var t : getFiltered()) includedIds.remove(t.getId()); }).bounds(listLeft + 74, btnY, 50, 20).build());
    }

    private List<MusicTrack> getFiltered() {
        if (searchText == null || searchText.isBlank()) return allTracks;
        String q = searchText.toLowerCase(java.util.Locale.ROOT).trim();
        return allTracks.stream().filter(t -> (t.getTitle() != null && t.getTitle().toLowerCase(java.util.Locale.ROOT).contains(q)) || (t.getArtist() != null && t.getArtist().toLowerCase(java.util.Locale.ROOT).contains(q)) || t.getId().toLowerCase(java.util.Locale.ROOT).contains(q)).toList();
    }

    private void saveAndClose() {
        String newName = nameField.getValue().trim();
        if (!newName.isEmpty()) preset.setName(newName);
        String newDesc = descField.getValue().trim();
        preset.setDescription(newDesc.isEmpty() ? null : newDesc);
        preset.getTrackIds().clear();
        preset.getTrackIds().addAll(includedIds);
        try { var inst = CueMyMusic.getInstance(); if (inst != null) { inst.getLibrary().addOrReplacePreset(preset); inst.saveAll(); } } catch (Exception ignored) {}
        onClose();
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreenAndShow(parent); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        gfx.centeredText(font, Component.literal("Edit Preset — " + preset.getName()), width / 2, 8, 0xFFFFFFFF);
        gfx.text(font, Component.literal("Included tracks: " + includedIds.size() + " / " + allTracks.size()), listLeft, listTop - 12, 0xFFAAAAAA);
        gfx.fill(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2, 0xFF000000);
        gfx.fill(listLeft, listTop, listRight, listBottom, 0xFF2F2F2F);
        List<MusicTrack> filtered = getFiltered();
        int totalHeight = filtered.size() * ROW_HEIGHT;
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT + 1;
        int firstVisible = scrollOffset / ROW_HEIGHT;
        int yOffset = -(scrollOffset % ROW_HEIGHT);
        gfx.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < visibleRows + 1; i++) {
            int idx = firstVisible + i;
            if (idx >= filtered.size()) break;
            MusicTrack t = filtered.get(idx);
            int y = listTop + yOffset + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT < listTop || y >= listBottom) continue;
            boolean hovered = mouseX >= listLeft && mouseX < listRight && mouseY >= y && mouseY < y + ROW_HEIGHT;
            boolean checked = includedIds.contains(t.getId());
            int bg = (idx & 1) == 0 ? 0xFF3A3A3A : 0xFF333333;
            if (hovered) bg = 0xFF4A4A4A;
            gfx.fill(listLeft + 1, y, listRight - 1, y + ROW_HEIGHT, bg);
            int boxX = listLeft + 5;
            int boxY = y + 4;
            gfx.fill(boxX, boxY, boxX + 10, boxY + 10, 0xFF1A1A1A);
            gfx.fill(boxX + 1, boxY + 1, boxX + 9, boxY + 9, checked ? 0xFF7ED321 : 0xFF2A2A2A);
            if (checked) gfx.text(font, Component.literal("\u2713"), boxX + 2, boxY + 1, 0xFF000000);
            String title = t.getTitle() != null ? t.getTitle() : t.getId();
            String artist = t.getArtist() != null ? t.getArtist() : "Unknown";
            int textMax = listRight - (boxX + 18) - 60;
            String titleDraw = title;
            while (font.width(titleDraw) > textMax && titleDraw.length() > 2) titleDraw = titleDraw.substring(0, titleDraw.length() - 1);
            if (!titleDraw.equals(title)) titleDraw = titleDraw.substring(0, Math.max(0, titleDraw.length() - 3)) + "...";
            gfx.text(font, Component.literal(titleDraw), boxX + 14, y + 2, 0xFFFFFFFF);
            gfx.text(font, Component.literal(artist), boxX + 14, y + 10, 0xFFAAAAAA);
            String src = t.getSourceType() != null ? t.getSourceType().name() : "?";
            if (src.length() > 8) src = src.substring(0, 8);
            gfx.text(font, Component.literal(src), listRight - font.width(src) - 6, y + 5, 0xFF888888);
        }
        gfx.disableScissor();
        if (totalHeight > (listBottom - listTop)) {
            int trackH = listBottom - listTop;
            int thumbH = Math.max(16, trackH * trackH / totalHeight);
            int maxScroll = totalHeight - trackH;
            int thumbY = listTop + (maxScroll == 0 ? 0 : (scrollOffset * (trackH - thumbH) / maxScroll));
            gfx.fill(listRight - 4, listTop, listRight, listBottom, 0xFF1A1A1A);
            gfx.fill(listRight - 4, thumbY, listRight, thumbY + thumbH, 0xFFAAAAAA);
        }
        if (filtered.isEmpty()) {
            String msg = searchText.isBlank() ? "No tracks in library" : "No matches for \"" + searchText + "\"";
            gfx.text(font, Component.literal(msg), listLeft + (listRight - listLeft - font.width(msg)) / 2, listTop + 20, 0xFFAAAAAA);
        }
        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }
}
