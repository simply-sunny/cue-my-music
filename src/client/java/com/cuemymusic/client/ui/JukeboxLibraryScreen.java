package com.cuemymusic.client.ui;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.client.CueMyMusicClient;
import com.cuemymusic.client.download.YoutubeDownloader;
import com.cuemymusic.client.playback.MusicDirector;
import com.cuemymusic.client.playback.PlaybackState;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

public class JukeboxLibraryScreen extends Screen {
    private static final Component TITLE = Component.literal("Cue My Music");
    private static final Component SUB = Component.literal("Vanilla Music, Discs & YouTube");
    public static final int ROW_H = 20, ROW_CONTROL_SIZE = 20;

    public record Layout(
        int contentW,
        int contentL,
        int searchX,
        int searchY,
        int searchW,
        int searchH,
        int sortX,
        int sortY,
        int sortW,
        int sortH,
        int scrubX,
        int scrubY,
        int scrubW,
        int scrubH,
        boolean scrubWrapped,
        int listTop,
        int listHeight,
        int doneX,
        int doneY,
        int doneW,
        int doneH,
        int downloadAllX,
        int downloadAllY,
        int downloadAllW,
        int downloadAllH
    ) {
        public int searchRight() { return searchX + searchW; }
        public int sortRight() { return sortX + sortW; }
        public int scrubRight() { return scrubX + scrubW; }
        public int scrubBottom() { return scrubY + scrubH; }
        public int listBottom() { return listTop + listHeight; }
    }

    public static Layout computeLayout(int width, int height) {
        int contentW = Math.min(620, width - 40);
        int contentL = (width - contentW) / 2;
        int searchW = Math.max(150, Math.min(180, contentW - 118));
        int searchX = contentL;
        int searchY = 30;
        int searchH = 20;
        int sortX = searchX + searchW + 8;
        int sortY = 30;
        int sortW = 110;
        int sortH = 20;
        int inlineScrubX = sortX + sortW + 8;
        boolean wrapped = contentL + contentW - inlineScrubX < 140;
        int scrubX = wrapped ? contentL : inlineScrubX;
        int scrubY = wrapped ? 54 : 30;
        int scrubW = wrapped ? contentW : contentL + contentW - inlineScrubX;
        int scrubH = 20;
        int listTop = wrapped ? 96 : 72;
        int doneY = height - 24;
        int doneW = 80;
        int doneX = (width - doneW) / 2;
        int doneH = 20;
        int listHeight = Math.max(ROW_H, doneY - 8 - listTop);

        int downloadAllW = Math.min(110, Math.max(70, doneX - contentL - 10));
        int downloadAllX = doneX - downloadAllW - 8;
        int downloadAllY = doneY;
        int downloadAllH = 20;

        return new Layout(
            contentW,
            contentL,
            searchX,
            searchY,
            searchW,
            searchH,
            sortX,
            sortY,
            sortW,
            sortH,
            scrubX,
            scrubY,
            scrubW,
            scrubH,
            wrapped,
            listTop,
            listHeight,
            doneX,
            doneY,
            doneW,
            doneH,
            downloadAllX,
            downloadAllY,
            downloadAllW,
            downloadAllH
        );
    }

    private final Screen parent;
    private MusicLibrary library;
    private String search = "";
    private enum SortMode { ARTIST, TITLE, SOURCE }
    private SortMode sortMode = SortMode.ARTIST;
    private final List<MusicTrack> displayed = new ArrayList<>();

    private Layout layout;
    private EditBox searchField;
    private Button sortButton;
    private PlaybackSlider slider;
    private TrackList trackList;
    private Button downloadAllButton;
    private Button doneButton;

