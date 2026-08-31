package com.cuemymusic.client.ui;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.data.MusicCollection;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicPreset;
import com.cuemymusic.data.MusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Jukebox Library — main UI. Vanilla-feeling, left sidebar + scrollable vertical list.
 */
public class JukeboxLibraryScreen extends Screen {

    private static final Component TITLE = Component.literal("Cue My Music \u2014 Jukebox Library");
    private static final int SIDEBAR_WIDTH = 96;
    private static final int SIDEBAR_GAP = 8;
    private static final int ROW_HEIGHT = 18;
    private static final int TOP_CONTROLS_H = 22;

    private final Screen parent;
    private MusicLibrary library;

    private MusicCollection selectedCollection = MusicCollection.ALL;
    private String activePresetId = null;
    private String searchText = "";
    private boolean enabledOnly = false;
    private boolean ambientOnly = false;

    private enum SortMode { TITLE, ARTIST, SOURCE }
    private SortMode sortMode = SortMode.TITLE;

    private int scrollOffset = 0;
    private final List<MusicTrack> displayedTracks = new ArrayList<>();
    private MusicTrack selectedTrack = null;

    private EditBox searchField;
    private Button enabledToggle;
    private Button ambientToggle;
    private Button sortButton;

    private int contentLeft, contentRight, contentTop, contentBottom, sidebarLeft, sidebarRight;
    private TrackDetailWidget detailWidget;

