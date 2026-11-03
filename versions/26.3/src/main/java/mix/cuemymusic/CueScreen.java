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

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

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
    private static final Art STATS_ART = Art.block("block/cartography_table_top");
    private static final Art LIKED_ART = new Art.Sprite(Identifier.withDefaultNamespace("hud/heart/full"), 0xFF5A3BD6, 0xFF8FB6E0);
    private static final Art DISLIKED_ART = new Art.Initials("\u2298", 0xFF5A2A2A, 0xFF3A1E1E);
    private static final Art RADIO_ART = Art.block("block/jukebox_side");
    /** Blocks an artist with no art of their own gets one of, behind their initials. */
    private static final List<String> ARTIST_BLOCKS = List.of("oak_planks", "spruce_planks", "cherry_planks", "bamboo_planks",
            "crimson_planks", "warped_planks", "bricks", "mud_bricks", "stone_bricks", "deepslate_tiles", "moss_block",
            "amethyst_block", "prismarine", "terracotta", "blue_ice", "copper_block", "lapis_block", "purpur_block",
            "quartz_bricks", "red_sandstone", "end_stone_bricks", "honeycomb_block", "glowstone", "packed_mud", "calcite");
    /** Whether each block texture exists in this version of the game, as looked up once. */
    private static final Map<Identifier, Boolean> TEXTURE_EXISTS = new HashMap<>();
    private static final Map<Identifier, Integer> SHEET_HEIGHTS = new java.util.HashMap<>();
    private static final int LIB_ROW = 12;
    private static final int LIB_SECTION = 14;
    private static final Art NEW_PLAYLIST_ART = new Art.Initials("+", 0xFF2E2E2E, 0xFF242424);
    /** A block for each of the game's places, so each track pool looks like where it plays. */
    private static final Map<String, String> POOL_BLOCKS = Map.ofEntries(
            Map.entry("music.menu", "bookshelf"), Map.entry("music.game", "grass_block_side"),
            Map.entry("music.creative", "diamond_block"), Map.entry("music.under_water", "prismarine"),
            Map.entry("music.end", "end_stone"), Map.entry("music.dragon", "dragon_egg"), Map.entry("music.credits", "obsidian"),
            Map.entry("deep_dark", "sculk"), Map.entry("sulfur_caves", "sulfur"), Map.entry("dripstone_caves", "dripstone_block"), Map.entry("grove", "powder_snow"),
            Map.entry("jagged_peaks", "packed_ice"), Map.entry("lush_caves", "moss_block"), Map.entry("swamp", "mud"),
            Map.entry("forest", "oak_log"), Map.entry("old_growth_taiga", "podzol_top"), Map.entry("meadow", "hay_block_side"),
            Map.entry("cherry_grove", "cherry_leaves"), Map.entry("frozen_peaks", "ice"), Map.entry("snowy_slopes", "snow"),
            Map.entry("stony_peaks", "calcite"), Map.entry("flower_forest", "flowering_azalea_leaves"), Map.entry("desert", "sand"),
            Map.entry("badlands", "red_sand"), Map.entry("jungle", "jungle_log"), Map.entry("sparse_jungle", "jungle_planks"),
            Map.entry("bamboo_jungle", "bamboo_block"), Map.entry("nether_wastes", "netherrack"),
            Map.entry("warped_forest", "warped_nylium"), Map.entry("soul_sand_valley", "soul_sand"),
            Map.entry("crimson_forest", "crimson_nylium"), Map.entry("basalt_deltas", "basalt_side"));
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
    private enum View { PLAYLIST, LIKED, DISLIKED, RADIO, LIST, ARTIST, PACK, STATS, SEARCH, SETTINGS, PANEL, HOME, POOL }

    /** A library row's right-click menu, cut to where it's on screen. */
    private record LibraryMenu(int x0, int y0, int x1, int y1, java.util.function.Supplier<List<MenuItem>> items) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    /** A track row that opens the song menu when right-clicked. */
    private record RowHit(int x0, int y0, int x1, int y1, Track track, int index) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private enum MenuIcon { NONE, PLUS, LIKE, LIKED, DISLIKE, DISLIKED, QUEUE, PLAY, RADIO, POOL, RESET, ARTIST, REMOVE, DELETE }

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

    /** A draggable area; it only takes presses inside its clip, the part of it on screen. */
    private record Drag(int x0, int y0, int x1, int y1, boolean vertical, DoubleConsumer onFraction, Runnable onRelease,
            int clipX0, int clipY0, int clipX1, int clipY1) {
        Drag(int x0, int y0, int x1, int y1, boolean vertical, DoubleConsumer onFraction, Runnable onRelease) {
            this(x0, y0, x1, y1, vertical, onFraction, onRelease, x0, y0, x1, y1);
        }

        boolean contains(double x, double y) {
            return x >= Math.max(x0, clipX0) && x < Math.min(x1, clipX1) && y >= Math.max(y0, clipY0) && y < Math.min(y1, clipY1);
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
    private List<LibraryMenu> libraryMenus = new ArrayList<>();
    private List<LibraryMenu> nextLibraryMenus = new ArrayList<>();
    private Menu menu;
    /** What's typed in the menu's playlist search, whether that submenu is open, and how far it's scrolled. */
    private String menuFilter = "";
    private boolean subOpen;
    private int subScroll;
    /** The name box while a playlist is being renamed, else null. */
    private EditBox renameBox;
    private String renamingId;
    /** Until when a second click on Delete removes the playlist. */
    /** Until when a track pool's Reset, clicked once, takes a second click to do it. */
    private long resetArmedUntil;
    /** When Reset stats was first clicked; a second click soon after resets. */
    private long resetArmedAt;
    /** What's typed in the search box, kept while other pages are open. */
    private String searchQuery = "";
    /** Puts the cursor in the search box the next time it's drawn. */
    private boolean focusSearch;
    // The mix page: its songs, its title, and the transition being edited (the index of its first song), or -1 for the list.
    private List<Track> mixTracks = List.of();
    /** The transition open in the editor, as its two songs' ids; null for the mix's songs. */
    private String mixFrom, mixTo;
    private int mixEdit = -1;
    /** The open transition's two songs on screen, which scroll sideways to move it; null with none shown. */
    private int mixLaneX0, mixLaneY0, mixLaneX1, mixLaneY1;
    private DoubleConsumer mixLaneScroll, nextMixLaneScroll, mixLaneScrollNext, nextMixLaneScrollNext;
    /** Above this y scrolling moves the first song, below it the next one. */
    private int mixLaneSplit;
    /** The music volume before the speaker muted it. */
    private static double unmutedVolume = 1;
    /** Sideways scrolling not yet a whole beat, as trackpads send it in small amounts. */
    private double mixScrollRest;
    /** The transition being edited, played but not saved until Save; null with the editor closed. */
    private Automix.Transition mixDraft;
    // Dragging a song in the editor: seconds per pixel, each song's value now and how to set it, and the drag under way
    // (1 the first song, 2 the next), where it was pressed and the value then.
    private double mixLaneSpp, mixLaneValueA, mixLaneValueB;
    private DoubleConsumer mixLaneSetA, mixLaneSetB;
    private int laneDrag;
    private double laneDragX, laneDragBase;
    /** The presets row: how far it's scrolled sideways, how far it can be, and where it was drawn. */
    private double presetScroll;
    private int presetScrollMax, presetX0, presetY0, presetX1, presetY1;
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
    /** Next from's rows, as last drawn, and the one being dragged (-1 for none), where, and where it was pressed. */
    private int nextFromTop, nextFromRows, nextFromX0, nextFromX1;
    private int nextFromDrag = -1;
    private double nextFromDragY, nextFromPressY;
    private double queueDragY;

    public CueScreen(Screen parent) {
        super(Component.translatable("cuemymusic.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ audit

    /** Set by {@code /cmm audit}: the next player opened walks every page and mode, noting what doesn't make sense. */
    static volatile boolean auditRequested;
    /** The page being noted while an audit runs, else null. Everything drawn tells it what it shows. */
    private UiAudit.Page audit;
    /** The library section rows are being drawn in, for the audit. */
    private String auditSection = "top";
    private String auditOrigin = "";
    private List<Runnable> auditSteps;
    private List<String> auditNames;
    private int auditStep;
    private final List<UiAudit.Page> auditPages = new ArrayList<>();
    private String auditMode, auditRadio, auditLastPage;

    /** Lines up every page in both modes to be drawn once each, one a frame. */
    private void startAudit() {
        auditMode = cfg.mode;
        auditRadio = cfg.radioSource;
        auditLastPage = cfg.lastPage;
        auditSteps = new ArrayList<>();
        auditNames = new ArrayList<>();
        auditPages.clear();
        auditStep = 0;
        for (String mode : List.of("fx", "addons")) {
            String m = mode.equals("fx") ? "Vanilla + FX" : "Add-ons";
            auditStep(m + " / Home", () -> {
                Modes.set(mode);
                open(View.HOME, null);
            });
            for (View v : List.of(View.PLAYLIST, View.LIKED, View.DISLIKED, View.RADIO, View.SEARCH, View.STATS, View.SETTINGS)) {
                auditStep(m + " / " + words(v.name().toLowerCase(java.util.Locale.ROOT)), () -> open(v, null));
            }
            List<String> artists = new ArrayList<>(mode.equals("addons") ? MusicLibrary.packArtists() : Catalog.ARTISTS);
            for (String a : artists) auditStep(m + " / Artist: " + a, () -> open(View.ARTIST, a));
            if (mode.equals("addons")) {
                for (PackInfo p : MusicLibrary.packs()) auditStep(m + " / Pack: " + p.name(), () -> open(View.PACK, p.id()));
                for (CuePanel panel : CuePanels.all()) auditStep(m + " / " + panel.title(), () -> open(View.PANEL, panel.id()));
            } else {
                for (Identifier pool : sortedPools()) auditStep(m + " / Pool: " + poolName(pool), () -> open(View.POOL, pool.toString()));
            }
            for (CueConfig.Playlist p : cfg.playlists) auditStep(m + " / Playlist: " + p.name(), () -> open(View.LIST, p.id()));
        }
    }

    private void auditStep(String name, Runnable go) {
        auditNames.add(name);
        auditSteps.add(go);
    }

    /** Before each frame of an audit: keeps the page just drawn and goes to the next, or at the end writes the report. */
    private void auditFrame() {
        if (audit != null) auditPages.add(audit);
        audit = null;
        if (auditStep >= auditSteps.size()) {
            finishAudit();
            return;
        }
        auditSteps.get(auditStep).run();
        audit = new UiAudit.Page(auditNames.get(auditStep++));
        audit.fact("mode", mode());
        Track t = player.current();
        if (t != null) audit.fact("playing", t.title());
        audit.fact("repeat", player.repeat().name().toLowerCase(java.util.Locale.ROOT));
        audit.fact("endless", player.isPlayingList(CuePlayer.RADIO));
        audit.fact("list", player.isPlayingList(CuePlayer.RADIO) ? "Radio" : "a list");
    }

    /** Puts the mode and page back as they were, and writes what was found to config/cuemymusic-audit.md. */
    private void finishAudit() {
        auditSteps = null;
        Modes.set(auditMode);
        cfg.radioSource = auditRadio;
        player.refreshRadio();
        String page = auditLastPage;
        int colon = page.indexOf(':');
        try {
            open(View.valueOf(colon < 0 ? page : page.substring(0, colon)), colon < 0 ? null : page.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            open(View.HOME, null);
        }
        cfg.save();
        List<UiAudit.Finding> found = UiAudit.check(auditPages);
        java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("cuemymusic-audit.md");
        try {
            java.nio.file.Files.writeString(file, UiAudit.report(found, auditPages.size()));
            showToast(tr("cuemymusic.audit.done", found.size(), auditPages.size()));
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("cuemymusic").warn("Couldn't write {}", file, e);
            showToast(tr("cuemymusic.audit.failed"));
        }
    }

    /** True once the page last open has been opened again; init runs again on every resize. */
    private boolean restored;

    @Override
    protected void init() {
        // Once per opening, not on every resize: reloads keep the library up to date in between.
        if (!restored) {
            restored = true;
            if (auditRequested) {
                auditRequested = false;
                startAudit();
            }
            MusicLibrary.refresh();
            String page = cfg.lastPage.isEmpty() ? View.HOME.name() : cfg.lastPage;
            int colon = page.indexOf(':');
            try {
                View v = View.valueOf(colon < 0 ? page : page.substring(0, colon));
                // Search opens empty, so it starts on the box.
                if (v == View.SEARCH) openSearch();
                else open(v, colon < 0 ? null : page.substring(colon + 1));
            } catch (IllegalArgumentException e) {
                // Nothing saved yet, or a page that's gone: all game music.
            }
        }
    }

    @Override
    public void onClose() {
        closeTransition();
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
        nextLibraryMenus = new ArrayList<>();
        nextFieldHits = new ArrayList<>();
        nextMixLaneScroll = null;
        nextMixLaneScrollNext = null;
        this.partialTick = partialTick;
        if (auditSteps != null) auditFrame();
        auditSection = "top";
        if (cfg.vanilla) {
            drawVanilla(g, mouseX, mouseY);
            hits = nextHits;
            drags = nextDrags;
            rowHits = nextRowHits;
            fieldHits = nextFieldHits;
        mixLaneScroll = nextMixLaneScroll;
        mixLaneScrollNext = nextMixLaneScrollNext;
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
        libraryMenus = nextLibraryMenus;
        fieldHits = nextFieldHits;
        mixLaneScroll = nextMixLaneScroll;
        mixLaneScrollNext = nextMixLaneScrollNext;
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
        drawModeSwitch(g, x0, y0 - 40, x0 + w, mx, my);
        bigText(g, tr("cuemymusic.vanilla.title"), x0 + 14, y0 + 14, 2, WHITE);
        g.textWithWordWrap(font, body, x0 + 14, y0 + 40, w - 28, DIM, false);
        Canvas c = new Canvas(g, x0 + 4, y0 + 48 + bodyH, x0 + w - 4, mx, my);
        int bw = c.button(c.left(), tr("cuemymusic.vanilla.off"), PanelCanvas.ButtonStyle.PRIMARY, true, () -> setVanilla(false));
        c.button(c.left() + bw + 6, tr("cuemymusic.vanilla.close"), PanelCanvas.ButtonStyle.SECONDARY, true, this::onClose);
    }

    private void setVanilla(boolean on) {
        Modes.setVanilla(on);
    }

    // ---------------------------------------------------------------- library

    private void drawLibrary(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        g.fill(x0, y0, x1, y1, PANEL);
        g.text(font, clip(tr("cuemymusic.library"), x1 - x0 - 42), x0 + 6, y0 + 7, WHITE, false);
        // Search, at the right of the heading.
        int sx = x1 - 15, sy = y0 + 5;
        boolean searchHover = mx >= sx - 3 && mx < sx + 12 && my >= sy - 3 && my < sy + 12;
        searchIcon(g, sx, sy, view == View.SEARCH ? GREEN : searchHover ? WHITE : DIM);
        nextHits.add(new Hit(sx - 3, sy - 3, sx + 12, sy + 12, this::openSearch));
        // Home, left of search.
        int hx = sx - 15;
        boolean homeHover = mx >= hx - 3 && mx < hx + 12 && my >= sy - 3 && my < sy + 12;
        pixels(g, hx, sy, view == View.HOME ? GREEN : homeHover ? WHITE : DIM,
                "....X....", "...XXX...", "..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", ".XX...XX.", ".XX...XX.", ".XX...XX.", ".XXXXXXX.");
        nextHits.add(new Hit(hx - 3, sy - 3, hx + 12, sy + 12, () -> open(View.HOME, null)));

        List<Track> all = MusicLibrary.gameMusic();
        // Each mode shows its own music: the game's artists, or the add-ons' artists and packs.
        boolean addons = mode().equals("addons");
        List<String> artists = new ArrayList<>();
        if (addons) {
            artists.addAll(MusicLibrary.packArtists());
        } else {
            artists.addAll(Catalog.ARTISTS.stream().filter(a -> !MusicLibrary.byArtist(a).isEmpty()).toList());
            if (MusicLibrary.hasUnknown()) artists.add(Catalog.UNKNOWN_ARTIST);
        }
        List<CuePanel> panels = CuePanels.all();
        List<PackInfo> packs = MusicLibrary.packs().stream()
                .filter(p -> panels.stream().noneMatch(panel -> p.id().equals(panel.packId()))).toList();
        List<CueConfig.Playlist> playlists = cfg.playlists;
        List<Track> disliked = disliked();
        artists = librarySorted(artists, a -> a, a -> "artist:" + a);
        packs = librarySorted(packs, PackInfo::name, p -> "pack:" + p.id());
        playlists = librarySorted(playlists, CueConfig.Playlist::name, p -> "list:" + p.id());

        // Stats and Settings stay at the bottom; everything above them scrolls.
        int big = y1 - y0 > 200 ? 22 : 14;
        int settingsY = y1 - big - 2;
        int statsY = settingsY - big;
        libraryX0 = x0;
        libraryX1 = x1;
        libraryTop = drawLibraryTools(g, x0, y0 + 18, x1, mx, my);
        libraryBottom = statsY - 2;
        g.enableScissor(x0, libraryTop, x1, libraryBottom);
        int y = libraryTop + 2 - (int) libraryScroll;
        int start = y;

        {
            if (!addons) {
                y = libraryItem(g, x0, y, x1, big, PLAYLIST_ART, tr("cuemymusic.playlist.title"),
                        tr("cuemymusic.playlist.subtitle", all.size()), view == View.PLAYLIST, mx, my, () -> open(View.PLAYLIST, null),
                        () -> listMenu(CuePlayer.ALL, all, null));
            }
            // Liked Songs is there once something is liked, like any playlist.
            List<Track> liked = liked();
            if (!liked.isEmpty() || view == View.LIKED) {
                y = libraryItem(g, x0, y, x1, big, LIKED_ART, tr("cuemymusic.liked.title"),
                        tr("cuemymusic.playlist.subtitle", liked.size()), view == View.LIKED, mx, my, () -> open(View.LIKED, null),
                        () -> listMenu("liked", liked, null));
            }
            // Radio is for add-on songs; the game's music already comes on its own in Vanilla + FX.
            if (addons) y = libraryItem(g, x0, y, x1, big, RADIO_ART, tr("cuemymusic.radio.title"), tr("cuemymusic.radio.subtitle"),
                    view == View.RADIO, mx, my, () -> open(View.RADIO, null), () -> List.of(
                            new MenuItem(MenuIcon.RADIO, tr("cuemymusic.radio.start_all"), () -> {
                                player.startRadio(null);
                                open(View.RADIO, null);
                            })));
            if (!disliked.isEmpty() || view == View.DISLIKED) {
                y = libraryItem(g, x0, y, x1, big, DISLIKED_ART, tr("cuemymusic.disliked.title"),
                        tr("cuemymusic.playlist.subtitle", disliked.size()), view == View.DISLIKED, mx, my, () -> open(View.DISLIKED, null));
            }
            for (CuePanel panel : addons ? panels : List.<CuePanel>of()) {
                y = libraryItem(g, x0, y, x1, big, panel.icon(), panel.title(), panel.subtitle(),
                        view == View.PANEL && panel.id().equals(key), mx, my, () -> open(View.PANEL, panel.id()));
            }
            y += 4;
        }

        if (!artists.isEmpty()) {
            y = librarySection(g, x0, y, x1, "artists", tr("cuemymusic.section.artists"), artists.size(), mx, my);
            if (!cfg.isCollapsed("artists")) {
                for (String a : artists) {
                    Art art = artistArt(a);
                    y = libraryItem(g, x0, y, x1, LIB_ROW, art, a, null, view == View.ARTIST && a.equals(key), mx, my,
                            () -> open(View.ARTIST, a), () -> listMenu("artist:" + a, Modes.only(MusicLibrary.songsBy(a)), null));
                }
            }
        }
        if (addons && !packs.isEmpty()) {
            y = librarySection(g, x0, y, x1, "packs", tr("cuemymusic.section.packs"), packs.size(), mx, my);
            if (!cfg.isCollapsed("packs")) {
                for (PackInfo p : packs) {
                    y = libraryItem(g, x0, y, x1, LIB_ROW, p.art(), p.name(), null, view == View.PACK && p.id().equals(key), mx, my,
                            () -> open(View.PACK, p.id()), () -> listMenu("pack:" + p.id(), Modes.only(MusicLibrary.byPack(p.id())), null));
                }
            }
        }
        {
            // Your playlists and, in Vanilla + FX, the game's track pools: playlists too, that you can change.
            List<LibraryList> lists = new ArrayList<>();
            for (CueConfig.Playlist p : playlists) {
                lists.add(new LibraryList("list:" + p.id(), p.name(), Art.initials(p.name()), "", view == View.LIST && p.id().equals(key),
                        () -> open(View.LIST, p.id()), () -> listMenu("list:" + p.id(), Modes.only(MusicLibrary.tracks(p.songs())), p)));
            }
            for (Identifier p : addons ? List.<Identifier>of() : sortedPools()) {
                String id = p.toString();
                String name = cfg.poolEdit(id).isEmpty() ? poolName(p) : tr("cuemymusic.pool.edited_name", poolName(p));
                lists.add(new LibraryList("pool:" + id, name, poolArt(p), p.getNamespace().equals("minecraft") ? "game" : "addon",
                        view == View.POOL && id.equals(key), () -> open(View.POOL, id), () -> poolListMenu(p)));
            }
            lists = librarySorted(lists, LibraryList::name, LibraryList::key);
            y = librarySection(g, x0, y, x1, "playlists", tr("cuemymusic.section.playlists"), lists.size(), mx, my);
            if (!cfg.isCollapsed("playlists") || lists.isEmpty()) {
                for (LibraryList l : lists) {
                    auditOrigin = l.origin();
                    y = libraryItem(g, x0, y, x1, LIB_ROW, l.art(), l.name(), null, l.selected(), mx, my, l.open(), l.menu());
                }
                auditOrigin = "";
                y = libraryItem(g, x0, y, x1, LIB_ROW, NEW_PLAYLIST_ART, tr("cuemymusic.playlists.new"), null, false, mx, my,
                        this::newPlaylist);
            }
        }
        auditOrigin = "";
        auditSection = "bottom";
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

    /** The add-on page that stands in for pack {@code packId}, or null. */
    private static CuePanel panelFor(String packId) {
        for (CuePanel p : CuePanels.all()) {
            if (packId.equals(p.packId())) return p;
        }
        return null;
    }

    /** A playlist in the library: one of yours, or one of the game's track pools. */
    private record LibraryList(String key, String name, Art art, String origin, boolean selected, Runnable open,
            java.util.function.Supplier<List<MenuItem>> menu) {
    }

    private static final List<String> LIBRARY_SORTS = List.of("recents", "alphabetical", "default");
    private static final List<String> MODES = Modes.ALL;

    /** The mode: "vanilla" (pure vanilla, the mod off), "fx" (the game's music with effects) or "addons". */
    private String mode() {
        return Modes.current();
    }

    /** Switches mode, staying on Settings if that's where it was switched. */
    private void setMode(String m) {
        if (!Modes.set(m)) return;
        libraryScroll = 0;
        if (!m.equals("vanilla") && view != View.SETTINGS) open(View.HOME, null);
        showToast(tr("cuemymusic.mode." + m + ".toast"));
    }

    /**
     * The three modes as one switch, the one on in white, with a line saying
     * what it means under it. Returns the y below.
     */
    private int drawModeSwitch(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        int h = 14, w = x1 - x0;
        String current = mode();
        // Full names where they fit, short ones in a narrow library.
        boolean full = MODES.stream().allMatch(m -> font.width(tr("cuemymusic.mode." + m)) + 6 <= w / 3);
        List<String> labels = MODES.stream().map(m -> tr("cuemymusic.mode." + m + (full ? "" : ".short"))).toList();
        y = segmented(g, x0, y, x1, mx, my, labels, MODES.indexOf(current), i -> setMode(MODES.get(i)));
        Component what = Component.translatable("cuemymusic.mode." + current + ".desc");
        g.textWithWordWrap(font, what, x0, y, w, FAINT, false);
        return y + font.wordWrapHeight(what, w) + 4;
    }

    /**
     * Choices side by side in one bar, the one picked in white, as the Mode
     * switch and an add-on's tabs. Returns the y below.
     */
    private int segmented(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, List<String> labels, int picked,
            java.util.function.IntConsumer pick) {
        int h = 14, n = labels.size(), w = x1 - x0;
        g.fill(x0, y, x1, y + h, 0xFF2A2A2A);
        for (int i = 0; i < n; i++) {
            int sx0 = x0 + w * i / n, sx1 = x0 + w * (i + 1) / n, at = i;
            boolean on = i == picked, hover = mx >= sx0 && mx < sx1 && my >= y && my < y + h;
            if (on) g.fill(sx0, y, sx1, y + h, WHITE);
            else if (hover) g.fill(sx0, y, sx1, y + h, 0xFF3A3A3A);
            if (i > 0 && !on && i - 1 != picked) g.fill(sx0, y + 3, sx0 + 1, y + h - 3, 0xFF4D4D4D);
            String label = clip("button", labels.get(i), sx1 - sx0 - 4);
            g.text(font, label, (sx0 + sx1 - font.width(label)) / 2, y + 3, on ? BLACK : WHITE, false);
            nextHits.add(new Hit(sx0, y, sx1, y + h, () -> pick.accept(at)));
        }
        return y + h + 4;
    }

    /** Under the library's heading: the library's order. Returns the y below. */
    private int drawLibraryTools(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        y += 2;
        String sort = tr("cuemymusic.library.sort." + cfg.librarySort) + " \u25BC";
        int sw = font.width(sort);
        boolean hover = mx >= x0 + 4 && mx < x0 + 8 + sw && my >= y - 1 && my < y + 10;
        g.text(font, clip(sort, x1 - x0 - 12), x0 + 6, y, hover ? WHITE : DIM, false);
        int menuY = y + 11;
        nextHits.add(new Hit(x0 + 4, y - 1, x0 + 8 + sw, y + 10, () -> {
            List<MenuItem> items = new ArrayList<>();
            for (String option : LIBRARY_SORTS) {
                String label = (option.equals(cfg.librarySort) ? "\u2713 " : "    ") + tr("cuemymusic.library.sort." + option);
                items.add(new MenuItem(label, () -> {
                    cfg.librarySort = option;
                    cfg.save();
                }));
            }
            showMenu(new Menu(x0 + 4, menuY, items));
        }));
        return y + 12;
    }

    /** {@code items} in the library's order: as they come, by name, or most recently played first. */
    private <T> List<T> librarySorted(List<T> items, java.util.function.Function<T, String> name,
            java.util.function.Function<T, String> listKey) {
        return switch (cfg.librarySort) {
            case "alphabetical" -> items.stream().sorted(java.util.Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER)).toList();
            // Never played keeps its place, after the ones that were.
            case "recents" -> items.stream().sorted(java.util.Comparator.comparingLong(
                    (T t) -> -cfg.listsPlayed.getOrDefault(listKey.apply(t), 0L))).toList();
            default -> items;
        };
    }

    /** A section heading that folds its rows away when clicked. Shows the count while folded. */
    private int librarySection(GuiGraphicsExtractor g, int x0, int y, int x1, String id, String label, int count, int mx, int my) {
        // An empty section stays open, so what would go in it (such as New playlist) shows.
        boolean folded = cfg.isCollapsed(id) && count > 0;
        auditSection = id;
        if (audit != null && folded) audit.fact("count " + label, count);
        boolean hover = libraryHover(x0, y, x1, y + LIB_SECTION, mx, my);
        int color = hover ? WHITE : DIM;
        int ay = y + 5;
        if (folded) {
            for (int i = 0; i < 3; i++) g.fill(x0 + 7 + i, ay + i - 1, x0 + 8 + i, ay + 4 - i, color);
        } else {
            for (int i = 0; i < 3; i++) g.fill(x0 + 6 + i, ay + i, x0 + 11 - i, ay + i + 1, color);
        }
        String text = folded ? label + "  " + count : label;
        g.text(font, clip("heading", text, x1 - x0 - 20), x0 + 15, y + 3, color, false);
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

    private int libraryItem(GuiGraphicsExtractor g, int x0, int y, int x1, int h, Art art, String title,
            String subtitle, boolean selected, int mx, int my, Runnable click) {
        return libraryItem(g, x0, y, x1, h, art, title, subtitle, selected, mx, my, click, null);
    }

    /**
     * The right-click menu for a list in the library: play it, queue it, start
     * a radio from it, and for one of your playlists rename or delete it.
     */
    private List<MenuItem> listMenu(String listKey, List<Track> tracks, CueConfig.Playlist playlist) {
        List<MenuItem> items = new ArrayList<>();
        if (!tracks.isEmpty()) {
            items.add(new MenuItem(MenuIcon.PLAY, tr("cuemymusic.menu.play"), () -> player.playAll(listKey, tracks)));
            items.add(new MenuItem(MenuIcon.PLUS, tr("cuemymusic.queue.add_all"), () -> {
                player.enqueueAll(tracks);
                showToast(tr("cuemymusic.queue.added_all", tracks.size()));
            }));
            if (radioHere()) {
                items.add(new MenuItem(MenuIcon.RADIO, tr("cuemymusic.menu.radio"), () -> {
                    player.startRadio(tracks.get(new java.util.Random().nextInt(tracks.size())));
                    open(View.RADIO, null);
                }));
            }
        }
        if (playlist != null) {
            String id = playlist.id();
            items.add(MenuItem.DIVIDER);
            items.add(new MenuItem(tr("cuemymusic.playlists.rename"), () -> {
                open(View.LIST, id);
                startRename(id);
            }));
            items.add(new MenuItem(MenuIcon.DELETE, tr("cuemymusic.playlists.delete"), () -> showMenu(new Menu(menuX, menuY, List.of(
                    new MenuItem(MenuIcon.DELETE, tr("cuemymusic.playlists.delete_named", playlist.name()), () -> {
                        cfg.deletePlaylist(id);
                        if (view == View.LIST && id.equals(key)) open(View.PLAYLIST, null);
                        showToast(tr("cuemymusic.playlists.deleted"));
                    }))))));
        }
        return items;
    }

    /** Where the last menu opened, so a confirmation can open in its place. */
    private int menuX, menuY;

    /** A library row. With a {@code subtitle} and room for it, two lines; otherwise one compact line. */
    private int libraryItem(GuiGraphicsExtractor g, int x0, int y, int x1, int h, Art art, String title,
            String subtitle, boolean selected, int mx, int my, Runnable click, java.util.function.Supplier<List<MenuItem>> menuItems) {
        if (audit != null) audit.row(auditSection, title, String.valueOf(art), auditOrigin);
        if (y + h < libraryTop || y > libraryBottom) return y + h;
        boolean hover = libraryHover(x0, y, x1, y + h, mx, my);
        if (selected || hover) g.fill(x0 + 2, y, x1 - 2, y + h, selected ? SELECTED : ROW_HOVER);
        int artSize = Math.min(16, h - 2);
        int ax = subtitle == null ? x0 + 15 : x0 + 5;
        art(g, art, ax, y + (h - artSize) / 2, artSize);
        int tx = ax + artSize + 4;
        int w = x1 - tx - 5;
        if (subtitle != null && h >= 21) {
            g.text(font, clip("label", title, w), tx, y + 3, selected ? GREEN : WHITE, false);
            g.text(font, clip(subtitle, w), tx, y + 12, DIM, false);
        } else {
            g.text(font, clip(title, w), tx, y + (h - 8) / 2, selected ? GREEN : subtitle == null && !hover ? DIM : WHITE, false);
        }
        libraryHit(x0, y, x1, y + h, click);
        int top = Math.max(y, libraryTop), bottom = Math.min(y + h, libraryBottom);
        if (menuItems != null && bottom > top) nextLibraryMenus.add(new LibraryMenu(x0, top, x1, bottom, menuItems));
        return y + h;
    }

    /** Radio is only in Add-ons: in Vanilla + FX the game's music comes on its own. */
    private boolean radioHere() {
        return mode().equals("addons");
    }

    private void open(View v, String k) {
        if (v == View.RADIO && !radioHere()) v = View.HOME;
        // A pack an add-on's page stands in for opens as that page: one place for its songs.
        CuePanel standIn = v == View.PACK && k != null ? panelFor(k) : null;
        if (standIn != null) {
            v = View.PANEL;
            k = standIn.id();
        }
        // Each mode's own pages: the game's lists in Vanilla + FX, the packs in Add-ons.
        boolean addons = mode().equals("addons");
        if (addons && (v == View.PLAYLIST || v == View.POOL) || !addons && (v == View.PACK || v == View.PANEL)) {
            v = View.HOME;
            k = null;
        }
        cfg.lastPage = k == null ? v.name() : v.name() + ":" + k;
        finishRename(true);
        clearSelection();
        if (v != view || !java.util.Objects.equals(k, key)) {
            fields.clear();
            fieldSubmits.clear();
            unfocusField();
            pageFilterOpen = false;
            closeTransition();
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
        // Track pools are the game's music: Vanilla + FX only.
        if (mode().equals("fx") && !MusicLibrary.pools().isEmpty()) {
            items.add(new MenuItem(MenuIcon.POOL, tr("cuemymusic.menu.add_to_pool"),
                    () -> showMenu(new Menu(x, y, poolItems(x, y, pools -> addToPools(List.of(t), pools))))));
        }
        boolean liked = isLiked(t);
        items.add(new MenuItem(liked ? MenuIcon.LIKED : MenuIcon.LIKE, tr(liked ? "cuemymusic.menu.unlike" : "cuemymusic.menu.like"), () -> toggleLiked(t)));
        items.add(new MenuItem(MenuIcon.QUEUE, tr("cuemymusic.menu.queue"), () -> {
            player.enqueue(t);
            showToast(tr("cuemymusic.queue.added"));
        }));
        if (Modes.allows(t)) {
            boolean disliked = isDisliked(t);
            items.add(new MenuItem(disliked ? MenuIcon.DISLIKED : MenuIcon.DISLIKE,
                    tr(disliked ? "cuemymusic.menu.undislike" : "cuemymusic.menu.dislike"), () -> toggleDisliked(t)));
        }
        items.add(MenuItem.DIVIDER);
        if (radioHere()) {
            items.add(new MenuItem(MenuIcon.RADIO, tr("cuemymusic.menu.song_radio"), () -> {
                player.startRadio(t);
                open(View.RADIO, null);
            }));
        }
        if (!t.artist().isBlank() && !(view == View.ARTIST && t.artist().equals(key))) {
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
        if (view == View.POOL) {
            String pool = key;
            removing.add(new MenuItem(MenuIcon.REMOVE, tr("cuemymusic.menu.remove_from_pool"), () -> removeFromPool(List.of(t), pool)));
        }
        CuePanel panel = view == View.PANEL ? CuePanels.get(key) : panelFor(t);
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
        menuX = m.x();
        menuY = m.y();
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
            case PLAY -> pixels(g, x, y + 1, color, "XX.......", "XXXX.....", "XXXXXX...", "XXXXXXXX.", "XXXXXX...", "XXXX.....",
                    "XX.......");
            // A dot sending out waves.
            case RADIO -> pixels(g, x, y, color, ".X.....X.", "X..X.X..X", "X.X...X.X", "X.X.X.X.X", "X.X...X.X", "X..X.X..X",
                    ".X.....X.", "....X....", "...XXX...");
            // Two notes: a place's music.
            case POOL -> pixels(g, x, y, color, "...XXXXXX", "...X....X", "...X....X", "...X....X", ".XXX..XXX", "XXXX.XXXX",
                    ".XX...XX.");
            case RESET -> pixels(g, x, y, color, "..XXXX.X.", ".X....XX.", "X....XXX.", "X........", "X.......X", "X.......X",
                    ".X.....X.", "..XXXXX..");
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
        int hitsFrom = nextHits.size(), dragsFrom = nextDrags.size(), fieldsFrom = nextFieldHits.size();
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
            case RADIO -> drawRadio(g, x0, contentTop, x1, mx, my);
            case HOME -> drawHome(g, x0, contentTop, x1, mx, my);
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
            case ARTIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, "artist:" + key, Modes.only(MusicLibrary.songsBy(key)),
                    artistArt(key), tr("cuemymusic.artist"),
                    key, null, artistColor(key));
            case PACK -> drawTrackPage(g, x0, contentTop, x1, mx, my, "pack:" + key, Modes.only(MusicLibrary.byPack(key)), pack.art(),
                    tr("cuemymusic.pack"), pack.name(), pack.author(), PackNames.gradient(pack.name())[1]);
            case PLAYLIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, CuePlayer.ALL, gameMusicShown(), PLAYLIST_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.playlist.title"), null, 0xFF1E3A2A);
            case LIKED -> drawTrackPage(g, x0, contentTop, x1, mx, my, "liked", liked(), LIKED_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.liked.title"), null, 0xFF3A2E6E);
            case DISLIKED -> drawTrackPage(g, x0, contentTop, x1, mx, my, "disliked", disliked(), DISLIKED_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.disliked.title"), tr("cuemymusic.disliked.byline"), 0xFF4A2424);
            case POOL -> {
                Identifier p = key == null ? null : Identifier.tryParse(key);
                if (p == null || !MusicLibrary.pools().contains(p)) {
                    view = View.PLAYLIST;
                    yield contentTop;
                }
                yield drawTrackPage(g, x0, contentTop, x1, mx, my, "pool:" + key, poolTracks(p), poolArt(p), tr("cuemymusic.playlist"),
                        poolName(p), tr(cfg.poolEdit(key).isEmpty() ? "cuemymusic.pool.byline" : "cuemymusic.pool.byline_edited"),
                        0xFF24384A);
            }
            case LIST -> {
                CueConfig.Playlist p = cfg.playlist(key);
                if (p == null) {
                    view = View.PLAYLIST;
                    yield contentTop;
                }
                yield drawTrackPage(g, x0, contentTop, x1, mx, my, "list:" + p.id(), Modes.only(MusicLibrary.tracks(p.songs())),
                        Art.initials(p.name()), tr("cuemymusic.playlist"), p.name(), null, PackNames.gradient(p.name())[1]);
            }
        };
        g.disableScissor();
        clipToCenter(hitsFrom, dragsFrom, fieldsFrom);
        scrollMax = Math.max(0, contentBottom + (int) scroll - y1 + 8);
        scroll = Math.clamp(scroll, 0, scrollMax);
    }

    /**
     * Cuts what the centre panel just made clickable down to the part on
     * screen, so something scrolled under the bottom bar, say, can't be clicked
     * through it. Takes the hits, drags and text fields from these indexes on.
     */
    private void clipToCenter(int hitsFrom, int dragsFrom, int fieldsFrom) {
        int cx0 = centerX0, cy0 = centerY0, cx1 = centerX1, cy1 = centerY1;
        for (int i = nextHits.size() - 1; i >= hitsFrom; i--) {
            Hit h = nextHits.get(i);
            int x0 = Math.max(h.x0(), cx0), y0 = Math.max(h.y0(), cy0), x1 = Math.min(h.x1(), cx1), y1 = Math.min(h.y1(), cy1);
            if (x0 >= x1 || y0 >= y1) nextHits.remove(i);
            else nextHits.set(i, new Hit(x0, y0, x1, y1, h.click()));
        }
        for (int i = nextDrags.size() - 1; i >= dragsFrom; i--) {
            Drag d = nextDrags.get(i);
            nextDrags.set(i, new Drag(d.x0(), d.y0(), d.x1(), d.y1(), d.vertical(), d.onFraction(), d.onRelease(),
                    Math.max(d.clipX0(), cx0), Math.max(d.clipY0(), cy0), Math.min(d.clipX1(), cx1), Math.min(d.clipY1(), cy1)));
        }
        for (int i = nextFieldHits.size() - 1; i >= fieldsFrom; i--) {
            FieldHit f = nextFieldHits.get(i);
            int x0 = Math.max(f.x0(), cx0), y0 = Math.max(f.y0(), cy0), x1 = Math.min(f.x1(), cx1), y1 = Math.min(f.y1(), cy1);
            if (x0 >= x1 || y0 >= y1) nextFieldHits.remove(i);
            else nextFieldHits.set(i, new FieldHit(x0, y0, x1, y1, f.key()));
        }
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
            if (audit != null) audit.box("Search this page", ix - 1, y + 4, right + 1, y + 16);
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
        if (audit != null) {
            audit.kind = kind;
            audit.title = title;
            audit.summary = summary;
        }
        g.text(font, kind, tx, y + 14, WHITE, false);
        if (view == View.LIST && renameBox != null) {
            // Renaming: the name box sits where the title was.
            renameBox.setX(tx);
            renameBox.setY(y + 27);
            renameBox.setWidth(Math.max(60, x1 - tx - 12));
        } else {
            bigText(g, clip("heading", title, (x1 - tx - 8) / 2), tx, y + 26, 2, WHITE);
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
            else player.playAll(listKey, tracks);
        }));
        // Shuffle, beside it as on Spotify: green while on.
        int sx = x0 + 10 + pb + 9, sy = y + 6 + 5;
        shuffleIcon(g, sx, sy, player.shuffle() ? GREEN : hoverColor(sx - 3, y + 6, 15, pb, mx, my));
        if (player.shuffle()) g.fill(sx + 4, sy + 10, sx + 6, sy + 11, GREEN);
        nextHits.add(new Hit(sx - 3, y + 6, sx + 12, y + 6 + pb, this::toggleShuffle));
        // A playlist's Rename and Delete are in its "..." menu, as on Spotify, which leaves the toolbar room.
        if (view == View.LIST && cfg.playlist(key) != null) {
            CueConfig.Playlist p = cfg.playlist(key);
            int dx = x1 - 22, ay = y + 6;
            boolean hover = mx >= dx && mx < dx + 14 && my >= ay && my < ay + 18;
            if (audit != null) audit.box("Playlist menu", dx, ay, dx + 14, ay + 18);
            for (int i = 0; i < 3; i++) g.fill(dx + 2 + i * 4, ay + 8, dx + 4 + i * 4, ay + 10, hover ? WHITE : DIM);
            nextHits.add(new Hit(dx, ay, dx + 14, ay + 18,
                    () -> showMenu(new Menu(dx, ay + 18, listMenu("list:" + p.id(), Modes.only(MusicLibrary.tracks(p.songs())), p)))));
            toolRight = dx - 8;
        }
        // A track pool you've changed can be put back as the game has it; that takes a second click.
        if (view == View.POOL && !cfg.poolEdit(key).isEmpty()) {
            String pool = key;
            boolean armed = System.currentTimeMillis() < resetArmedUntil;
            String reset = tr(armed ? "cuemymusic.pool.reset_confirm" : "cuemymusic.pool.reset");
            int rx = x1 - 10 - font.width(reset), ay = y + 11;
            toolRight = rx - 14;
            boolean hover = mx >= rx - 2 && mx < x1 - 8 && my >= ay - 3 && my < ay + 11;
            g.text(font, reset, rx, ay, armed ? RED : hover ? WHITE : DIM, false);
            if (audit != null) audit.box(reset, rx, ay, rx + font.width(reset), ay + 9);
            nextHits.add(new Hit(rx - 2, ay - 3, x1 - 8, ay + 11, () -> {
                if (System.currentTimeMillis() < resetArmedUntil) {
                    resetArmedUntil = 0;
                    resetPool(pool);
                } else {
                    resetArmedUntil = System.currentTimeMillis() + 3000;
                }
            }));
        }
        // Add the whole list to the queue, in the order shown.
        int toolLeft = x0 + 10 + pb + 21;
        boolean mixMode = false;
        if (!tracks.isEmpty()) {
            int qx = toolLeft + 10, qy = y + 6;
            String label = tr("cuemymusic.queue.add_all");
            int qw = 13 + font.width(label);
            // Mix: shows the transitions in place of the list, once two songs next to each other can blend.
            boolean canMix = false;
            for (int i = 0; i + 1 < tracks.size() && !canMix; i++) canMix = blends(tracks, i);
            // Shown as a mix while it can be, as last left; saved, so it stays a mix next time.
            mixMode = canMix && cfg.isMixPage(listKey);
            String mixLabel = tr("cuemymusic.mix.open");
            int mixW = canMix ? 14 + font.width(mixLabel) + 12 : 0;
            // Too narrow for the words: Add all to queue is just its +, so nothing runs into the search.
            if (qx + qw + mixW > toolRight - 20) {
                label = "";
                qw = 9;
            }
            int chipsRight = qx + qw + mixW;
            // Without room for an open search box, it takes these buttons' place until it closes.
            if (!pageFilterOpen || toolRight - chipsRight - 12 >= 100) {
                boolean qHover = mx >= qx - 2 && mx < qx + qw + 2 && my >= qy && my < qy + pb;
                if (audit != null) audit.box(tr("cuemymusic.queue.add_all"), qx, qy, qx + qw, qy + pb);
                plusIcon(g, qx, qy + 5, qHover ? WHITE : DIM);
                g.text(font, label, qx + 13, qy + 6, qHover ? WHITE : DIM, false);
                nextHits.add(new Hit(qx - 2, qy, qx + qw + 2, qy + pb, () -> {
                    player.enqueueAll(tracks);
                    showToast(tr("cuemymusic.queue.added_all", tracks.size()));
                }));
                toolLeft = qx + qw;
                if (canMix) {
                    toolLeft += 14 + chip(g, qx + qw + 14, qy + 3, mixLabel, mixMode, mx, my, () -> {
                        cfg.setMixPage(listKey, !cfg.isMixPage(listKey));
                        closeTransition();
                        clearSelection();
                        scroll = 0;
                    });
                }
            }
        }
        // All game music narrows to one of the game's music pools.
        if (CuePlayer.ALL.equals(listKey) && !MusicLibrary.pools().isEmpty()) {
            String poolLabel = (gamePool == null ? tr("cuemymusic.pool.all") : poolName(gamePool)) + " \u25BC";
            int px = toolLeft + 12, py = y + 9;
            toolLeft = px + chip(g, px, py, poolLabel, gamePool != null, mx, my, () -> openPoolMenu(px, py + 15));
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
            if (mixEdit < 0 && mixFrom != null) closeTransition();
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
                nextHits.add(new Hit(x0, top, x1, bottom, () -> rowClick(listKey, tracks, index)));
                nextRowHits.add(new RowHit(x0, top, x1, bottom, t, index));
                // Like: always shown once liked, otherwise on hover, left of add to queue.
                boolean liked = isLiked(t);
                // Dislike: always shown once disliked, otherwise on hover, left of the heart. Only for songs the mode can pick.
                if ((hover || disliked) && Modes.allows(t)) {
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
            // Songs the mode leaves out aren't gone: say where they are.
            CueConfig.Playlist p = view == View.LIST ? cfg.playlist(key) : null;
            int hidden = (view == View.LIKED ? MusicLibrary.liked(cfg.liked)
                    : MusicLibrary.tracks(view == View.DISLIKED ? cfg.disliked : p != null ? p.songs() : List.of())).size();
            Component hint = hidden > 0
                    ? Component.translatable("cuemymusic.page.other_mode", hidden,
                            tr(mode().equals("addons") ? "cuemymusic.mode.fx" : "cuemymusic.mode.addons"))
                    : view == View.LIKED ? Component.translatable("cuemymusic.liked.empty")
                    : view == View.DISLIKED ? Component.translatable("cuemymusic.disliked.empty")
                    : Component.translatable("cuemymusic.playlists.empty");
            g.textWithWordWrap(font, hint, x0 + 10, y + 4, x1 - x0 - 20, DIM, false);
            y += font.wordWrapHeight(hint, x1 - x0 - 20) + 4;
        }
        if (view == View.LIST && filter.isEmpty()) y = drawPlaylistFinder(g, x0, y + 10, x1, mx, my, key);
        return y + 8;
    }

    private static final String PLAYLIST_FIND = "playlist_find";

    /** As on Spotify, under a playlist's songs: search the library for more, with Add on each song found. */
    private int drawPlaylistFinder(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String playlistId) {
        Canvas c = new Canvas(g, x0, y, x1, mx, my);
        c.heading(tr("cuemymusic.playlists.find"));
        String typed = c.textField(PLAYLIST_FIND, Component.translatable("cuemymusic.search.hint"), Math.min(220, c.right() - c.left()),
                text -> unfocusField());
        c.space(c.buttonHeight() + 8);
        if (typed.isBlank()) return c.y;
        CueConfig.Playlist list = cfg.playlist(playlistId);
        List<Track> found = searchSongs(typed).stream().filter(t -> list == null || !list.songs().contains(id(t))).toList();
        if (found.isEmpty()) {
            c.text(Component.translatable("cuemymusic.search.none", typed.strip()), DIM);
            return c.y;
        }
        int left = c.left(), right = c.right(), ry = c.y;
        for (Track t : found.subList(0, Math.min(20, found.size()))) {
            boolean hover = mx >= x0 && mx < x1 && my >= ry && my < ry + ROW && my >= centerY0 && my < centerY1;
            if (hover) g.fill(x0 + 4, ry, x1 - 4, ry + ROW, ROW_HOVER);
            String add = tr("cuemymusic.playlists.add");
            int aw = font.width(add) + 12;
            queueRow(g, t, left, ry, right - left - aw - 8, WHITE, t.artist());
            chip(g, right - aw, ry + 3, add, false, mx, my, () -> {
                if (cfg.addToPlaylist(playlistId, id(t))) showToast(tr("cuemymusic.playlists.added", cfg.playlist(playlistId).name()));
            });
            ry += ROW;
        }
        return ry;
    }

    private List<Track> liked() {
        return Modes.only(MusicLibrary.liked(cfg.liked));
    }

    private List<Track> disliked() {
        return Modes.only(MusicLibrary.tracks(cfg.disliked));
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

    // ------------------------------------------------------------------ pools

    /** The music pool All game music is narrowed to, or null for all of it. Kept while the game runs. */
    private static Identifier gamePool;
    /** The usual order of the game's own pools in the menu; biomes come after, under their dimension. */
    private static final List<String> POOL_ORDER = List.of("music.menu", "music.game", "music.creative", "music.under_water",
            "music.end", "music.dragon", "music.credits");

    private List<Track> gameMusicShown() {
        List<Track> all = MusicLibrary.gameMusic();
        if (gamePool == null) return all;
        if (!MusicLibrary.pools().contains(gamePool)) {
            gamePool = null;
            return all;
        }
        return all.stream().filter(t -> MusicLibrary.inPool(t, gamePool)).toList();
    }

    /** A pool's name: the biome's own for biome music, otherwise the player's ("Survival" for music.game). */
    private static String poolName(Identifier pool) {
        String path = pool.getPath();
        for (String place : List.of("music.overworld.", "music.nether.")) {
            if (path.startsWith(place)) {
                String biome = path.substring(place.length());
                String key = "biome." + pool.getNamespace() + "." + biome;
                return Language.getInstance().has(key) ? tr(key) : words(biome);
            }
        }
        String rest = path.startsWith("music.") ? path.substring("music.".length()) : path;
        String key = "cuemymusic.pool." + rest;
        return Language.getInstance().has(key) ? tr(key) : words(rest);
    }

    /** "cherry_grove" as "Cherry Grove". */
    private static String words(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : id.split("[._/]+")) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private void setGamePool(Identifier pool) {
        gamePool = pool;
        scroll = 0;
        clearSelection();
    }

    /** The pool drop-down: all, the game's own pools, then the overworld's and the nether's biomes, each a list of its own. */
    private void openPoolMenu(int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(tr("cuemymusic.pool.all"), () -> setGamePool(null)));
        items.add(MenuItem.DIVIDER);
        items.addAll(poolItems(x, y, pools -> setGamePool(pools.getFirst())));
        showMenu(new Menu(x, y, items));
    }

    /** The game's pools in order: its own (the menu, Survival, Creative...), then the overworld's biomes, then the nether's. */
    private static List<Identifier> sortedPools() {
        // Only places the game plays music in: a pool only an add-on's page plays, such as Downloads, isn't one.
        List<Track> game = MusicLibrary.gameMusic();
        List<Identifier> pools = new ArrayList<>(MusicLibrary.pools().stream()
                .filter(p -> game.stream().anyMatch(t -> MusicLibrary.inPool(t, p))).toList());
        pools.sort(java.util.Comparator.comparingInt((Identifier p) -> {
            if (p.getPath().startsWith("music.overworld.")) return POOL_ORDER.size() + 1;
            if (p.getPath().startsWith("music.nether.")) return POOL_ORDER.size() + 2;
            int i = POOL_ORDER.indexOf(p.getPath());
            return i < 0 ? POOL_ORDER.size() : i;
        }).thenComparing(CueScreen::poolName));
        return pools;
    }

    /**
     * A menu's rows for the pools: the game's own, then the overworld's and the
     * nether's biomes, each a menu of its own. {@code pick} gets the pools a row stands for.
     */
    private List<MenuItem> poolItems(int x, int y, java.util.function.Consumer<List<Identifier>> pick) {
        List<MenuItem> items = new ArrayList<>();
        List<Identifier> overworld = new ArrayList<>(), nether = new ArrayList<>();
        for (Identifier p : sortedPools()) {
            if (p.getPath().startsWith("music.overworld.")) overworld.add(p);
            else if (p.getPath().startsWith("music.nether.")) nether.add(p);
            else items.add(new MenuItem(poolName(p), () -> pick.accept(List.of(p))));
        }
        if (!overworld.isEmpty() || !nether.isEmpty()) items.add(MenuItem.DIVIDER);
        if (!overworld.isEmpty()) {
            items.add(new MenuItem(tr("cuemymusic.pool.overworld"), () -> showMenu(new Menu(x, y, biomeItems(overworld, pick)))));
        }
        if (!nether.isEmpty()) {
            items.add(new MenuItem(tr("cuemymusic.pool.nether"), () -> showMenu(new Menu(x, y, biomeItems(nether, pick)))));
        }
        return items;
    }

    /** A track pool's picture: the block of the place it plays in, or its initials for a place without one. */
    private Art poolArt(Identifier pool) {
        String path = pool.getPath();
        String biome = path.substring(path.lastIndexOf('.') + 1);
        String block = POOL_BLOCKS.getOrDefault(path, POOL_BLOCKS.get(biome));
        if (block == null) return Art.initials(poolName(pool));
        Identifier id = Identifier.withDefaultNamespace("textures/block/" + block + ".png");
        boolean exists = TEXTURE_EXISTS.computeIfAbsent(id, i -> minecraft.getResourceManager().getResource(i).isPresent());
        if (!exists) return Art.initials(poolName(pool));
        int height = SHEET_HEIGHTS.computeIfAbsent(id, this::pngHeight);
        return height > 16 ? new Art.Sheet(id, height) : Art.block("block/" + block);
    }

    /** The height of the PNG {@code id}, from its header; a block animated with frames is taller than it is wide. */
    private int pngHeight(Identifier id) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(minecraft.getResourceManager().getResourceOrThrow(id).open())) {
            in.skipNBytes(20);
            return in.readInt();
        } catch (java.io.IOException e) {
            return 16;
        }
    }

    /** A track pool's songs: the game's own, with your changes laid over. */
    private List<Track> poolTracks(Identifier pool) {
        List<String> game = MusicLibrary.gameMusic().stream().filter(t -> MusicLibrary.inPool(t, pool)).map(CueScreen::id).toList();
        return Modes.only(MusicLibrary.tracks(TrackPools.apply(game, cfg.poolEdit(pool.toString()))));
    }

    /** Puts {@code tracks} in each of {@code pools}, so the game can play them there. */
    private void addToPools(List<Track> tracks, List<Identifier> pools) {
        for (Identifier p : pools) {
            TrackPools.Edit e = cfg.poolEdit(p.toString());
            for (Track t : tracks) e = e.add(id(t), MusicLibrary.inPool(t, p));
            cfg.setPoolEdit(p.toString(), e);
        }
        player.replan();
        String where = pools.size() == 1 ? poolName(pools.getFirst()) : tr("cuemymusic.pool.and_more", poolName(pools.getFirst()), pools.size() - 1);
        showToast(tracks.size() == 1 ? tr("cuemymusic.pool.added", where) : tr("cuemymusic.pool.added_many", tracks.size(), where));
    }

    /** Takes {@code tracks} out of the pool, so the game no longer plays them there. */
    private void removeFromPool(List<Track> tracks, String pool) {
        Identifier p = Identifier.tryParse(pool);
        if (p == null) return;
        TrackPools.Edit e = cfg.poolEdit(pool);
        for (Track t : tracks) e = e.remove(id(t), MusicLibrary.inPool(t, p));
        cfg.setPoolEdit(pool, e);
        player.replan();
        showToast(tracks.size() == 1 ? tr("cuemymusic.pool.removed", poolName(p)) : tr("cuemymusic.pool.removed_many", tracks.size(), poolName(p)));
    }

    /** Puts the pool back as the game has it. */
    private void resetPool(String pool) {
        cfg.setPoolEdit(pool, null);
        player.replan();
        Identifier p = Identifier.tryParse(pool);
        showToast(tr("cuemymusic.pool.was_reset", p == null ? pool : poolName(p)));
    }

    /** A track pool's right-click menu in the library: as any list's, then Reset once it's changed. */
    private List<MenuItem> poolListMenu(Identifier pool) {
        String id = pool.toString();
        List<MenuItem> items = new ArrayList<>(listMenu("pool:" + id, poolTracks(pool), null));
        if (!cfg.poolEdit(id).isEmpty()) {
            items.add(MenuItem.DIVIDER);
            items.add(new MenuItem(MenuIcon.RESET, tr("cuemymusic.pool.reset"), () -> resetPool(id)));
        }
        return items;
    }

    /** One row per biome; biomes that play exactly the same songs share a row, which keeps the list on screen. */
    private List<MenuItem> biomeItems(List<Identifier> biomes, java.util.function.Consumer<List<Identifier>> pick) {
        Map<List<Track>, List<Identifier>> bySongs = new java.util.LinkedHashMap<>();
        List<Track> all = MusicLibrary.gameMusic();
        for (Identifier b : biomes) {
            bySongs.computeIfAbsent(all.stream().filter(t -> MusicLibrary.inPool(t, b)).toList(), k -> new ArrayList<>()).add(b);
        }
        List<MenuItem> items = new ArrayList<>();
        for (List<Identifier> same : bySongs.values()) {
            Identifier first = same.getFirst();
            String label = same.size() == 1 ? poolName(first) : tr("cuemymusic.pool.and_more", poolName(first), same.size() - 1);
            items.add(new MenuItem(label, () -> pick.accept(List.copyOf(same))));
        }
        return items;
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
        // Every pair is either a transition or a cut, so the numbers add up.
        int cuts = Math.max(0, mixTracks.size() - 1 - transitions);
        String summary = cuts == 0 ? tr("cuemymusic.mix.summary", mixTracks.size(), transitions)
                : tr("cuemymusic.mix.summary_cuts", mixTracks.size(), transitions, cuts);
        g.text(font, clip(summary, right - left), left, y, DIM, false);
        y += 16;

        // Songs, and between two that blend a line down with the transition's pill.
        int rowH = 30, art = 24, lineX = left + art / 2;
        for (int i = 0; i < mixTracks.size(); i++) {
            Track t = mixTracks.get(i);
            boolean hover = mx >= left - 4 && mx < right + 4 && my >= y && my < y + rowH && my >= centerY0 && my < centerY1;
            if (hover) box(g, left - 4, y, right + 4, y + rowH, ROW_HOVER);
            art(g, t.art(), left, y + 3, art);
            boolean current = t.equals(player.current());
            int textRight = right - mixRowInfo(g, t, right, y + 11);
            boolean mixes = AutomixProfiles.applies(t);
            g.text(font, clip(t.title(), textRight - left - art - 16), left + art + 8, y + 5, current ? GREEN : mixes ? WHITE : FAINT, false);
            g.text(font, clip(t.artist(), textRight - left - art - 16), left + art + 8, y + 17, mixes ? DIM : FAINT, false);
            int index = i, top = Math.max(y, centerY0), bottom = Math.min(y + rowH, centerY1);
            if (bottom > top) {
                nextHits.add(new Hit(left - 4, top, right + 4, bottom, () -> player.play(listKey, mixTracks, index)));
                nextRowHits.add(new RowHit(left - 4, top, right + 4, bottom, t, index));
            }
            y += rowH;
            if (i + 1 >= mixTracks.size()) break;
            if (!blends(i)) {
                y += 4;
                continue;
            }
            int gap = 22;
            g.fill(lineX, y, lineX + 1, y + gap, 0xFF3A3A3A);
            String from = id(t), to = id(mixTracks.get(i + 1));
            Automix.Transition tn = cfg.savedTransition(from, to);
            Automix.Profile pa = AutomixProfiles.get(t), pb = AutomixProfiles.get(mixTracks.get(i + 1));
            String label = presetName(tn.preset());
            if (tn.preset() == Automix.Preset.AUTO) {
                label = "✦ " + label + (pa != null && pb != null ? " · " + presetName(Automix.plan(pa, pb, tn).preset()) : "");
            }
            label += "  ›";
            int w = font.width(label) + 14, cx = lineX + 10, cy = y + 4;
            boolean chipHover = mx >= cx && mx < cx + w && my >= cy && my < cy + 14 && my >= centerY0 && my < centerY1;
            box(g, cx, cy, cx + w, cy + 14, chipHover ? 0xFF3A3A3A : 0xFF262626);
            g.text(font, label, cx + 7, cy + 3, chipHover ? WHITE : DIM, false);
            nextHits.add(new Hit(cx, cy, cx + w, cy + 14, () -> openTransition(from, to)));
            y += gap;
        }
        if (!cfg.automix) {
            y += 6;
            g.text(font, clip(tr("cuemymusic.mix.off.hint"), right - left), left, y, DIM, false);
            y += 14;
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

    private static final int CURVE_FILTER = 0xFFE98AF0;
    private static final int CURVE_LOOP = 0xFFF5A623;
    private static final int CURVE_EFFECT = 0xFFBDBDBD;

    /** Opens the editor on the transition from song {@code from} into {@code to}, as a draft of what's saved. */
    private void openTransition(String from, String to) {
        mixFrom = from;
        mixTo = to;
        mixDraft = cfg.savedTransition(from, to);
        cfg.previewTransition(from, to, mixDraft);
        presetScroll = 0;
    }

    /** Closes the editor; anything not saved is dropped. */
    private void closeTransition() {
        if (mixFrom != null) player.stopPreview();
        cfg.previewTransition(null, null, null);
        mixFrom = mixTo = null;
        mixDraft = null;
        laneDrag = 0;
    }

    /** Changes the draft, which is what plays while the editor is open. */
    private void editDraft(java.util.function.UnaryOperator<Automix.Transition> change) {
        if (mixDraft == null || mixFrom == null) return;
        mixDraft = change.apply(mixDraft);
        cfg.previewTransition(mixFrom, mixTo, mixDraft);
    }

    /**
     * The editor for transition {@link #mixEdit}, laid out like Spotify's:
     * close, Beta, more and Save along the top; both songs' waveforms, one
     * above the other, with the transition marked and its curves over them,
     * a preview button between them and its length under them; the presets;
     * and the parts, each a drop-down, with beatmatching. Changes are a draft
     * until Save: they play while it's open, and closing drops them.
     */
    private int drawTransitionEditor(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String listKey) {
        Track a = mixTracks.get(mixEdit), b = mixTracks.get(mixEdit + 1);
        String from = id(a), to = id(b);
        if (mixDraft == null) openTransition(from, to);
        Automix.Transition t = mixDraft;
        int left = x0 + 12, right = x1 - 12;

        // Top bar.
        int bar = 18;
        boolean closeHover = hovered(left, y, left + bar, y + bar, mx, my);
        box(g, left, y, left + bar, y + bar, closeHover ? 0xFF3A3A3A : 0xFF2A2A2A);
        crossIcon(g, left + 6, y + 6, WHITE);
        nextHits.add(new Hit(left, y, left + bar, y + bar, this::closeTransition));
        boolean dirty = !t.equals(cfg.savedTransition(from, to));
        String save = tr("cuemymusic.mix.save");
        int sw = font.width(save) + 20, sx = right - sw;
        boolean saveHover = dirty && hovered(sx, y, right, y + bar, mx, my);
        box(g, sx, y, right, y + bar, saveHover ? 0xFF333333 : 0xFF262626);
        g.text(font, save, sx + 10, y + 5, dirty ? GREEN : 0xFF2C5E3E, false);
        nextHits.add(new Hit(sx, y, right, y + bar, () -> {
            if (!dirty) return;
            cfg.setTransition(from, to, mixDraft);
            cfg.save();
            closeTransition();
            showToast(tr("cuemymusic.mix.saved"));
        }));
        int ox = sx - 6 - bar;
        boolean moreHover = hovered(ox, y, ox + bar, y + bar, mx, my);
        box(g, ox, y, ox + bar, y + bar, moreHover ? 0xFF3A3A3A : 0xFF2A2A2A);
        for (int i = 0; i < 3; i++) g.fill(ox + 4 + i * 4, y + 8, ox + 6 + i * 4, y + 10, WHITE);
        int menuY = y + bar + 2;
        nextHits.add(new Hit(ox, y, ox + bar, y + bar, () -> showMenu(new Menu(ox, menuY, List.of(
                new MenuItem(tr("cuemymusic.mix.reset"), () -> editDraft(d -> Automix.Transition.AUTO)))))));
        String beta = tr("cuemymusic.mix.beta");
        int bw = font.width(beta) + 8, bx = ox - 6 - bw;
        box(g, bx, y + 4, bx + bw, y + 15, 0xFF5E5E5E);
        g.text(font, beta, bx + 4, y + 6, 0xFF1A1A1A, false);
        y += bar + 12;

        Automix.Profile pa = AutomixProfiles.get(a), pb = AutomixProfiles.get(b);
        if (pa == null || pb == null) {
            g.text(font, tr("cuemymusic.mix.analysing"), left, y, DIM, false);
            return y + 20;
        }
        Automix.Plan plan = Automix.plan(pa, pb, t);
        Automix.Shape shape = plan.shape();

        // The two songs, edge to edge, centred on the transition.
        double aEnd = pa.seconds(pa.end()), start = pa.seconds(plan.fadeStart()), len = pa.seconds(plan.fadeFrames());
        double enter = pb.seconds(plan.enterAt()), stretch = plan.stretch();
        double span = Math.max(10, len * 1.5), t0 = start + len / 2 - span / 2;
        int laneH = 40, gapH = 16, laneA = y, laneB = laneA + laneH + gapH, boxBottom = laneB + laneH + 8;
        int wx0 = x0, wx1 = x1;
        int ox0 = timeX(start, t0, span, wx0, wx1), ox1 = timeX(start + len, t0, span, wx0, wx1);
        box(g, ox0, laneA - 4, ox1, boxBottom, 0xFF262626);
        // Beat lines across the transition, brighter on each bar.
        if (pa.bpm() > 0) {
            double beat = pa.beat(), origin = pa.hasBars() ? pa.downbeat() : start;
            for (long k = (long) Math.ceil((start - origin) / beat); origin + k * beat <= start + len; k++) {
                int x = timeX(origin + k * beat, t0, span, wx0, wx1);
                g.fill(x, laneA - 4, x + 1, boxBottom - 4, Math.floorMod(k, 4) == 0 ? 0x30FFFFFF : 0x12FFFFFF);
            }
        }
        drawLane(g, pa, shape, true, start, len, t0, span, start, 1, wx0, laneA, wx1, laneH, ox0, ox1);
        drawLane(g, pb, shape, false, start, len, t0, span, enter, stretch, wx0, laneB, wx1, laneH, ox0, ox1);
        if (shape.loop() == Automix.Loop.LOOP_OUT) {
            // The bar that repeats, marked over the first song.
            int lx = timeX(start + pa.seconds(Automix.loopFrames(pa, plan.fadeFrames())), t0, span, wx0, wx1);
            g.fill(ox0, laneA - 2, lx, laneA, CURVE_LOOP);
            g.fill(lx - 1, laneA - 2, lx, laneA + 4, CURVE_LOOP);
        }
        // Where the music is now, if it's in this stretch.
        Track now = player.current();
        double nowTau = now == null ? Double.NaN : now.equals(a) ? player.position()
                : now.equals(b) ? start + (player.position() - enter) / stretch : Double.NaN;
        if (nowTau >= t0 && nowTau <= t0 + span) {
            int px = timeX(nowTau, t0, span, wx0, wx1);
            g.fill(px, laneA - 4, px + 1, laneB + laneH + 4, WHITE);
        }
        // Preview, between the songs: just the transition, from a few seconds before it to a few after, then it pauses.
        int pw = 26, ph = 16, pbx = (ox0 + ox1) / 2 - pw / 2, pby = laneA + laneH + gapH / 2 - ph / 2;
        boolean previewing = player.isPreviewing();
        double until = enter + len * stretch + 4;
        box(g, pbx, pby, pbx + pw, pby + ph, hovered(pbx, pby, pbx + pw, pby + ph, mx, my) ? 0xFF4A4A4A : 0xFF383838);
        if (previewing) pauseIcon(g, pbx + 10, pby + 4, 8, WHITE);
        else playIcon(g, pbx + 11, pby + 4, 8, WHITE);
        int edit = mixEdit;
        nextHits.add(new Hit(pbx, pby, pbx + pw, pby + ph, () -> {
            if (previewing) {
                player.stopPreview();
            } else if (cfg.automix) {
                player.previewTransition(listKey, mixTracks, edit, Math.max(0, start - 5), until);
            } else {
                showToast(tr("cuemymusic.mix.off.hint"));
            }
        }));
        // Its length, on the transition's bottom edge.
        boolean cut = shape.volume() == Automix.Volume.CUT;
        double barSeconds = pa.bpm() > 0 ? 240 / pa.bpm() : 0;
        java.util.function.DoubleFunction<String> lengthText = sec -> barSeconds > 0
                ? tr(Math.abs(sec / barSeconds - 1) < 0.05 ? "cuemymusic.mix.bar" : "cuemymusic.mix.bars",
                        String.format(Locale.ROOT, "%.1f", sec / barSeconds).replace(".0", ""))
                : tr("cuemymusic.mix.seconds", String.format(Locale.ROOT, "%.1f", sec).replace(".0", ""));
        String lenLabel = lengthText.apply(len);
        int lw = font.width(lenLabel) + 22, lx = (ox0 + ox1) / 2 - lw / 2, ly = boxBottom - 8;
        boolean lenHover = hovered(lx, ly, lx + lw, ly + 16, mx, my);
        box(g, lx, ly, lx + lw, ly + 16, lenHover ? 0xFF3A3A3A : 0xFF2E2E2E);
        g.text(font, lenLabel, lx + 8, ly + 4, WHITE, false);
        chevron(g, lx + lw - 11, ly + 7, WHITE);
        double most = cut ? 4 : 40;
        double autoLen = cut ? 1 : Automix.autoSeconds(pa, shape, plan.preset());
        nextHits.add(new Hit(lx, ly, lx + lw, ly + 16, () -> {
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem((t.seconds() < 0 ? "✔ " : "    ") + tr("cuemymusic.mix.auto_value", lengthText.apply(autoLen)),
                    () -> editDraft(d -> d.withSeconds(-1))));
            for (double n : barSeconds > 0 ? new double[] {1, 2, 4, 8, 16} : new double[] {2, 4, 8, 15, 30}) {
                double sec = Math.round((barSeconds > 0 ? n * barSeconds : n) * 100) / 100.0;
                if (sec > most) continue;
                items.add(new MenuItem((t.seconds() >= 0 && Math.abs(t.seconds() - sec) < 0.05 ? "✔ " : "    ") + lengthText.apply(sec),
                        () -> editDraft(d -> d.withSeconds(sec))));
            }
            showMenu(new Menu(lx, ly + 18, items));
        }));
        // Dragging or scrolling sideways over a song moves it, a beat at a time: the first song moves where the
        // transition starts, the next one where it comes in.
        mixLaneX0 = wx0;
        mixLaneY0 = Math.max(laneA - 4, centerY0);
        mixLaneX1 = wx1;
        mixLaneY1 = Math.min(laneB + laneH, centerY1);
        mixLaneSplit = laneA + laneH + gapH / 2;
        mixLaneSpp = span / (wx1 - wx0);
        mixLaneValueA = aEnd - start;
        mixLaneValueB = enter;
        double bLength = pb.seconds(pb.frames());
        mixLaneSetA = sbe -> {
            double at = onBeat(pa, aEnd - sbe);
            editDraft(d -> d.withStart(Math.clamp(Math.round((aEnd - at) * 100) / 100.0, 0, 60)));
        };
        mixLaneSetB = e -> {
            double at = onBeat(pb, e);
            editDraft(d -> d.withEnter(Math.clamp(Math.round(at * 100) / 100.0, 0, Math.max(0, bLength - 1))));
        };
        nextMixLaneScroll = steps -> mixLaneSetA.accept(aEnd - start + steps * beatOr(pa));
        nextMixLaneScrollNext = steps -> mixLaneSetB.accept(enter + steps * beatOr(pb));
        y = boxBottom + 18;

        // Presets, in a row that scrolls sideways.
        String presets = tr("cuemymusic.mix.presets");
        int pwText = font.width(presets), mid = (left + right) / 2;
        g.fill(left, y + 4, mid - pwText / 2 - 8, y + 5, 0xFF2A2A2A);
        g.fill(mid + pwText / 2 + 8, y + 4, right, y + 5, 0xFF2A2A2A);
        g.text(font, presets, mid - pwText / 2, y, DIM, false);
        y += 16;
        int rowH = 22, gap = 6;
        List<Automix.Preset> shown = java.util.Arrays.stream(Automix.Preset.values()).filter(p -> p != Automix.Preset.CUSTOM).toList();
        int each = Math.max(52, (int) ((right - left - 3 * gap) / 3.6));
        int total = shown.size() * (each + gap) - gap;
        presetScrollMax = Math.max(0, total - (right - left));
        presetScroll = Math.clamp(presetScroll, 0, presetScrollMax);
        presetX0 = left;
        presetX1 = right;
        presetY0 = y;
        presetY1 = y + rowH;
        g.enableScissor(left, Math.max(y, centerY0), right, Math.min(y + rowH, centerY1));
        for (int i = 0; i < shown.size(); i++) {
            Automix.Preset p = shown.get(i);
            int bx0 = left + i * (each + gap) - (int) presetScroll, bx1 = bx0 + each;
            if (bx1 < left || bx0 > right) continue;
            boolean on = t.preset() == p;
            boolean hover = hovered(Math.max(bx0, left), y, Math.min(bx1, right), y + rowH, mx, my);
            box(g, bx0, y, bx1, y + rowH, on ? WHITE : hover ? 0xFF333333 : 0xFF242424);
            String label = (p == Automix.Preset.AUTO ? "✦ " : "") + presetName(p);
            g.text(font, clip(label, each - 8), bx0 + (each - Math.min(font.width(label), each - 8)) / 2, y + 7, on ? BLACK : WHITE, false);
            nextHits.add(new Hit(Math.max(bx0, left), y, Math.min(bx1, right), y + rowH, () -> editDraft(d -> d.withPreset(p))));
        }
        // Back to the centre panel's own clip, which is still underneath.
        g.disableScissor();
        y += rowH + 12;

        // The parts, each a drop-down; changing one makes the transition custom.
        y = partRow(g, left, y, right, mx, my, CURVE_VOLUME, tr("cuemymusic.mix.part.volume"), shape.volume(), Automix.Volume.values(),
                v -> shape.withVolume((Automix.Volume) v));
        y = partRow(g, left, y, right, mx, my, CURVE_EQ, tr("cuemymusic.mix.part.eq"), shape.eq(), Automix.Eq.values(),
                v -> shape.withEq((Automix.Eq) v));
        y = partRow(g, left, y, right, mx, my, CURVE_FILTER, tr("cuemymusic.mix.part.filter"), shape.filter(), Automix.Filter.values(),
                v -> shape.withFilter((Automix.Filter) v));
        y = partRow(g, left, y, right, mx, my, CURVE_EFFECT, tr("cuemymusic.mix.part.effects"), shape.effect(), Automix.Effect.values(),
                v -> shape.withEffect((Automix.Effect) v));
        y = partRow(g, left, y, right, mx, my, CURVE_LOOP, tr("cuemymusic.mix.part.looping"), shape.loop(), Automix.Loop.values(),
                v -> shape.withLoop((Automix.Loop) v));

        // Beatmatching: on unless turned off, when the tempos are known and close enough to meet.
        y += 6;
        g.text(font, tr("cuemymusic.mix.part.beatmatch"), left, y + 4, WHITE, false);
        double canStretch = Automix.stretch(pa, pb);
        boolean known = pa.bpm() > 0 && pb.bpm() > 0;
        boolean inTime = known && Automix.tempoGap(pa.bpm(), pb.bpm()) < 0.002;
        boolean available = known && (canStretch != 1 || inTime) && !cut;
        boolean on = available && t.beatmatch();
        int tw = 24, th = 12, tx = right - tw, ty = y + 2;
        box(g, tx, ty, tx + tw, ty + th, on ? GREEN : available ? 0xFF4D4D4D : 0xFF2E2E2E);
        int knob = on ? tx + tw - th + 1 : tx + 1;
        box(g, knob, ty + 1, knob + th - 2, ty + th - 1, available ? WHITE : 0xFF6A6A6A);
        String note = !known ? tr("cuemymusic.mix.beatmatch.unknown") : cut ? tr("cuemymusic.mix.beatmatch.cut")
                : !available ? tr("cuemymusic.mix.beatmatch.too_far")
                : inTime ? tr("cuemymusic.mix.beatmatch.in_time")
                : on ? tr("cuemymusic.mix.beatmatch.speed", String.format(Locale.ROOT, "%+.1f", (canStretch - 1) * 100)) : "";
        if (!note.isEmpty()) {
            note = clip(note, tx - left - font.width(tr("cuemymusic.mix.part.beatmatch")) - 20);
            g.text(font, note, tx - 8 - font.width(note), y + 4, FAINT, false);
        }
        if (available) nextHits.add(new Hit(tx - 4, y, tx + tw + 4, y + 16, () -> editDraft(d -> d.withBeatmatch(!d.beatmatch()))));
        return y + 30;
    }

    /** True if the mouse is over the box, inside the centre panel. */
    private boolean hovered(int x0, int y0, int x1, int y1, int mx, int my) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1 && my >= centerY0 && my < centerY1;
    }

    /**
     * One song's lane: its waveform in three colours, dimmed outside the
     * transition, with its curves over the transition: volume in blue, the bass
     * in yellow for a bass swap, and the filter in pink. Song time at timeline
     * time {@code tau} is {@code origin + (tau - start) * rate}.
     */
    private void drawLane(GuiGraphicsExtractor g, Automix.Profile p, Automix.Shape shape, boolean outgoing, double start, double len,
            double t0, double span, double origin, double rate, int x0, int y, int x1, int h, int rx0, int rx1) {
        int mid = y + h / 2, half = h / 2 - 1;
        double step = span / (x1 - x0);
        boolean filtered = outgoing ? shape.filter().lowPassOut() : shape.filter() != Automix.Filter.NONE && !shape.filter().equals(Automix.Filter.FALL);
        int lastVol = -1, lastEq = -1, lastFilter = -1;
        for (int x = x0; x < x1; x++) {
            double tau = t0 + (x - x0 + 0.5) * step, songT = origin + (tau - start) * rate;
            double prog = (tau - start) / len;
            boolean inside = x >= rx0 && x < rx1;
            if (songT < 0 || songT > p.seconds(p.frames())) continue;
            double vol = outgoing ? (prog <= 0 ? 1 : Automix.volumeOut(shape, prog)) : (prog <= 0 ? 0 : Automix.volumeIn(shape, prog));
            float lo = band(p.low(), songT, step * rate), md = band(p.mid(), songT, step * rate), hi = band(p.high(), songT, step * rate);
            float shade = inside ? (float) (0.55 + 0.45 * Math.max(vol, 0)) : 0.38f;
            // Bass widest and behind, the mids over it, the highs on top as a thin core.
            int hl = (int) Math.min(half, lo * half * 1.3), hm = (int) Math.min(half, md * half * 1.2), hh = (int) Math.min(half, hi * half * 1.6);
            if (hl > 0) g.fill(x, mid - hl, x + 1, mid + hl + 1, shade(WAVE_LOW, shade));
            if (hm > 0) g.fill(x, mid - hm, x + 1, mid + hm + 1, shade(WAVE_MID, shade));
            if (hh > 0) g.fill(x, mid - hh / 2, x + 1, mid + hh / 2 + 1, shade(WAVE_HIGH, shade));
            if (!inside) continue;
            double p01 = Math.clamp(prog, 0, 1);
            int vy = y + h - 2 - (int) Math.round(vol * (h - 4));
            lastVol = curve(g, x, vy, lastVol, 2, CURVE_VOLUME);
            if (shape.eq() != Automix.Eq.NONE) {
                double bass = outgoing ? Automix.bassOut(shape, p01) : Automix.bassIn(shape, p01);
                lastEq = curve(g, x, y + h - 2 - (int) Math.round(bass * (h - 4)), lastEq, 1, CURVE_EQ);
            }
            if (filtered) {
                double open = outgoing ? Automix.filterOut(shape, p01) : Automix.filterIn(shape, p01);
                lastFilter = curve(g, x, y + h - 2 - (int) Math.round(open * (h - 4)), lastFilter, 1, CURVE_FILTER);
            }
        }
    }

    /** One column of a curve at {@code y}, joined to the last column's {@code last}. Returns {@code y}. */
    private static int curve(GuiGraphicsExtractor g, int x, int y, int last, int thick, int color) {
        int from = last < 0 ? y : last;
        g.fill(x, Math.min(y, from), x + 1, Math.max(y, from) + thick, color);
        return y;
    }

    /** A row with a coloured mark, a part's name, and its value as a drop-down of the choices. */
    private int partRow(GuiGraphicsExtractor g, int left, int y, int right, int mx, int my, int color, String label, Enum<?> value,
            Enum<?>[] choices, java.util.function.Function<Enum<?>, Automix.Shape> with) {
        int h = 24;
        boolean hover = hovered(left - 4, y, right + 4, y + h, mx, my);
        if (hover) box(g, left - 4, y, right + 4, y + h, 0xFF1C1C1C);
        g.fill(left, y + 6, left + 2, y + h - 6, color);
        g.text(font, label, left + 10, y + 8, WHITE, false);
        String shown = partName(value);
        int vx = right - 12 - font.width(shown);
        shown = clip(shown, right - 12 - (left + 20 + font.width(label)));
        vx = right - 12 - font.width(shown);
        g.text(font, shown, vx, y + 8, hover ? WHITE : DIM, false);
        chevron(g, right - 7, y + 10, hover ? WHITE : DIM);
        int top = Math.max(y, centerY0), bottom = Math.min(y + h, centerY1);
        int menuX = Math.max(left, right - 150);
        if (bottom > top) nextHits.add(new Hit(left - 4, top, right + 4, bottom, () -> {
            List<MenuItem> items = new ArrayList<>();
            for (Enum<?> c : choices) {
                items.add(new MenuItem((c == value ? "✔ " : "    ") + partName(c), () -> editDraft(d -> d.withShape(with.apply(c)))));
            }
            showMenu(new Menu(menuX, y + h, items));
        }));
        return y + h + 2;
    }

    /** A small downward chevron, 5 wide, its top left at {@code x}, {@code y}. */
    private static void chevron(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 3; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + 4 - i, y + i, x + 5 - i, y + i + 1, color);
        }
    }

    /** A filled box with each corner pixel snipped off, the player's one shape for buttons and pills. */
    private static void box(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0 + 1, y0, x1 - 1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
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

    /** A beat's length in {@code p}, or half a second with no tempo. */
    private static double beatOr(Automix.Profile p) {
        return p.bpm() > 0 ? p.beat() : 0.5;
    }

    /** {@code seconds} moved onto the nearest of {@code p}'s beats, if they're known. */
    private static double onBeat(Automix.Profile p, double seconds) {
        if (!p.hasBars()) return seconds;
        return p.downbeat() + Math.round((seconds - p.downbeat()) / p.beat()) * p.beat();
    }

    private static int timeX(double seconds, double t0, double span, int x0, int x1) {
        return x0 + (int) Math.round((seconds - t0) / span * (x1 - x0));
    }

    /** A small rounded label button. Returns its width. */
    private int chip(GuiGraphicsExtractor g, int x, int y, String label, boolean on, int mx, int my, Runnable click) {
        int w = font.width(label) + 12;
        if (audit != null) audit.box(label, x, y, x + w, y + 13);
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

    /** True if the page open is a list of songs, with its own search. */
    private boolean hasSongList() {
        return switch (view) {
            case PLAYLIST, LIKED, DISLIKED, RADIO, LIST, ARTIST, PACK -> mixFrom == null;
            default -> false;
        };
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
        List<String> artists = MusicLibrary.all().stream().filter(Modes::allows).map(Track::artist).distinct()
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
        for (Track t : Modes.only(MusicLibrary.all())) {
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

    /**
     * An artist's art: theirs, for the game's own artists; otherwise their
     * initials over a block picked from their name, or on a gradient if that
     * block isn't in this version of the game.
     */
    private Art artistArt(String artist) {
        if (Catalog.ARTIST_ART.containsKey(artist)) return Art.block(Catalog.ARTIST_ART.get(artist).texture());
        String block = ARTIST_BLOCKS.get(Math.floorMod(artist.hashCode(), ARTIST_BLOCKS.size()));
        Identifier id = Identifier.withDefaultNamespace("textures/block/" + block + ".png");
        boolean exists = TEXTURE_EXISTS.computeIfAbsent(id, i -> minecraft.getResourceManager().getResource(i).isPresent());
        return exists ? new Art.Stamp(PackNames.initials(artist), id) : Art.initials(artist);
    }

    // ------------------------------------------------------------------ radio

    /**
     * Radio: endless music picked for you, from a song or from everything,
     * with a choice of the game's music, add-on songs, or both. Its songs so far
     * and next are listed, and play like any other list.
     */
    private int drawRadio(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        Track seed = player.radioSeed();
        String title = seed == null ? tr("cuemymusic.radio.title") : tr("cuemymusic.radio.song", seed.title());
        String summary = seed == null ? tr("cuemymusic.radio.summary") : tr("cuemymusic.radio.based_on", seed.title(), seed.artist());
        // The mode says where its songs come from, so the page doesn't ask again.
        y = drawHeader(g, x0, y, x1, seed == null ? RADIO_ART : seed.art(), tr("cuemymusic.radio.kind." + mode()), title, summary,
                0xFF24456B);
        int left = x0 + 10, right = x1 - 10;
        if (seed != null) {
            chip(g, left, y, tr("cuemymusic.radio.everything"), false, mx, my, () -> {
                player.clearRadioSeed();
                showToast(tr("cuemymusic.radio.from_everything"));
            });
            y += 22;
        }
        List<Track> tracks = player.radioTracks();
        if (tracks.isEmpty()) {
            g.textWithWordWrap(font, Component.translatable("cuemymusic.radio.empty"), left, y + 4, right - left, DIM, false);
            return y + 24;
        }
        return drawTrackList(g, x0, y, x1, mx, my, CuePlayer.RADIO, tracks);
    }

    // ------------------------------------------------------------------- home

    /** A list Home can show: what it's called, its art, and how to open and play it. */
    private record HomeList(String key, String name, Art art, Runnable open, Runnable play) {
    }

    /** The list a player key names, if it's still there; null for one that isn't, or isn't a list of the library's. */
    private HomeList homeList(String k) {
        int colon = k.indexOf(':');
        String kind = colon < 0 ? k : k.substring(0, colon), rest = colon < 0 ? "" : k.substring(colon + 1);
        return switch (kind) {
            case CuePlayer.ALL -> mode().equals("addons") ? null : new HomeList(k, tr("cuemymusic.playlist.title"), PLAYLIST_ART, () -> open(View.PLAYLIST, null),
                    () -> player.playAll(k, gameMusicShown()));
            case "liked" -> liked().isEmpty() ? null : new HomeList(k, tr("cuemymusic.liked.title"), LIKED_ART,
                    () -> open(View.LIKED, null), () -> player.playAll(k, liked()));
            case CuePlayer.RADIO -> !radioHere() ? null : new HomeList(k, tr("cuemymusic.radio.title"), RADIO_ART, () -> open(View.RADIO, null),
                    () -> player.startRadio(player.radioSeed()));
            case "artist" -> Modes.only(MusicLibrary.songsBy(rest)).isEmpty() ? null : new HomeList(k, rest, artistArt(rest),
                    () -> open(View.ARTIST, rest), () -> player.playAll(k, Modes.only(MusicLibrary.songsBy(rest))));
            case "pack" -> {
                PackInfo p = MusicLibrary.pack(rest);
                CuePanel panel = panelFor(rest);
                yield p == null || Modes.only(MusicLibrary.byPack(rest)).isEmpty() ? null
                        : new HomeList(k, panel != null ? panel.title() : p.name(), panel != null ? panel.icon() : p.art(), () -> open(View.PACK, rest),
                        () -> player.playAll(k, Modes.only(MusicLibrary.byPack(rest))));
            }
            case "pool" -> {
                Identifier p = Identifier.tryParse(rest);
                yield p == null || !MusicLibrary.pools().contains(p) || mode().equals("addons") ? null : new HomeList(k, poolName(p), poolArt(p),
                        () -> open(View.POOL, rest), () -> player.playAll(k, poolTracks(p)));
            }
            case "list" -> {
                CueConfig.Playlist p = cfg.playlist(rest);
                yield p == null ? null : new HomeList(k, p.name(), Art.initials(p.name()), () -> open(View.LIST, rest),
                        () -> player.playAll(k, Modes.only(MusicLibrary.tracks(p.songs()))));
            }
            default -> null;
        };
    }

    /**
     * As Spotify's Home: a greeting, the lists played lately as tiles, then
     * your top songs, your top artists, and radios from your top songs.
     */
    private int drawHome(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        int left = x0 + 12, right = x1 - 12, w = right - left;
        g.fillGradient(x0, y, x1, y + 90, 0xFF1E3A2A, PANEL);
        int hour = java.time.LocalTime.now().getHour();
        String greeting = tr(hour < 5 || hour >= 18 ? "cuemymusic.home.evening" : hour < 12 ? "cuemymusic.home.morning"
                : "cuemymusic.home.afternoon");
        bigText(g, clip(greeting, w / 2), left, y + 14, 2, WHITE);
        y += 38;

        // Lists played lately, then the ones everyone has, up to 8.
        List<HomeList> tiles = new ArrayList<>();
        List<String> recent = cfg.listsPlayed.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        List<String> keys = new ArrayList<>(recent);
        keys.addAll(List.of("liked", CuePlayer.ALL, CuePlayer.RADIO));
        for (String k : keys) {
            if (tiles.size() >= 8 || tiles.stream().anyMatch(t -> t.key().equals(k))) continue;
            HomeList l = homeList(k);
            if (l != null) tiles.add(l);
        }
        int cols = w >= 360 ? 4 : 2, gap = 6, tileW = (w - gap * (cols - 1)) / cols, tileH = 28;
        for (int i = 0; i < tiles.size(); i++) {
            HomeList l = tiles.get(i);
            int tx = left + (i % cols) * (tileW + gap), ty = y + (i / cols) * (tileH + gap);
            boolean hover = mx >= tx && mx < tx + tileW && my >= ty && my < ty + tileH && my >= centerY0 && my < centerY1;
            g.fill(tx, ty, tx + tileW, ty + tileH, hover ? 0xFF3A3A3A : 0xFF2A2A2A);
            art(g, l.art(), tx, ty, tileH);
            g.text(font, clip(l.name(), tileW - tileH - (hover ? 30 : 10)), tx + tileH + 6, ty + 10, WHITE, false);
            nextHits.add(new Hit(tx, ty, tx + tileW, ty + tileH, l.open()));
            if (hover) {
                // Play, without opening it.
                int pb = 18, px = tx + tileW - pb - 4, py = ty + 5;
                roundButton(g, px, py, pb, GREEN);
                playIcon(g, px + 7, py + 5, 8, BLACK);
                nextHits.add(new Hit(px, py, px + pb, py + pb, l.play()));
            }
        }
        y += (tiles.size() + cols - 1) / cols * (tileH + gap) + 14;

        List<Track> top = ListeningStats.INSTANCE.top(24).stream().map(CueScreen::statsTrack).filter(t -> t != null && Modes.allows(t)).toList();
        if (top.isEmpty()) {
            g.textWithWordWrap(font, Component.translatable("cuemymusic.home.empty"), left, y, w, DIM, false);
            return y + font.wordWrapHeight(Component.translatable("cuemymusic.home.empty"), w) + 8;
        }
        int card = 64, fit = Math.max(1, (w + 8) / (card + 8));
        y = homeRow(g, left, y, right, tr("cuemymusic.home.top_songs"), top.subList(0, Math.min(fit, top.size())), mx, my,
                Track::art, Track::title, Track::artist, (t, i) -> player.play("stats", top, i));

        Map<String, Double> byArtist = new java.util.LinkedHashMap<>();
        ListeningStats.INSTANCE.songs().forEach((id, song) -> {
            Track t = statsTrack(id);
            if (t != null && Modes.allows(t) && song.seconds > 0) byArtist.merge(t.artist(), song.seconds, Double::sum);
        });
        List<String> artists = byArtist.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(fit).toList();
        y = homeRow(g, left, y, right, tr("cuemymusic.home.top_artists"), artists, mx, my, this::artistArt, a -> a,
                a -> tr("cuemymusic.artist"), (a, i) -> open(View.ARTIST, a));

        if (!radioHere()) return y;
        List<Track> seeds = top.subList(0, Math.min(fit, top.size()));
        return homeRow(g, left, y, right, tr("cuemymusic.home.radio"), seeds, mx, my, Track::art,
                t -> tr("cuemymusic.radio.song", t.title()), Track::artist, (t, i) -> {
                    player.startRadio(t);
                    open(View.RADIO, null);
                });
    }

    /** A titled row of cards, each art over two lines of text; clicking one runs {@code click} with it and its place. */
    private <T> int homeRow(GuiGraphicsExtractor g, int left, int y, int right, String title, List<T> items, int mx, int my,
            java.util.function.Function<T, Art> art, java.util.function.Function<T, String> line1,
            java.util.function.Function<T, String> line2, java.util.function.BiConsumer<T, Integer> click) {
        if (items.isEmpty()) return y;
        bigText(g, title, left, y, 1.5f, WHITE);
        y += 18;
        int card = 64, cx = left;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            int h = card + 30;
            boolean hover = mx >= cx - 4 && mx < cx + card + 4 && my >= y - 4 && my < y + h && my >= centerY0 && my < centerY1;
            if (hover) g.fill(cx - 4, y - 4, cx + card + 4, y + h, ROW_HOVER);
            art(g, art.apply(item), cx, y, card);
            g.text(font, clip(line1.apply(item), card), cx, y + card + 5, WHITE, false);
            g.text(font, clip(line2.apply(item), card), cx, y + card + 16, DIM, false);
            int index = i;
            nextHits.add(new Hit(cx - 4, y - 4, cx + card + 4, y + h, () -> click.accept(item, index)));
            cx += card + 8;
            if (cx + card > right) break;
        }
        return y + card + 40;
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
            if (Modes.allows(skipped) && !isDisliked(skipped)) {
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

        // The mode first: everything below depends on it.
        g.text(font, tr("cuemymusic.settings.mode"), left, y, WHITE, false);
        y += 14;
        y = drawModeSwitch(g, left, y, right, mx, my);
        y = drawModeKey(g, left, y + 4, right, mx, my);
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.mode_cycle"), tr("cuemymusic.option.mode_cycle.desc"),
                cfg.modeKeyCyclesAll, () -> cfg.modeKeyCyclesAll = !cfg.modeKeyCyclesAll);
        y += 10;

        g.text(font, tr("cuemymusic.settings.playback"), left, y, WHITE, false);
        y += 14;
        if (mode().equals("fx")) y = drawSongGap(g, left, y, right);
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
        y = sliderRow(g, left, y, right, tr("cuemymusic.option.crossfade"),
                cfg.crossfadeSeconds == 0 ? tr("cuemymusic.option.crossfade.off")
                        : tr("cuemymusic.option.crossfade.value", cfg.crossfadeSeconds),
                cfg.crossfadeSeconds / 12.0, f -> cfg.crossfadeSeconds = (int) Math.round(f * 12), 4 / 12.0, -1);
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.skip_blend"), tr("cuemymusic.option.skip_blend.desc"),
                cfg.skipWithTransition, () -> cfg.skipWithTransition = !cfg.skipWithTransition);
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.autoplay"), tr("cuemymusic.option.autoplay.desc"),
                cfg.autoplay, () -> cfg.autoplay = !cfg.autoplay);
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

    /** The mode key: what it's bound to, which opens the game's Controls to change it, and what it does. */
    private int drawModeKey(GuiGraphicsExtractor g, int left, int y, int right, int mx, int my) {
        net.minecraft.client.KeyMapping k = CueMyMusicClient.modeKey();
        String bound = k.isUnbound() ? tr("cuemymusic.option.mode_key.unbound") : k.getTranslatedKeyMessage().getString();
        String change = tr("cuemymusic.option.mode_key.change");
        g.text(font, tr("cuemymusic.option.mode_key"), left, y + 1, WHITE, false);
        String desc = tr(cfg.modeKeyCyclesAll ? "cuemymusic.option.mode_key.desc_all" : "cuemymusic.option.mode_key.desc");
        int cw = font.width(change) + 12, cx = right - cw;
        g.text(font, clip(desc, cx - left - font.width(bound) - 16), left, y + 11, DIM, false);
        g.text(font, bound, cx - 8 - font.width(bound), y + 6, k.isUnbound() ? FAINT : GREEN, false);
        boolean hover = mx >= cx && mx < right && my >= y + 2 && my < y + 17;
        g.fill(cx, y + 2, right, y + 17, hover ? 0xFF3A3A3A : 0xFF2A2A2A);
        g.text(font, change, cx + 6, y + 6, WHITE, false);
        nextHits.add(new Hit(cx, y + 2, right, y + 17,
                () -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(this, minecraft.options))));
        return y + 24;
    }

    /** Vanilla + FX's time between the game's songs: the game's Frequency, or a set time; short enough, its songs blend. */
    private int drawSongGap(GuiGraphicsExtractor g, int left, int y, int right) {
        List<Integer> steps = CueConfig.SONG_GAPS;
        int at = Math.max(0, steps.indexOf(cfg.songGap));
        y = sliderRow(g, left, y, right, tr("cuemymusic.option.gap"), gapLabel(cfg.songGap), at / (double) (steps.size() - 1),
                f -> {
                    int gap = steps.get((int) Math.round(f * (steps.size() - 1)));
                    if (gap == cfg.songGap) return;
                    cfg.songGap = gap;
                    player.songGapChanged();
                }, 0, -1);
        boolean constant = minecraft.options.musicFrequency().get() == net.minecraft.client.sounds.MusicManager.MusicFrequency.CONSTANT;
        boolean blends = cfg.songGap >= 0 ? cfg.songGap <= 5 : constant;
        Component note = Component.translatable(blends ? "cuemymusic.option.gap.blends"
                : cfg.songGap < 0 ? "cuemymusic.option.gap.game" : "cuemymusic.option.gap.desc");
        g.textWithWordWrap(font, note, left, y - 4, right - left, FAINT, false);
        return y + font.wordWrapHeight(note, right - left);
    }

    /** "Game's Frequency", "None", "15 s" or "2 min". */
    private static String gapLabel(int seconds) {
        if (seconds < 0) return tr("cuemymusic.option.gap.frequency");
        if (seconds == 0) return tr("cuemymusic.option.gap.none");
        return seconds < 60 ? tr("cuemymusic.option.gap.seconds", seconds) : tr("cuemymusic.option.gap.minutes", seconds / 60);
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
            // Add-ons with no add-on songs stays quiet: say why.
            Component none = Component.translatable(mode().equals("addons") && MusicLibrary.addOnMusic().isEmpty()
                    ? "cuemymusic.now_playing.no_addons" : "cuemymusic.now_playing.none");
            g.textWithWordWrap(font, none, left, y0 + 24, w, DIM, false);
            drawQueue(g, x0, y0 + 24 + font.wordWrapHeight(none, w) + 12, x1, y1, mx, my);
            return;
        }
        // Where it's playing from, art, title, artist. Long titles and artists scroll.
        Object list = player.playingList();
        String from = list == null ? tr("cuemymusic.now_playing.from_game") : tr("cuemymusic.now_playing.from", listName(list));
        marquee(g, from, left, y0 + 7, w, WHITE, 1);
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
        if (Modes.allows(t)) {
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
        // Next from's rows count only where they're drawn this frame.
        nextFromRows = 0;
        int pad = 8;
        int left = x0 + pad, right = x1 - pad;
        List<Track> queue = player.queue();
        // The game's next song comes after the queue, with the break before it.
        Track game = player.upcoming();
        String gameWhen = null;
        if (game != null) {
            String wait = time(player.ticksUntilUpcoming() / 20.0);
            gameWhen = !player.waitingForGame() && player.ticksUntilUpcoming() < 20 ? tr("cuemymusic.queue.right_after")
                    : player.waitingForGame() && queue.isEmpty()
                    ? tr("cuemymusic.queue.in", wait)
                    : tr("cuemymusic.queue.after_break", wait);
        }

        Track listNext = player.listNext();
        if (queue.isEmpty() && listNext != null) {
            if (y + 12 + ROW > y1) return;
            // Repeat one, or the list going round again, has one next song; otherwise all that's left, as on Spotify.
            List<Track> rest = player.upNext(Math.max(1, (y1 - y - 12 - 4) / QROW));
            // Repeat one: the song playing comes round again, so it's "On repeat", not next up.
            boolean again = listNext.equals(player.current());
            if (audit != null && !again) audit.fact("next up", listNext.title());
            if (rest.isEmpty() || !rest.getFirst().equals(listNext)) {
                g.text(font, clip(tr(again ? "cuemymusic.queue.on_repeat" : "cuemymusic.queue.next_up"), right - left), left, y, WHITE, false);
                nextRow(g, listNext, listNext.artist(), x0, y + 12, x1, mx, my, player::next);
                return;
            }
            drawNextFrom(g, rest, x0, y, x1, y1, mx, my);
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
            if (audit != null) audit.fact("next up", game.title());
            gameRow(g, game, gameWhen, x0, y + 12, x1, mx, my);
            int hy = y + 12 + ROW + 8;
            Component hint = Component.translatable("cuemymusic.queue.empty");
            if (hy + font.wordWrapHeight(hint, right - left) < y1) g.textWithWordWrap(font, hint, left, hy, right - left, FAINT, false);
            return;
        }

        int gameH = game != null ? 12 + ROW + 6 : 0;
        g.text(font, clip(tr("cuemymusic.queue"), right - left - 30), left, y, WHITE, false);
        // Clear, as on Spotify.
        String clear = tr("cuemymusic.queue.clear");
        int cw = font.width(clear), cx = right - cw;
        boolean clearHover = queueDrag < 0 && mx >= cx - 2 && mx < right + 2 && my >= y - 2 && my < y + 10;
        g.text(font, clear, cx, y, clearHover ? WHITE : DIM, false);
        nextHits.add(new Hit(cx - 2, y - 2, right + 2, y + 10, () -> {
            player.clearQueue();
            queueScroll = 0;
            showToast(tr("cuemymusic.queue.cleared"));
        }));
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
        List<Track> rest = queue.size() <= fit ? player.upNext(Math.max(0, (y1 - end - 6 - 12 - 4) / QROW)) : List.of();
        if (!rest.isEmpty()) {
            // Then the rest of the list, as Spotify's Next from.
            drawNextFrom(g, rest, x0, end + 6, x1, y1, mx, my);
        } else if (showGame && player.playingList() == null) {
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

    /** Repeat off, all, one; while Radio plays, says why it can't. */
    private void cycleRepeat() {
        if (player.isPlayingList(CuePlayer.RADIO)) {
            showToast(tr("cuemymusic.repeat.radio"));
            return;
        }
        PlayOrder.Repeat next = cfg.repeatMode().cycle();
        cfg.setRepeat(next);
        showToast(tr("cuemymusic.repeat." + next.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /** "Next from" the list playing, and its songs to come; clicking one skips the list to it. */
    private void drawNextFrom(GuiGraphicsExtractor g, List<Track> rest, int x0, int y, int x1, int y1, int mx, int my) {
        int left = x0 + 8, right = x1 - 8;
        g.text(font, clip(tr("cuemymusic.queue.next_from", listName(player.playingList())), right - left), left, y, WHITE, false);
        // Clicking a row plays it; dragging one moves it, as in the queue.
        nextFromX0 = x0;
        nextFromX1 = x1;
        nextFromTop = y + 12;
        nextFromRows = 0;
        boolean dragging = nextFromDrag >= 0 && nextFromDrag < rest.size() && Math.abs(nextFromDragY - nextFromPressY) > 4;
        for (int k = 0; k < rest.size(); k++) {
            int ry = nextFromTop + k * QROW;
            if (ry + QROW > y1) break;
            nextFromRows++;
            if (dragging && k == nextFromDrag) continue;
            boolean hover = queueDrag < 0 && nextFromDrag < 0 && mx >= x0 && mx < x1 && my >= ry && my < ry + QROW;
            if (hover) g.fill(x0 + 4, ry, x1 - 4, ry + QROW, ROW_HOVER);
            compactQueueRow(g, rest.get(k), left, ry, right - left);
        }
        if (dragging) {
            int ly = nextFromTop + nextFromSlot(nextFromDragY) * QROW;
            g.fill(x0 + 4, ly - 1, x1 - 4, ly + 1, GREEN);
            int ry = (int) Math.round(nextFromDragY) - QROW / 2;
            g.fill(x0 + 4, ry, x1 - 4, ry + QROW, 0xFF333333);
            compactQueueRow(g, rest.get(nextFromDrag), left, ry, right - left);
        }
    }

    /** Where a dragged Next from row would land, as a place between its rows. */
    private int nextFromSlot(double y) {
        return Math.clamp((int) Math.round((y - nextFromTop) / QROW), 0, nextFromRows);
    }

    /** Pressing a Next from row: it plays on release, or moves if it's dragged first. */
    private boolean nextFromClicked(double x, double y) {
        if (nextFromRows == 0 || x < nextFromX0 || x >= nextFromX1 || y < nextFromTop || y >= nextFromTop + nextFromRows * QROW) return false;
        nextFromDrag = (int) ((y - nextFromTop) / QROW);
        nextFromDragY = nextFromPressY = y;
        return true;
    }

    /** What a list the player plays through is called, from its key. */
    private String listName(Object listKey) {
        String k = String.valueOf(listKey);
        int colon = k.indexOf(':');
        String kind = colon < 0 ? k : k.substring(0, colon), rest = colon < 0 ? "" : k.substring(colon + 1);
        return switch (kind) {
            case CuePlayer.ALL -> tr("cuemymusic.playlist.title");
            case CuePlayer.RADIO -> tr("cuemymusic.radio.title");
            case "liked" -> tr("cuemymusic.liked.title");
            case "disliked" -> tr("cuemymusic.disliked.title");
            case "artist" -> rest;
            case "pack" -> {
                // An add-on's page names its list "pack:<id>", or "pack:<id>:filter:<words>" while filtered.
                int filter = rest.indexOf(":filter:");
                String id = filter < 0 ? rest : rest.substring(0, filter);
                CuePanel panel = panelFor(id);
                PackInfo p = MusicLibrary.pack(id);
                String name = panel != null ? panel.title() : p != null ? p.name() : tr("cuemymusic.queue.a_pack");
                yield filter < 0 ? name : tr("cuemymusic.queue.filtered", name, rest.substring(filter + ":filter:".length()));
            }
            case "stats" -> tr("cuemymusic.home.top_songs");
            case "list" -> {
                CueConfig.Playlist p = cfg.playlist(rest);
                yield p == null ? tr("cuemymusic.playlist") : p.name();
            }
            case "pool" -> {
                Identifier p = Identifier.tryParse(rest);
                yield p == null ? rest : poolName(p);
            }
            case "search" -> tr("cuemymusic.queue.search", rest);
            default -> tr("cuemymusic.queue.the_list");
        };
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
            int tx = x0 + GAP + 36, room = leftW - 36 - 16;
            String title = clip(t.title(), room), artist = clip(t.artist(), room);
            g.text(font, title, tx, y0 + 12, WHITE, false);
            g.text(font, artist, tx, y0 + 22, DIM, false);
            // Like, right of the song, as Spotify's save button.
            int hx = tx + Math.max(font.width(title), font.width(artist)) + 8, hy = y0 + 17;
            boolean liked = isLiked(t);
            heartIcon(g, hx, hy, liked, liked ? GREEN : hoverColor(hx - 2, hy - 2, 13, 12, mx, my));
            nextHits.add(new Hit(hx - 2, hy - 2, hx + 11, hy + 10, () -> toggleLiked(t)));
        }

        // Controls in the middle.
        int cx = (x0 + x1) / 2;
        int cy = y0 + 5;
        int btn = 14;
        int shx = cx - btn / 2 - 42;
        shuffleIcon(g, shx, cy + 2, player.shuffle() ? GREEN : hoverColor(shx - 2, cy, 13, 12, mx, my));
        if (player.shuffle()) g.fill(shx + 4, cy + 12, shx + 6, cy + 13, GREEN);
        nextHits.add(new Hit(shx - 2, cy, shx + 11, cy + 12, this::toggleShuffle));
        nextHits.add(new Hit(cx - btn / 2 - 22, cy, cx - btn / 2 - 10, cy + 12, player::previous));
        previousIcon(g, cx - btn / 2 - 20, cy + 2, 8, hoverColor(cx - btn / 2 - 22, cy, 12, 12, mx, my));
        roundButton(g, cx - btn / 2, cy - 1, btn, WHITE);
        if (player.isPlaying()) pauseIcon(g, cx - 3, cy + 2, 7, BLACK);
        else playIcon(g, cx - 2, cy + 2, 7, BLACK);
        nextHits.add(new Hit(cx - btn / 2, cy - 1, cx + btn / 2, cy - 1 + btn, player::togglePause));
        nextHits.add(new Hit(cx + btn / 2 + 10, cy, cx + btn / 2 + 22, cy + 12, player::next));
        nextIcon(g, cx + btn / 2 + 12, cy + 2, 8, hoverColor(cx + btn / 2 + 10, cy, 12, 12, mx, my));
        // Repeat: off, all, one.
        // Radio never ends, so repeat is greyed out while it plays.
        PlayOrder.Repeat repeat = player.repeat();
        boolean radio = player.isPlayingList(CuePlayer.RADIO);
        int rx = cx + btn / 2 + 32;
        repeatIcon(g, rx, cy + 2, repeat, radio ? 0xFF3A3A3A : repeat != PlayOrder.Repeat.OFF ? GREEN : hoverColor(rx - 2, cy, 13, 12, mx, my));
        nextHits.add(new Hit(rx - 2, cy, rx + 11, cy + 12, this::cycleRepeat));

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

        // Right of the progress bar, from the right: pitch and speed (opens settings), the ring that shows a
        // change on its way, the music volume. The volume always has its place; pitch and speed
        // are left out first when the bar is narrow, as on a big GUI scale.
        String fx = cfg.enabled ? tr("cuemymusic.bar.fx", signed(cfg.pitchSemitones), String.format("%.2f", cfg.speed))
                : tr("cuemymusic.bar.fx_off");
        int roomLeft = bx1 + font.width(lenText) + 16;
        int right = x1 - GAP - 4;
        // Speaker 15, slider 56, the ring's place 24: the ring has room kept for it, so nothing shifts when it shows.
        // Where the full readout doesn't fit, a short one: still says what it is, and still opens Settings.
        if (right - font.width(fx) - 24 - 56 - 15 <= roomLeft) {
            fx = cfg.enabled ? tr("cuemymusic.bar.fx_short", signed(cfg.pitchSemitones), String.format("%.2f", cfg.speed))
                    : tr("cuemymusic.bar.fx_off_short");
        }
        int fw = font.width(fx);
        boolean showFx = right - fw - 24 - 24 - 15 > roomLeft;
        int fx0 = showFx ? right - fw : right + 10;
        if (showFx) {
            if (audit != null) audit.text("readout", fx, fx);
            if (audit != null) audit.box(fx, fx0, y0 + 13, fx0 + fw, y0 + 28);
            g.text(font, fx, fx0, y0 + 17, cfg.enabled && (cfg.pitchSemitones != 0 || cfg.speed != 1) ? GREEN : DIM, false);
            nextHits.add(new Hit(fx0, y0 + 12, x1, y0 + 30, () -> open(View.SETTINGS, null)));
        }
        double applying = applying();
        if (applying >= 0) applyingIcon(g, fx0 - 11, y0 + 17, applying);
        // The slider narrows before it would go.
        int vx1 = fx0 - 24, vx0 = Math.min(vx1 - 24, Math.max(vx1 - 56, roomLeft + 15)), ix = vx0 - 15;
        if (ix > roomLeft - 2) {
            drawVolume(g, ix, vx0, vx1, y0 + 20, mx, my);
            if (audit != null) audit.box("Volume", ix - 2, y0 + 13, vx1 + 2, y0 + 28);
        }
        if (audit != null) audit.fact("volume shown", ix > roomLeft - 2);
    }

    /**
     * The game's music volume, as a speaker and a slider: the same setting as
     * Music in the game's sound options. Clicking the speaker mutes and unmutes.
     */
    private void drawVolume(GuiGraphicsExtractor g, int ix, int vx0, int vx1, int vy, int mx, int my) {
        OptionInstance<Double> music = minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
        double v = music.get();
        boolean iconHover = mx >= ix - 2 && mx < ix + 12 && my >= vy - 7 && my < vy + 7;
        speakerIcon(g, ix, vy - 5, v, iconHover ? WHITE : DIM);
        nextHits.add(new Hit(ix - 2, vy - 7, ix + 12, vy + 7, () -> {
            if (music.get() > 0) {
                unmutedVolume = music.get();
                music.set(0.0);
            } else {
                music.set(unmutedVolume > 0 ? unmutedVolume : 1.0);
            }
            minecraft.options.save();
        }));
        boolean hover = mx >= vx0 - 2 && mx < vx1 + 2 && my >= vy - 6 && my < vy + 8;
        boolean held = dragging != null && dragging.y0() == vy - 6 && dragging.x0() == vx0 - 2;
        g.fill(vx0, vy, vx1, vy + 3, TRACK_BG);
        int px = vx0 + (int) Math.round(Math.clamp(v, 0, 1) * (vx1 - vx0));
        g.fill(vx0, vy, px, vy + 3, hover || held ? GREEN : WHITE);
        if (hover || held) g.fill(px - 3, vy - 2, px + 3, vy + 5, WHITE);
        nextDrags.add(new Drag(vx0 - 2, vy - 6, vx1 + 2, vy + 8, false, f -> music.set(Math.round(f * 100) / 100.0), minecraft.options::save));
    }

    /** A speaker with sound waves for how loud {@code v} is, or a cross when it's 0. 11 wide, 11 high. */
    private static void speakerIcon(GuiGraphicsExtractor g, int x, int y, double v, int color) {
        g.fill(x, y + 4, x + 3, y + 7, color);
        g.fill(x + 3, y + 3, x + 4, y + 8, color);
        g.fill(x + 4, y + 2, x + 5, y + 9, color);
        g.fill(x + 5, y + 1, x + 6, y + 10, color);
        if (v <= 0) {
            for (int i = 0; i < 4; i++) {
                g.fill(x + 7 + i, y + 4 + i, x + 8 + i, y + 5 + i, color);
                g.fill(x + 10 - i, y + 4 + i, x + 11 - i, y + 5 + i, color);
            }
            return;
        }
        g.fill(x + 7, y + 4, x + 8, y + 7, color);
        if (v > 0.33) g.fill(x + 9, y + 2, x + 10, y + 9, color);
        if (v > 0.66) g.fill(x + 11, y, x + 12, y + 11, color);
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

    /**
     * Spotify's player keys, with Cmd (Ctrl elsewhere): left and right for
     * previous and next, up and down for the music volume, S for shuffle and
     * R for repeat.
     */
    private boolean playerKey(KeyEvent event) {
        if (!event.hasControlDownWithQuirk() || event.hasShiftDown() || event.hasAltDown()) return false;
        switch (event.key()) {
            case InputConstants.KEY_LEFT -> player.previous();
            case InputConstants.KEY_RIGHT -> player.next();
            case InputConstants.KEY_UP, InputConstants.KEY_DOWN -> {
                OptionInstance<Double> music = minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
                double step = event.key() == InputConstants.KEY_UP ? 0.1 : -0.1;
                music.set(Math.clamp(Math.round((music.get() + step) * 10) / 10.0, 0, 1));
                minecraft.options.save();
                showToast(tr("cuemymusic.volume", (int) Math.round(music.get() * 100)));
            }
            case InputConstants.KEY_S -> toggleShuffle();
            case InputConstants.KEY_R -> cycleRepeat();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The song row last clicked and when, so a second click on it soon after plays it. */
    private String lastRowClick;
    private long lastRowClickAt;
    /** The list and its shown songs that rows were last clicked in, so Enter can play the selected one. */
    private String rowListKey;
    private List<Track> rowList = List.of();
    private static final long DOUBLE_CLICK_MILLIS = 400;

    /** As on Spotify: a click selects the song, and a double click plays it from there. */
    private void rowClick(String listKey, List<Track> tracks, int index) {
        String id = id(tracks.get(index));
        long now = System.currentTimeMillis();
        rowListKey = listKey;
        rowList = tracks;
        if (id.equals(lastRowClick) && now - lastRowClickAt < DOUBLE_CLICK_MILLIS) {
            lastRowClick = null;
            clearSelection();
            player.play(listKey, tracks, index);
            return;
        }
        lastRowClick = id;
        lastRowClickAt = now;
        clearSelection();
        selected.add(id);
        anchor = index;
        focusRow = index;
    }

    /** Shift+up/down grows or shrinks the selection, Cmd/Ctrl+A selects all, Esc clears, Delete removes, Enter plays. */
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
        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && rowListKey != null) {
            // The first selected song, from the list it's in.
            for (int i = 0; i < rowList.size(); i++) {
                if (selected.contains(id(rowList.get(i)))) {
                    player.play(rowListKey, rowList, i);
                    clearSelection();
                    return true;
                }
            }
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
            case POOL -> {
                String pool = key;
                yield new MenuItem(MenuIcon.REMOVE, tr("cuemymusic.bulk.remove_from_pool", n), () -> {
                    removeFromPool(tracks, pool);
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
            default -> {
                // Anywhere else, such as search or an artist's page: the add-on whose songs they all are deletes them, as on its own page.
                CuePanel panel = panelFor(tracks.getFirst());
                for (Track t : tracks) {
                    if (panel != null && panel != panelFor(t)) panel = null;
                }
                CuePanel.Action a = panel != null ? panel.deleteAction(tracks) : null;
                yield a == null ? null : new MenuItem(MenuIcon.DELETE, a.label(), () -> {
                    a.run().run();
                    done.run();
                });
            }
        };
    }

    /** The add-on page whose pack {@code t} is in, or null. */
    private static CuePanel panelFor(Track t) {
        if (t.packId() == null) return null;
        for (CuePanel p : CuePanels.all()) {
            if (t.packId().equals(p.packId())) return p;
        }
        return null;
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
        if (mode().equals("fx") && !MusicLibrary.pools().isEmpty()) {
            items.add(new MenuItem(MenuIcon.POOL, tr("cuemymusic.bulk.add_to_pool", n),
                    () -> showMenu(new Menu(x, y, poolItems(x, y, pools -> addToPools(tracks, pools))))));
        }
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
        List<Track> game = tracks.stream().filter(Modes::allows).toList();
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
        // One clicked song is just highlighted, as on Spotify.
        if (selected.size() < 2 || shownTracks.isEmpty()) return;
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
        public void tabs(List<String> labels, int picked, java.util.function.IntConsumer pick) {
            y = segmented(g, left(), y, right(), mx, my, labels, picked, pick) + 4;
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
        if (menu == null && mixFrom != null && event.key() == InputConstants.KEY_ESCAPE) {
            closeTransition();
            return true;
        }
        if (menu != null && event.key() == InputConstants.KEY_BACKSPACE && !menuFilter.isEmpty()) {
            menuFilter = menuFilter.substring(0, menuFilter.offsetByCodePoints(menuFilter.length(), -1));
            subScroll = 0;
            return true;
        }
        // While a menu is open, keys type into its playlist search rather than playing, pausing or seeking.
        if (menu != null) return true;
        // Cmd/Ctrl+Shift+L searches everything, as in Spotify; Cmd/Ctrl+F searches the page open, if it's a list of songs.
        if (event.key() == InputConstants.KEY_L && event.hasControlDownWithQuirk() && event.hasShiftDown()) {
            if (view == View.SEARCH) focusSearch = true;
            else openSearch();
            return true;
        }
        if (event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk()) {
            if (view == View.SEARCH) {
                focusSearch = true;
            } else if (hasSongList()) {
                pageFilterOpen = true;
                focusPageFilter = true;
            } else {
                openSearch();
            }
            return true;
        }
        if (playerKey(event)) return true;
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
            for (LibraryMenu m : libraryMenus) {
                if (m.contains(event.x(), event.y())) {
                    List<MenuItem> items = m.items().get();
                    if (!items.isEmpty()) showMenu(new Menu((int) event.x(), (int) event.y(), items));
                    return true;
                }
            }
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
            if (nextFromClicked(x, y)) return true;
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
            // The transition editor's songs drag sideways.
            if (mixLaneScroll != null && x >= mixLaneX0 && x < mixLaneX1 && y >= mixLaneY0 && y < mixLaneY1) {
                laneDrag = y < mixLaneSplit ? 1 : 2;
                laneDragX = x;
                laneDragBase = laneDrag == 1 ? mixLaneValueA : mixLaneValueB;
                return true;
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
        if (nextFromDrag >= 0) {
            nextFromDragY = event.y();
            return true;
        }
        if (dragging != null) {
            dragging.onFraction().accept(dragging.fraction(event.x(), event.y()));
            return true;
        }
        if (laneDrag != 0) {
            // The song follows the mouse: the first one's start moves later as it's dragged left, the next one comes in later.
            double moved = (event.x() - laneDragX) * mixLaneSpp;
            if (laneDrag == 1 && mixLaneSetA != null) mixLaneSetA.accept(laneDragBase + moved);
            if (laneDrag == 2 && mixLaneSetB != null) mixLaneSetB.accept(laneDragBase - moved);
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
        if (nextFromDrag >= 0) {
            int from = nextFromDrag;
            nextFromDrag = -1;
            if (Math.abs(event.y() - nextFromPressY) <= 4) {
                player.playUpNext(from);
            } else {
                int slot = nextFromSlot(event.y());
                player.moveUpNext(from, slot > from ? slot - 1 : slot);
            }
            return true;
        }
        if (dragging != null) {
            Drag d = dragging;
            dragging = null;
            d.onRelease().run();
            return true;
        }
        if (laneDrag != 0) {
            laneDrag = 0;
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
        boolean sideways = Math.abs(horizontal) > Math.abs(vertical) || minecraft.hasShiftDown();
        if (presetScrollMax > 0 && mixFrom != null && x >= presetX0 && x < presetX1 && y >= presetY0 && y < presetY1) {
            presetScroll = Math.clamp(presetScroll - (horizontal != 0 ? horizontal : vertical) * 24, 0, presetScrollMax);
            return true;
        }
        if (mixLaneScroll != null && sideways && x >= mixLaneX0 && x < mixLaneX1 && y >= mixLaneY0 && y < mixLaneY1) {
            // Whole beats only: what's left over waits for more scrolling.
            mixScrollRest += horizontal != 0 ? horizontal : vertical;
            int steps = (int) mixScrollRest;
            mixScrollRest -= steps;
            if (steps != 0) (y < mixLaneSplit ? mixLaneScroll : mixLaneScrollNext).accept(steps);
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
            case Art.Sheet sheet -> g.blit(RenderPipelines.GUI_TEXTURED, sheet.id(), x, y, 0, 0, size, size, 16, 16, 16, sheet.height());
            case Art.Sprite sp -> {
                g.fillGradient(x, y, x + size, y + size, sp.top(), sp.bottom());
                int s = Math.max(5, size * 9 / 16);
                g.blitSprite(RenderPipelines.GUI_TEXTURED, sp.sprite(), x + (size - s) / 2, y + (size - s) / 2, s, s);
            }
            case Art.Stamp st -> {
                g.blit(RenderPipelines.GUI_TEXTURED, st.block(), x, y, 0, 0, size, size, 16, 16, 16, 16);
                g.fill(x, y, x + size, y + size, 0x66000000);
                float scale = Math.min(size * 0.7f / Math.max(1, font.width(st.text())), size * 0.45f / 8);
                g.pose().pushMatrix();
                g.pose().translate(x + size / 2f, y + size / 2f);
                g.pose().scale(scale, scale);
                g.text(font, st.text(), -font.width(st.text()) / 2, -4, WHITE, true);
                g.pose().popMatrix();
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

    /** Text that fits is drawn as is; longer text waits, scrolls to its end, waits, and scrolls back. */
    private void marquee(GuiGraphicsExtractor g, String s, int x, int y, int w, int color, float scale) {
        float textW = font.width(s) * scale;
        if (textW <= w) {
            bigText(g, s, x, y, scale, color);
            return;
        }
        // Back and forth, pausing at each end: one copy of the text, never its end run into its start.
        float travel = textW - w;
        double pauseMs = 2000, speed = 25 * scale / 1000.0, move = travel / speed;
        double t = System.currentTimeMillis() % (long) (2 * (pauseMs + move));
        double out = t < pauseMs ? 0 : t < pauseMs + move ? (t - pauseMs) / move : t < 2 * pauseMs + move ? 1
                : 1 - (t - 2 * pauseMs - move) / move;
        float offset = (float) (out * travel);
        g.enableScissor(x, y - 1, x + w, y + (int) Math.ceil(9 * scale) + 1);
        g.pose().pushMatrix();
        g.pose().translate(x - offset, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        g.pose().popMatrix();
        g.disableScissor();
    }

    private String clip(String s, int maxWidth) {
        return clip("row", s, maxWidth);
    }

    /** {@code s} cut to fit {@code maxWidth}, ending in "..." if it had to be. {@code role} says to the audit what it is. */
    private String clip(String role, String s, int maxWidth) {
        String shown = maxWidth <= 0 ? "" : font.width(s) <= maxWidth ? s
                : font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
        if (audit != null && !shown.equals(s)) audit.text(role, s, shown);
        return shown;
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

    /** Two crossing arrows, 9×8. */
    private static void shuffleIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        pixels(g, x, y, color, "......X..", "XX...XXX.", "..X.X.X..", "...X.....", "...X.....", "..X.X.X..", "XX...XXX.", "......X..");
    }

    private void toggleShuffle() {
        boolean on = !player.shuffle();
        player.setShuffle(on);
        showToast(tr(on ? "cuemymusic.shuffle.on" : "cuemymusic.shuffle.off"));
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