    public JukeboxLibraryScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
        var inst = CueMyMusic.getInstance();
        this.library = inst != null ? inst.getLibrary() : null;
        if (this.library == null) this.library = new MusicLibrary();
    }

    public JukeboxLibraryScreen() {
        this(null);
    }

    private Component sortLabel() {
        return Component.literal(switch (sortMode) {
            case ARTIST -> "Sort: Artist \u25BE";
            case TITLE -> "Sort: Title \u25BE";
            case SOURCE -> "Sort: Source \u25BE";
        });
    }

    @Override
    protected void init() {
        this.layout = computeLayout(width, height);

        searchField = new EditBox(font, layout.searchX(), layout.searchY(), layout.searchW(), layout.searchH(), Component.literal("Search"));
        searchField.setHint(Component.literal("Search tracks..."));
        searchField.setMaxLength(64);
        searchField.setValue(search);
        searchField.setResponder(v -> {
            search = v;
            rebuild();
            if (trackList != null) trackList.setScrollAmount(0.0);
        });
        addRenderableWidget(searchField);

        sortButton = Button.builder(sortLabel(), b -> {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            b.setMessage(sortLabel());
            rebuild();
        }).bounds(layout.sortX(), layout.sortY(), layout.sortW(), layout.sortH()).build();
        addRenderableWidget(sortButton);

        slider = new PlaybackSlider(layout.scrubX(), layout.scrubY(), layout.scrubW());
        addRenderableWidget(slider);

        trackList = new TrackList(minecraft, layout);
        addRenderableWidget(trackList);

        downloadAllButton = Button.builder(Component.literal("Download all"), b -> handleDownloadAll())
                .bounds(layout.downloadAllX(), layout.downloadAllY(), layout.downloadAllW(), layout.downloadAllH())
                .build();
        addRenderableWidget(downloadAllButton);

        doneButton = Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(layout.doneX(), layout.doneY(), layout.doneW(), layout.doneH())
                .build();
        addRenderableWidget(doneButton);

        rebuild();
    }

    private void handleDownloadAll() {
        var dl = CueMyMusicClient.getDownloader();
        if (dl == null) return;
        dl.downloadAll(
                () -> Minecraft.getInstance().execute(this::rebuild),
                () -> Minecraft.getInstance().execute(() -> {
                    try { Minecraft.getInstance().reloadResourcePacks(); } catch (Exception ignored) {}
                    rebuild();
                })
        );
        rebuild();
    }

    void rebuild() {
        var all = library.getAllTracks();
        Map<String, MusicTrack> dedup = new LinkedHashMap<>();
        for (var t : all) dedup.putIfAbsent(t.getId(), t);
        var filtered = new ArrayList<>(library.filter(new ArrayList<>(dedup.values()), search));
        Comparator<MusicTrack> cmp = switch (sortMode) {
            case ARTIST -> Comparator.comparing((MusicTrack t) -> t.getArtist() != null ? t.getArtist().toLowerCase(Locale.ROOT) : "")
                    .thenComparing(t -> t.getTitle() != null ? t.getTitle().toLowerCase(Locale.ROOT) : "");
            case TITLE -> Comparator.comparing((MusicTrack t) -> t.getTitle() != null ? t.getTitle().toLowerCase(Locale.ROOT) : t.getId().toLowerCase(Locale.ROOT));
            case SOURCE -> Comparator.comparing((MusicTrack t) -> t.getSourceType().name())
                    .thenComparing(t -> t.getTitle() != null ? t.getTitle() : "");
        };
        filtered.sort(cmp);
        displayed.clear();
        displayed.addAll(filtered);
        if (trackList != null) {
            trackList.setTracks(displayed);
        }
        if (downloadAllButton != null) {
            var dl = CueMyMusicClient.getDownloader();
            int missing = dl != null ? dl.getMissingCount() : 0;
            downloadAllButton.visible = missing > 0;
            downloadAllButton.active = dl != null && !dl.isDownloading();
            downloadAllButton.setMessage(Component.literal("Download all (" + missing + ")"));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mx, int my, float pt) {
        var director = MusicDirector.getInstance();
        var state = director.getState();
        boolean seekable = state == PlaybackState.PLAYING && director.canSeek();
        slider.sync(director.getPositionSeconds(), director.getDurationSeconds(), seekable);

        super.extractRenderState(gfx, mx, my, pt);

        gfx.centeredText(font, TITLE, width / 2, 8, 0xFFFFFFFF);
        gfx.centeredText(font, SUB, width / 2, 18, 0xFFAAAAAA);

        int headerY = layout.listTop() - 12;
        int colCheckX = layout.contentL() + 2;
        int colSourceX = layout.contentL() + 26;
        int colTitleX = layout.contentL() + 70;
        int previewW = ROW_CONTROL_SIZE + 6;
        int availableTextW = Math.max(10, (layout.contentL() + layout.contentW() - previewW) - colTitleX);
        int titleW = (int) (availableTextW * 0.55);
        int colArtistX = colTitleX + titleW;

        gfx.text(font, Component.literal("Source"), colSourceX, headerY, 0xFFAAAAAA);
        gfx.text(font, Component.literal("Title"), colTitleX, headerY, 0xFFAAAAAA);
        gfx.text(font, Component.literal("Artist"), colArtistX, headerY, 0xFFAAAAAA);

        if (displayed.isEmpty()) {
            String msg = search.isBlank() ? "No tracks" : "No matches for \"" + search + "\"";
            gfx.centeredText(font, Component.literal(msg), width / 2, layout.listTop() + 20, 0xFFAAAAAA);
        }

        int footerY = layout.doneY() + 6;
        long selected = library.getAllTracks().stream().filter(MusicTrack::isAmbientEligible).count();
        String selText = selected + " selected";
        gfx.text(font, Component.literal(selText), colCheckX, footerY, 0xFF7ED321);

        String count = displayed.size() + " tracks" + (displayed.size() != library.getAllTracks().size() ? " (filtered)" : "");
        int cw = font.width(count);
        gfx.text(font, Component.literal(count), layout.contentL() + layout.contentW() - cw, footerY, 0xFFAAAAAA);
    }

    private void previewTrack(MusicTrack track) {
        if (track == null) return;
        try {
            var mc = Minecraft.getInstance();
            var director = MusicDirector.getInstance();
            director.previewTrack(mc, track);
        } catch (Exception ignored) {}
    }

    @Override
    public void onClose() {
        try {
            CueMyMusic.getInstance().saveAll();
        } catch (Exception ignored) {}
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    static String ellipsize(Font font, String s, int maxW) {
        if (s == null) return "";
        if (maxW <= 0) return "";
        if (font == null || font.width(s) <= maxW) return s;
        String ell = "...";
        int ew = font.width(ell);
        if (maxW <= ew) return "";
        String out = font.plainSubstrByWidth(s, maxW - ew);
        return out + ell;
    }

    final class TrackList extends ContainerObjectSelectionList<TrackRow> {
        TrackList(Minecraft mc, Layout l) {
            super(mc, l.contentW(), l.listHeight(), l.listTop(), ROW_H);
            setX(l.contentL());
        }

        @Override
        public int getRowWidth() {
            return width;
        }

        @Override
        protected int scrollBarX() {
            return getX() + width - 6;
        }

        void setTracks(List<MusicTrack> tracks) {
            clearEntries();
            for (var t : tracks) {
                addEntry(new TrackRow(t));
            }
        }
    }

    final class TrackRow extends ContainerObjectSelectionList.Entry<TrackRow> {
        private final MusicTrack track;
        private final Button queued;
        private final Button actionBtn;

        TrackRow(MusicTrack track) {
            this.track = track;
            queued = Button.builder(Component.literal(track.isAmbientEligible() ? "☑" : "☐"), b -> {
                        track.setAmbientEligible(!track.isAmbientEligible());
                        b.setMessage(Component.literal(track.isAmbientEligible() ? "☑" : "☐"));
                        var inst = CueMyMusic.getInstance();
                        if (inst != null) inst.saveAll();
                    })
                    .createNarration(ignored -> Component.literal(
                            (track.isAmbientEligible() ? "Remove from queue: " : "Add to queue: ") + (track.getTitle() != null ? track.getTitle() : track.getId())))
                    .bounds(0, 0, ROW_CONTROL_SIZE, ROW_CONTROL_SIZE).build();

            actionBtn = Button.builder(Component.literal(">"), b -> handleAction())
                    .createNarration(ignored -> Component.literal("Action for " + (track.getTitle() != null ? track.getTitle() : track.getId())))
                    .bounds(0, 0, ROW_CONTROL_SIZE, ROW_CONTROL_SIZE).build();
        }

        private void handleAction() {
            if (track.getSourceType() == SourceType.YOUTUBE) {
                var dl = CueMyMusicClient.getDownloader();
                if (dl != null) {
                    String slug = track.getId().startsWith("youtube:") ? track.getId().substring("youtube:".length()) : track.getId();
                    var status = dl.getStatus(slug);
                    if (status == YoutubeDownloader.DownloadStatus.MISSING || status == YoutubeDownloader.DownloadStatus.FAILED) {
                        dl.downloadTrack(slug, () -> Minecraft.getInstance().execute(() -> {
                            try { Minecraft.getInstance().reloadResourcePacks(); } catch (Exception ignored) {}
                            rebuild();
                        }));
                        rebuild();
                        return;
                    }
                }
            }
            previewTrack(track);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(queued, actionBtn);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(queued, actionBtn);
        }

        @Override
        public void visitWidgets(Consumer<AbstractWidget> consumer) {
            consumer.accept(queued);
            consumer.accept(actionBtn);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor gfx, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX();
            int y = getContentY();

            queued.setPosition(x, y);
            queued.extractRenderState(gfx, mouseX, mouseY, partialTick);

            var director = MusicDirector.getInstance();
            var curTrack = director.getCurrentTrack().orElse(null);
            boolean isCurrent = curTrack != null && curTrack.getId().equals(track.getId());
            boolean isPlaying = isCurrent && director.getState() == PlaybackState.PLAYING;

            if (track.getSourceType() == SourceType.YOUTUBE) {
                var dl = CueMyMusicClient.getDownloader();
                String slug = track.getId().startsWith("youtube:") ? track.getId().substring("youtube:".length()) : track.getId();
                var status = dl != null ? dl.getStatus(slug) : YoutubeDownloader.DownloadStatus.MISSING;
                switch (status) {
                    case MISSING -> {
                        actionBtn.setMessage(Component.literal("↓"));
                        actionBtn.active = true;
                    }
                    case DOWNLOADING -> {
                        actionBtn.setMessage(Component.literal("…"));
                        actionBtn.active = false;
                    }
                    case FAILED -> {
                        actionBtn.setMessage(Component.literal("↻"));
                        actionBtn.active = true;
                    }
                    case READY -> {
                        actionBtn.setMessage(Component.literal(isPlaying ? "||" : ">"));
                        actionBtn.active = true;
                    }
                }
            } else {
                actionBtn.setMessage(Component.literal(isPlaying ? "||" : ">"));
                actionBtn.active = true;
            }

            int actionX = getContentRight() - ROW_CONTROL_SIZE - 6;
            actionBtn.setPosition(actionX, y);
            actionBtn.extractRenderState(gfx, mouseX, mouseY, partialTick);

            int textY = y + (ROW_H - 9) / 2;
            int colSourceX = x + 24;
            int colTitleX = x + 68;
            int availableTextW = Math.max(10, actionX - 6 - colTitleX);
            int titleW = (int) (availableTextW * 0.55);
            int artistW = Math.max(10, availableTextW - titleW);
            int colArtistX = colTitleX + titleW;

            Font font = Minecraft.getInstance().font;

            String sourceType = switch (track.getSourceType()) {
                case MUSIC_DISC -> "Disc";
                case YOUTUBE -> "YT";
                default -> "Music";
            };
            gfx.text(font, Component.literal(sourceType), colSourceX, textY, 0xFFAAAAAA);

            String title = track.getTitle() != null ? track.getTitle() : track.getId();
            String ellipsizedTitle = ellipsize(font, title, titleW - 4);
            gfx.text(font, Component.literal(ellipsizedTitle), colTitleX, textY, 0xFFFFFFFF);

            String artist = track.getArtist() != null ? track.getArtist() : "Unknown";
            String ellipsizedArtist = ellipsize(font, artist, artistW - 4);
            gfx.text(font, Component.literal(ellipsizedArtist), colArtistX, textY, 0xFFAAAAAA);
        }
    }
}