    public JukeboxLibraryScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
        try {
            var inst = CueMyMusic.getInstance();
            if (inst != null) {
                this.library = inst.getLibrary();
                this.activePresetId = inst.getLibrary().getActivePresetId();
            }
        } catch (Exception ignored) {}
        if (this.library == null) this.library = new MusicLibrary();
    }

    public JukeboxLibraryScreen() { this(null); }

    @Override
    protected void init() {
        int panelLeft = 10;
        int panelRight = width - 10;
        sidebarLeft = panelLeft;
        sidebarRight = sidebarLeft + SIDEBAR_WIDTH;
        contentLeft = sidebarRight + SIDEBAR_GAP;
        contentRight = panelRight;
        int controlsTop = 18;
        contentTop = controlsTop + TOP_CONTROLS_H + 4;
        contentBottom = height - 30 - 24;
        if (contentBottom - contentTop < 40) contentBottom = contentTop + 40;

        detailWidget = new TrackDetailWidget(Minecraft.getInstance());

        int searchW = Math.min(160, width / 3);
        int searchX = contentLeft;
        int searchY = controlsTop + 1;
        this.searchField = new EditBox(this.font, searchX, searchY, searchW, 18, Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setValue(searchText);
        searchField.setResponder(v -> {
            searchText = v;
            rebuildDisplayedTracks();
            scrollOffset = 0;
        });
        addRenderableWidget(searchField);

        int toggleX = searchX + searchW + 6;
        int toggleW = 72;
        this.enabledToggle = Button.builder(labelForEnabled(), b -> {
            enabledOnly = !enabledOnly;
            b.setMessage(labelForEnabled());
            rebuildDisplayedTracks();
            scrollOffset = 0;
        }).bounds(toggleX, searchY, toggleW, 18).build();
        addRenderableWidget(enabledToggle);

        this.ambientToggle = Button.builder(labelForAmbient(), b -> {
            ambientOnly = !ambientOnly;
            b.setMessage(labelForAmbient());
            rebuildDisplayedTracks();
            scrollOffset = 0;
        }).bounds(toggleX + toggleW + 4, searchY, toggleW, 18).build();
        addRenderableWidget(ambientToggle);

        int sortX = toggleX + toggleW * 2 + 8;
        int sortW = Math.min(110, contentRight - sortX);
        if (sortW < 60) { sortX = contentLeft; sortW = 100; }
        this.sortButton = Button.builder(labelForSort(), b -> {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            b.setMessage(labelForSort());
            rebuildDisplayedTracks();
        }).bounds(sortX, searchY, sortW, 18).build();
        addRenderableWidget(sortButton);

        int sbY = contentTop + 2 + 12;
        int sbBtnH = 18;
        int gap = 4;
        for (MusicCollection col : MusicCollection.values()) {
            Button btn = Button.builder(Component.literal(col.getDisplayName()), b -> {
                selectedCollection = col;
                rebuildDisplayedTracks();
                scrollOffset = 0;
            }).bounds(sidebarLeft + 2, sbY, SIDEBAR_WIDTH - 4, sbBtnH).build();
            addRenderableWidget(btn);
            sbY += sbBtnH + gap;
        }
        sbY += 8 + 12;
        for (MusicPreset preset : library.getAllPresets()) {
            if (sbY + sbBtnH > height - 36) break;
            String label = preset.getName() != null ? preset.getName() : preset.getId();
            String shortLabel = label;
            while (font.width(shortLabel) > SIDEBAR_WIDTH - 12 && shortLabel.length() > 1) shortLabel = shortLabel.substring(0, shortLabel.length() - 1);
            if (!shortLabel.equals(label) && shortLabel.length() > 3) shortLabel = shortLabel.substring(0, shortLabel.length() - 2) + "..";
            MusicPreset captured = preset;
            Button btn = Button.builder(Component.literal(shortLabel), b -> {
                activePresetId = captured.getId();
                try { library.setActivePresetId(captured.getId()); } catch (Exception ignored) {}
                rebuildDisplayedTracks();
                scrollOffset = 0;
            }).bounds(sidebarLeft + 2, sbY, SIDEBAR_WIDTH - 4, sbBtnH).build();
            addRenderableWidget(btn);
            sbY += sbBtnH + gap;
        }

        int bottomY = height - 24;
        int btnW = 90;
        addRenderableWidget(Button.builder(Component.literal("Add Music"), b -> onAddMusic()).bounds(panelLeft, bottomY, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Edit Preset"), b -> onEditPreset()).bounds(panelLeft + btnW + 6, bottomY, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(panelRight - 72, bottomY, 70, 20).build());

        rebuildDisplayedTracks();
        clampScroll();
    }

    private Component labelForEnabled() { return Component.literal((enabledOnly ? "\u2611 " : "\u2610 ") + "Enabled only"); }
    private Component labelForAmbient() { return Component.literal((ambientOnly ? "\u2611 " : "\u2610 ") + "Ambient only"); }
    private Component labelForSort() {
        String s = switch (sortMode) { case TITLE -> "Title"; case ARTIST -> "Artist"; case SOURCE -> "Source"; };
        return Component.literal("Sort: " + s);
    }

    private void onAddMusic() { onEditPreset(); }

    private void onEditPreset() {
        String pid = activePresetId != null ? activePresetId : library.getActivePresetId();
        if (pid == null) pid = "my_mix";
        final String finalPid = pid;
        var opt = library.getPreset(finalPid);
        MusicPreset preset = opt.orElseGet(() -> { var p = new MusicPreset(finalPid, finalPid, false); library.addOrReplacePreset(p); return p; });
        Minecraft.getInstance().setScreenAndShow(new EditPresetScreen(this, preset));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    void rebuildDisplayedTracks() {
        displayedTracks.clear();
        List<MusicTrack> base;
        if (activePresetId != null) {
            var presetOpt = library.getPreset(activePresetId);
            if (presetOpt.isPresent()) {
                base = new ArrayList<>(library.getTracksForPreset(activePresetId));
                if (selectedCollection != MusicCollection.ALL) base.removeIf(t -> !selectedCollection.matches(t));
            } else base = new ArrayList<>(library.getTracksForCollection(selectedCollection));
        } else base = new ArrayList<>(library.getTracksForCollection(selectedCollection));

        String q = searchText != null ? searchText.toLowerCase(Locale.ROOT).trim() : "";
        for (MusicTrack t : base) {
            if (enabledOnly && !t.isEnabled()) continue;
            if (ambientOnly && !t.isAmbientEligible()) continue;
            if (!q.isEmpty()) {
                String title = t.getTitle() != null ? t.getTitle().toLowerCase(Locale.ROOT) : "";
                String artist = t.getArtist() != null ? t.getArtist().toLowerCase(Locale.ROOT) : "";
                String id = t.getId().toLowerCase(Locale.ROOT);
                if (!title.contains(q) && !artist.contains(q) && !id.contains(q)) continue;
            }
            displayedTracks.add(t);
        }
        Comparator<MusicTrack> cmp = switch (sortMode) {
            case TITLE -> Comparator.comparing(t -> t.getTitle() != null ? t.getTitle().toLowerCase(Locale.ROOT) : t.getId());
            case ARTIST -> Comparator.comparing(t -> t.getArtist() != null ? t.getArtist().toLowerCase(Locale.ROOT) : "");
            case SOURCE -> Comparator.comparing(t -> t.getSourceType() != null ? t.getSourceType().name() : "");
        };
        displayedTracks.sort(cmp);
        if (selectedTrack != null && !displayedTracks.contains(selectedTrack)) selectedTrack = null;
    }

    private void clampScroll() {
        int visibleH = contentBottom - contentTop;
        int totalH = displayedTracks.size() * ROW_HEIGHT;
        int max = Math.max(0, totalH - visibleH);
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > max) scrollOffset = max;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        // Title
        gfx.centeredText(font, TITLE, width / 2, 6, 0xFFFFFFFF);
        // Sidebar panel
        gfx.fill(sidebarLeft - 1, contentTop - 1, sidebarRight + 1, contentBottom + 25, 0xFF000000);
        gfx.fill(sidebarLeft, contentTop, sidebarRight, contentBottom + 24, 0xFF3A3A3A);

        int labelX = sidebarLeft + 4;
        int yCursor = contentTop + 4;
        gfx.text(font, Component.literal("Collections"), labelX, yCursor, 0xFFFFD966);
        yCursor += 12;
        int collIdx = selectedCollection.ordinal();
        int selY = contentTop + 2 + 12 + collIdx * (18 + 4);
        gfx.fill(sidebarLeft + 1, selY - 1, sidebarRight - 1, selY + 19, 0x33FFD966);
        yCursor += MusicCollection.values().length * (18 + 4) + 8;
        gfx.text(font, Component.literal("Presets"), labelX, yCursor, 0xFF8ED9FF);

        // Main content
        gfx.fill(contentLeft - 1, contentTop - 1, contentRight + 1, contentBottom + 1, 0xFF000000);
        gfx.fill(contentLeft, contentTop, contentRight, contentBottom, 0xFF2F2F2F);

        int totalH = displayedTracks.size() * ROW_HEIGHT;
        int visibleH = contentBottom - contentTop;
        int firstVisible = visibleH == 0 ? 0 : scrollOffset / ROW_HEIGHT;
        int yOffset = -(scrollOffset % ROW_HEIGHT);
        int visibleRows = visibleH / ROW_HEIGHT + 2;

        gfx.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
        for (int i = 0; i < visibleRows; i++) {
            int idx = firstVisible + i;
            if (idx >= displayedTracks.size()) break;
            MusicTrack track = displayedTracks.get(idx);
            int y = contentTop + yOffset + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT < contentTop || y >= contentBottom) continue;
            boolean isSelected = track.equals(selectedTrack);
            boolean hovered = mouseX >= contentLeft && mouseX < contentRight && mouseY >= y && mouseY < y + ROW_HEIGHT;
            int bg = (idx & 1) == 0 ? 0xFF3A3A3A : 0xFF333333;
            if (isSelected) bg = 0xFF4A5A3A; else if (hovered) bg = 0xFF444444;
            gfx.fill(contentLeft + 1, y, contentRight - 1, y + ROW_HEIGHT, bg);
            if (isSelected) gfx.fill(contentLeft + 1, y, contentLeft + 3, y + ROW_HEIGHT, 0xFF7ED321);
            String flags = !track.isEnabled() ? " \u2718" : (track.isFavorite() ? " \u2665" : "");
            String ambientMark = track.isAmbientEligible() ? "" : " \u25CB";
            String title = track.getTitle() != null ? track.getTitle() : track.getId();
            String artist = track.getArtist() != null ? track.getArtist() : "Unknown";
            String source = track.getSourceType() != null ? shortSource(track.getSourceType().name()) : "?";
            int leftPad = 8;
            int rightReserve = 90;
            int textMax = contentRight - contentLeft - leftPad - rightReserve;
            if (textMax < 40) textMax = 40;
            String titleDraw = truncate(title, textMax);
            int titleW = font.width(titleDraw);
            String artistDraw = " \u2014 " + artist;
            int artistMax = contentRight - contentLeft - leftPad - titleW - rightReserve;
            if (artistMax > 20) artistDraw = truncate(artistDraw, artistMax); else artistDraw = "";
            gfx.text(font, Component.literal(titleDraw), contentLeft + leftPad, y + 3, 0xFFFFFFFF);
            if (!artistDraw.isEmpty()) {
                gfx.text(font, Component.literal(artistDraw), contentLeft + leftPad + titleW, y + 3, 0xFFAAAAAA);
                titleW += font.width(artistDraw);
            }
            if (!flags.isBlank()) gfx.text(font, Component.literal(flags), contentLeft + leftPad + titleW + 2, y + 3, track.isEnabled() ? 0xFFFF6B6B : 0xFF888888);
            String srcDraw = source + ambientMark;
            int sw = font.width(srcDraw);
            gfx.text(font, Component.literal(srcDraw), contentRight - sw - 8, y + 5, 0xFF888888);
            int dotX = contentRight - 6;
            int dotY = y + 6;
            int dotColor = track.isEnabled() ? 0xFF7ED321 : 0xFF555555;
            gfx.fill(dotX, dotY, dotX + 4, dotY + 4, dotColor);
        }
        gfx.disableScissor();
        if (displayedTracks.isEmpty()) {
            String msg = searchText.isBlank() ? "No tracks found" : "No matches for \"" + searchText + "\"";
            int tw = font.width(msg);
            gfx.text(font, Component.literal(msg), contentLeft + (contentRight - contentLeft - tw) / 2, contentTop + 24, 0xFFAAAAAA);
        }
        if (totalH > visibleH) {
            int trackH = visibleH;
            int thumbH = Math.max(16, trackH * trackH / totalH);
            int maxScroll = totalH - trackH;
            int thumbY = contentTop + (maxScroll == 0 ? 0 : (scrollOffset * (trackH - thumbH) / maxScroll));
            gfx.fill(contentRight - 4, contentTop, contentRight, contentBottom, 0xFF1A1A1A);
            gfx.fill(contentRight - 4, thumbY, contentRight, thumbY + thumbH, 0xFFAAAAAA);
        }
        int detailTop = contentBottom + 1;
        int detailH = 22;
        if (detailWidget != null) detailWidget.render(gfx, contentLeft, detailTop, contentRight - contentLeft, detailH, selectedTrack);
        String countText = displayedTracks.size() + " track" + (displayedTracks.size() == 1 ? "" : "s");
        if (displayedTracks.size() != library.getAllTracks().size()) countText += " (filtered from " + library.getAllTracks().size() + ")";
        gfx.text(font, Component.literal(countText), contentLeft + 2, detailTop + detailH + 4, 0xFFAAAAAA);
        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (selectedTrack != null && displayedTracks.contains(selectedTrack)) {
            int idx = displayedTracks.indexOf(selectedTrack);
            if (event.key() == 265) { // up
                if (idx > 0) { selectedTrack = displayedTracks.get(idx - 1); ensureVisible(idx - 1); }
                return true;
            } else if (event.key() == 264) { // down
                if (idx < displayedTracks.size() - 1) { selectedTrack = displayedTracks.get(idx + 1); ensureVisible(idx + 1); }
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void ensureVisible(int idx) {
        int visibleH = contentBottom - contentTop;
        int topIdx = scrollOffset / ROW_HEIGHT;
        int visibleCount = visibleH / ROW_HEIGHT;
        if (idx < topIdx) scrollOffset = idx * ROW_HEIGHT;
        else if (idx >= topIdx + visibleCount) scrollOffset = (idx - visibleCount + 1) * ROW_HEIGHT;
        clampScroll();
    }

    private static String shortSource(String name) {
        return switch (name) {
            case "VANILLA" -> "Vanilla"; case "MUSIC_DISC" -> "Disc"; case "SPOTIFY" -> "Spotify";
            case "YOUTUBE" -> "YouTube"; case "LOCAL_GENERIC" -> "Local"; default -> name;
        };
    }

    private String truncate(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ell = "...";
        int ellW = font.width(ell);
        String out = s;
        while (out.length() > 0 && font.width(out) + ellW > maxWidth) out = out.substring(0, out.length() - 1);
        return out + ell;
    }
}
