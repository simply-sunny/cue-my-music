package mix.cuemymusic;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.api.CuePanel;
import mix.cuemymusic.api.CuePanels;
import mix.cuemymusic.api.PanelCanvas;
import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.library.Art;
import mix.cuemymusic.library.Catalog;
import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.PackInfo;
import mix.cuemymusic.library.PackNames;
import mix.cuemymusic.library.Track;

/**
 * The player: library sidebar on the left, the open playlist, artist, pack or
 * settings page in the middle, now playing and the queue on the right, and
 * play controls along the bottom. Everything is drawn by hand; clicks and
 * drags hit the regions recorded while drawing the previous frame.
 */
public final class CueScreen extends Screen {
    private static final int BLACK = 0xFF000000;
    private static final int PANEL = 0xFF121212;
    private static final int ROW_HOVER = 0xFF2A2A2A;
    private static final int SELECTED = 0xFF232323;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFFB3B3B3;
    private static final int FAINT = 0xFF6A6A6A;
    private static final int GREEN = 0xFF1ED760;
    private static final int RED = 0xFFFF6B6B;
    private static final int TRACK_BG = 0xFF4D4D4D;
    private static final int GAP = 4;
    private static final int BOTTOM = 42;
    private static final int ROW = 20;
    /** Queue rows are a single line, so more of the queue fits. */
    private static final int QROW = 13;
    private static final Art PLAYLIST_ART = Art.block("block/jukebox_top");
    private static final Art SETTINGS_ART = Art.block("block/crafting_table_top");
    private static final Art STATS_ART = Art.block("block/note_block");
    private static final Art LIKED_ART = new Art.Sprite(Identifier.withDefaultNamespace("hud/heart/full"), 0xFF5A3BD6, 0xFF8FB6E0);
    private static final Art DISLIKED_ART = new Art.Initials("\u2298", 0xFF5A2A2A, 0xFF3A1E1E);
    private static final int LIB_ROW = 12;
    private static final int LIB_SECTION = 14;
    private static final Art NEW_PLAYLIST_ART = new Art.Initials("+", 0xFF2E2E2E, 0xFF242424);
    private static final long TOAST_MILLIS = 1500;

