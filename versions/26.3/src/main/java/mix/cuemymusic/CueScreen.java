package mix.cuemymusic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
    private static final int TRACK_BG = 0xFF4D4D4D;
    private static final int GAP = 4;
    private static final int BOTTOM = 42;
    private static final int ROW = 20;
    /** Queue rows are a single line, so more of the queue fits. */
    private static final int QROW = 13;
    private static final Art PLAYLIST_ART = Art.block("block/jukebox_top");
    private static final Art SETTINGS_ART = Art.block("block/crafting_table_top");
    private static final Art LIKED_ART = new Art.Sprite(Identifier.withDefaultNamespace("hud/heart/full"), 0xFF5A3BD6, 0xFF8FB6E0);
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

    private enum View { PLAYLIST, LIKED, ARTIST, PACK, SETTINGS }

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
    /** The artist name or pack id of the open page. */
    private String key;
    private Sort sort = Sort.NONE;
    private boolean ascending = true;
    private double scroll;
    private int scrollMax;
    private int centerX0, centerY0, centerX1, centerY1;
    private double seekPreview = -1;
    private String toast;
    private long toastUntil;

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

        int sideW = Math.clamp(width / 5, 92, 150);
        int rightW = width >= 400 ? Math.clamp(width / 5, 96, 150) : 0;
        int top = GAP;
        int bottom = height - BOTTOM;

        drawLibrary(g, GAP, top, GAP + sideW, bottom - GAP, mouseX, mouseY);
        centerX0 = GAP * 2 + sideW;
        centerX1 = width - GAP - (rightW > 0 ? rightW + GAP : 0);
        centerY0 = top;
        centerY1 = bottom - GAP;
        drawCenter(g, mouseX, mouseY);
        if (rightW > 0) {
            drawNowPlaying(g, width - GAP - rightW, top, width - GAP, bottom - GAP, mouseX, mouseY);
        } else {
            queueRows = 0;
            queueFit = 0;
        }
        drawBottomBar(g, 0, bottom, width, height, mouseX, mouseY);
        drawToast(g);

        hits = nextHits;
        drags = nextDrags;
    }

    // ---------------------------------------------------------------- library

    private void drawLibrary(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        g.fill(x0, y0, x1, y1, PANEL);
        g.text(font, tr("cuemymusic.library"), x0 + 6, y0 + 7, WHITE, false);

        List<Track> all = MusicLibrary.all();
        List<String> artists = new ArrayList<>(Catalog.ARTISTS.stream().filter(a -> !MusicLibrary.byArtist(a).isEmpty()).toList());
        if (MusicLibrary.hasUnknown()) artists.add(Catalog.UNKNOWN_ARTIST);
        List<PackInfo> packs = MusicLibrary.packs();

        // Rows shrink to a single line when the window is short, so every entry still fits.
        int rows = artists.size() + packs.size() + 3;
        int h = Math.clamp((y1 - y0 - 24) / rows, 12, 22);
        int last = y1 - h - 2;
        int y = y0 + 20;
        y = libraryItem(g, x0, y, x1, h, PLAYLIST_ART, tr("cuemymusic.playlist.title"),
                tr("cuemymusic.playlist.subtitle", all.size()), view == View.PLAYLIST, mx, my, () -> open(View.PLAYLIST, null));
        y = libraryItem(g, x0, y, x1, h, LIKED_ART, tr("cuemymusic.liked.title"),
                tr("cuemymusic.playlist.subtitle", liked().size()), view == View.LIKED, mx, my, () -> open(View.LIKED, null));
        for (String a : artists) {
            if (y + h > last) break;
            Art art = Art.block(Catalog.ARTIST_ART.get(a).texture());
            y = libraryItem(g, x0, y, x1, h, art, a, tr("cuemymusic.artist"),
                    view == View.ARTIST && a.equals(key), mx, my, () -> open(View.ARTIST, a));
        }
        for (PackInfo p : packs) {
            if (y + h > last) break;
            y = libraryItem(g, x0, y, x1, h, p.art(), p.name(), tr("cuemymusic.pack"),
                    view == View.PACK && p.id().equals(key), mx, my, () -> open(View.PACK, p.id()));
        }

        libraryItem(g, x0, last, x1, h, SETTINGS_ART, tr("cuemymusic.settings"), tr("cuemymusic.settings.subtitle"),
                view == View.SETTINGS, mx, my, () -> open(View.SETTINGS, null));
    }

    private int libraryItem(GuiGraphicsExtractor g, int x0, int y, int x1, int h, Art art, String title,
            String subtitle, boolean selected, int mx, int my, Runnable click) {
        boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + h;
        if (selected || hover) g.fill(x0 + 2, y, x1 - 2, y + h, selected ? SELECTED : ROW_HOVER);
        int artSize = Math.min(16, h - 2);
        art(g, art, x0 + 5, y + (h - artSize) / 2, artSize);
        int tx = x0 + 9 + artSize;
        int w = x1 - tx - 4;
        if (h >= 21) {
            g.text(font, clip(title, w), tx, y + 3, selected ? GREEN : WHITE, false);
            g.text(font, clip(subtitle, w), tx, y + 12, DIM, false);
        } else {
            g.text(font, clip(title, w), tx, y + (h - 8) / 2, selected ? GREEN : WHITE, false);
        }
        nextHits.add(new Hit(x0, y, x1, y + h, click));
        return y + h;
    }

    private void open(View v, String k) {
        view = v;
        key = k;
        scroll = 0;
    }

    // ----------------------------------------------------------------- center

    private void drawCenter(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = centerX0, y0 = centerY0, x1 = centerX1, y1 = centerY1;
        g.fill(x0, y0, x1, y1, PANEL);
        g.enableScissor(x0, y0, x1, y1);
        int contentTop = y0 - (int) scroll;
        PackInfo pack = view == View.PACK ? MusicLibrary.pack(key) : null;
        if (view == View.PACK && pack == null) view = View.PLAYLIST;
        int contentBottom = switch (view) {
            case SETTINGS -> drawSettings(g, x0, contentTop, x1, mx, my);
            case ARTIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, "artist:" + key, MusicLibrary.byArtist(key),
                    Art.block(Catalog.ARTIST_ART.getOrDefault(key, Catalog.UNKNOWN_ALBUM).texture()), tr("cuemymusic.artist"),
                    key, null, artistColor(key));
            case PACK -> drawTrackPage(g, x0, contentTop, x1, mx, my, "pack:" + key, MusicLibrary.byPack(key), pack.art(),
                    tr("cuemymusic.pack"), pack.name(), pack.author(), PackNames.gradient(pack.name())[1]);
            case PLAYLIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, CuePlayer.ALL, MusicLibrary.all(), PLAYLIST_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.playlist.title"), null, 0xFF1E3A2A);
            case LIKED -> drawTrackPage(g, x0, contentTop, x1, mx, my, "liked", liked(), LIKED_ART,
                    tr("cuemymusic.playlist"), tr("cuemymusic.liked.title"), null, 0xFF3A2E6E);
        };
        g.disableScissor();
        scrollMax = Math.max(0, contentBottom + (int) scroll - y1 + 8);
        scroll = Math.clamp(scroll, 0, scrollMax);
    }

    /** A list of songs. {@code listKey} names it to the player, so its big button knows when it's the one playing. */
    private int drawTrackPage(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, String listKey, List<Track> unsorted,
            Art art, String kind, String title, String byline, int color) {
        List<Track> tracks = sorted(unsorted);

        // Header: art, kind, big title, count and length.
        int headerH = 70;
        g.fillGradient(x0, y, x1, y + headerH, color, PANEL);
        art(g, art, x0 + 10, y + 12, 48);
        int tx = x0 + 66;
        g.text(font, kind, tx, y + 14, WHITE, false);
        bigText(g, clip(title, (x1 - tx - 8) / 2), tx, y + 26, 2, WHITE);
        double total = tracks.stream().mapToDouble(t -> Double.isNaN(t.seconds()) ? 0 : t.seconds()).sum();
        String summary = tr("cuemymusic.page.summary", tracks.size(), longDuration(total));
        if (byline != null) summary = tr("cuemymusic.page.byline", byline, summary);
        g.text(font, clip(summary, x1 - tx - 8), tx, y + 48, DIM, false);
        y += headerH;

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
        // Add the whole list to the queue, in the order shown.
        if (!tracks.isEmpty()) {
            int qx = x0 + 10 + pb + 10, qy = y + 6;
            String label = tr("cuemymusic.queue.add_all");
            int qw = 13 + font.width(label);
            boolean qHover = mx >= qx - 2 && mx < qx + qw + 2 && my >= qy && my < qy + pb;
            plusIcon(g, qx, qy + 5, qHover ? WHITE : DIM);
            g.text(font, label, qx + 13, qy + 6, qHover ? WHITE : DIM, false);
            nextHits.add(new Hit(qx - 2, qy, qx + qw + 2, qy + pb, () -> {
                player.enqueueAll(tracks);
                showToast(tr("cuemymusic.queue.added_all", tracks.size()));
            }));
        }
        y += pb + 12;

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

        Track current = player.current();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            int index = i;
            boolean visible = y + ROW > centerY0 && y < centerY1;
            if (visible) {
                boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + ROW && my >= centerY0 && my < centerY1;
                boolean isCurrent = t.equals(current);
                if (hover) g.fill(x0 + 4, y, x1 - 4, y + ROW, ROW_HOVER);
                if (isCurrent && player.isPlaying()) equalizerIcon(g, x0 + 9, y + 6, GREEN);
                else g.text(font, Integer.toString(i + 1), x0 + 10, y + 6, isCurrent ? GREEN : DIM, false);
                art(g, t.art(), colTitle, y + 2, 16);
                int textX = colTitle + 20;
                int titleW = (showAlbum ? colAlbum - 6 : colTime - 60) - textX;
                g.text(font, clip(t.title(), titleW), textX, y + 2, isCurrent ? GREEN : WHITE, false);
                g.text(font, clip(t.artist(), titleW), textX, y + 11, DIM, false);
                if (showAlbum) g.text(font, clip(t.album(), colTime - 48 - colAlbum), colAlbum, y + 6, DIM, false);
                String len = Double.isNaN(t.seconds()) ? "-:--" : time(t.seconds());
                g.text(font, len, colTime - font.width(len), y + 6, DIM, false);
                int top = Math.max(y, centerY0), bottom = Math.min(y + ROW, centerY1);
                nextHits.add(new Hit(x0, top, x1, bottom, () -> player.play(listKey, tracks, index)));
                // Like: always shown once liked, otherwise on hover, left of add to queue.
                boolean liked = isLiked(t);
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
        if (tracks.isEmpty() && view == View.LIKED) {
            Component hint = Component.translatable("cuemymusic.liked.empty");
            g.textWithWordWrap(font, hint, x0 + 10, y + 4, x1 - x0 - 20, DIM, false);
            y += font.wordWrapHeight(hint, x1 - x0 - 20) + 4;
        }
        return y + 8;
    }

    private List<Track> liked() {
        return MusicLibrary.liked(cfg.liked);
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

    private int drawSettings(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my) {
        int pad = 12;
        int left = x0 + pad;
        int right = x1 - pad;
        bigText(g, tr("cuemymusic.settings"), left, y + 10, 2, WHITE);
        y += 36;

        g.text(font, tr("cuemymusic.settings.playback"), left, y, WHITE, false);
        y += 14;
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.enabled"), tr("cuemymusic.option.enabled.desc"), cfg.enabled,
                () -> cfg.enabled = !cfg.enabled);
        y = sliderRow(g, left, y, right, tr("cuemymusic.option.pitch"),
                tr("cuemymusic.option.pitch.value", signed(cfg.pitchSemitones)), (cfg.pitchSemitones + 12) / 24.0,
                f -> cfg.pitchSemitones = (int) Math.round(f * 24) - 12, 0.5);
        y = sliderRow(g, left, y, right, tr("cuemymusic.option.speed"),
                tr("cuemymusic.option.speed.value", String.format("%.2f", cfg.speed)), (cfg.speed - 0.5) / 1.5,
                f -> cfg.speed = Math.round((0.5 + f * 1.5) * 20) / 20.0, (1.0 - 0.5) / 1.5);
        y = toggleRow(g, left, y, right, tr("cuemymusic.option.travel"), tr("cuemymusic.option.travel.desc"), cfg.pauseOnTravel,
                () -> cfg.pauseOnTravel = !cfg.pauseOnTravel);
        y += 10;

        g.text(font, tr("cuemymusic.settings.equalizer"), left, y, WHITE, false);
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
            DoubleConsumer set, double defaultFraction) {
        g.text(font, label, left, y + 1, WHITE, false);
        g.text(font, value, right - font.width(value), y + 1, GREEN, false);
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
        marquee(g, t.artist(), left, y, w - 12, DIM, 1);
        boolean liked = isLiked(t);
        int hx = left + w - 7;
        boolean heartHover = mx >= hx - 2 && mx < hx + 9 && my >= y - 2 && my < y + 8;
        heartIcon(g, hx, y + 1, liked, liked ? GREEN : heartHover ? WHITE : DIM);
        nextHits.add(new Hit(hx - 2, y - 2, hx + 9, y + 8, () -> toggleLiked(t)));
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
            g.text(font, tr("cuemymusic.queue.next_up"), left, y, WHITE, false);
            nextRow(g, listNext, listNext.artist(), x0, y + 12, x1, mx, my, player::next);
            return;
        }

        if (queue.isEmpty()) {
            if (game == null || y + 12 + ROW > y1) {
                if (y + 20 < y1) {
                    g.text(font, tr("cuemymusic.queue"), left, y, WHITE, false);
                    g.textWithWordWrap(font, Component.translatable("cuemymusic.queue.empty"), left, y + 12, right - left, FAINT, false);
                }
                return;
            }
            g.text(font, tr("cuemymusic.queue.next_up"), left, y, WHITE, false);
            gameRow(g, game, gameWhen, x0, y + 12, x1, mx, my);
            int hy = y + 12 + ROW + 8;
            Component hint = Component.translatable("cuemymusic.queue.empty");
            if (hy + font.wordWrapHeight(hint, right - left) < y1) g.textWithWordWrap(font, hint, left, hy, right - left, FAINT, false);
            return;
        }

        int gameH = game != null ? 12 + ROW + 6 : 0;
        g.text(font, tr("cuemymusic.queue"), left, y, WHITE, false);
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
            g.text(font, tr("cuemymusic.queue.from_game"), left, end + 6, WHITE, false);
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
        int x = (centerX0 + centerX1 - w) / 2, y = centerY1 - 22;
        g.fill(x, y, x + w, y + 16, WHITE);
        g.text(font, toast, x + 8, y + 4, BLACK, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(KeyEvent event) {
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            double x = event.x(), y = event.y();
            if (queueClicked(x, y, doubleClick)) return true;
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
        if (queueFit > 0 && x >= queueX0 && x < queueX1 && y >= queueTop && y < queueTop + queueFit * QROW) {
            queueScroll -= vertical;
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