    /** EQ presets, in dB for each band of {@link Equalizer#FREQUENCIES}. */
    private static final Map<String, double[]> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("flat", new double[] {0, 0, 0, 0, 0, 0});
        PRESETS.put("bass_booster", new double[] {6, 4, 1.5, 0, 0, 0});
        PRESETS.put("bass_reducer", new double[] {-6, -4, -1.5, 0, 0, 0});
        PRESETS.put("treble_booster", new double[] {0, 0, 0, 1.5, 4, 6});
        PRESETS.put("treble_reducer", new double[] {0, 0, 0, -1.5, -4, -6});
        PRESETS.put("vocal_booster", new double[] {-2, -1, 1, 4, 3, 0});
        PRESETS.put("small_speakers", new double[] {-6, -2, 1, 2, 2, -1});
    }

    /** PLAYLIST is all game music; LIST is one of the player's own playlists; PANEL is an add-on's page. */
    private enum View { PLAYLIST, LIKED, DISLIKED, LIST, ARTIST, PACK, STATS, SEARCH, SETTINGS, PANEL }

    /** A track row that opens the song menu when right-clicked. */
    private record RowHit(int x0, int y0, int x1, int y1, Track track, int index) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private enum MenuIcon { NONE, PLUS, LIKE, LIKED, DISLIKE, DISLIKED, QUEUE, ARTIST, REMOVE, DELETE }

    /** Adding to a playlist from the menu: the playlist to leave out (the one open), what adding does, and New playlist. */
    private record PlaylistPick(String exclude, Consumer<CueConfig.Playlist> add, Runnable addNew) {
    }

    /** A menu row: its icon, label and what clicking does. A divider has no label; {@code pick} opens the playlist submenu. */
    private record MenuItem(MenuIcon icon, String label, Runnable action, PlaylistPick pick) {
        static final MenuItem DIVIDER = new MenuItem(MenuIcon.NONE, null, null, null);
        /** The submenu's search box. */
        static final MenuItem FIELD = new MenuItem(MenuIcon.NONE, "", null, null);

        MenuItem(String label, Runnable action) {
            this(MenuIcon.NONE, label, action, null);
        }

        MenuItem(MenuIcon icon, String label, Runnable action) {
            this(icon, label, action, null);
        }

        static MenuItem playlists(String label, PlaylistPick pick) {
            return new MenuItem(MenuIcon.PLUS, label, null, pick);
        }
    }

    /** Where an add-on page's text field was drawn, for clicks. */
    private record FieldHit(int x0, int y0, int x1, int y1, String key) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    /** The right-click menu for a song, drawn over everything else. */
    private record Menu(int x, int y, List<MenuItem> items) {
    }

    /** Track list order. Clicking a column heading cycles it through ascending, descending and off. */
    private enum Sort { NONE, TITLE, ALBUM, TIME }

    private record Hit(int x0, int y0, int x1, int y1, Runnable click) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private record Drag(int x0, int y0, int x1, int y1, boolean vertical, DoubleConsumer onFraction, Runnable onRelease) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }

        double fraction(double x, double y) {
            double f = vertical ? 1 - (y - y0) / (double) (y1 - y0) : (x - x0) / (double) (x1 - x0);
            return Math.clamp(f, 0, 1);
        }
    }

    private final Screen parent;
    private final CueConfig cfg = CueConfig.INSTANCE;
    private final CuePlayer player = CuePlayer.INSTANCE;
    private List<Hit> hits = new ArrayList<>();
    private List<Drag> drags = new ArrayList<>();
    private List<Hit> nextHits = new ArrayList<>();
    private List<Drag> nextDrags = new ArrayList<>();
    private Drag dragging;
    private View view = View.PLAYLIST;
    /** The artist name, pack id, or playlist id of the open page. */
    private String key;
    private Sort sort = Sort.NONE;
    private boolean ascending = true;
    private double scroll;
    private int scrollMax;
    private int centerX0, centerY0, centerX1, centerY1;
    private double seekPreview = -1;
    // A pitch, speed or EQ change on its way to the speakers.
    private CueStream.Params applyTarget;
    private long applyStart;
    private String toast;
    private long toastUntil;
    private List<RowHit> rowHits = new ArrayList<>();
    private List<RowHit> nextRowHits = new ArrayList<>();
    private Menu menu;
    /** What's typed in the menu's playlist search, whether that submenu is open, and how far it's scrolled. */
    private String menuFilter = "";
    private boolean subOpen;
    private int subScroll;
    /** The name box while a playlist is being renamed, else null. */
    private EditBox renameBox;
    private String renamingId;
    /** Until when a second click on Delete removes the playlist. */
    private long deleteArmedUntil;
    /** When Reset stats was first clicked; a second click soon after resets. */
    private long resetArmedAt;
    /** What's typed in the search box, kept while other pages are open. */
    private String searchQuery = "";
    /** Puts the cursor in the search box the next time it's drawn. */
    private boolean focusSearch;
    // The mix page: its songs, its title, and the transition being edited (the index of its first song), or -1 for the list.
    /** Whether the page shows its mix (tempos, keys and transitions) in place of its song list. */
    private boolean mixMode;
    private List<Track> mixTracks = List.of();
    /** The transition open in the editor, as its two songs' ids; null for the mix's songs. */
    private String mixFrom, mixTo;
    private int mixEdit = -1;
    /** The title of the page header drawn last, for the mix editor. */
    // An add-on page's text fields, by key, and the one being typed in.
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final Map<String, Consumer<String>> fieldSubmits = new HashMap<>();
    private EditBox focusedField;
    private List<FieldHit> fieldHits = new ArrayList<>();
    private List<FieldHit> nextFieldHits = new ArrayList<>();
    private float partialTick;
    // Selected songs in the page's track list, by sound file id, and the rows a shift-selection runs between.
    private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
    private int anchor = -1, focusRow = -1;
    private boolean selectDragging;
    // A plain press on a playlist row: where, and whether it has become a drag to reorder.
    private RowHit pressed;
    private double pressX, pressY;
    private boolean reordering;
    // The search box in a page's toolbar: whether it's open, and whether to put the cursor in it.
    private boolean pageFilterOpen;
    private boolean focusPageFilter;
    private static final String PAGE_FILTER = "page-filter";
    /** The page's track list as shown last frame, and the y of its first row. */
    private List<Track> shownTracks = List.of();
    private int shownTop;
    // The library's scroll, and where its scrolling part was drawn last frame.
    private double libraryScroll;
    private int libraryScrollMax;
    private int libraryX0, libraryX1, libraryTop, libraryBottom, libraryScrollTop, libraryScrollBottom;

    // Queue panel layout from the last frame, and the row being dragged.
    private int queueX0, queueX1, queueTop, queueRows;
    /** Rows of the queue that fit in the panel, and how far it's scrolled, in rows. */
    private int queueFit;
    private double queueScroll;
    private int queueDrag = -1;
    private double queueDragY;

    public CueScreen(Screen parent) {
        super(Component.translatable("cuemymusic.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MusicLibrary.refresh();
    }

    @Override
    public void onClose() {
        cfg.save();
        StatsFile.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BLACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        nextHits = new ArrayList<>();
        nextDrags = new ArrayList<>();
        nextRowHits = new ArrayList<>();
        nextFieldHits = new ArrayList<>();
        this.partialTick = partialTick;
        if (cfg.vanilla) {
            drawVanilla(g, mouseX, mouseY);
            hits = nextHits;
            drags = nextDrags;
            rowHits = nextRowHits;
            fieldHits = nextFieldHits;
            return;
        }

        int sideW = Math.clamp(width / 5, 92, 150);
        int rightW = width >= 400 ? Math.clamp(width / 5, 96, 150) : 0;
        int top = GAP;
        int bottom = height - BOTTOM;

        drawLibrary(g, GAP, top, GAP + sideW, bottom - GAP, mouseX, mouseY);
        centerX0 = GAP * 2 + sideW;
        centerX1 = width - GAP - (rightW > 0 ? rightW + GAP : 0);
        centerY0 = top;
        centerY1 = bottom - GAP;
        // Dragging a selection or a song to the list's top or bottom edge, or past it, scrolls the list.
        if (selectDragging || reordering) {
            if (mouseY < centerY0 + 10) scroll = Math.max(0, scroll - 6);
            else if (mouseY > centerY1 - 10) scroll = Math.min(scrollMax, scroll + 6);
        }
        drawCenter(g, mouseX, mouseY);
        if (selectDragging) {
            int row = rowAt(mouseY);
            if (row >= 0 && row != focusRow) {
                focusRow = row;
                selectRange(anchor, row);
            }
        }
        if (rightW > 0) {
            drawNowPlaying(g, width - GAP - rightW, top, width - GAP, bottom - GAP, mouseX, mouseY);
        } else {
            queueRows = 0;
            queueFit = 0;
        }
        drawBottomBar(g, 0, bottom, width, height, mouseX, mouseY);
        if (renameBox != null) renameBox.extractRenderState(g, mouseX, mouseY, partialTick);
        drawSelectionBar(g);
        drawReorder(g, mouseX, mouseY);
        drawMenu(g, mouseX, mouseY);
        drawToast(g);

        hits = nextHits;
        drags = nextDrags;
        rowHits = nextRowHits;
        fieldHits = nextFieldHits;
    }

    /** With vanilla music on, the whole window is one card saying so, with a button to turn the mods back on. */
    private void drawVanilla(GuiGraphicsExtractor g, int mx, int my) {
        // Buttons only take clicks inside the centre panel, so here that's the whole window.
        centerX0 = 0;
        centerY0 = 0;
        centerX1 = width;
        centerY1 = height;
        g.fill(0, 0, width, height, 0xFF000000);
        int w = Math.min(300, width - 40), x0 = (width - w) / 2;
        Component body = Component.translatable("cuemymusic.vanilla.body");
        int bodyH = font.wordWrapHeight(body, w - 28);
        int h = 64 + bodyH + 30, y0 = (height - h) / 2;
        g.fill(x0, y0, x0 + w, y0 + h, PANEL);
        bigText(g, tr("cuemymusic.vanilla.title"), x0 + 14, y0 + 14, 2, WHITE);
        g.textWithWordWrap(font, body, x0 + 14, y0 + 40, w - 28, DIM, false);
        Canvas c = new Canvas(g, x0 + 4, y0 + 48 + bodyH, x0 + w - 4, mx, my);
        int bw = c.button(c.left(), tr("cuemymusic.vanilla.off"), PanelCanvas.ButtonStyle.PRIMARY, true, () -> setVanilla(false));
        c.button(c.left() + bw + 6, tr("cuemymusic.vanilla.close"), PanelCanvas.ButtonStyle.SECONDARY, true, this::onClose);
    }

    /** Turns vanilla music on (both mods off: the player stops and the game takes over its music) or off. */
    private void setVanilla(boolean on) {
        if (on) player.stopForVanilla();
        cfg.vanilla = on;
        cfg.save();
        // Back on: the game's own song stops so Cue My Music picks the next one.
        if (!on) minecraft.getMusicManager().stopPlaying();
    }

    // ---------------------------------------------------------------- library

    private void drawLibrary(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        g.fill(x0, y0, x1, y1, PANEL);
        g.text(font, tr("cuemymusic.library"), x0 + 6, y0 + 7, WHITE, false);
        // Search, at the right of the heading.
        int sx = x1 - 15, sy = y0 + 5;
        boolean searchHover = mx >= sx - 3 && mx < sx + 12 && my >= sy - 3 && my < sy + 12;
        searchIcon(g, sx, sy, view == View.SEARCH ? GREEN : searchHover ? WHITE : DIM);
        nextHits.add(new Hit(sx - 3, sy - 3, sx + 12, sy + 12, this::openSearch));

        List<Track> all = MusicLibrary.gameMusic();
        List<String> artists = new ArrayList<>(Catalog.ARTISTS.stream().filter(a -> !MusicLibrary.byArtist(a).isEmpty()).toList());
        if (MusicLibrary.hasUnknown()) artists.add(Catalog.UNKNOWN_ARTIST);
        List<CuePanel> panels = CuePanels.all();
        List<PackInfo> packs = MusicLibrary.packs().stream()
                .filter(p -> panels.stream().noneMatch(panel -> p.id().equals(panel.packId()))).toList();
        List<CueConfig.Playlist> playlists = cfg.playlists;
        List<Track> disliked = disliked();

        // Stats and Settings stay at the bottom; everything above them scrolls.
        int big = y1 - y0 > 200 ? 22 : 14;
        int settingsY = y1 - big - 2;
        int statsY = settingsY - big;
        libraryX0 = x0;
        libraryX1 = x1;
        libraryTop = y0 + 18;
        libraryBottom = statsY - 2;
        g.enableScissor(x0, libraryTop, x1, libraryBottom);
        int y = libraryTop + 2 - (int) libraryScroll;
        int start = y;

        y = libraryItem(g, x0, y, x1, big, PLAYLIST_ART, tr("cuemymusic.playlist.title"),
                tr("cuemymusic.playlist.subtitle", all.size()), view == View.PLAYLIST, mx, my, () -> open(View.PLAYLIST, null));
        y = libraryItem(g, x0, y, x1, big, LIKED_ART, tr("cuemymusic.liked.title"),
                tr("cuemymusic.playlist.subtitle", liked().size()), view == View.LIKED, mx, my, () -> open(View.LIKED, null));
        if (!disliked.isEmpty() || view == View.DISLIKED) {
            y = libraryItem(g, x0, y, x1, big, DISLIKED_ART, tr("cuemymusic.disliked.title"),
                    tr("cuemymusic.playlist.subtitle", disliked.size()), view == View.DISLIKED, mx, my, () -> open(View.DISLIKED, null));
        }
        for (CuePanel panel : panels) {
            y = libraryItem(g, x0, y, x1, big, panel.icon(), panel.title(), panel.subtitle(),
                    view == View.PANEL && panel.id().equals(key), mx, my, () -> open(View.PANEL, panel.id()));
        }

        y += 4;
        y = librarySection(g, x0, y, x1, "artists", tr("cuemymusic.section.artists"), artists.size(), mx, my);
        if (!cfg.isCollapsed("artists")) {
            for (String a : artists) {
                Art art = Art.block(Catalog.ARTIST_ART.get(a).texture());
                y = libraryItem(g, x0, y, x1, LIB_ROW, art, a, null, view == View.ARTIST && a.equals(key), mx, my,
                        () -> open(View.ARTIST, a));
            }
        }
        if (!packs.isEmpty()) {
            y = librarySection(g, x0, y, x1, "packs", tr("cuemymusic.section.packs"), packs.size(), mx, my);
            if (!cfg.isCollapsed("packs")) {
                for (PackInfo p : packs) {
                    y = libraryItem(g, x0, y, x1, LIB_ROW, p.art(), p.name(), null, view == View.PACK && p.id().equals(key), mx, my,
                            () -> open(View.PACK, p.id()));
                }
            }
        }
        y = librarySection(g, x0, y, x1, "playlists", tr("cuemymusic.section.playlists"), playlists.size(), mx, my);
        if (!cfg.isCollapsed("playlists")) {
            for (CueConfig.Playlist p : playlists) {
                y = libraryItem(g, x0, y, x1, LIB_ROW, Art.initials(p.name()), p.name(), null,
                        view == View.LIST && p.id().equals(key), mx, my, () -> open(View.LIST, p.id()));
            }
            y = libraryItem(g, x0, y, x1, LIB_ROW, NEW_PLAYLIST_ART, tr("cuemymusic.playlists.new"), null, false, mx, my,
                    this::newPlaylist);
        }
        g.disableScissor();
        libraryScrollTop = libraryTop;
        libraryScrollBottom = libraryBottom;
        libraryScrollMax = Math.max(0, y - start + 4 - (libraryBottom - libraryTop));
        libraryScroll = Math.clamp(libraryScroll, 0, libraryScrollMax);
        if (libraryScrollMax > 0) {
            // A thin bar showing how far the library is scrolled.
            int track = libraryBottom - libraryTop, bar = Math.max(12, track * track / (track + libraryScrollMax));
            int by = libraryTop + (int) ((track - bar) * libraryScroll / libraryScrollMax);
            g.fill(x1 - 3, by, x1 - 1, by + bar, 0xFF4D4D4D);
        }

        libraryTop = y0;
        libraryBottom = y1;
        libraryItem(g, x0, statsY, x1, big, STATS_ART, tr("cuemymusic.stats.title"), tr("cuemymusic.stats.subtitle"),
                view == View.STATS, mx, my, () -> open(View.STATS, null));
        libraryItem(g, x0, settingsY, x1, big, SETTINGS_ART, tr("cuemymusic.settings"), tr("cuemymusic.settings.subtitle"),
                view == View.SETTINGS, mx, my, () -> open(View.SETTINGS, null));
    }

    /** A section heading that folds its rows away when clicked. Shows the count while folded. */
    private int librarySection(GuiGraphicsExtractor g, int x0, int y, int x1, String id, String label, int count, int mx, int my) {
        boolean folded = cfg.isCollapsed(id);
        boolean hover = libraryHover(x0, y, x1, y + LIB_SECTION, mx, my);
        int color = hover ? WHITE : DIM;
        int ay = y + 5;
        if (folded) {
            for (int i = 0; i < 3; i++) g.fill(x0 + 7 + i, ay + i - 1, x0 + 8 + i, ay + 4 - i, color);
        } else {
            for (int i = 0; i < 3; i++) g.fill(x0 + 6 + i, ay + i, x0 + 11 - i, ay + i + 1, color);
        }
        String text = folded ? label + "  " + count : label;
        g.text(font, clip(text, x1 - x0 - 20), x0 + 15, y + 3, color, false);
        libraryHit(x0, y, x1, y + LIB_SECTION, () -> cfg.toggleCollapsed(id));
        return y + LIB_SECTION;
    }

    private boolean libraryHover(int x0, int y0, int x1, int y1, int mx, int my) {
        return mx >= x0 && mx < x1 && my >= Math.max(y0, libraryTop) && my < Math.min(y1, libraryBottom);
    }

    /** A click area in the library, cut to the part that's scrolled into view. */
    private void libraryHit(int x0, int y0, int x1, int y1, Runnable click) {
        int top = Math.max(y0, libraryTop), bottom = Math.min(y1, libraryBottom);
        if (bottom > top) nextHits.add(new Hit(x0, top, x1, bottom, click));
    }

    /** A library row. With a {@code subtitle} and room for it, two lines; otherwise one compact line. */
    private int libraryItem(GuiGraphicsExtractor g, int x0, int y, int x1, int h, Art art, String title,
            String subtitle, boolean selected, int mx, int my, Runnable click) {
        if (y + h < libraryTop || y > libraryBottom) return y + h;
        boolean hover = libraryHover(x0, y, x1, y + h, mx, my);
        if (selected || hover) g.fill(x0 + 2, y, x1 - 2, y + h, selected ? SELECTED : ROW_HOVER);
        int artSize = Math.min(16, h - 2);
        int ax = subtitle == null ? x0 + 15 : x0 + 5;
        art(g, art, ax, y + (h - artSize) / 2, artSize);
        int tx = ax + artSize + 4;
        int w = x1 - tx - 5;
        if (subtitle != null && h >= 21) {
            g.text(font, clip(title, w), tx, y + 3, selected ? GREEN : WHITE, false);
            g.text(font, clip(subtitle, w), tx, y + 12, DIM, false);
        } else {
            g.text(font, clip(title, w), tx, y + (h - 8) / 2, selected ? GREEN : subtitle == null && !hover ? DIM : WHITE, false);
        }
        libraryHit(x0, y, x1, y + h, click);
        return y + h;
    }

    private void open(View v, String k) {
        finishRename(true);
        clearSelection();
        if (v != view || !java.util.Objects.equals(k, key)) {
            fields.clear();
            fieldSubmits.clear();
            unfocusField();
            pageFilterOpen = false;
            mixMode = false;
            mixFrom = mixTo = null;
        }
        view = v;
        key = k;
        scroll = 0;
    }

    // -------------------------------------------------------------- playlists

    private void newPlaylist() {
        String id = cfg.createPlaylist(tr("cuemymusic.playlists.default_name", cfg.playlists.size() + 1));
        open(View.LIST, id);
        startRename(id);
    }

    private void startRename(String id) {
        CueConfig.Playlist p = cfg.playlist(id);
        if (p == null) return;
        renamingId = id;
        renameBox = new EditBox(font, 0, 0, 120, 14, Component.translatable("cuemymusic.playlists.rename"));
        renameBox.setMaxLength(48);
        renameBox.setValue(p.name());
        renameBox.moveCursorToEnd(false);
        renameBox.setHighlightPos(0);
        renameBox.setFocused(true);
        setFocused(renameBox);
    }

    /** Ends renaming, keeping the typed name if {@code keep} and it isn't blank. */
    private void finishRename(boolean keep) {
        if (renameBox == null) return;
        String name = renameBox.getValue().strip();
        if (keep && !name.isEmpty()) cfg.renamePlaylist(renamingId, name);
        renameBox = null;
        renamingId = null;
        setFocused(null);
    }

    /** Right-click menu for a song, laid out like Spotify's: playlists, liked, queue, then where to go, then removing. */
    private void openMenu(Track t, int x, int y) {
        String song = t.location().toString();
        List<MenuItem> items = new ArrayList<>();
        String exclude = view == View.LIST ? key : null;
        items.add(MenuItem.playlists(tr("cuemymusic.menu.add_to_playlist"), new PlaylistPick(exclude,
                p -> showToast(cfg.addToPlaylist(p.id(), song) ? tr("cuemymusic.playlists.added", p.name()) : tr("cuemymusic.playlists.already", p.name())),
                () -> {
                    String id = cfg.createPlaylist(tr("cuemymusic.playlists.default_name", cfg.playlists.size() + 1));
                    cfg.addToPlaylist(id, song);
                    open(View.LIST, id);
                    startRename(id);
                })));
        boolean liked = isLiked(t);
        items.add(new MenuItem(liked ? MenuIcon.LIKED : MenuIcon.LIKE, tr(liked ? "cuemymusic.menu.unlike" : "cuemymusic.menu.like"), () -> toggleLiked(t)));
        items.add(new MenuItem(MenuIcon.QUEUE, tr("cuemymusic.menu.queue"), () -> {
            player.enqueue(t);
            showToast(tr("cuemymusic.queue.added"));
        }));
        if (MusicLibrary.isGameMusic(t)) {
            boolean disliked = isDisliked(t);
            items.add(new MenuItem(disliked ? MenuIcon.DISLIKED : MenuIcon.DISLIKE,
                    tr(disliked ? "cuemymusic.menu.undislike" : "cuemymusic.menu.dislike"), () -> toggleDisliked(t)));
        }
        if (!t.artist().isBlank() && !(view == View.ARTIST && t.artist().equals(key))) {
            items.add(MenuItem.DIVIDER);
            items.add(new MenuItem(MenuIcon.ARTIST, tr("cuemymusic.menu.go_artist"), () -> open(View.ARTIST, t.artist())));
        }
        List<MenuItem> removing = new ArrayList<>();
        if (view == View.LIST) {
            String listId = key;
            removing.add(new MenuItem(MenuIcon.REMOVE, tr("cuemymusic.menu.remove"), () -> {
                cfg.removeFromPlaylist(listId, song);
                showToast(tr("cuemymusic.playlists.removed"));
            }));
        }
        CuePanel panel = view == View.PANEL ? CuePanels.get(key) : null;
        if (panel != null) {
            for (CuePanel.Action a : panel.songActions(t)) removing.add(new MenuItem(a.label(), a.run()));
            CuePanel.Action delete = panel.deleteAction(List.of(t));
            if (delete != null) removing.add(new MenuItem(MenuIcon.DELETE, delete.label(), delete.run()));
        }
        if (!removing.isEmpty()) {
            items.add(MenuItem.DIVIDER);
            items.addAll(removing);
        }
        showMenu(new Menu(x, y, items));
    }

    private void showMenu(Menu m) {
        menu = m;
        menuFilter = "";
        subOpen = false;
        subScroll = 0;
    }

    private static final int MENU_ROW = 18;
    private static final int MENU_DIVIDER = 7;
    private static final int MENU_PAD = 4;
    private static final int SUB_WIDTH = 160;
    private static final int SUB_ROWS = 8;
    private static final int MENU_BG = 0xFF282828;
    private static final int MENU_HOVER = 0xFF3E3E3E;
    private static final int MENU_LINE = 0xFF3A3A3A;

    /** One row of the menu or its playlist list, where it was laid out. */
    private record MenuRow(int y0, int y1, MenuItem item) {
    }

    private record MenuLayout(int x, int y, int w, int h, List<MenuRow> rows) {
        MenuRow at(double mx, double my) {
            if (mx < x || mx >= x + w) return null;
            for (MenuRow r : rows) {
                if (my >= r.y0() && my < r.y1()) return r;
            }
            return null;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** The menu, at the click, moved in so it stays on screen. */
    private MenuLayout menuLayout() {
        int w = 0, h = MENU_PAD * 2;
        for (MenuItem i : menu.items()) {
            if (i.label() != null) w = Math.max(w, font.width(i.label()) + (i.pick() != null ? 18 : 0));
            h += i.label() == null ? MENU_DIVIDER : MENU_ROW;
        }
        w += 36;
        int x = Math.max(2, Math.min(menu.x(), width - w - 2)), y = Math.max(2, Math.min(menu.y(), height - h - 2));
        List<MenuRow> rows = new ArrayList<>();
        int ry = y + MENU_PAD;
        for (MenuItem i : menu.items()) {
            int rh = i.label() == null ? MENU_DIVIDER : MENU_ROW;
            rows.add(new MenuRow(ry, ry + rh, i));
            ry += rh;
        }
        return new MenuLayout(x, y, w, h, rows);
    }

    /** The playlists the menu's submenu offers, matching what's typed in its search box. */
    private List<CueConfig.Playlist> pickable(PlaylistPick pick) {
        String q = normalize(menuFilter.strip());
        return cfg.playlists.stream()
                .filter(p -> !p.id().equals(pick.exclude()))
                .filter(p -> q.isEmpty() || normalize(p.name()).contains(q))
                .toList();
    }

    /**
     * The playlist submenu beside the menu row that opened it: a search box,
     * New playlist, then the playlists, {@link #SUB_ROWS} at a time.
     */
    private MenuLayout subLayout(MenuLayout main) {
        MenuRow from = null;
        for (MenuRow r : main.rows()) {
            if (r.item().pick() != null) from = r;
        }
        if (from == null) return null;
        PlaylistPick pick = from.item().pick();
        List<CueConfig.Playlist> lists = pickable(pick);
        subScroll = Math.clamp(subScroll, 0, Math.max(0, lists.size() - SUB_ROWS));
        int shown = Math.min(SUB_ROWS, lists.size() - subScroll);
        int h = MENU_PAD * 2 + 20 + MENU_ROW + MENU_DIVIDER + Math.max(1, shown) * MENU_ROW;
        int x = main.x() + main.w() + 2;
        if (x + SUB_WIDTH > width - 2) x = main.x() - SUB_WIDTH - 2;
        int y = Math.max(2, Math.min(from.y0() - MENU_PAD, height - h - 2));
        List<MenuRow> rows = new ArrayList<>();
        int ry = y + MENU_PAD;
        rows.add(new MenuRow(ry, ry + 20, MenuItem.FIELD));
        ry += 20;
        rows.add(new MenuRow(ry, ry + MENU_ROW, new MenuItem(MenuIcon.PLUS, tr("cuemymusic.menu.new_playlist"), pick.addNew())));
        ry += MENU_ROW;
        rows.add(new MenuRow(ry, ry + MENU_DIVIDER, MenuItem.DIVIDER));
        ry += MENU_DIVIDER;
        if (lists.isEmpty()) {
            rows.add(new MenuRow(ry, ry + MENU_ROW, new MenuItem(MenuIcon.NONE, tr("cuemymusic.menu.no_playlists"), null)));
        }
        for (int i = subScroll; i < subScroll + shown; i++) {
            CueConfig.Playlist p = lists.get(i);
            rows.add(new MenuRow(ry, ry + MENU_ROW, new MenuItem(MenuIcon.NONE, p.name(), () -> pick.add().accept(p))));
            ry += MENU_ROW;
        }
        return new MenuLayout(x, y, SUB_WIDTH, h, rows);
    }

    private void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
        if (menu == null) return;
        MenuLayout main = menuLayout();
        MenuLayout sub = subOpen ? subLayout(main) : null;
        // Hovering a row opens or closes the submenu; moving into the submenu keeps it open.
        MenuRow over = main.at(mx, my);
        if (over != null && over.item().label() != null && (sub == null || !sub.contains(mx, my))) {
            boolean open = over.item().pick() != null;
            if (open != subOpen) {
                subOpen = open;
                sub = open ? subLayout(main) : null;
            }
        }
        drawMenuBox(g, main);
        for (MenuRow r : main.rows()) {
            MenuItem i = r.item();
            if (i.label() == null) {
                g.fill(main.x() + 6, r.y0() + 3, main.x() + main.w() - 6, r.y0() + 4, MENU_LINE);
                continue;
            }
            boolean hover = r == over || (i.pick() != null && subOpen);
            if (hover) g.fill(main.x() + 3, r.y0(), main.x() + main.w() - 3, r.y1(), MENU_HOVER);
            menuIcon(g, i.icon(), main.x() + 9, r.y0() + 4, i.icon() == MenuIcon.LIKED ? GREEN : WHITE);
            g.text(font, i.label(), main.x() + 26, r.y0() + 5, i.icon() == MenuIcon.DELETE ? RED : WHITE, false);
            if (i.pick() != null) {
                int ax = main.x() + main.w() - 11, ay = r.y0() + 5;
                for (int k = 0; k < 4; k++) g.fill(ax + k, ay + k, ax + k + 1, ay + 8 - k, WHITE);
            }
        }
        if (sub == null) return;
        drawMenuBox(g, sub);
        MenuRow subOver = sub.at(mx, my);
        for (MenuRow r : sub.rows()) {
            MenuItem i = r.item();
            if (i == MenuItem.FIELD) {
                g.fill(sub.x() + 4, r.y0() + 1, sub.x() + sub.w() - 4, r.y1() - 2, 0xFF3A3A3A);
                searchIcon(g, sub.x() + 8, r.y0() + 5, DIM);
                boolean empty = menuFilter.isEmpty();
                String text = empty ? tr("cuemymusic.menu.find_playlist") : clip(menuFilter, sub.w() - 34);
                g.text(font, text, sub.x() + 22, r.y0() + 6, empty ? FAINT : WHITE, false);
                if (System.currentTimeMillis() / 500 % 2 == 0) {
                    int cx = sub.x() + 22 + (empty ? 0 : font.width(text));
                    g.fill(cx, r.y0() + 5, cx + 1, r.y0() + 14, WHITE);
                }
            } else if (i.label() == null) {
                g.fill(sub.x() + 6, r.y0() + 3, sub.x() + sub.w() - 6, r.y0() + 4, MENU_LINE);
            } else {
                if (r == subOver && i.action() != null) g.fill(sub.x() + 3, r.y0(), sub.x() + sub.w() - 3, r.y1(), MENU_HOVER);
                int tx = sub.x() + (i.icon() == MenuIcon.NONE ? 9 : 26);
                if (i.icon() != MenuIcon.NONE) menuIcon(g, i.icon(), sub.x() + 9, r.y0() + 4, WHITE);
                g.text(font, clip(i.label(), sub.x() + sub.w() - 8 - tx), tx, r.y0() + 5, i.action() == null ? FAINT : WHITE, false);
            }
        }
        // Where the list is when there are more playlists than fit.
        List<CueConfig.Playlist> lists = pickable(main.rows().stream().filter(r -> r.item().pick() != null).findFirst().orElseThrow().item().pick());
        if (lists.size() > SUB_ROWS) {
            int top = sub.rows().get(3).y0(), bottom = sub.y() + sub.h() - MENU_PAD, barH = bottom - top;
            int thumbH = Math.max(6, barH * SUB_ROWS / lists.size());
            int thumbY = top + (barH - thumbH) * subScroll / (lists.size() - SUB_ROWS);
            g.fill(sub.x() + sub.w() - 4, thumbY, sub.x() + sub.w() - 2, thumbY + thumbH, 0xFF6A6A6A);
        }
    }

    private static void drawMenuBox(GuiGraphicsExtractor g, MenuLayout l) {
        int x = l.x(), y = l.y(), w = l.w(), h = l.h();
        // Rounded by knocking the corners off, with a soft shadow.
        g.fill(x + 2, y + 3, x + w + 3, y + h + 3, 0x66000000);
        g.fill(x + 1, y, x + w - 1, y + h, MENU_BG);
        g.fill(x, y + 1, x + w, y + h - 1, MENU_BG);
    }

    /** A 9×9 icon for a menu row. */
    private static void menuIcon(GuiGraphicsExtractor g, MenuIcon icon, int x, int y, int color) {
        switch (icon) {
            case NONE -> {
            }
            case PLUS -> plusIcon(g, x, y, color);
            case LIKE -> heartIcon(g, x + 1, y + 2, false, color);
            case LIKED -> heartIcon(g, x + 1, y + 2, true, color);
            case DISLIKE -> dislikeIcon(g, x, y, false, color);
            case DISLIKED -> dislikeIcon(g, x, y, true, color);
            case QUEUE -> pixels(g, x, y, color, ".X.......", "XXX.XXXXX", ".X.......", ".........", "XXXXXXXXX", ".........", ".........",
                    "XXXXXXXXX", ".........");
            case ARTIST -> pixels(g, x, y, color, "...XXX...", "..X...X..", "..X...X..", "..X...X..", "...XXX...", ".........",
                    ".XXXXXXX.", "X.......X", "X.......X");
            case REMOVE -> pixels(g, x, y, color, "..XXXXX..", ".X.....X.", "X.......X", "X.......X", "X.XXXXX.X", "X.......X",
                    "X.......X", ".X.....X.", "..XXXXX..");
            case DELETE -> pixels(g, x, y, RED, "...XXX...", "XXXXXXXXX", ".X.....X.", ".X.X.X.X.", ".X.X.X.X.", ".X.X.X.X.",
                    ".X.X.X.X.", ".X.....X.", "..XXXXX..");
        }
    }

    private static void pixels(GuiGraphicsExtractor g, int x, int y, int color, String... rows) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == 'X') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }

    /** A click while the menu is open: runs the row under it, or opens the playlist submenu. Anywhere else closes it. */
    private void menuClicked(double mx, double my) {
        MenuLayout main = menuLayout();
        MenuLayout sub = subOpen ? subLayout(main) : null;
        if (sub != null && sub.contains(mx, my)) {
            MenuRow r = sub.at(mx, my);
            if (r != null && r.item().action() != null && r.item() != MenuItem.FIELD) {
                menu = null;
                r.item().action().run();
            }
            return;
        }
        MenuRow r = main.at(mx, my);
        if (r == null) {
            menu = null;
            return;
        }
        if (r.item().pick() != null) {
            subOpen = true;
        } else if (r.item().action() != null) {
            menu = null;
            r.item().action().run();
        }
    }

    /** Typing while the menu is open searches its playlists. */
    private boolean menuTyped(CharacterEvent event) {
        if (menuLayout().rows().stream().noneMatch(r -> r.item().pick() != null) || !event.isAllowedChatCharacter()) return false;
        subOpen = true;
        menuFilter += event.codepointAsString();
        subScroll = 0;
        return true;
    }

    /** Scrolls the playlist submenu when the wheel is over it. */
    private boolean menuScrolled(double mx, double my, double amount) {
        MenuLayout sub = subOpen ? subLayout(menuLayout()) : null;
        if (sub == null || !sub.contains(mx, my)) return false;
        subScroll -= (int) Math.signum(amount);
        return true;
    }

    // ----------------------------------------------------------------- center

    private void drawCenter(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = centerX0, y0 = centerY0, x1 = centerX1, y1 = centerY1;
        shownTracks = List.of();
        g.fill(x0, y0, x1, y1, PANEL);
        g.enableScissor(x0, y0, x1, y1);
        int contentTop = y0 - (int) scroll;
        PackInfo pack = view == View.PACK ? MusicLibrary.pack(key) : null;
        if (view == View.PACK && pack == null) view = View.PLAYLIST;
        CuePanel panel = view == View.PANEL ? CuePanels.get(key) : null;
        if (view == View.PANEL && panel == null) view = View.PLAYLIST;
        int contentBottom = switch (view) {
            case SETTINGS -> drawSettings(g, x0, contentTop, x1, mx, my);
            case STATS -> drawStats(g, x0, contentTop, x1, mx, my);
            case SEARCH -> drawSearch(g, x0, contentTop, x1, mx, my);
            case PANEL -> {
                Canvas canvas = new Canvas(g, x0, contentTop, x1, mx, my);
                try {
                    panel.draw(canvas);
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger("cuemymusic").error("The {} panel failed to draw", panel.id(), e);
                    view = View.PLAYLIST;
                }
                yield canvas.y + 8;
            }
            case ARTIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, "artist:" + key, MusicLibrary.songsBy(key),
                    Art.block(Catalog.ARTIST_ART.getOrDefault(key, Catalog.UNKNOWN_ALBUM).texture()), tr("cuemymusic.artist"),
                    key, null, artistColor(key));
            case PACK -> drawTrackPage(g, x0, contentTop, x1, mx, my, "pack:" + key, MusicLibrary.byPack(key), pack.art(),
                    tr("cuemymusic.pack"), pack.name(), pack.author(), PackNames.gradient(pack.name())[1]);
            case PLAYLIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, CuePlayer.ALL, MusicLibrary.gameMusic(), PLAYLIST_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.playlist.title"), null, 0xFF1E3A2A);
            case LIKED -> drawTrackPage(g, x0, contentTop, x1, mx, my, "liked", liked(), LIKED_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.liked.title"), null, 0xFF3A2E6E);
            case DISLIKED -> drawTrackPage(g, x0, contentTop, x1, mx, my, "disliked", disliked(), DISLIKED_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.disliked.title"), tr("cuemymusic.disliked.byline"), 0xFF4A2424);
            case LIST -> {
                CueConfig.Playlist p = cfg.playlist(key);
                if (p == null) {
                    view = View.PLAYLIST;
                    yield contentTop;
                }
                yield drawTrackPage(g, x0, contentTop, x1, mx, my, "list:" + p.id(), MusicLibrary.tracks(p.songs()),
                        Art.initials(p.name()), tr("cuemymusic.playlist"), p.name(), null, PackNames.gradient(p.name())[1]);
            }
        };
        g.disableScissor();
        scrollMax = Math.max(0, contentBottom + (int) scroll - y1 + 8);
        scroll = Math.clamp(scroll, 0, scrollMax);
    }

    /** A list of songs. {@code listKey} names it to the player, so its big button knows when it's the one playing. */
    private int drawTrackPage(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String listKey, List<Track> unsorted,
            Art art, String kind, String title, String byline, int color) {
        double total = unsorted.stream().mapToDouble(t -> Double.isNaN(t.seconds()) ? 0 : t.seconds()).sum();
        String summary = tr("cuemymusic.page.summary", unsorted.size(), longDuration(total));
        if (byline != null) summary = tr("cuemymusic.page.byline", byline, summary);
        y = drawHeader(g, x0, y, x1, art, kind, title, summary, color);
        return drawTrackList(g, x0, y, x1, mx, my, listKey, unsorted);
    }

    /** What's typed in the page's search box, or "" when it's closed. */
    private String pageFilter() {
        EditBox box = fields.get(PAGE_FILTER);
        return pageFilterOpen && box != null ? box.getValue() : "";
    }

    /**
     * The page's search, between {@code left} and {@code right}: a magnifier, or once clicked,
     * a box that filters the songs shown as you type. It closes when it's empty
     * and you click elsewhere or press Esc.
     */
    private void drawPageFilter(GuiGraphicsExtractor g, int left, int right, int y, int mx, int my) {
        if (!pageFilterOpen) {
            int ix = right - 10;
            boolean hover = mx >= ix - 3 && mx < right + 3 && my >= y && my < y + 18;
            searchIcon(g, ix, y + 5, hover ? WHITE : DIM);
            nextHits.add(new Hit(ix - 3, y, right + 3, y + 18, () -> {
                pageFilterOpen = true;
                focusPageFilter = true;
            }));
            return;
        }
        // As wide as fits between the toolbar's other buttons, up to 140.
        int w = Math.min(140, right - left);
        if (w < 40) {
            pageFilterOpen = false;
            return;
        }
        Canvas c = new Canvas(g, right - w - Canvas.PAD, y, right + Canvas.PAD, mx, my);
        c.textField(PAGE_FILTER, Component.translatable("cuemymusic.page.filter"), w, text -> unfocusField());
        EditBox box = fields.get(PAGE_FILTER);
        if (focusPageFilter) {
            focusPageFilter = false;
            unfocusField();
            focusedField = box;
            box.setFocused(true);
            setFocused(box);
        } else if (box != focusedField && box.getValue().isEmpty()) {
            pageFilterOpen = false;
        }
    }

    /** A page's header: art, kind, big title, and a summary line. Returns the y below it. */
    private int drawHeader(GuiGraphicsExtractor g, int x0, int y, int x1, Art art, String kind, String title, String summary,
            int color) {
        int headerH = 70;
        g.fillGradient(x0, y, x1, y + headerH, color, PANEL);
        art(g, art, x0 + 10, y + 12, 48);
        int tx = x0 + 66;
        g.text(font, kind, tx, y + 14, WHITE, false);
        if (view == View.LIST && renameBox != null) {
            // Renaming: the name box sits where the title was.
            renameBox.setX(tx);
            renameBox.setY(y + 27);
            renameBox.setWidth(Math.max(60, x1 - tx - 12));
        } else {
            bigText(g, clip(title, (x1 - tx - 8) / 2), tx, y + 26, 2, WHITE);
            if (view == View.LIST) {
                String id = key;
                nextHits.add(new Hit(tx, Math.max(y + 24, centerY0), x1 - 8, y + 44, () -> startRename(id)));
            }
        }
        g.text(font, clip(summary, x1 - tx - 8), tx, y + 48, DIM, false);
        return y + headerH;
    }

    /** The big play button, add all to queue, the column headings, and the songs. Returns the y below them. */
    private int drawTrackList(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String listKey, List<Track> unsorted) {
        String filter = pageFilter();
        List<String> filterTerms = terms(filter);
        List<Track> tracks = sorted(unsorted).stream()
                .filter(t -> filterTerms.isEmpty() || matches(normalize(t.title() + " " + t.artist() + " " + t.album()), filterTerms))
                .toList();
        int toolRight = x1 - 10;

        // Big play button for the whole list: pauses and resumes it once it's the one playing.
        int pb = 18;
        boolean thisList = player.current() != null && player.isPlayingList(listKey);
        roundButton(g, x0 + 10, y + 6, pb, GREEN);
        if (thisList && player.isPlaying()) pauseIcon(g, x0 + 10 + 6, y + 6 + 5, 8, BLACK);
        else playIcon(g, x0 + 10 + 7, y + 6 + 5, 8, BLACK);
        nextHits.add(new Hit(x0 + 10, y + 6, x0 + 10 + pb, y + 6 + pb, () -> {
            if (thisList) player.togglePause();
            else if (!tracks.isEmpty()) player.play(listKey, tracks, 0);
        }));
        // A playlist can be renamed or deleted; delete takes a second click.
        if (view == View.LIST) {
            String id = key;
            String del = tr(System.currentTimeMillis() < deleteArmedUntil ? "cuemymusic.playlists.delete_confirm" : "cuemymusic.playlists.delete");
            String ren = tr("cuemymusic.playlists.rename");
            int dx = x1 - 10 - font.width(del), rx = dx - 12 - font.width(ren), ay = y + 11;
            toolRight = rx - 14;
            boolean delHover = mx >= dx - 2 && mx < x1 - 8 && my >= ay - 3 && my < ay + 11;
            boolean renHover = mx >= rx - 2 && mx < dx - 10 && my >= ay - 3 && my < ay + 11;
            g.text(font, ren, rx, ay, renHover ? WHITE : DIM, false);
            g.text(font, del, dx, ay, System.currentTimeMillis() < deleteArmedUntil ? RED : delHover ? WHITE : DIM, false);
            nextHits.add(new Hit(rx - 2, ay - 3, dx - 10, ay + 11, () -> startRename(id)));
            nextHits.add(new Hit(dx - 2, ay - 3, x1 - 8, ay + 11, () -> {
                if (System.currentTimeMillis() < deleteArmedUntil) {
                    deleteArmedUntil = 0;
                    cfg.deletePlaylist(id);
                    open(View.PLAYLIST, null);
                    showToast(tr("cuemymusic.playlists.deleted"));
                } else {
                    deleteArmedUntil = System.currentTimeMillis() + 3000;
                }
            }));
        }
        // Add the whole list to the queue, in the order shown.
        int toolLeft = x0 + 10 + pb;
        if (!tracks.isEmpty()) {
            int qx = x0 + 10 + pb + 10, qy = y + 6;
            String label = tr("cuemymusic.queue.add_all");
            int qw = 13 + font.width(label);
            // Mix: shows the transitions in place of the list, once two songs next to each other can blend.
            boolean canMix = false;
            for (int i = 0; i + 1 < tracks.size() && !canMix; i++) canMix = blends(tracks, i);
            if (!canMix) mixMode = false;
            String mixLabel = tr("cuemymusic.mix.open");
            int chipsRight = qx + qw + (canMix ? 14 + font.width(mixLabel) + 12 : 0);
            // Without room for an open search box, it takes these buttons' place until it closes.
            if (!pageFilterOpen || toolRight - chipsRight - 12 >= 100) {
                boolean qHover = mx >= qx - 2 && mx < qx + qw + 2 && my >= qy && my < qy + pb;
                plusIcon(g, qx, qy + 5, qHover ? WHITE : DIM);
                g.text(font, label, qx + 13, qy + 6, qHover ? WHITE : DIM, false);
                nextHits.add(new Hit(qx - 2, qy, qx + qw + 2, qy + pb, () -> {
                    player.enqueueAll(tracks);
                    showToast(tr("cuemymusic.queue.added_all", tracks.size()));
                }));
                toolLeft = qx + qw;
                if (canMix) {
                    toolLeft += 14 + chip(g, qx + qw + 14, qy + 3, mixLabel, mixMode, mx, my, () -> {
                        mixMode = !mixMode;
                        mixFrom = mixTo = null;
                        clearSelection();
                        scroll = 0;
                    });
                }
            }
        } else {
            mixMode = false;
        }
        drawPageFilter(g, toolLeft + 12, toolRight, y + 6, mx, my);
        y += pb + 12;
        if (mixMode) {
            mixTracks = tracks;
            mixEdit = -1;
            for (int i = 0; mixFrom != null && i + 1 < tracks.size(); i++) {
                if (id(tracks.get(i)).equals(mixFrom) && id(tracks.get(i + 1)).equals(mixTo) && blends(i)) {
                    mixEdit = i;
                    break;
                }
            }
            if (mixEdit < 0) mixFrom = mixTo = null;
            return mixEdit >= 0 ? drawTransitionEditor(g, x0, y, x1, mx, my, listKey) : drawMix(g, x0, y, x1, mx, my, listKey);
        }

        // Column headings; title, album and time sort.
        boolean showAlbum = x1 - x0 > 260;
        int colTitle = x0 + 26;
        int colAlbum = x0 + (x1 - x0) * 55 / 100;
        int colTime = x1 - 10;
        g.text(font, "#", x0 + 10, y, DIM, false);
        heading(g, tr("cuemymusic.column.title"), colTitle, y, Sort.TITLE, mx, my);
        if (showAlbum) heading(g, tr("cuemymusic.column.album"), colAlbum, y, Sort.ALBUM, mx, my);
        boolean timeHover = mx >= colTime - 18 && mx < colTime + 2 && my >= y - 2 && my < y + 10;
        clockIcon(g, colTime - 7, y, sort == Sort.TIME ? GREEN : timeHover ? WHITE : DIM);
        if (sort == Sort.TIME) arrow(g, colTime - 15, y + 1, ascending, GREEN);
        nextHits.add(new Hit(colTime - 18, y - 2, colTime + 2, y + 10, () -> cycleSort(Sort.TIME)));
        y += 11;
        g.fill(x0 + 8, y, x1 - 8, y + 1, 0xFF2A2A2A);
        y += 4;
        if (tracks.isEmpty() && !filterTerms.isEmpty()) {
            g.text(font, clip(tr("cuemymusic.page.filter_none", filter.strip()), x1 - x0 - 20), x0 + 10, y + 6, DIM, false);
            y += 20;
        }

        shownTracks = tracks;
        shownTop = y;
        Track current = player.current();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            int index = i;
            boolean visible = y + ROW > centerY0 && y < centerY1;
            if (visible) {
                boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + ROW && my >= centerY0 && my < centerY1;
                boolean isCurrent = t.equals(current);
                boolean isSelected = selected.contains(t.location().toString());
                if (isSelected) g.fill(x0 + 4, y, x1 - 4, y + ROW, hover ? 0xFF3A4A40 : 0xFF2C3A31);
                else if (hover) g.fill(x0 + 4, y, x1 - 4, y + ROW, ROW_HOVER);
                if (isCurrent && player.isPlaying()) equalizerIcon(g, x0 + 9, y + 6, GREEN);
                else g.text(font, Integer.toString(i + 1), x0 + 10, y + 6, isCurrent ? GREEN : DIM, false);
                art(g, t.art(), colTitle, y + 2, 16);
                int textX = colTitle + 20;
                int titleW = (showAlbum ? colAlbum - 6 : colTime - 60) - textX;
                boolean disliked = isDisliked(t);
                g.text(font, clip(t.title(), titleW), textX, y + 2, isCurrent ? GREEN : disliked ? FAINT : WHITE, false);
                g.text(font, clip(t.artist(), titleW), textX, y + 11, DIM, false);
                // Stops short of the dislike, heart and + when they show.
                int albumEnd = hover || disliked ? colTime - 76 : isLiked(t) ? colTime - 60 : colTime - 48;
                if (showAlbum) g.text(font, clip(t.album(), albumEnd - colAlbum), colAlbum, y + 6, DIM, false);
                String len = Double.isNaN(t.seconds()) ? "-:--" : time(t.seconds());
                g.text(font, len, colTime - font.width(len), y + 6, DIM, false);
                int top = Math.max(y, centerY0), bottom = Math.min(y + ROW, centerY1);
                nextHits.add(new Hit(x0, top, x1, bottom, () -> player.play(listKey, tracks, index)));
                nextRowHits.add(new RowHit(x0, top, x1, bottom, t, index));
                // Like: always shown once liked, otherwise on hover, left of add to queue.
                boolean liked = isLiked(t);
                // Dislike: always shown once disliked, otherwise on hover, left of the heart. Only for songs the game plays.
                if ((hover || disliked) && MusicLibrary.isGameMusic(t)) {
                    int dx = colTime - 72, dy = y + 5;
                    boolean dislikeHover = hover && mx >= dx - 2 && mx < dx + 11 && my >= dy - 2 && my < dy + 11;
                    dislikeIcon(g, dx, dy, disliked, disliked ? RED : dislikeHover ? WHITE : DIM);
                    if (hover) nextHits.add(new Hit(dx - 2, Math.max(dy - 2, centerY0), dx + 11, Math.min(dy + 11, centerY1),
                            () -> toggleDisliked(t)));
                }
                if (hover || liked) {
                    int hx = colTime - 56, hy = y + 6;
                    boolean heartHover = hover && mx >= hx - 2 && mx < hx + 9 && my >= hy - 2 && my < hy + 8;
                    heartIcon(g, hx, hy, liked, liked ? GREEN : heartHover ? WHITE : DIM);
                    if (hover) nextHits.add(new Hit(hx - 2, Math.max(hy - 2, centerY0), hx + 9, Math.min(hy + 8, centerY1),
                            () -> toggleLiked(t)));
                }
                if (hover) {
                    // Add to queue, just left of the length.
                    int px = colTime - 40, py = y + 4;
                    boolean plusHover = mx >= px - 2 && mx < px + 11 && my >= py - 2 && my < py + 11;
                    plusIcon(g, px, py, plusHover ? WHITE : DIM);
                    nextHits.add(new Hit(px - 2, Math.max(py - 2, centerY0), px + 11, Math.min(py + 11, centerY1), () -> {
                        player.enqueue(t);
                        showToast(tr("cuemymusic.queue.added"));
                    }));
                }
            }
            y += ROW;
        }
        if (tracks.isEmpty() && (view == View.LIKED || view == View.LIST || view == View.DISLIKED)) {
            Component hint = view == View.LIKED ? Component.translatable("cuemymusic.liked.empty")
                    : view == View.DISLIKED ? Component.translatable("cuemymusic.disliked.empty")
                    : Component.translatable("cuemymusic.playlists.empty");
            g.textWithWordWrap(font, hint, x0 + 10, y + 4, x1 - x0 - 20, DIM, false);
            y += font.wordWrapHeight(hint, x1 - x0 - 20) + 4;
        }
        return y + 8;
    }

    private List<Track> liked() {
        return MusicLibrary.liked(cfg.liked);
    }

    private List<Track> disliked() {
        return MusicLibrary.tracks(cfg.disliked);
    }

    private boolean isDisliked(Track t) {
        return cfg.isDisliked(t.location().toString());
    }

    private void toggleDisliked(Track t) {
        showToast(tr(player.toggleDisliked(t) ? "cuemymusic.disliked.added" : "cuemymusic.disliked.removed"));
    }

    private boolean isLiked(Track t) {
        return cfg.isLiked(t.location().toString());
    }

    private void toggleLiked(Track t) {
        boolean was = isLiked(t);
        cfg.toggleLiked(t.location().toString());
        showToast(tr(was ? "cuemymusic.liked.removed" : "cuemymusic.liked.added"));
    }

    private void heading(GuiGraphicsExtractor g, String label, int x, int y, Sort column, int mx, int my) {
        int w = font.width(label);
        boolean hover = mx >= x - 2 && mx < x + w + 10 && my >= y - 2 && my < y + 10;
        boolean active = sort == column;
        g.text(font, label, x, y, active ? GREEN : hover ? WHITE : DIM, false);
        if (active) arrow(g, x + w + 3, y + 1, ascending, GREEN);
        nextHits.add(new Hit(x - 2, y - 2, x + w + 10, y + 10, () -> cycleSort(column)));
    }

    private void cycleSort(Sort column) {
        if (sort != column) {
            sort = column;
            ascending = true;
        } else if (ascending) {
            ascending = false;
        } else {
            sort = Sort.NONE;
        }
    }

    private List<Track> sorted(List<Track> tracks) {
        Comparator<Track> by = switch (sort) {
            case NONE -> null;
            case TITLE -> Comparator.comparing(t -> t.title().toLowerCase());
            case ALBUM -> Comparator.comparing(t -> t.album().toLowerCase());
            case TIME -> Comparator.comparingDouble(t -> Double.isNaN(t.seconds()) ? Double.MAX_VALUE : t.seconds());
        };
        if (by == null) return tracks;
        // Stable sort, so tracks with the same album keep their listed order.
        return tracks.stream().sorted(ascending ? by : by.reversed()).toList();
    }

    // -------------------------------------------------------------------- mix

    private static final int MIX_PURPLE = 0xFFB18CFF;

    // Waveform and curve colours, as in Spotify's mix editor: bass blue, mids orange, highs white;
    // the volume curve light blue and the bass swap yellow.
    private static final int WAVE_LOW = 0xFF2F6BFF;
    private static final int WAVE_MID = 0xFFFF8A2A;
    private static final int WAVE_HIGH = 0xFFF2F2F2;
    private static final int CURVE_VOLUME = 0xFF8CCBFF;
    private static final int CURVE_EQ = 0xFFFFD84A;

    /** True if songs {@code i} and {@code i + 1} of the mix blend, or would once both are analysed. */
    private boolean blends(int i) {
        return blends(mixTracks, i);
    }

    /** True if songs {@code i} and {@code i + 1} of {@code tracks} blend, or would once both are analysed. */
    private static boolean blends(List<Track> tracks, int i) {
        Track a = tracks.get(i), b = tracks.get(i + 1);
        if (!AutomixProfiles.applies(a) || !AutomixProfiles.applies(b) || a.equals(b)) return false;
        Automix.Profile pa = AutomixProfiles.get(a), pb = AutomixProfiles.get(b);
        return pa == null || pb == null || pa.blendsWith(pb);
    }

    /**
     * A page's mix, in place of its song list: the songs in order, each with
     * its tempo and key, and the transition between each pair that blends.
     * Songs that don't (the game's own) are dimmed and play as they are.
     */
    private int drawMix(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String listKey) {
        int transitions = 0;
        for (int i = 0; i + 1 < mixTracks.size(); i++) {
            if (blends(i)) transitions++;
        }
        int left = x0 + 10, right = x1 - 10;
        int cw = chip(g, left, y, tr(cfg.automix ? "cuemymusic.mix.on" : "cuemymusic.mix.off"), cfg.automix, mx, my, () -> {
            cfg.automix = !cfg.automix;
            cfg.save();
        });
        String summary = tr("cuemymusic.mix.summary", mixTracks.size(), transitions);
        g.text(font, clip(summary, right - left - cw - 10), left + cw + 10, y + 3, DIM, false);
        y += 20;
        if (!cfg.automix) {
            g.text(font, clip(tr("cuemymusic.mix.off.hint"), right - left), left, y, DIM, false);
            y += 14;
        }

        // Songs, and between two that blend a line down with the transition's chip.
        int rowH = 30, art = 24, lineX = left + art / 2;
        for (int i = 0; i < mixTracks.size(); i++) {
            Track t = mixTracks.get(i);
            boolean hover = mx >= left && mx < right && my >= y && my < y + rowH && my >= centerY0 && my < centerY1;
            if (hover) g.fill(left - 4, y, right + 4, y + rowH, ROW_HOVER);
            art(g, t.art(), left, y + 3, art);
            boolean current = t.equals(player.current());
            int textRight = right - mixRowInfo(g, t, right, y + 11);
            boolean mixes = AutomixProfiles.applies(t);
            g.text(font, clip(t.title(), textRight - left - art - 16), left + art + 8, y + 5, current ? GREEN : mixes ? WHITE : FAINT, false);
            g.text(font, clip(t.artist(), textRight - left - art - 16), left + art + 8, y + 17, mixes ? DIM : FAINT, false);
            int index = i, top = Math.max(y, centerY0), bottom = Math.min(y + rowH, centerY1);
            if (bottom > top) nextHits.add(new Hit(left - 4, top, right + 4, bottom, () -> player.play(listKey, mixTracks, index)));
            y += rowH;
            if (i + 1 >= mixTracks.size()) break;
            if (!blends(i)) {
                y += 4;
                continue;
            }
            int gap = 20;
            g.fill(lineX, y, lineX + 1, y + gap, 0xFF5A5A5A);
            Automix.Transition tn = cfg.transition(id(t), id(mixTracks.get(i + 1)));
            String label = (tn.preset() == Automix.Preset.AUTO ? "✦ " : "") + presetName(tn.preset()) + "  ›";
            String from = id(t), to = id(mixTracks.get(i + 1));
            chip(g, lineX + 10, y + 3, label, false, mx, my, () -> {
                mixFrom = from;
                mixTo = to;
            });
            y += gap;
        }
        return y + 10;
    }

    /** A song's tempo, key and length at the right of its row, ending at {@code right}. Returns their width. */
    private int mixRowInfo(GuiGraphicsExtractor g, Track t, int right, int y) {
        String length = Double.isNaN(t.seconds()) ? "" : time(t.seconds());
        int x = right - font.width(length);
        g.text(font, length, x, y, DIM, false);
        Automix.Profile p = AutomixProfiles.applies(t) ? AutomixProfiles.get(t) : null;
        if (p == null) return right - x + 8;
        x -= 10;
        if (!p.key().isEmpty()) x -= keyBadge(g, p.key(), x, y - 3, true) + 6;
        if (p.bpm() > 0) {
            String bpm = tr("cuemymusic.mix.bpm", Math.round(p.bpm()));
            x -= font.width(bpm);
            g.text(font, bpm, x, y, FAINT, false);
        }
        return right - x + 8;
    }

    /**
     * A Camelot key ("5A") in a badge coloured by its place on the wheel, so
     * keys that mix well (the same number, or one either side) look alike.
     * With {@code alignRight} it ends at {@code x}. Returns its width.
     */
    private int keyBadge(GuiGraphicsExtractor g, String key, int x, int y, boolean alignRight) {
        int w = font.width(key) + 8;
        if (alignRight) x -= w;
        int n;
        try {
            n = Integer.parseInt(key.substring(0, key.length() - 1));
        } catch (NumberFormatException e) {
            n = 1;
        }
        boolean minor = key.endsWith("A");
        g.fill(x + 1, y, x + w - 1, y + 13, wheelColour(n, minor));
        g.fill(x, y + 1, x + w, y + 12, wheelColour(n, minor));
        g.text(font, key, x + 4, y + 3, BLACK, false);
        return w;
    }

    /** The colour of Camelot number {@code n}: hues around the wheel, minor keys a little deeper. */
    private static int wheelColour(int n, boolean minor) {
        float h = ((n - 1) % 12) / 12f, s = minor ? 0.55f : 0.4f, v = minor ? 0.9f : 1f;
        int i = (int) (h * 6);
        float f = h * 6 - i, p = v * (1 - s), q = v * (1 - f * s), u = v * (1 - (1 - f) * s);
        float[] rgb = switch (i % 6) {
            case 0 -> new float[] {v, u, p};
            case 1 -> new float[] {q, v, p};
            case 2 -> new float[] {p, v, u};
            case 3 -> new float[] {p, q, v};
            case 4 -> new float[] {u, p, v};
            default -> new float[] {v, p, q};
        };
        return 0xFF000000 | Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
    }

    private static String presetName(Automix.Preset p) {
        return tr("cuemymusic.mix.preset." + p.name().toLowerCase(Locale.ROOT));
    }

    private static String partName(Enum<?> part) {
        return tr("cuemymusic.mix." + part.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT) + "."
                + part.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The editor for transition {@link #mixEdit}: both songs' waveforms around
     * it with the volume and bass curves, a preview button, the presets, the
     * parts as drop-downs, and the timing.
     */
    private int drawTransitionEditor(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String listKey) {
        Track a = mixTracks.get(mixEdit), b = mixTracks.get(mixEdit + 1);
        String from = id(a), to = id(b);
        int left = x0 + 12, right = x1 - 12;
        chip(g, left, y, "‹  " + tr("cuemymusic.mix.back"), false, mx, my, () -> mixFrom = mixTo = null);
        y += 22;
        g.text(font, tr("cuemymusic.mix.transition"), left, y, WHITE, false);
        y += 14;
        Automix.Profile pa = AutomixProfiles.get(a), pb = AutomixProfiles.get(b);
        if (pa == null || pb == null) {
            g.text(font, tr("cuemymusic.mix.analysing"), left, y, DIM, false);
            return y + 20;
        }
        Automix.Transition t = cfg.transition(from, to);
        Automix.Plan plan = Automix.plan(pa, pb, t);
        Automix.Shape shape = plan.shape();

        // The two songs, one above the other, lined up on the transition; preview between them.
        double aEnd = pa.seconds(pa.end()), start = pa.seconds(plan.fadeStart()), len = pa.seconds(plan.fadeFrames());
        double enter = pb.seconds(plan.enterAt());
        double span = Math.max(20, len * 2.2);
        double t0 = start + len / 2 - span / 2;
        int laneH = 44, gapH = 26, laneA = y + 14, laneB = laneA + laneH + gapH;
        mixSongLabel(g, a, pa, left, y, right);
        drawLane(g, pa, shape, true, start, len, t0, span, 0, left, laneA, right, laneH);
        drawLane(g, pb, shape, false, start, len, t0, span, enter - start, left, laneB, right, laneH);
        mixSongLabel(g, b, pb, left, laneB + laneH + 3, right);
        int ox0 = timeX(start, t0, span, left, right), ox1 = timeX(start + len, t0, span, left, right);
        g.fill(ox0, laneA, ox0 + 1, laneB + laneH, 0x66FFFFFF);
        g.fill(ox1 - 1, laneA, ox1, laneB + laneH, 0x66FFFFFF);
        // Where the music is now, if it's in this stretch.
        Track now = player.current();
        double nowTau = now == null ? Double.NaN : now.equals(a) ? player.position() : now.equals(b) ? start + player.position() - enter : Double.NaN;
        if (nowTau >= t0 && nowTau <= t0 + span) {
            int px = timeX(nowTau, t0, span, left, right);
            g.fill(px, laneA - 2, px + 1, laneB + laneH + 2, WHITE);
        }
        // Preview: from a few seconds before the transition.
        int pbs = 20, pbx = (left + right) / 2 - pbs / 2, pby = laneA + laneH + (gapH - pbs) / 2;
        boolean previewing = now != null && (now.equals(a) || now.equals(b)) && player.isPlayingList(listKey) && player.isPlaying();
        roundButton(g, pbx, pby, pbs, WHITE);
        if (previewing) pauseIcon(g, pbx + 7, pby + 6, 8, BLACK);
        else playIcon(g, pbx + 8, pby + 6, 8, BLACK);
        int edit = mixEdit;
        nextHits.add(new Hit(pbx, pby, pbx + pbs, pby + pbs, () -> {
            if (previewing) {
                player.togglePause();
            } else if (cfg.automix) {
                player.play(listKey, mixTracks, edit);
                player.seek(Math.max(0, start - 5));
            } else {
                showToast(tr("cuemymusic.mix.off.hint"));
            }
        }));
        // Drag along either song to move where the transition starts.
        double ts0 = t0;
        for (int lane : new int[] {laneA, laneB}) {
            nextDrags.add(new Drag(left, lane, right, lane + laneH, false, f -> {
                double at = ts0 + f * span - len / 2;
                cfg.setTransition(from, to, cfg.transition(from, to).withStart(Math.clamp(Math.round((aEnd - at) * 10) / 10.0, 0, 60)));
            }, cfg::save));
        }
        y = laneB + laneH + 18;

        // Presets.
        g.text(font, tr("cuemymusic.mix.presets"), left, y, DIM, false);
        y += 13;
        int cx = left;
        for (Automix.Preset p : Automix.Preset.values()) {
            if (p == Automix.Preset.CUSTOM && t.preset() != Automix.Preset.CUSTOM) continue;
            String label = p == Automix.Preset.AUTO
                    ? "✦ " + (t.preset() == Automix.Preset.AUTO ? tr("cuemymusic.mix.preset.auto_is", presetName(plan.preset())) : presetName(p))
                    : presetName(p);
            int w = font.width(label) + 12;
            if (cx > left && cx + w > right) {
                cx = left;
                y += 17;
            }
            cx += chip(g, cx, y, label, t.preset() == p, mx, my, () -> {
                if (p != Automix.Preset.CUSTOM) {
                    cfg.setTransition(from, to, cfg.transition(from, to).withPreset(p));
                    cfg.save();
                }
            }) + 5;
        }
        y += 18;
        String desc = t.preset() == Automix.Preset.AUTO ? tr("cuemymusic.mix.preset.auto.desc")
                : tr("cuemymusic.mix.preset." + plan.preset().name().toLowerCase(Locale.ROOT) + ".desc");
        g.text(font, clip(desc, right - left), left, y, FAINT, false);
        y += 18;

        // The parts, each a drop-down; changing one makes the transition custom.
        y = partRow(g, left, y, right, mx, my, tr("cuemymusic.mix.part.volume"), shape.volume(), Automix.Volume.values(),
                v -> shape.withVolume((Automix.Volume) v), from, to);
        y = partRow(g, left, y, right, mx, my, tr("cuemymusic.mix.part.eq"), shape.eq(), Automix.Eq.values(),
                v -> shape.withEq((Automix.Eq) v), from, to);
        y = partRow(g, left, y, right, mx, my, tr("cuemymusic.mix.part.filter"), shape.filter(), Automix.Filter.values(),
                v -> shape.withFilter((Automix.Filter) v), from, to);
        y = partRow(g, left, y, right, mx, my, tr("cuemymusic.mix.part.effects"), shape.effect(), Automix.Effect.values(),
                v -> shape.withEffect((Automix.Effect) v), from, to);
        // Songs aren't sped up or slowed down to meet, so there's no beatmatching to turn on.
        g.text(font, tr("cuemymusic.mix.part.beatmatch"), left, y + 3, FAINT, false);
        String none = tr("cuemymusic.mix.beatmatch.unavailable");
        g.text(font, none, right - font.width(none), y + 3, FAINT, false);
        y += 24;

        // Timing.
        boolean cut = shape.volume() == Automix.Volume.CUT;
        double most = cut ? 4 : 40;
        double autoLen = cut ? 1 : shape.volume() == Automix.Volume.DROP ? Automix.dropSeconds(pa) : Automix.autoSeconds(pa);
        // In bars too, as DJs count, when the tempo is known.
        String lenText = tr("cuemymusic.mix.seconds", String.format("%.1f", len));
        if (pa.bpm() > 0 && !cut) lenText = tr("cuemymusic.mix.seconds_bars", String.format("%.1f", len), String.format("%.1f", len * pa.bpm() / 240).replace(".0", ""));
        y = sliderRow(g, left, y, right, tr("cuemymusic.mix.length"), lenText,
                (len - 0.5) / (most - 0.5), f -> cfg.setTransition(from, to,
                        cfg.transition(from, to).withSeconds(Math.round((0.5 + f * (most - 0.5)) * 10) / 10.0)),
                (autoLen - 0.5) / (most - 0.5), -1);
        y = sliderRow(g, left, y, right, tr("cuemymusic.mix.start"), tr("cuemymusic.mix.before_end", String.format("%.1f", aEnd - start)),
                (aEnd - start) / 60, f -> cfg.setTransition(from, to, cfg.transition(from, to).withStart(Math.round(f * 600) / 10.0)),
                len / 60, -1);
        double bLen = Math.min(60, pb.seconds(pb.frames()));
        y = sliderRow(g, left, y, right, tr("cuemymusic.mix.enter"), tr("cuemymusic.mix.seconds_in", String.format("%.1f", enter)),
                enter / bLen, f -> cfg.setTransition(from, to, cfg.transition(from, to).withEnter(Math.round(f * bLen * 10) / 10.0)),
                pb.seconds(pb.start()) / bLen, -1);

        String info = tr("cuemymusic.mix.loudness", String.format("%+.1f", 20 * Math.log10(pa.gain())),
                String.format("%+.1f", 20 * Math.log10(pb.gain())));
        g.text(font, clip(info, right - left), left, y, FAINT, false);
        y += 14;
        Canvas c = new Canvas(g, left - 10, y, right + 10, mx, my);
        c.button(left, tr("cuemymusic.mix.reset"), PanelCanvas.ButtonStyle.SECONDARY, !t.equals(Automix.Transition.AUTO), () -> {
            cfg.setTransition(from, to, Automix.Transition.AUTO);
            cfg.save();
        });
        return y + c.buttonHeight() + 10;
    }

    /** A song's title over its lane, with its tempo and key at the right. */
    private void mixSongLabel(GuiGraphicsExtractor g, Track t, Automix.Profile p, int left, int y, int right) {
        int x = right;
        if (!p.key().isEmpty()) x -= keyBadge(g, p.key(), x, y - 2, true) + 6;
        if (p.bpm() > 0) {
            String bpm = tr("cuemymusic.mix.bpm", Math.round(p.bpm()));
            x -= font.width(bpm);
            g.text(font, bpm, x, y + 1, FAINT, false);
        }
        g.text(font, clip(t.title(), x - left - 8), left, y + 1, WHITE, false);
    }

    /**
     * One song's lane: its waveform in three colours, dimmed as its volume
     * falls, with the volume curve over it and, for a bass swap, the bass curve.
     * {@code offset} is the song's time at the lane's time 0 less that time,
     * so the next song lines up with the first.
     */
    private void drawLane(GuiGraphicsExtractor g, Automix.Profile p, Automix.Shape shape, boolean outgoing, double start, double len,
            double t0, double span, double offset, int x0, int y, int x1, int h) {
        g.fill(x0, y, x1, y + h, 0xFF181818);
        int mid = y + h / 2, half = h / 2 - 1;
        double step = span / (x1 - x0);
        int lastVol = -1, lastEq = -1;
        for (int x = x0; x < x1; x++) {
            double tau = t0 + (x - x0 + 0.5) * step, songT = tau + offset;
            double prog = (tau - start) / len;
            double vol = outgoing ? (prog <= 0 ? 1 : Automix.volumeOut(shape, prog)) : (prog <= 0 ? 0 : Automix.volumeIn(shape, prog));
            double bass = outgoing ? (prog <= 0 ? 1 : Automix.bassOut(shape, prog)) : (prog <= 0 ? 0 : Automix.bassIn(shape, prog));
            if (songT < 0 || songT > p.seconds(p.frames())) continue;
            float lo = band(p.low(), songT, step), md = band(p.mid(), songT, step), hi = band(p.high(), songT, step);
            float shade = (float) (0.3 + 0.7 * Math.max(vol, 0));
            // Bass widest and behind, the mids over it, the highs on top as a thin core.
            int hl = (int) Math.min(half, lo * half * 1.3), hm = (int) Math.min(half, md * half * 1.3), hh = (int) Math.min(half, hi * half * 1.6);
            int lowCol = shade(WAVE_LOW, shade * (float) (0.4 + 0.6 * bass));
            if (hl > 0) g.fill(x, mid - hl, x + 1, mid + hl + 1, lowCol);
            if (hm > 0) g.fill(x, mid - hm, x + 1, mid + hm + 1, shade(WAVE_MID, shade));
            if (hh > 0) g.fill(x, mid - hh / 2, x + 1, mid + hh / 2 + 1, shade(WAVE_HIGH, shade));
            if (prog >= 0 && prog <= 1) {
                int vy = y + h - 2 - (int) Math.round(vol * (h - 4));
                if (lastVol >= 0) g.fill(x, Math.min(vy, lastVol), x + 1, Math.max(vy, lastVol) + 2, CURVE_VOLUME);
                lastVol = vy;
                if (shape.eq() != Automix.Eq.NONE) {
                    int ey = y + h - 2 - (int) Math.round(bass * (h - 4));
                    if (lastEq >= 0) g.fill(x, Math.min(ey, lastEq), x + 1, Math.max(ey, lastEq) + 1, CURVE_EQ);
                    lastEq = ey;
                }
            }
        }
        g.fill(x0, mid, x1, mid + 1, 0x22FFFFFF);
    }

    private static int shade(int argb, float f) {
        int r = (int) (((argb >> 16) & 0xFF) * f), gg = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }

    /** The loudest point of one waveform band over {@code step} seconds from {@code seconds}. */
    private static float band(float[] wave, double seconds, double step) {
        int i0 = (int) Math.floor(seconds * Automix.WAVE_RATE), i1 = (int) Math.ceil((seconds + step) * Automix.WAVE_RATE);
        float most = 0;
        for (int i = Math.max(0, i0); i < Math.min(wave.length, Math.max(i0 + 1, i1)); i++) most = Math.max(most, wave[i]);
        return most;
    }

    /** A row with a part's name, and its value as a drop-down of the choices. */
    private int partRow(GuiGraphicsExtractor g, int left, int y, int right, int mx, int my, String label, Enum<?> value, Enum<?>[] choices,
            java.util.function.Function<Enum<?>, Automix.Shape> with, String from, String to) {
        g.text(font, label, left, y + 4, WHITE, false);
        String shown = partName(value) + "  ▾";
        int w = font.width(shown) + 14, bx = right - w;
        boolean hover = mx >= bx && mx < right && my >= y && my < y + 15 && my >= centerY0 && my < centerY1;
        g.fill(bx, y, right, y + 15, hover ? 0xFF3A3A3A : 0xFF2A2A2A);
        g.text(font, shown, bx + 7, y + 4, WHITE, false);
        int top = Math.max(y, centerY0), bottom = Math.min(y + 15, centerY1);
        if (bottom > top) {
            nextHits.add(new Hit(bx, top, right, bottom, () -> {
                List<MenuItem> items = new ArrayList<>();
                for (Enum<?> c : choices) {
                    items.add(new MenuItem((c == value ? "✔ " : "    ") + partName(c), () -> {
                        cfg.setTransition(from, to, cfg.transition(from, to).withShape(with.apply(c)));
                        cfg.save();
                    }));
                }
                showMenu(new Menu(bx, y + 16, items));
            }));
        }
        return y + 20;
    }

    private static int timeX(double seconds, double t0, double span, int x0, int x1) {
        return x0 + (int) Math.round((seconds - t0) / span * (x1 - x0));
    }

    /** A small rounded label button. Returns its width. */
    private int chip(GuiGraphicsExtractor g, int x, int y, String label, boolean on, int mx, int my, Runnable click) {
        int w = font.width(label) + 12;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 13 && my >= centerY0 && my < centerY1;
        g.fill(x, y, x + w, y + 13, on ? WHITE : hover ? 0xFF3A3A3A : 0xFF2A2A2A);
        g.text(font, label, x + 6, y + 3, on ? BLACK : WHITE, false);
        int top = Math.max(y, centerY0), bottom = Math.min(y + 13, centerY1);
        if (bottom > top) nextHits.add(new Hit(x, top, x + w, bottom, click));
        return w;
    }

    // ----------------------------------------------------------------- search

    private static final String SEARCH_FIELD = "search";

    private void openSearch() {
        open(View.SEARCH, null);
        focusSearch = true;
    }

    /** A search box, the artists whose names match, and every song whose title, artist or album does. */
    private int drawSearch(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        Canvas c = new Canvas(g, x0, y, x1, mx, my);
        bigText(g, tr("cuemymusic.search.title"), c.left(), y + 10, 2, WHITE);
        c.y += 36;
        boolean fresh = !fields.containsKey(SEARCH_FIELD);
        // Enter plays the results from the top.
        String typed = c.textField(SEARCH_FIELD, Component.translatable("cuemymusic.search.hint"), c.right() - c.left(), text -> {
            List<Track> songs = searchSongs(text);
            if (!songs.isEmpty()) player.play("search:" + text, songs, 0);
        });
        if (fresh && !searchQuery.isEmpty()) c.setText(SEARCH_FIELD, searchQuery);
        else searchQuery = typed;
        List<Track> found = searchSongs(searchQuery);
        if (focusSearch) {
            focusSearch = false;
            EditBox box = fields.get(SEARCH_FIELD);
            unfocusField();
            focusedField = box;
            box.setFocused(true);
            setFocused(box);
        }
        c.space(c.buttonHeight() + 12);

        List<String> terms = terms(searchQuery);
        List<String> artists = MusicLibrary.all().stream().map(Track::artist).distinct()
                .filter(a -> terms.isEmpty() || matches(normalize(a), terms)).sorted(String.CASE_INSENSITIVE_ORDER).limit(24).toList();
        if (terms.isEmpty()) {
            c.text(Component.translatable("cuemymusic.search.empty"), DIM);
            c.space(6);
        } else if (artists.isEmpty() && found.isEmpty()) {
            c.text(Component.translatable("cuemymusic.search.none", searchQuery.strip()), DIM);
            return c.y;
        }
        if (!artists.isEmpty()) {
            c.heading(tr(terms.isEmpty() ? "cuemymusic.search.browse" : "cuemymusic.search.artists"));
            c.y = artistChips(g, c.left(), c.y, c.right(), mx, my, artists) + 10;
        }
        if (!found.isEmpty()) {
            c.heading(tr("cuemymusic.search.songs", found.size()));
            c.y -= 6;
            c.tracks("search:" + searchQuery, found);
        }
        return c.y;
    }

    /** Artists as clickable chips, wrapping onto new lines. Returns the y below them. */
    private int artistChips(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, List<String> artists) {
        int cx = x0, h = 20;
        for (String artist : artists) {
            String name = clip(artist, x1 - x0 - 34);
            int w = 30 + font.width(name);
            if (cx > x0 && cx + w > x1) {
                cx = x0;
                y += h + 4;
            }
            boolean hover = mx >= cx && mx < cx + w && my >= y && my < y + h && my >= centerY0 && my < centerY1;
            g.fill(cx, y, cx + w, y + h, hover ? 0xFF333333 : 0xFF242424);
            art(g, artistArt(artist), cx + 2, y + 2, 16);
            g.text(font, name, cx + 22, y + 6, hover ? WHITE : DIM, false);
            int top = Math.max(y, centerY0), bottom = Math.min(y + h, centerY1);
            if (bottom > top) nextHits.add(new Hit(cx, top, cx + w, bottom, () -> open(View.ARTIST, artist)));
            cx += w + 4;
        }
        return y + h;
    }

    /**
     * Songs matching every word of {@code query} in their title, artist or album. Titles that
     * start with the query come first, then titles that contain it, then the rest.
     */
    private static List<Track> searchSongs(String query) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        String whole = String.join(" ", terms);
        List<Track> found = new ArrayList<>();
        for (Track t : MusicLibrary.all()) {
            if (matches(normalize(t.title() + " " + t.artist() + " " + t.album()), terms)) found.add(t);
        }
        found.sort(Comparator.comparingInt((Track t) -> {
            String title = normalize(t.title());
            return title.startsWith(whole) ? 0 : title.contains(whole) ? 1 : normalize(t.artist()).contains(whole) ? 2 : 3;
        }).thenComparing(Track::title, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    private static List<String> terms(String query) {
        String q = normalize(query).strip();
        return q.isEmpty() ? List.of() : List.of(q.split("\\s+"));
    }

    private static boolean matches(String text, List<String> terms) {
        for (String term : terms) {
            if (!text.contains(term)) return false;
        }
        return true;
    }

    /** Lower case, without accents, so "beyonce" finds "Beyoncé". */
    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static Art artistArt(String artist) {
        return Catalog.ARTIST_ART.containsKey(artist) ? Art.block(Catalog.ARTIST_ART.get(artist).texture()) : Art.initials(artist);
    }

    /** A 9×9 magnifying glass. */
    private static void searchIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        String[] rows = {".XXXX....", "X....X...", "X....X...", "X....X...", "X....X...", ".XXXX....", ".....XX..", "......XX.", ".......XX"};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 9; c++) {
                if (rows[r].charAt(c) == 'X') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }

    // ------------------------------------------------------------------ stats

    /** A Minecraft day, sunrise to sunrise. */
    private static final double MINECRAFT_DAY = 20 * 60;
    /** C418's Sweden is 3:35. */
    private static final double SWEDEN = 215;
    /** Calendar cells from no listening to the most in a day. */
    private static final int[] HEAT = {0xFF242424, 0xFF0E4429, 0xFF006D32, 0xFF26A641, 0xFF39D353};
    private static final int GAME_BLUE = 0xFF5A8DEE;
    private static final int GOLD = 0xFFFFC94A;
    private static final int ARTIST_PURPLE = 0xFF9B7BFF;
    private static final long RESET_MILLIS = 3000;

    private int drawStats(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        ListeningStats s = ListeningStats.INSTANCE;
        String summary = s.isEmpty() ? tr("cuemymusic.stats.summary.empty")
                : tr("cuemymusic.stats.summary", longDuration(s.totalSeconds()),
                        Instant.ofEpochMilli(s.since()).atZone(ZoneId.systemDefault()).toLocalDate()
                                .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())));
        y = drawHeader(g, x0, y, x1, STATS_ART, tr("cuemymusic.stats.kind"), tr("cuemymusic.stats.title"), summary, 0xFF1E3A5A) + 8;
        int left = x0 + 12, right = x1 - 12, w = right - left;
        if (s.isEmpty()) {
            Component empty = Component.translatable("cuemymusic.stats.empty");
            g.textWithWordWrap(font, empty, left, y, w, DIM, false);
            return y + font.wordWrapHeight(empty, w) + 8;
        }
        LocalDate today = LocalDate.now();
        Map<String, ListeningStats.Song> songs = s.songs();

        y = statTiles(g, left, y, right, s, songs.size(), today) + 8;
        Component fun = Component.translatable("cuemymusic.stats.fun", String.format("%.1f", s.totalSeconds() / MINECRAFT_DAY),
                String.format("%.1f", s.totalSeconds() / SWEDEN));
        g.textWithWordWrap(font, fun, left, y, w, FAINT, false);
        y += font.wordWrapHeight(fun, w) + 14;

        // Two columns when there's room, one under the other when there isn't.
        boolean wide = w >= 300;
        int clockW = 128;
        if (wide) {
            int a = topSongs(g, left, y, right - clockW - 16, mx, my, s, songs);
            int b = listeningClock(g, right - clockW, y, right, s);
            y = Math.max(a, b) + 14;
        } else {
            y = topSongs(g, left, y, right, mx, my, s, songs) + 14;
            y = listeningClock(g, left + (w - clockW) / 2, y, left + (w + clockW) / 2, s) + 14;
        }
        y = calendar(g, left, y, right, mx, my, s, today) + 14;
        if (wide) {
            int half = (w - 16) / 2;
            int a = topArtists(g, left, y, left + half, songs);
            int b = whereAndWho(g, left + half + 16, y, right, s);
            y = Math.max(a, b) + 14;
        } else {
            y = topArtists(g, left, y, right, songs) + 14;
            y = whereAndWho(g, left, y, right, s) + 14;
        }

        Canvas c = new Canvas(g, x0, y, x1, mx, my);
        Track skipped = statsTrack(s.mostSkipped());
        if (skipped != null) {
            c.heading(tr("cuemymusic.stats.skipped"));
            art(g, skipped.art(), left, c.y, 16);
            String count = tr("cuemymusic.stats.skips", songs.get(skipped.location().toString()).skips);
            int tw = right - left - 22 - font.width(count) - 8;
            g.text(font, clip(skipped.title(), tw), left + 22, c.y, WHITE, false);
            g.text(font, clip(skipped.artist(), tw), left + 22, c.y + 9, DIM, false);
            g.text(font, count, right - font.width(count), c.y + 4, DIM, false);
            c.y += 22;
            if (MusicLibrary.isGameMusic(skipped) && !isDisliked(skipped)) {
                c.text(Component.translatable("cuemymusic.stats.skipped.hint"), FAINT);
                c.space(2);
                c.button(left, tr("cuemymusic.menu.dislike"), PanelCanvas.ButtonStyle.SECONDARY, true, () -> toggleDisliked(skipped));
                c.space(c.buttonHeight());
            }
            c.space(14);
        }
        boolean armed = System.currentTimeMillis() - resetArmedAt < RESET_MILLIS;
        c.button(left, tr(armed ? "cuemymusic.stats.reset.confirm" : "cuemymusic.stats.reset"),
                armed ? PanelCanvas.ButtonStyle.DANGER : PanelCanvas.ButtonStyle.SECONDARY, true, () -> {
                    if (System.currentTimeMillis() - resetArmedAt < RESET_MILLIS) {
                        resetArmedAt = 0;
                        ListeningStats.INSTANCE.reset();
                        StatsFile.save();
                        showToast(tr("cuemymusic.stats.reset.done"));
                    } else {
                        resetArmedAt = System.currentTimeMillis();
                    }
                });
        return c.y + c.buttonHeight();
    }

    /** The song a stats id names, or null if it's gone (its pack was taken out). */
    private static Track statsTrack(String id) {
        Identifier location = id == null ? null : Identifier.tryParse(id);
        return location == null ? null : MusicLibrary.get(location);
    }

    /** Four tiles: time, plays, songs, and the streak. Two rows of two when narrow. */
    private int statTiles(GuiGraphicsExtractor g, int x0, int y, int x1, ListeningStats s, int songCount, LocalDate today) {
        String[] values = {longDuration(s.totalSeconds()), Integer.toString(s.totalPlays()), Integer.toString(songCount),
                Integer.toString(s.currentStreak(today))};
        String[] labels = {tr("cuemymusic.stats.tile.time"), tr("cuemymusic.stats.tile.plays"), tr("cuemymusic.stats.tile.songs"),
                tr("cuemymusic.stats.tile.streak", s.longestStreak())};
        int[] colors = {GREEN, WHITE, WHITE, 0xFFFFB347};
        int cols = x1 - x0 >= 300 ? 4 : 2, gap = 6, th = 36;
        int tw = (x1 - x0 - gap * (cols - 1)) / cols;
        for (int i = 0; i < 4; i++) {
            int tx = x0 + (i % cols) * (tw + gap), ty = y + (i / cols) * (th + gap);
            g.fill(tx, ty, tx + tw, ty + th, 0xFF1C1C1C);
            g.fill(tx, ty, tx + 2, ty + th, colors[i]);
            float scale = font.width(values[i]) * 1.5f <= tw - 14 ? 1.5f : 1;
            bigText(g, clip(values[i], (int) ((tw - 14) / scale)), tx + 8, ty + 7, scale, colors[i]);
            g.text(font, clip(labels[i], tw - 14), tx + 8, ty + 23, DIM, false);
        }
        return y + (4 / cols) * (th + gap) - gap;
    }

    /** The five most played songs, each with a bar for its plays. Click one to play the five from there. */
    private int topSongs(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, ListeningStats s,
            Map<String, ListeningStats.Song> songs) {
        g.text(font, tr("cuemymusic.stats.top_songs"), x0, y, WHITE, false);
        y += 14;
        List<Track> top = new ArrayList<>();
        for (String id : s.top(20)) {
            Track t = statsTrack(id);
            if (t != null && top.size() < 5) top.add(t);
        }
        if (top.isEmpty()) {
            g.text(font, tr("cuemymusic.stats.none"), x0, y, FAINT, false);
            return y + 12;
        }
        int max = songs.get(top.get(0).location().toString()).plays;
        for (int i = 0; i < top.size(); i++) {
            Track t = top.get(i);
            int plays = songs.get(t.location().toString()).plays;
            int ry = y + i * 22;
            boolean hover = mx >= x0 && mx < x1 && my >= ry && my < ry + 20 && my >= centerY0 && my < centerY1;
            if (hover) g.fill(x0 - 2, ry, x1 + 2, ry + 20, ROW_HOVER);
            String rank = Integer.toString(i + 1);
            g.text(font, rank, x0 + 4 - font.width(rank) / 2, ry + 6, i == 0 ? GOLD : DIM, false);
            art(g, t.art(), x0 + 12, ry + 2, 16);
            int tx = x0 + 32;
            String label = hover ? t.artist() : tr("cuemymusic.stats.plays", plays);
            int lw = font.width(label);
            g.text(font, clip(t.title(), x1 - tx - lw - 6), tx, ry + 2, i == 0 ? GREEN : WHITE, false);
            g.text(font, clip(label, x1 - tx), x1 - Math.min(lw, x1 - tx), ry + 2, DIM, false);
            int bw = (int) Math.round((x1 - tx) * plays / (double) Math.max(1, max));
            g.fill(tx, ry + 13, x1, ry + 16, 0xFF262626);
            g.fill(tx, ry + 13, tx + bw, ry + 16, i == 0 ? GREEN : 0xFF1F7A43);
            int index = i, top0 = Math.max(ry, centerY0), bottom = Math.min(ry + 20, centerY1);
            if (bottom > top0) nextHits.add(new Hit(x0, top0, x1, bottom, () -> player.play("stats", top, index)));
        }
        return y + top.size() * 22;
    }

    /**
     * A 24-hour clock with a petal for each hour, as long as the listening in that
     * hour. Midnight is at the top. The busiest hour is green, and a white dot on
     * the rim is the time now.
     */
    private int listeningClock(GuiGraphicsExtractor g, int x0, int y, int x1, ListeningStats s) {
        g.text(font, tr("cuemymusic.stats.clock"), x0, y, WHITE, false);
        y += 16;
        double[] hours = s.hours();
        double max = 0;
        for (double h : hours) max = Math.max(max, h);
        int peak = s.peakHour();
        int r = Math.min(50, (x1 - x0) / 2 - 12), inner = Math.max(8, r / 4);
        int cx = (x0 + x1) / 2, cy = y + r + 10;
        for (int h = 0; h < 24; h++) {
            int len = max > 0 ? (int) Math.round((r - inner) * hours[h] / max) : 0;
            int color = h == peak ? GREEN : 0xFF2E7D4F;
            if (len == 0) {
                double a = (h + 0.5) / 24 * 2 * Math.PI - Math.PI / 2;
                int px = cx + (int) Math.round(Math.cos(a) * inner), py = cy + (int) Math.round(Math.sin(a) * inner);
                g.fill(px, py, px + 1, py + 1, 0xFF3A3A3A);
                continue;
            }
            // Three rays across the hour make a petal.
            for (int k = -1; k <= 1; k++) {
                double a = (h + 0.5 + k * 0.28) / 24 * 2 * Math.PI - Math.PI / 2;
                double cos = Math.cos(a), sin = Math.sin(a);
                int reach = inner + (k == 0 ? len : len * 4 / 5);
                for (int d = inner; d <= reach; d++) {
                    int px = cx + (int) Math.round(cos * d), py = cy + (int) Math.round(sin * d);
                    g.fill(px, py, px + 2, py + 2, color);
                }
            }
        }
        // The rim: a dot every hour, bigger every six.
        for (int h = 0; h < 24; h++) {
            double a = h / 24.0 * 2 * Math.PI - Math.PI / 2;
            int px = cx + (int) Math.round(Math.cos(a) * (r + 4)), py = cy + (int) Math.round(Math.sin(a) * (r + 4));
            int size = h % 6 == 0 ? 2 : 1;
            g.fill(px, py, px + size, py + size, FAINT);
        }
        g.text(font, "0", cx - font.width("0") / 2 + 1, cy - r - 13, FAINT, false);
        g.text(font, "12", cx - font.width("12") / 2 + 1, cy + r + 7, FAINT, false);
        g.text(font, "6", cx + r + 8, cy - 3, FAINT, false);
        g.text(font, "18", cx - r - 8 - font.width("18"), cy - 3, FAINT, false);
        LocalTime now = LocalTime.now();
        double a = (now.getHour() + now.getMinute() / 60.0) / 24 * 2 * Math.PI - Math.PI / 2;
        int nx = cx + (int) Math.round(Math.cos(a) * (r + 4)), ny = cy + (int) Math.round(Math.sin(a) * (r + 4));
        g.fill(nx - 1, ny - 1, nx + 2, ny + 2, WHITE);

        y = cy + r + 20;
        if (peak >= 0) {
            String type = tr("cuemymusic.stats.type." + (peak < 5 || peak >= 22 ? "night" : peak < 11 ? "morning"
                    : peak < 17 ? "day" : "evening"));
            String when = tr("cuemymusic.stats.peak", LocalTime.of(peak, 0).format(DateTimeFormatter.ofPattern("h a", Locale.getDefault())));
            g.text(font, type, cx - font.width(type) / 2, y, GREEN, false);
            g.text(font, clip(when, x1 - x0), cx - Math.min(font.width(when), x1 - x0) / 2, y + 11, DIM, false);
            y += 22;
        }
        return y;
    }

    /** A cell per day for the last weeks, Monday at the top, greener the more was heard. Hover a day for its time. */
    private int calendar(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, ListeningStats s, LocalDate today) {
        g.text(font, tr("cuemymusic.stats.calendar"), x0, y, WHITE, false);
        y += 14;
        int cell = 7, step = 9, labelW = 12;
        int weeks = Math.clamp((x1 - x0 - labelW) / step, 4, 30);
        LocalDate first = today.minusDays(today.getDayOfWeek().getValue() - 1).minusWeeks(weeks - 1);
        double max = 0;
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) max = Math.max(max, s.day(d));
        Locale locale = Locale.getDefault();
        int gx = x0 + labelW, gy = y + 11;
        LocalDate hovered = null;
        int monthEnd = Integer.MIN_VALUE;
        for (int w = 0; w < weeks; w++) {
            LocalDate monday = first.plusWeeks(w);
            int cx = gx + w * step;
            if ((w == 0 || monday.getMonth() != monday.minusWeeks(1).getMonth()) && cx >= monthEnd) {
                String month = monday.getMonth().getDisplayName(TextStyle.SHORT, locale);
                g.text(font, month, cx, y, FAINT, false);
                monthEnd = cx + font.width(month) + 4;
            }
            for (int d = 0; d < 7; d++) {
                LocalDate date = monday.plusDays(d);
                if (date.isAfter(today)) continue;
                double sec = s.day(date);
                int level = sec <= 0 || max <= 0 ? 0 : 1 + Math.min(3, (int) (sec / max * 4 - 1e-9));
                int cy = gy + d * step;
                boolean hover = mx >= cx && mx < cx + step && my >= cy && my < cy + step && my >= centerY0 && my < centerY1;
                if (hover) hovered = date;
                if (date.equals(today) || hover) g.fill(cx - 1, cy - 1, cx + cell + 1, cy + cell + 1, hover ? WHITE : DIM);
                g.fill(cx, cy, cx + cell, cy + cell, HEAT[level]);
            }
        }
        for (int d = 0; d < 7; d += 2) {
            String day = java.time.DayOfWeek.of(d + 1).getDisplayName(TextStyle.NARROW, locale);
            g.text(font, day, x0, gy + d * step, FAINT, false);
        }
        int bottom = gy + 7 * step + 2, gridRight = gx + weeks * step - 2;
        if (hovered != null) {
            double sec = s.day(hovered);
            String label = hovered.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale)) + "  "
                    + (sec > 0 ? longDuration(Math.max(60, sec)) : tr("cuemymusic.stats.nothing"));
            g.text(font, clip(label, x1 - gx), gx, bottom, WHITE, false);
        } else {
            // Less ■■■■■ More
            String less = tr("cuemymusic.stats.less"), more = tr("cuemymusic.stats.more");
            int lx = gridRight - font.width(more);
            g.text(font, more, lx, bottom, FAINT, false);
            for (int i = HEAT.length - 1; i >= 0; i--) {
                lx -= step;
                g.fill(lx, bottom, lx + cell, bottom + cell, HEAT[i]);
            }
            g.text(font, less, lx - 3 - font.width(less), bottom, FAINT, false);
        }
        return bottom + 10;
    }

    /** The five artists heard longest. */
    private int topArtists(GuiGraphicsExtractor g, int x0, int y, int x1, Map<String, ListeningStats.Song> songs) {
        g.text(font, tr("cuemymusic.stats.top_artists"), x0, y, WHITE, false);
        y += 14;
        Map<String, Double> byArtist = new HashMap<>();
        songs.forEach((id, song) -> {
            Track t = statsTrack(id);
            if (t != null && song.seconds > 0) byArtist.merge(t.artist(), song.seconds, Double::sum);
        });
        List<Map.Entry<String, Double>> top = byArtist.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(5).toList();
        if (top.isEmpty()) {
            g.text(font, tr("cuemymusic.stats.none"), x0, y, FAINT, false);
            return y + 12;
        }
        double max = top.get(0).getValue();
        for (int i = 0; i < top.size(); i++) {
            String artist = top.get(i).getKey();
            double sec = top.get(i).getValue();
            Art art = artistArt(artist);
            int ry = y + i * 20;
            art(g, art, x0, ry + 1, 16);
            int tx = x0 + 20;
            String time = longDuration(sec);
            int lw = font.width(time);
            g.text(font, clip(artist, x1 - tx - lw - 6), tx, ry + 1, WHITE, false);
            g.text(font, time, x1 - lw, ry + 1, DIM, false);
            int bw = (int) Math.round((x1 - tx) * sec / max);
            g.fill(tx, ry + 12, x1, ry + 15, 0xFF262626);
            g.fill(tx, ry + 12, tx + bw, ry + 15, i == 0 ? ARTIST_PURPLE : 0xFF5C4A99);
        }
        return y + top.size() * 20;
    }

    /** Time per dimension as a bar of that dimension's blocks, then who picked the music: you or the game. */
    private int whereAndWho(GuiGraphicsExtractor g, int x0, int y, int x1, ListeningStats s) {
        g.text(font, tr("cuemymusic.stats.where"), x0, y, WHITE, false);
        y += 14;
        record Place(String name, Art art, double seconds) {
        }
        List<Place> places = new ArrayList<>();
        double inWorlds = 0;
        for (Map.Entry<String, Double> e : s.dimensions().entrySet()) {
            String id = e.getKey();
            String path = id.substring(id.indexOf(':') + 1);
            String block = switch (id) {
                case "minecraft:overworld" -> "block/moss_block";
                case "minecraft:the_nether" -> "block/netherrack";
                case "minecraft:the_end" -> "block/end_stone";
                default -> "block/stone";
            };
            String name = id.startsWith("minecraft:") ? tr("cuemymusic.stats.dimension." + path)
                    : Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
            places.add(new Place(name, Art.block(block), e.getValue()));
            inWorlds += e.getValue();
        }
        double menus = s.totalSeconds() - inWorlds;
        if (menus > s.totalSeconds() * 0.01) places.add(new Place(tr("cuemymusic.stats.menus"), Art.block("block/oak_planks"), menus));
        places.sort(Comparator.comparingDouble(Place::seconds).reversed());
        double total = places.stream().mapToDouble(Place::seconds).sum();
        int barH = 12;
        g.fill(x0, y, x1, y + barH, 0xFF262626);
        double sum = 0;
        int px = x0;
        for (Place p : places) {
            sum += p.seconds;
            int ex = x0 + (int) Math.round((x1 - x0) * sum / total);
            if (ex <= px) continue;
            g.enableScissor(px, y, ex, y + barH);
            for (int tx = px; tx < ex; tx += barH) art(g, p.art, tx, y, barH);
            g.disableScissor();
            if (ex < x1) g.fill(ex, y, ex + 1, y + barH, BLACK);
            px = ex;
        }
        y += barH + 4;
        int lx = x0;
        for (Place p : places) {
            String label = p.name + " " + Math.round(100 * p.seconds / total) + "%";
            int iw = 11 + font.width(label) + 8;
            if (lx > x0 && lx + iw > x1) {
                lx = x0;
                y += 11;
            }
            art(g, p.art, lx, y, 8);
            g.text(font, label, lx + 11, y, DIM, false);
            lx += iw;
        }
        y += 18;

        g.text(font, tr("cuemymusic.stats.who"), x0, y, WHITE, false);
        y += 14;
        double you = s.pickedSeconds(), game = s.gameSeconds();
        double youShare = you + game > 0 ? you / (you + game) : 0;
        int split = x0 + (int) Math.round((x1 - x0) * youShare);
        g.fill(x0, y, x1, y + 8, GAME_BLUE);
        g.fill(x0, y, split, y + 8, GREEN);
        y += 11;
        String youLabel = tr("cuemymusic.stats.who.you", Math.round(100 * youShare));
        String gameLabel = tr("cuemymusic.stats.who.game", 100 - Math.round(100 * youShare));
        g.text(font, youLabel, x0, y, GREEN, false);
        g.text(font, gameLabel, x1 - font.width(gameLabel), y, GAME_BLUE, false);
        y += 12;
        if (s.effectSeconds() > 0) {
            Component effects = Component.translatable("cuemymusic.stats.effects", longDuration(s.effectSeconds()));
            g.textWithWordWrap(font, effects, x0, y, x1 - x0, FAINT, false);
            y += font.wordWrapHeight(effects, x1 - x0);
        }
        return y;
    }

    private int drawSettings(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        int pad = 12;
        int left = x0 + pad;
        int right = x1 - pad;
        bigText(g, tr("cuemymusic.settings"), left, y + 10, 2, WHITE);
        y += 36;

        g.text(font, tr("cuemymusic.settings.playback"), left, y, WHITE, false);
        y += 14;
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.vanilla"), tr("cuemymusic.option.vanilla.desc"), cfg.vanilla,
                () -> setVanilla(!cfg.vanilla));
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.enabled"), tr("cuemymusic.option.enabled.desc"), cfg.enabled,
                () -> cfg.enabled = !cfg.enabled);
        double applying = applying();
        CueStream.Params heard = CueStream.heard(), target = CueStream.Params.now();
        boolean live = applying >= 0 && heard != null;
        y = sliderRow(g, left, y, right, tr("cuemymusic.option.pitch"),
                tr("cuemymusic.option.pitch.value", signed(cfg.pitchSemitones)), (cfg.pitchSemitones + 12) / 24.0,
                f -> cfg.pitchSemitones = (int) Math.round(f * 24) - 12, 0.5,
                live && heard.pitch() != target.pitch() ? applying : -1);
        y = sliderRow(g, left, y, right, tr("cuemymusic.option.speed"),
                tr("cuemymusic.option.speed.value", String.format("%.2f", cfg.speed)), (cfg.speed - 0.5) / 1.5,
                f -> cfg.speed = Math.round((0.5 + f * 1.5) * 20) / 20.0, (1.0 - 0.5) / 1.5,
                live && heard.tempo() != target.tempo() ? applying : -1);
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.travel"), tr("cuemymusic.option.travel.desc"), cfg.pauseOnTravel,
                () -> cfg.pauseOnTravel = !cfg.pauseOnTravel);
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.focus"), tr("cuemymusic.option.focus.desc"), cfg.pauseOnLostFocus,
                () -> cfg.pauseOnLostFocus = !cfg.pauseOnLostFocus);
        if (!CuePanels.all().isEmpty()) {
            // Only songs on add-on pages are affected, so these only show with an add-on.
            y = toggleRow(g, left, y, right, tr("cuemymusic.option.automix"), tr("cuemymusic.option.automix.desc"), cfg.automix,
                    () -> cfg.automix = !cfg.automix);
            y = toggleRow(g, left, y, right, tr("cuemymusic.option.outside"), tr("cuemymusic.option.outside.desc"),
                    cfg.keepOutOfRecordings, () -> cfg.keepOutOfRecordings = !cfg.keepOutOfRecordings);
        }
        y += 10;

        g.text(font, tr("cuemymusic.settings.equalizer"), left, y, WHITE, false);
        if (live && !java.util.Arrays.equals(heard.eq(), target.eq())) {
            applyingIcon(g, left + font.width(tr("cuemymusic.settings.equalizer")) + 5, y, applying);
        }
        y += 14;
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.eq"), tr("cuemymusic.option.eq.desc"), cfg.eqEnabled,
                () -> cfg.eqEnabled = !cfg.eqEnabled);

        // Preset chips, wrapping onto new lines as needed.
        int cx = left;
        for (Map.Entry<String, double[]> p : PRESETS.entrySet()) {
            String label = tr("cuemymusic.eq.preset." + p.getKey());
            int w = font.width(label) + 12;
            if (cx + w > right) {
                cx = left;
                y += 16;
            }
            boolean on = p.getKey().equals(cfg.eqPreset);
            boolean hover = mx >= cx && mx < cx + w && my >= y && my < y + 13;
            g.fill(cx, y, cx + w, y + 13, on ? WHITE : hover ? 0xFF3A3A3A : 0xFF2A2A2A);
            g.text(font, label, cx + 6, y + 3, on ? BLACK : WHITE, false);
            double[] gains = p.getValue();
            String presetKey = p.getKey();
            nextHits.add(new Hit(cx, y, cx + w, y + 13, () -> {
                cfg.eqGains = gains.clone();
                cfg.eqPreset = presetKey;
                cfg.eqEnabled = true;
                cfg.save();
            }));
            cx += w + 4;
        }
        y += 22;

        // Band sliders.
        int bands = Equalizer.FREQUENCIES.length;
        int areaW = Math.min(right - left, 260);
        int slotW = areaW / bands;
        int sliderH = 64;
        int dimColor = cfg.eqEnabled ? WHITE : FAINT;
        g.text(font, "+12", left, y, FAINT, false);
        g.text(font, "-12", left, y + sliderH - 8, FAINT, false);
        for (int b = 0; b < bands; b++) {
            int cxm = left + 18 + b * slotW + slotW / 2;
            int ty = y, by = y + sliderH;
            g.fill(cxm - 1, ty, cxm + 1, by, TRACK_BG);
            g.fill(cxm - 3, (ty + by) / 2, cxm + 3, (ty + by) / 2 + 1, FAINT);
            double gain = cfg.eqGains[b];
            double f = (gain + Equalizer.MAX_GAIN_DB) / (2 * Equalizer.MAX_GAIN_DB);
            int ky = by - (int) Math.round(f * (by - ty));
            int mid = (ty + by) / 2;
            if (cfg.eqEnabled && gain != 0) g.fill(cxm - 1, Math.min(ky, mid), cxm + 1, Math.max(ky, mid), GREEN);
            g.fill(cxm - 4, ky - 2, cxm + 4, ky + 2, dimColor);
            String label = frequencyLabel(Equalizer.FREQUENCIES[b]);
            g.text(font, label, cxm - font.width(label) / 2, by + 5, DIM, false);
            int band = b;
            nextDrags.add(new Drag(cxm - slotW / 2 + 2, ty - 3, cxm + slotW / 2 - 2, by + 3, true, v -> {
                double[] gains = cfg.eqGains.clone();
                gains[band] = Math.round((v * 2 - 1) * Equalizer.MAX_GAIN_DB * 2) / 2.0;
                cfg.eqGains = gains;
                cfg.eqPreset = "custom";
                cfg.eqEnabled = true;
            }, cfg::save));
        }
        y += sliderH + 18;
        return y;
    }

    private int toggleRow(GuiGraphicsExtractor g, int left, int y, int right, String label, String desc, boolean on, Runnable flip) {
        g.text(font, label, left, y + 1, WHITE, false);
        g.text(font, clip(desc, right - left - 34), left, y + 11, DIM, false);
        int sw = 22, sh = 11;
        int sx = right - sw;
        g.fill(sx, y + 4, sx + sw, y + 4 + sh, on ? GREEN : TRACK_BG);
        int knob = on ? sx + sw - sh + 1 : sx + 1;
        g.fill(knob, y + 5, knob + sh - 2, y + 4 + sh - 1, on ? BLACK : WHITE);
        nextHits.add(new Hit(sx - 4, y, sx + sw + 4, y + 20, () -> {
            flip.run();
            cfg.save();
        }));
        return y + 24;
    }

    private int sliderRow(GuiGraphicsExtractor g, int left, int y, int right, String label, String value, double fraction,
            DoubleConsumer set, double defaultFraction, double applying) {
        g.text(font, label, left, y + 1, WHITE, false);
        // While the change is on its way, the value is dimmed with a ring that fills until it's heard.
        g.text(font, value, right - font.width(value), y + 1, applying >= 0 ? DIM : GREEN, false);
        if (applying >= 0) applyingIcon(g, right - font.width(value) - 11, y + 1, applying);
        int bx0 = left, bx1 = right, by = y + 15;
        g.fill(bx0, by, bx1, by + 3, TRACK_BG);
        int dx = bx0 + (int) Math.round(defaultFraction * (bx1 - bx0));
        g.fill(dx, by - 2, dx + 1, by + 5, FAINT);
        int kx = bx0 + (int) Math.round(Math.clamp(fraction, 0, 1) * (bx1 - bx0));
        g.fill(bx0, by, kx, by + 3, cfg.enabled ? GREEN : FAINT);
        g.fill(kx - 3, by - 3, kx + 3, by + 6, WHITE);
        nextDrags.add(new Drag(bx0 - 4, by - 6, bx1 + 4, by + 9, false, set, cfg::save));
        return y + 26;
    }

    // ------------------------------------------------------ now playing, queue

    private void drawNowPlaying(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        g.fill(x0, y0, x1, y1, PANEL);
        Track t = player.current();
        int pad = 8;
        int w = x1 - x0 - pad * 2;
        int left = x0 + pad;
        queueRows = 0;
        queueFit = 0;
        if (t == null) {
            g.text(font, tr("cuemymusic.now_playing"), left, y0 + 7, WHITE, false);
            g.textWithWordWrap(font, Component.translatable("cuemymusic.now_playing.none"), left, y0 + 24, w, DIM, false);
            drawQueue(g, x0, y0 + 24 + font.wordWrapHeight(Component.translatable("cuemymusic.now_playing.none"), w) + 12, x1, y1, mx, my);
            return;
        }
        // Album (or pack), art, title, artist. Long titles and artists scroll.
        marquee(g, t.album(), left, y0 + 7, w, WHITE, 1);
        // The art gives up room to the queue when there is one.
        int size = Math.min(w, (y1 - y0) * (player.queue().isEmpty() ? 2 : 1) / (player.queue().isEmpty() ? 5 : 4));
        art(g, t.art(), left, y0 + 20, size);
        int y = y0 + 25 + size;
        marquee(g, t.title(), left, y, w, WHITE, 1.5f);
        y += 15;
        marquee(g, t.artist(), left, y, w - 24, DIM, 1);
        boolean liked = isLiked(t);
        int hx = left + w - 7;
        boolean heartHover = mx >= hx - 2 && mx < hx + 9 && my >= y - 2 && my < y + 8;
        heartIcon(g, hx, y + 1, liked, liked ? GREEN : heartHover ? WHITE : DIM);
        nextHits.add(new Hit(hx - 2, y - 2, hx + 9, y + 8, () -> toggleLiked(t)));
        if (MusicLibrary.isGameMusic(t)) {
            int dx = hx - 14;
            boolean disliked = isDisliked(t);
            boolean dislikeHover = mx >= dx - 2 && mx < dx + 11 && my >= y - 2 && my < y + 11;
            dislikeIcon(g, dx, y, disliked, disliked ? RED : dislikeHover ? WHITE : DIM);
            nextHits.add(new Hit(dx - 2, y - 2, dx + 11, y + 11, () -> toggleDisliked(t)));
        }
        y += 16;
        drawQueue(g, x0, y, x1, y1, mx, my);
    }

    private void drawQueue(GuiGraphicsExtractor g, int x0, int y, int x1, int y1, int mx, int my) {
        int pad = 8;
        int left = x0 + pad, right = x1 - pad;
        List<Track> queue = player.queue();
        // The game's next song comes after the queue, with the break before it.
        Track game = player.upcoming();
        String gameWhen = null;
        if (game != null) {
            String wait = time(player.ticksUntilUpcoming() / 20.0);
            gameWhen = player.waitingForGame() && queue.isEmpty()
                    ? tr("cuemymusic.queue.in", wait)
                    : tr("cuemymusic.queue.after_break", wait);
        }

        Track listNext = player.listNext();
        if (queue.isEmpty() && listNext != null) {
            if (y + 12 + ROW > y1) return;
            g.text(font, clip(tr("cuemymusic.queue.next_up"), right - left), left, y, WHITE, false);
            nextRow(g, listNext, listNext.artist(), x0, y + 12, x1, mx, my, player::next);
            return;
        }

        if (queue.isEmpty()) {
            if (game == null || y + 12 + ROW > y1) {
                if (y + 20 < y1) {
                    g.text(font, clip(tr("cuemymusic.queue"), right - left), left, y, WHITE, false);
                    g.textWithWordWrap(font, Component.translatable("cuemymusic.queue.empty"), left, y + 12, right - left, FAINT, false);
                }
                return;
            }
            g.text(font, clip(tr("cuemymusic.queue.next_up"), right - left), left, y, WHITE, false);
            gameRow(g, game, gameWhen, x0, y + 12, x1, mx, my);
            int hy = y + 12 + ROW + 8;
            Component hint = Component.translatable("cuemymusic.queue.empty");
            if (hy + font.wordWrapHeight(hint, right - left) < y1) g.textWithWordWrap(font, hint, left, hy, right - left, FAINT, false);
            return;
        }

        int gameH = game != null ? 12 + ROW + 6 : 0;
        g.text(font, clip(tr("cuemymusic.queue"), right - left), left, y, WHITE, false);
        queueX0 = x0;
        queueX1 = x1;
        queueTop = y + 12;
        // The queue gets the room first; the game's song only shows if the whole queue fits above it.
        // A longer queue scrolls.
        int fit = Math.max(0, (y1 - queueTop - 4) / QROW);
        boolean showGame = game != null && queue.size() <= (y1 - gameH - queueTop - 4) / QROW;
        queueFit = fit;
        // Dragging a row past the top or bottom edge scrolls the queue.
        if (queueDrag >= 0 && queueDragY < queueTop + 4) queueScroll -= 0.15;
        if (queueDrag >= 0 && queueDragY > queueTop + fit * QROW - 4) queueScroll += 0.15;
        queueScroll = Math.clamp(queueScroll, 0, Math.max(0, queue.size() - fit));
        int first = (int) Math.round(queueScroll);
        queueRows = Math.min(fit, queue.size() - first);

        for (int k = 0; k < queueRows; k++) {
            int i = first + k;
            if (i == queueDrag) continue;
            int ry = queueTop + k * QROW;
            boolean hover = queueDrag < 0 && mx >= x0 && mx < x1 && my >= ry && my < ry + QROW;
            if (hover) g.fill(x0 + 4, ry, x1 - 4, ry + QROW, ROW_HOVER);
            compactQueueRow(g, queue.get(i), left, ry, right - left - (hover ? 10 : 0));
            if (hover) {
                boolean xHover = mx >= right - 9;
                crossIcon(g, right - 7, ry + 3, xHover ? WHITE : DIM);
            }
        }
        int end = queueTop + queueRows * QROW;
        if (queue.size() > fit && fit > 0) {
            // Scrollbar along the right edge.
            int barH = fit * QROW;
            int thumbH = Math.max(6, barH * fit / queue.size());
            int thumbY = queueTop + (int) Math.round((barH - thumbH) * queueScroll / (queue.size() - fit));
            g.fill(x1 - 3, queueTop, x1 - 2, queueTop + barH, 0xFF2A2A2A);
            g.fill(x1 - 3, thumbY, x1 - 2, thumbY + thumbH, FAINT);
        }
        if (showGame) {
            g.text(font, clip(tr("cuemymusic.queue.from_game"), right - left), left, end + 6, WHITE, false);
            gameRow(g, game, gameWhen, x0, end + 18, x1, mx, my);
        }

        // The row being dragged follows the mouse, with a line where it will land.
        if (queueDrag >= 0 && queueDrag < queue.size()) {
            int slot = dropSlot(queueDragY);
            int ly = queueTop + (slot - first) * QROW;
            g.fill(x0 + 4, ly - 1, x1 - 4, ly + 1, GREEN);
            int ry = (int) Math.round(queueDragY) - QROW / 2;
            g.fill(x0 + 4, ry, x1 - 4, ry + QROW, 0xFF333333);
            compactQueueRow(g, queue.get(queueDrag), left, ry, right - left);
        }
    }

    /** The game's next song. Clicking it plays it now. */
    private void gameRow(GuiGraphicsExtractor g, Track t, String when, int x0, int y, int x1, int mx, int my) {
        nextRow(g, t, when, x0, y, x1, mx, my, player::playUpcoming);
    }

    /** The song that plays next, with a line below it; clicking plays it now. */
    private void nextRow(GuiGraphicsExtractor g, Track t, String below, int x0, int y, int x1, int mx, int my, Runnable play) {
        boolean hover = queueDrag < 0 && mx >= x0 && mx < x1 && my >= y && my < y + ROW;
        if (hover) g.fill(x0 + 4, y, x1 - 4, y + ROW, ROW_HOVER);
        // The wait can be wider than the panel, so it scrolls like the song names.
        queueRow(g, t, x0 + 8, y, x1 - x0 - 16, DIM, "");
        marquee(g, below, x0 + 28, y + 11, x1 - x0 - 36, DIM, 1);
        nextHits.add(new Hit(x0, y, x1, y + ROW, play));
    }

    private void queueRow(GuiGraphicsExtractor g, Track t, int x, int y, int w, int titleColor, String below) {
        art(g, t.art(), x, y + 2, 16);
        g.text(font, clip(t.title(), w - 20), x + 20, y + 2, titleColor, false);
        g.text(font, clip(below, w - 20), x + 20, y + 11, DIM, false);
    }

    /** A queued song on one line: small art, the title, then the artist dimmed. */
    private void compactQueueRow(GuiGraphicsExtractor g, Track t, int x, int y, int w) {
        art(g, t.art(), x, y + 2, 9);
        int tx = x + 13, avail = w - 13;
        String title = clip(t.title(), avail);
        g.text(font, title, tx, y + 3, WHITE, false);
        int aw = avail - font.width(title) - 5;
        if (aw > 12) g.text(font, clip(t.artist(), aw), tx + font.width(title) + 5, y + 3, DIM, false);
    }

    /** Where a dragged queue row would land, as an index between rows. */
    private int dropSlot(double y) {
        int first = (int) Math.round(queueScroll);
        return first + Math.clamp((int) Math.round((y - queueTop) / QROW), 0, queueRows);
    }

    // ------------------------------------------------------------- bottom bar

    private void drawBottomBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        Track t = player.current();
        // Current song on the left.
        int barW = Math.min(360, (x1 - x0) * 2 / 5);
        // Stops short of the elapsed time left of the progress bar.
        int leftW = Math.min(170, (x0 + x1) / 2 - barW / 2 - 12 - font.width("00:00") - x0 - GAP);
        if (t != null) {
            art(g, t.art(), x0 + GAP + 2, y0 + 7, 28);
            int tx = x0 + GAP + 36;
            g.text(font, clip(t.title(), leftW - 36), tx, y0 + 12, WHITE, false);
            g.text(font, clip(t.artist(), leftW - 36), tx, y0 + 22, DIM, false);
        }

        // Controls in the middle.
        int cx = (x0 + x1) / 2;
        int cy = y0 + 5;
        int btn = 14;
        nextHits.add(new Hit(cx - btn / 2 - 22, cy, cx - btn / 2 - 10, cy + 12, player::previous));
        previousIcon(g, cx - btn / 2 - 20, cy + 2, 8, hoverColor(cx - btn / 2 - 22, cy, 12, 12, mx, my));
        roundButton(g, cx - btn / 2, cy - 1, btn, WHITE);
        if (player.isPlaying()) pauseIcon(g, cx - 3, cy + 2, 7, BLACK);
        else playIcon(g, cx - 2, cy + 2, 7, BLACK);
        nextHits.add(new Hit(cx - btn / 2, cy - 1, cx + btn / 2, cy - 1 + btn, player::togglePause));
        nextHits.add(new Hit(cx + btn / 2 + 10, cy, cx + btn / 2 + 22, cy + 12, player::next));
        nextIcon(g, cx + btn / 2 + 12, cy + 2, 8, hoverColor(cx + btn / 2 + 10, cy, 12, 12, mx, my));
        // Repeat: off, all, one.
        PlayOrder.Repeat repeat = cfg.repeatMode();
        int rx = cx + btn / 2 + 32;
        repeatIcon(g, rx, cy + 2, repeat, repeat != PlayOrder.Repeat.OFF ? GREEN : hoverColor(rx - 2, cy, 13, 12, mx, my));
        nextHits.add(new Hit(rx - 2, cy, rx + 11, cy + 12, () -> {
            PlayOrder.Repeat next = cfg.repeatMode().cycle();
            cfg.setRepeat(next);
            showToast(tr("cuemymusic.repeat." + next.name().toLowerCase(java.util.Locale.ROOT)));
        }));

        // Progress bar under the controls.
        int bx0 = cx - barW / 2, bx1 = cx + barW / 2, by = y0 + 27;
        double len = t == null || Double.isNaN(t.seconds()) ? 0 : t.seconds();
        double pos = seekPreview >= 0 ? seekPreview : player.position();
        String posText = t == null ? "-:--" : time(pos);
        String lenText = len == 0 ? "-:--" : time(len);
        g.text(font, posText, bx0 - 6 - font.width(posText), by - 3, DIM, false);
        g.text(font, lenText, bx1 + 6, by - 3, DIM, false);
        g.fill(bx0, by, bx1, by + 3, TRACK_BG);
        boolean barHover = mx >= bx0 && mx < bx1 && my >= by - 5 && my < by + 8;
        if (len > 0) {
            int px = bx0 + (int) Math.round(Math.clamp(pos / len, 0, 1) * (bx1 - bx0));
            g.fill(bx0, by, px, by + 3, barHover || dragging != null && seekPreview >= 0 ? GREEN : WHITE);
            if (barHover || seekPreview >= 0) g.fill(px - 3, by - 2, px + 3, by + 5, WHITE);
            nextDrags.add(new Drag(bx0, by - 5, bx1, by + 8, false, f -> seekPreview = f * len, () -> {
                player.seek(seekPreview);
                seekPreview = -1;
            }));
        }

        // Pitch and speed on the right; opens settings.
        String fx = cfg.enabled ? tr("cuemymusic.bar.fx", signed(cfg.pitchSemitones), String.format("%.2f", cfg.speed))
                : tr("cuemymusic.bar.fx_off");
        int fw = font.width(fx);
        if (x1 - GAP - 4 - fw > bx1 + 40) {
            int fx0 = x1 - GAP - 4 - fw;
            g.text(font, fx, fx0, y0 + 17, cfg.enabled && (cfg.pitchSemitones != 0 || cfg.speed != 1) ? GREEN : DIM, false);
            double applying = applying();
            if (applying >= 0) applyingIcon(g, fx0 - 11, y0 + 17, applying);
            nextHits.add(new Hit(fx0, y0 + 12, x1, y0 + 30, () -> open(View.SETTINGS, null)));
        }
    }

    private static int hoverColor(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h ? WHITE : DIM;
    }

    private void showToast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + TOAST_MILLIS;
    }

    private void drawToast(GuiGraphicsExtractor g) {
        if (toast == null || System.currentTimeMillis() > toastUntil) return;
        int w = font.width(toast) + 16;
        int x = (centerX0 + centerX1 - w) / 2, y = centerY1 - (selected.isEmpty() ? 22 : 42);
        g.fill(x, y, x + w, y + 16, WHITE);
        g.text(font, toast, x + 8, y + 4, BLACK, false);
    }

    // -------------------------------------------------------------- selection

    private static String id(Track t) {
        return t.location().toString();
    }

    private void clearSelection() {
        selected.clear();
        anchor = -1;
        focusRow = -1;
        selectDragging = false;
    }

    /** The selected songs, in the order shown. */
    private List<Track> selectedTracks() {
        return shownTracks.stream().filter(t -> selected.contains(id(t))).toList();
    }

    /** Selects the rows from {@code a} to {@code b}, either way round, and nothing else. */
    private void selectRange(int a, int b) {
        selected.clear();
        if (shownTracks.isEmpty()) return;
        int last = shownTracks.size() - 1;
        int lo = Math.clamp(Math.min(a, b), 0, last), hi = Math.clamp(Math.max(a, b), 0, last);
        for (int i = lo; i <= hi; i++) selected.add(id(shownTracks.get(i)));
    }

    /** The row at screen height {@code y}, held to the list's ends, or -1 with no list. */
    private int rowAt(double y) {
        if (shownTracks.isEmpty()) return -1;
        double inside = Math.clamp(y, centerY0, centerY1 - 1);
        return Math.clamp((int) Math.floor((inside - shownTop) / ROW), 0, shownTracks.size() - 1);
    }

    /** Songs can be dragged into a new order on a playlist shown in its own order, without a search. */
    private boolean canReorder() {
        return view == View.LIST && sort == Sort.NONE && pageFilter().isBlank() && !shownTracks.isEmpty();
    }

    /** Where a dragged song would go: the gap before row n, from 0 to the number of rows. */
    private int dropGap(double y) {
        double inside = Math.clamp(y, centerY0, centerY1 - 1);
        return Math.clamp((int) Math.round((inside - shownTop) / ROW), 0, shownTracks.size());
    }

    /** Drops the dragged song, or the selection if it was in it, into the gap at {@code y}. */
    private void dropRows(RowHit r, double y) {
        if (view != View.LIST) return;
        List<String> moving = selected.contains(id(r.track())) && selected.size() > 1 ? List.copyOf(selected) : List.of(id(r.track()));
        int gap = dropGap(y);
        String before = gap < shownTracks.size() ? id(shownTracks.get(gap)) : null;
        // Dropped just under itself: no change.
        if (moving.size() == 1 && gap == r.index() + 1) return;
        cfg.movePlaylistSongs(key, moving, before);
    }

    /** While a song is dragged: a green line where it would go, and its title by the pointer. */
    private void drawReorder(GuiGraphicsExtractor g, int mx, int my) {
        if (pressed == null || !reordering) return;
        int gy = shownTop + dropGap(my) * ROW;
        if (gy >= centerY0 - 1 && gy <= centerY1) {
            g.fill(centerX0 + 8, gy - 1, centerX1 - 8, gy + 1, GREEN);
            g.fill(centerX0 + 6, gy - 3, centerX0 + 10, gy + 3, GREEN);
        }
        int n = selected.contains(id(pressed.track())) && selected.size() > 1 ? selected.size() : 1;
        String label = n > 1 ? tr("cuemymusic.page.moving", n) : pressed.track().title();
        int w = font.width(label) + 10;
        g.fill(mx + 10, my - 4, mx + 10 + w, my + 10, 0xEE282828);
        g.text(font, label, mx + 15, my, WHITE, false);
    }

    /** Scrolls just enough to show row {@code i}. */
    private void revealRow(int i) {
        int top = shownTop + i * ROW;
        if (top < centerY0) scroll -= centerY0 - top;
        else if (top + ROW > centerY1) scroll += top + ROW - centerY1;
        scroll = Math.clamp(scroll, 0, scrollMax);
    }

    /**
     * Shift+click selects from the last row clicked, and dragging on carries the
     * selection along. Cmd (Ctrl elsewhere)+click adds or removes one row. A plain
     * click on a row clears the selection and plays the row as before.
     */
    private boolean rowClicked(MouseButtonEvent event) {
        boolean shift = event.hasShiftDown(), toggle = event.hasControlDownWithQuirk();
        for (RowHit r : rowHits) {
            if (!r.contains(event.x(), event.y())) continue;
            if (!shift && !toggle) {
                if (!canReorder()) {
                    clearSelection();
                    return false;
                }
                // Wait to see if it's a click (play, like...) or a drag to move the song.
                pressed = r;
                pressX = event.x();
                pressY = event.y();
                reordering = false;
                return true;
            }
            if (toggle && !shift) {
                if (!selected.remove(id(r.track()))) selected.add(id(r.track()));
                anchor = r.index();
                focusRow = r.index();
            } else {
                if (anchor < 0) anchor = r.index();
                focusRow = r.index();
                selectRange(anchor, focusRow);
                selectDragging = true;
            }
            return true;
        }
        return false;
    }

    /** Shift+up/down grows or shrinks the selection, Cmd/Ctrl+A selects all, Esc clears, Delete removes. */
    private boolean selectionKey(KeyEvent event) {
        if (shownTracks.isEmpty()) return false;
        int key = event.key(), last = shownTracks.size() - 1;
        if (event.isSelectAll()) {
            anchor = 0;
            focusRow = last;
            selectRange(0, last);
            return true;
        }
        if (event.hasShiftDown() && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
            if (focusRow < 0) {
                int playing = shownTracks.indexOf(player.current());
                focusRow = playing >= 0 ? playing : 0;
                anchor = focusRow;
            } else {
                focusRow = Math.clamp(focusRow + (key == InputConstants.KEY_UP ? -1 : 1), 0, last);
                if (anchor < 0) anchor = focusRow;
            }
            selectRange(anchor, focusRow);
            revealRow(focusRow);
            return true;
        }
        if (selected.isEmpty()) return false;
        if (key == InputConstants.KEY_ESCAPE) {
            clearSelection();
            return true;
        }
        if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
            MenuItem delete = deleteItem(selectedTracks());
            if (delete != null) delete.action().run();
            return true;
        }
        return false;
    }

    /** What Delete does on this page: take the songs out of the playlist, Liked Songs, Disliked Songs, or the add-on's page. */
    private MenuItem deleteItem(List<Track> tracks) {
        if (tracks.isEmpty()) return null;
        int n = tracks.size();
        Runnable done = this::clearSelection;
        return switch (view) {
            case LIST -> {
                String listId = key;
                yield new MenuItem(MenuIcon.REMOVE, tr("cuemymusic.bulk.remove", n), () -> {
                    for (Track t : tracks) cfg.removeFromPlaylist(listId, id(t));
                    showToast(tr("cuemymusic.bulk.removed", n));
                    done.run();
                });
            }
            case LIKED -> new MenuItem(MenuIcon.LIKED, tr("cuemymusic.bulk.unlike", n), () -> {
                for (Track t : tracks) {
                    if (isLiked(t)) cfg.toggleLiked(id(t));
                }
                showToast(tr("cuemymusic.bulk.unliked", n));
                done.run();
            });
            case DISLIKED -> new MenuItem(MenuIcon.DISLIKED, tr("cuemymusic.bulk.undislike", n), () -> {
                for (Track t : tracks) {
                    if (isDisliked(t)) player.toggleDisliked(t);
                }
                showToast(tr("cuemymusic.bulk.undisliked", n));
                done.run();
            });
            case PANEL -> {
                CuePanel panel = CuePanels.get(key);
                CuePanel.Action a = panel != null ? panel.deleteAction(tracks) : null;
                yield a == null ? null : new MenuItem(MenuIcon.DELETE, a.label(), () -> {
                    a.run().run();
                    done.run();
                });
            }
            default -> null;
        };
    }

    /** The right-click menu for several selected songs. */
    private void openBulkMenu(List<Track> tracks, int x, int y) {
        int n = tracks.size();
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.playlists(tr("cuemymusic.bulk.add_to_playlist", n), new PlaylistPick(view == View.LIST ? key : null,
                p -> {
                    int added = 0;
                    for (Track t : tracks) {
                        if (cfg.addToPlaylist(p.id(), id(t))) added++;
                    }
                    showToast(tr("cuemymusic.bulk.added", added, p.name()));
                },
                () -> {
                    String listId = cfg.createPlaylist(tr("cuemymusic.playlists.default_name", cfg.playlists.size() + 1));
                    for (Track t : tracks) cfg.addToPlaylist(listId, id(t));
                    open(View.LIST, listId);
                    startRename(listId);
                })));
        boolean allLiked = tracks.stream().allMatch(this::isLiked);
        if (view != View.LIKED || !allLiked) {
            items.add(new MenuItem(allLiked ? MenuIcon.LIKED : MenuIcon.LIKE, tr(allLiked ? "cuemymusic.bulk.unlike" : "cuemymusic.bulk.like", n), () -> {
                for (Track t : tracks) {
                    if (isLiked(t) == allLiked) cfg.toggleLiked(id(t));
                }
                showToast(tr(allLiked ? "cuemymusic.bulk.unliked" : "cuemymusic.bulk.liked", n));
            }));
        }
        items.add(new MenuItem(MenuIcon.QUEUE, tr("cuemymusic.bulk.queue", n), () -> {
            player.enqueueAll(tracks);
            showToast(tr("cuemymusic.queue.added_all", n));
        }));
        List<Track> game = tracks.stream().filter(MusicLibrary::isGameMusic).toList();
        boolean allDisliked = !game.isEmpty() && game.stream().allMatch(this::isDisliked);
        if (!game.isEmpty() && view != View.DISLIKED) {
            items.add(new MenuItem(allDisliked ? MenuIcon.DISLIKED : MenuIcon.DISLIKE,
                    tr(allDisliked ? "cuemymusic.bulk.undislike" : "cuemymusic.bulk.dislike", game.size()), () -> {
                for (Track t : game) {
                    if (isDisliked(t) == allDisliked) player.toggleDisliked(t);
                }
                showToast(tr(allDisliked ? "cuemymusic.bulk.undisliked" : "cuemymusic.bulk.disliked", game.size()));
            }));
        }
        MenuItem delete = deleteItem(tracks);
        if (delete != null && delete.icon() != MenuIcon.LIKED && delete.icon() != MenuIcon.DISLIKED) {
            items.add(MenuItem.DIVIDER);
            items.add(delete);
        }
        showMenu(new Menu(x, y, items));
    }

    /** While songs are selected: how many, and what to do with them. */
    private void drawSelectionBar(GuiGraphicsExtractor g) {
        if (selected.isEmpty() || shownTracks.isEmpty()) return;
        String text = tr("cuemymusic.selection", selected.size());
        int w = font.width(text) + 16;
        int x = (centerX0 + centerX1 - w) / 2, y = centerY1 - 22;
        g.fill(x, y, x + w, y + 16, 0xFF2C3A31);
        g.fill(x, y, x + 2, y + 16, GREEN);
        g.text(font, text, x + 9, y + 4, WHITE, false);
    }

    // ------------------------------------------------------------- add-on pages

    private void unfocusField() {
        if (focusedField == null) return;
        focusedField.setFocused(false);
        focusedField = null;
        setFocused(null);
    }

    private void submitField() {
        for (Map.Entry<String, EditBox> e : fields.entrySet()) {
            if (e.getValue() == focusedField) {
                Consumer<String> submit = fieldSubmits.get(e.getKey());
                if (submit != null) submit.accept(focusedField.getValue());
                return;
            }
        }
    }

    /** Draws an add-on's page with the player's own pieces. Each call draws at {@link #y} and moves it down. */
    private final class Canvas implements PanelCanvas {
        private static final int PAD = 10;
        private static final int BUTTON_H = 18;
        private final GuiGraphicsExtractor g;
        private final int x0, x1, mx, my;
        private int y;

        Canvas(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
            this.g = g;
            this.x0 = x0;
            this.x1 = x1;
            this.y = y;
            this.mx = mx;
            this.my = my;
        }

        @Override public int left() { return x0 + PAD; }
        @Override public int right() { return x1 - PAD; }
        @Override public int y() { return y; }
        @Override public void space(int pixels) { y += pixels; }
        @Override public int mouseX() { return mx; }
        @Override public int mouseY() { return my; }
        @Override public net.minecraft.client.gui.Font font() { return font; }
        @Override public GuiGraphicsExtractor graphics() { return g; }
        @Override public int buttonHeight() { return BUTTON_H; }

        @Override
        public void header(Art art, String kind, String title, String summary, int color) {
            y = drawHeader(g, x0, y, x1, art, kind, title, summary, color) + 8;
        }

        @Override
        public void heading(String text) {
            g.text(font, clip(text, right() - left()), left(), y, WHITE, false);
            y += 14;
        }

        @Override
        public void text(Component text, int color) {
            int w = right() - left();
            g.textWithWordWrap(font, text, left(), y, w, color, false);
            y += font.wordWrapHeight(text, w) + 2;
        }

        @Override
        public String textField(String key, Component hint, int width, Consumer<String> onSubmit) {
            EditBox box = fields.computeIfAbsent(key, k -> {
                EditBox b = new EditBox(font, 0, 0, 100, 9, hint);
                b.setBordered(false);
                b.setMaxLength(2048);
                b.setHint(hint.copy().withColor(FAINT));
                b.setTextShadow(false);
                return b;
            });
            fieldSubmits.put(key, onSubmit);
            int fx0 = left(), fx1 = Math.min(right(), left() + width);
            boolean focused = box == focusedField;
            boolean hover = mx >= fx0 && mx < fx1 && my >= y && my < y + BUTTON_H;
            g.fill(fx0, y, fx1, y + BUTTON_H, focused ? WHITE : hover ? 0xFF8A8A8A : 0xFF3A3A3A);
            g.fill(fx0 + 1, y + 1, fx1 - 1, y + BUTTON_H - 1, 0xFF242424);
            box.setX(fx0 + 6);
            box.setY(y + (BUTTON_H - 8) / 2);
            box.setWidth(fx1 - fx0 - 12);
            box.extractRenderState(g, mx, my, partialTick);
            int top = Math.max(y, centerY0), bottom = Math.min(y + BUTTON_H, centerY1);
            if (bottom > top) nextFieldHits.add(new FieldHit(fx0, top, fx1, bottom, key));
            return box.getValue();
        }

        @Override
        public void setText(String key, String text) {
            EditBox box = fields.get(key);
            if (box != null) box.setValue(text);
        }

        @Override
        public int buttonWidth(String label) {
            return font.width(label) + 20;
        }

        @Override
        public int button(int x, String label, ButtonStyle style, boolean enabled, Runnable click) {
            int w = buttonWidth(label), bx1 = x + w;
            boolean hover = enabled && mx >= x && mx < bx1 && my >= y && my < y + BUTTON_H && my >= centerY0 && my < centerY1;
            int fg;
            switch (style) {
                case PRIMARY -> {
                    int bg = !enabled ? 0xFF2F5E40 : hover ? 0xFF3BE477 : GREEN;
                    g.fill(x + 1, y, bx1 - 1, y + BUTTON_H, bg);
                    g.fill(x, y + 1, bx1, y + BUTTON_H - 1, bg);
                    fg = enabled ? BLACK : 0xFF1B3324;
                }
                default -> {
                    int edge = !enabled ? 0xFF3A3A3A : style == ButtonStyle.DANGER ? RED : hover ? WHITE : 0xFF8A8A8A;
                    g.fill(x, y, bx1, y + BUTTON_H, edge);
                    g.fill(x + 1, y + 1, bx1 - 1, y + BUTTON_H - 1, hover ? 0xFF2A2A2A : PANEL);
                    fg = !enabled ? FAINT : style == ButtonStyle.DANGER ? RED : WHITE;
                }
            }
            g.text(font, label, x + 10, y + (BUTTON_H - 8) / 2, fg, false);
            if (enabled) {
                int top = Math.max(y, centerY0), bottom = Math.min(y + BUTTON_H, centerY1);
                if (bottom > top) nextHits.add(new Hit(x, top, bx1, bottom, click));
            }
            return w;
        }

        @Override
        public void progress(double fraction, String label) {
            if (label != null && !label.isEmpty()) {
                g.text(font, clip(label, right() - left()), left(), y, DIM, false);
                y += 11;
            }
            int bx0 = left(), bx1 = right(), w = bx1 - bx0;
            g.fill(bx0, y, bx1, y + 4, TRACK_BG);
            if (fraction >= 0) {
                g.fill(bx0, y, bx0 + (int) Math.round(w * Math.min(1, fraction)), y + 4, GREEN);
            } else {
                // Unknown progress: a segment sweeping across.
                int seg = Math.max(20, w / 4);
                double t = (System.currentTimeMillis() % 1400) / 1400.0;
                int sx = bx0 - seg + (int) ((w + seg) * t);
                g.fill(Math.max(bx0, sx), y, Math.min(bx1, sx + seg), y + 4, GREEN);
            }
            y += 10;
        }

        @Override
        public void tracks(String listKey, List<Track> tracks) {
            y = drawTrackList(g, x0, y, x1, mx, my, listKey, tracks);
        }

        @Override
        public void toast(String text) {
            showToast(text);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (cfg.vanilla) return super.keyPressed(event);
        if (renameBox != null) {
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                finishRename(true);
            } else if (event.key() == InputConstants.KEY_ESCAPE) {
                finishRename(false);
            } else {
                renameBox.keyPressed(event);
            }
            return true;
        }
        if (focusedField != null) {
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                submitField();
            } else if (event.key() == InputConstants.KEY_ESCAPE) {
                unfocusField();
            } else {
                focusedField.keyPressed(event);
            }
            return true;
        }
        if (menu != null && event.key() == InputConstants.KEY_ESCAPE) {
            menu = null;
            return true;
        }
        if (menu != null && event.key() == InputConstants.KEY_BACKSPACE && !menuFilter.isEmpty()) {
            menuFilter = menuFilter.substring(0, menuFilter.offsetByCodePoints(menuFilter.length(), -1));
            subScroll = 0;
            return true;
        }
        // While a menu is open, keys type into its playlist search rather than playing, pausing or seeking.
        if (menu != null) return true;
        if (event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk()) {
            if (view == View.SEARCH) focusSearch = true;
            else openSearch();
            return true;
        }
        if (selectionKey(event)) return true;
        if (event.key() == InputConstants.KEY_SPACE) {
            player.togglePause();
            return true;
        }
        if (CueMyMusicClient.openKey(event)) {
            onClose();
            return true;
        }
        if (CueMyMusicClient.mediaKey(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (renameBox != null) return renameBox.charTyped(event);
        if (focusedField != null) return focusedField.charTyped(event);
        if (menu != null && menuTyped(event)) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (menu != null) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) menuClicked(event.x(), event.y());
            else menu = null;
            return true;
        }
        if (renameBox != null) {
            if (renameBox.isMouseOver(event.x(), event.y())) return renameBox.mouseClicked(event, doubleClick);
            finishRename(true);
        }
        for (FieldHit f : fieldHits) {
            EditBox box = fields.get(f.key());
            if (box != null && f.contains(event.x(), event.y()) && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                if (focusedField != box) {
                    unfocusField();
                    focusedField = box;
                    box.setFocused(true);
                    setFocused(box);
                }
                box.mouseClicked(event, doubleClick);
                return true;
            }
        }
        unfocusField();
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            for (RowHit r : rowHits) {
                if (r.contains(event.x(), event.y())) {
                    if (selected.size() > 1 && selected.contains(r.track().location().toString())) {
                        openBulkMenu(selectedTracks(), (int) event.x(), (int) event.y());
                    } else {
                        clearSelection();
                        openMenu(r.track(), (int) event.x(), (int) event.y());
                    }
                    return true;
                }
            }
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            double x = event.x(), y = event.y();
            if (queueClicked(x, y, doubleClick)) return true;
            if (rowClicked(event)) return true;
            for (Drag d : drags) {
                if (d.contains(x, y)) {
                    dragging = d;
                    d.onFraction().accept(d.fraction(x, y));
                    return true;
                }
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).contains(x, y)) {
                    hits.get(i).click().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Queue rows: × removes, a double click plays, and pressing anywhere else starts a drag to reorder. */
    private boolean queueClicked(double x, double y, boolean doubleClick) {
        if (queueRows == 0 || x < queueX0 || x >= queueX1 || y < queueTop || y >= queueTop + queueRows * QROW) return false;
        int i = (int) Math.round(queueScroll) + (int) ((y - queueTop) / QROW);
        if (x >= queueX1 - 17) {
            player.unqueue(i);
        } else if (doubleClick) {
            player.playQueued(i);
        } else {
            queueDrag = i;
            queueDragY = y;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (pressed != null) {
            if (!reordering && Math.abs(event.y() - pressY) > 4) reordering = true;
            return true;
        }
        if (selectDragging) {
            int row = rowAt(event.y());
            if (row >= 0) {
                focusRow = row;
                selectRange(anchor, row);
            }
            return true;
        }
        if (queueDrag >= 0) {
            queueDragY = event.y();
            return true;
        }
        if (dragging != null) {
            dragging.onFraction().accept(dragging.fraction(event.x(), event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressed != null) {
            RowHit r = pressed;
            pressed = null;
            if (reordering) {
                reordering = false;
                dropRows(r, event.y());
            } else {
                // Just a click: what it would have done straight away.
                clearSelection();
                for (int i = hits.size() - 1; i >= 0; i--) {
                    if (hits.get(i).contains(pressX, pressY)) {
                        hits.get(i).click().run();
                        break;
                    }
                }
            }
            return true;
        }
        if (selectDragging) {
            selectDragging = false;
            return true;
        }
        if (queueDrag >= 0) {
            int from = queueDrag, slot = dropSlot(queueDragY);
            queueDrag = -1;
            player.moveQueued(from, slot > from ? slot - 1 : slot);
            return true;
        }
        if (dragging != null) {
            Drag d = dragging;
            dragging = null;
            d.onRelease().run();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (menu != null && menuScrolled(x, y, vertical)) return true;
        if (queueFit > 0 && x >= queueX0 && x < queueX1 && y >= queueTop && y < queueTop + queueFit * QROW) {
            queueScroll -= vertical;
            return true;
        }
        if (x >= libraryX0 && x < libraryX1 && y >= libraryScrollTop && y < libraryScrollBottom && libraryScrollMax > 0) {
            libraryScroll = Math.clamp(libraryScroll - vertical * 24, 0, libraryScrollMax);
            return true;
        }
        if (x >= centerX0 && x < centerX1 && y >= centerY0 && y < centerY1) {
            scroll = Math.clamp(scroll - vertical * 24, 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    // ---------------------------------------------------------------- drawing

    private void art(GuiGraphicsExtractor g, Art art, int x, int y, int size) {
        switch (art) {
            case Art.Texture tex -> g.blit(RenderPipelines.GUI_TEXTURED, tex.id(), x, y, 0, 0, size, size,
                    tex.width(), tex.height(), tex.width(), tex.height());
            case Art.Sprite sp -> {
                g.fillGradient(x, y, x + size, y + size, sp.top(), sp.bottom());
                int s = Math.max(5, size * 9 / 16);
                g.blitSprite(RenderPipelines.GUI_TEXTURED, sp.sprite(), x + (size - s) / 2, y + (size - s) / 2, s, s);
            }
            case Art.Initials ini -> {
                g.fillGradient(x, y, x + size, y + size, ini.top(), ini.bottom());
                float scale = Math.min(size * 0.7f / Math.max(1, font.width(ini.text())), size * 0.45f / 8);
                g.pose().pushMatrix();
                g.pose().translate(x + size / 2f, y + size / 2f);
                g.pose().scale(scale, scale);
                g.text(font, ini.text(), -font.width(ini.text()) / 2, -4, WHITE, false);
                g.pose().popMatrix();
            }
        }
    }

    private void bigText(GuiGraphicsExtractor g, String s, int x, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** Text that fits is drawn as is; longer text waits, then scrolls left in a loop. */
    private void marquee(GuiGraphicsExtractor g, String s, int x, int y, int w, int color, float scale) {
        float textW = font.width(s) * scale;
        if (textW <= w) {
            bigText(g, s, x, y, scale, color);
            return;
        }
        float gap = 24 * scale;
        float travel = textW + gap;
        double pauseMs = 2000, speed = 25 * scale / 1000.0;
        double cycle = pauseMs + travel / speed;
        double t = System.currentTimeMillis() % (long) cycle;
        float offset = t < pauseMs ? 0 : (float) ((t - pauseMs) * speed);
        g.enableScissor(x, y - 1, x + w, y + (int) Math.ceil(9 * scale) + 1);
        g.pose().pushMatrix();
        g.pose().translate(x - offset, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        g.text(font, s, Math.round(travel / scale), 0, color, false);
        g.pose().popMatrix();
        g.disableScissor();
    }

    private String clip(String s, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(s) <= maxWidth) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private static void roundButton(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        // A square with its corners knocked off reads as round at this size.
        g.fill(x + 2, y, x + size - 2, y + size, color);
        g.fill(x, y + 2, x + size, y + size - 2, color);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, color);
    }

    private static void playIcon(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        for (int i = 0; i < h; i++) {
            int w = Math.min(i, h - 1 - i) + 1;
            g.fill(x, y + i, x + w, y + i + 1, color);
        }
    }

    private static void pauseIcon(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        g.fill(x, y, x + 2, y + h, color);
        g.fill(x + 4, y, x + 6, y + h, color);
    }

    private static void nextIcon(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        playIcon(g, x, y, h, color);
        g.fill(x + h / 2 + 1, y, x + h / 2 + 3, y + h, color);
    }

    private static void previousIcon(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        g.fill(x, y, x + 2, y + h, color);
        for (int i = 0; i < h; i++) {
            int w = Math.min(i, h - 1 - i) + 1;
            g.fill(x + 2 + h / 2 + 1 - w, y + i, x + 2 + h / 2 + 1, y + i + 1, color);
        }
    }

    private static void clockIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 1, y, x + 6, y + 1, color);
        g.fill(x + 1, y + 6, x + 6, y + 7, color);
        g.fill(x, y + 1, x + 1, y + 6, color);
        g.fill(x + 6, y + 1, x + 7, y + 6, color);
        g.fill(x + 3, y + 2, x + 4, y + 4, color);
        g.fill(x + 4, y + 3, x + 5, y + 4, color);
    }

    /** A small triangle: pointing up for ascending, down for descending. */
    private static void arrow(GuiGraphicsExtractor g, int x, int y, boolean up, int color) {
        for (int i = 0; i < 3; i++) {
            int row = up ? i : 2 - i;
            g.fill(x + 2 - row, y + i + 1, x + 3 + row, y + i + 2, color);
        }
    }

    /** A 9×9 thumbs down, filled when the song is disliked: the game won't play it. */
    private static void dislikeIcon(GuiGraphicsExtractor g, int x, int y, boolean filled, int color) {
        String[] rows = filled
                ? new String[] {"XX.XXXXX.", "XX.XXXXX.", "XX.XXXXXX", "XX.XXXXX.", "XX.XXXXXX", "XX.XXXXX.", "...XXX...", "...XX....", "....X...."}
                : new String[] {"XX.XXXXX.", "XX.X...X.", "XX.X....X", "XX.X...X.", "XX.X....X", "XX.XX..X.", "...X.XX..", "...X.X...", "....X...."};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 9; c++) {
                if (rows[r].charAt(c) == 'X') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }

    /** A 7×6 heart, filled when the song is liked. */
    private static void heartIcon(GuiGraphicsExtractor g, int x, int y, boolean filled, int color) {
        String[] rows = filled
                ? new String[] {".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."}
                : new String[] {".XX.XX.", "X..X..X", "X.....X", ".X...X.", "..X.X..", "...X..."};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 7; c++) {
                if (rows[r].charAt(c) == 'X') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }

    /** How far a pitch, speed or EQ change has got to the speakers, 0 to 1, or -1 once it's heard. */
    private double applying() {
        CueStream.Params target = CueStream.Params.now();
        long due = CueStream.audibleAt(target);
        long now = System.nanoTime();
        if (due == 0) {
            applyTarget = null;
            return -1;
        }
        if (!target.equals(applyTarget)) {
            applyTarget = target;
            applyStart = now;
        }
        return due <= applyStart ? 1 : Math.clamp((now - applyStart) / (double) (due - applyStart), 0, 1);
    }

    /** A ring of 8 dots that fills as a change reaches the speakers, with one dot circling so it reads as working. */
    private static void applyingIcon(GuiGraphicsExtractor g, int x, int y, double progress) {
        int[][] dots = {{3, 0}, {5, 1}, {6, 3}, {5, 5}, {3, 6}, {1, 5}, {0, 3}, {1, 1}};
        int lit = (int) Math.ceil(progress * dots.length);
        int spin = (int) (System.currentTimeMillis() / 100 % dots.length);
        for (int i = 0; i < dots.length; i++) {
            int color = i < lit ? GREEN : i == spin ? WHITE : FAINT;
            g.fill(x + dots[i][0], y + dots[i][1], x + dots[i][0] + 1, y + dots[i][1] + 1, color);
        }
    }

    /** A loop of two arrows, 9 by 8; with a 1 inside for repeat one. */
    private static void repeatIcon(GuiGraphicsExtractor g, int x, int y, PlayOrder.Repeat mode, int color) {
        g.fill(x + 1, y + 1, x + 8, y + 2, color);
        g.fill(x + 8, y + 2, x + 9, y + 4, color);
        g.fill(x + 6, y, x + 7, y + 1, color);
        g.fill(x + 6, y + 2, x + 7, y + 3, color);
        g.fill(x + 1, y + 6, x + 8, y + 7, color);
        g.fill(x, y + 3, x + 1, y + 6, color);
        g.fill(x + 2, y + 5, x + 3, y + 6, color);
        g.fill(x + 2, y + 7, x + 3, y + 8, color);
        if (mode == PlayOrder.Repeat.ONE) {
            g.fill(x + 4, y + 2, x + 5, y + 6, color);
            g.fill(x + 3, y + 3, x + 4, y + 4, color);
        }
    }

    private static void plusIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y + 4, x + 9, y + 5, color);
        g.fill(x + 4, y, x + 5, y + 9, color);
    }

    private static void crossIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 7; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + 6 - i, y + i, x + 7 - i, y + i + 1, color);
        }
    }

    private void equalizerIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        // Three bars bouncing at different rates.
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            int h = 2 + (int) Math.round(3.5 * (1 + Math.sin(t / (140.0 + i * 45) + i * 1.7)));
            g.fill(x + i * 3, y + 9 - h, x + i * 3 + 2, y + 9, color);
        }
    }

    // ------------------------------------------------------------- formatting

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** "+1", "-1" or "0". */
    private static String signed(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    private static String time(double seconds) {
        int s = (int) Math.floor(seconds);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private static String longDuration(double seconds) {
        int minutes = (int) Math.round(seconds / 60);
        return minutes >= 60 ? tr("cuemymusic.duration.hours", minutes / 60, minutes % 60)
                : tr("cuemymusic.duration.minutes", minutes);
    }

    private static String frequencyLabel(double hz) {
        return hz >= 1000 ? (hz % 1000 == 0 ? (int) (hz / 1000) + "k" : String.format("%.1fk", hz / 1000)) : (int) hz + "";
    }

    private static int artistColor(String artist) {
        int[] palette = {0xFF2B4A2B, 0xFF4A1F2A, 0xFF4A3A1F, 0xFF1F334A, 0xFF2A2A4A, 0xFF3A4A1F, 0xFF333333};
        int i = Catalog.ARTISTS.indexOf(artist);
        return palette[i < 0 ? palette.length - 1 : i];
    }
}
