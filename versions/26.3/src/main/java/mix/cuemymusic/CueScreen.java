package mix.cuemymusic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.library.Catalog;
import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * The player: library sidebar on the left, the open playlist, artist or
 * settings page in the middle, now playing on the right, and play controls
 * along the bottom. Everything is drawn by hand; clicks and drags hit the
 * regions recorded while drawing the previous frame.
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
    private static final String PLAYLIST_TEXTURE = "block/jukebox_top";
    private static final String SETTINGS_TEXTURE = "block/crafting_table_top";

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

    private enum View { PLAYLIST, ARTIST, SETTINGS }

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
    private String artist;
    private double scroll;
    private int scrollMax;
    private int centerX0, centerY0, centerX1, centerY1;
    private double seekPreview = -1;

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
        if (rightW > 0) drawNowPlaying(g, width - GAP - rightW, top, width - GAP, bottom - GAP);
        drawBottomBar(g, 0, bottom, width, height, mouseX, mouseY);

        hits = nextHits;
        drags = nextDrags;
    }

    // ---------------------------------------------------------------- library

    private void drawLibrary(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        g.fill(x0, y0, x1, y1, PANEL);
        g.text(font, Component.translatable("cuemymusic.library").getString(), x0 + 6, y0 + 7, WHITE, false);

        List<Track> all = MusicLibrary.all();
        List<String> artists = new ArrayList<>(Catalog.ARTISTS);
        if (MusicLibrary.hasUnknown()) artists.add(Catalog.UNKNOWN_ARTIST);

        // Rows shrink to a single line when the window is short, so every artist still fits.
        int rows = artists.size() + 2;
        int h = Math.clamp((y1 - y0 - 24) / rows, 12, 22);
        int y = y0 + 20;
        y = libraryItem(g, x0, y, x1, h, PLAYLIST_TEXTURE, tr("cuemymusic.playlist.title"),
                tr("cuemymusic.playlist.subtitle", all.size()), view == View.PLAYLIST, mx, my, () -> open(View.PLAYLIST, null));
        for (String a : artists) {
            String tex = Catalog.ARTIST_ART.get(a).texture();
            y = libraryItem(g, x0, y, x1, h, tex, a, tr("cuemymusic.artist"),
                    view == View.ARTIST && a.equals(artist), mx, my, () -> open(View.ARTIST, a));
        }

        libraryItem(g, x0, y1 - h - 2, x1, h, SETTINGS_TEXTURE, tr("cuemymusic.settings"), tr("cuemymusic.settings.subtitle"),
                view == View.SETTINGS, mx, my, () -> open(View.SETTINGS, null));
    }

    private int libraryItem(GuiGraphicsExtractor g, int x0, int y, int x1, int h, String texture, String title,
            String subtitle, boolean selected, int mx, int my, Runnable click) {
        boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + h;
        if (selected || hover) g.fill(x0 + 2, y, x1 - 2, y + h, selected ? SELECTED : ROW_HOVER);
        int artSize = Math.min(16, h - 2);
        art(g, texture, x0 + 5, y + (h - artSize) / 2, artSize);
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

    private void open(View v, String a) {
        view = v;
        artist = a;
        scroll = 0;
    }

    // ----------------------------------------------------------------- center

    private void drawCenter(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = centerX0, y0 = centerY0, x1 = centerX1, y1 = centerY1;
        g.fill(x0, y0, x1, y1, PANEL);
        g.enableScissor(x0, y0, x1, y1);
        int contentTop = y0 - (int) scroll;
        int contentBottom = switch (view) {
            case SETTINGS -> drawSettings(g, x0, contentTop, x1, mx, my);
            case ARTIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, MusicLibrary.byArtist(artist),
                    Catalog.ARTIST_ART.getOrDefault(artist, Catalog.UNKNOWN_ALBUM).texture(), tr("cuemymusic.artist"), artist,
                    artistColor(artist));
            case PLAYLIST -> drawTrackPage(g, x0, contentTop, x1, mx, my, MusicLibrary.all(), PLAYLIST_TEXTURE,
                    tr("cuemymusic.playlist"), tr("cuemymusic.playlist.title"), 0xFF1E3A2A);
        };
        g.disableScissor();
        scrollMax = Math.max(0, contentBottom + (int) scroll - y1 + 8);
        scroll = Math.clamp(scroll, 0, scrollMax);
    }

    private int drawTrackPage(GuiGraphicsExtractor g, int x0, int y, int x1, int mx, int my, List<Track> tracks,
            String texture, String kind, String title, int color) {
        // Header: art, kind, big title, count and length.
        int headerH = 70;
        g.fillGradient(x0, y, x1, y + headerH, color, PANEL);
        art(g, texture, x0 + 10, y + 12, 48);
        int tx = x0 + 66;
        g.text(font, kind, tx, y + 14, WHITE, false);
        bigText(g, clip(title, (x1 - tx - 8) / 2), tx, y + 26, 2, WHITE);
        double total = tracks.stream().mapToDouble(t -> Double.isNaN(t.seconds()) ? 0 : t.seconds()).sum();
        g.text(font, tr("cuemymusic.page.summary", tracks.size(), longDuration(total)), tx, y + 48, DIM, false);
        y += headerH;

        // Big play button for the whole list.
        int pb = 18;
        boolean listActive = player.current() != null && tracks.contains(player.current()) && player.isPlaying();
        roundButton(g, x0 + 10, y + 6, pb, GREEN);
        if (listActive) pauseIcon(g, x0 + 10 + 6, y + 6 + 5, 8, BLACK);
        else playIcon(g, x0 + 10 + 7, y + 6 + 5, 8, BLACK);
        nextHits.add(new Hit(x0 + 10, y + 6, x0 + 10 + pb, y + 6 + pb, () -> {
            if (listActive || (player.current() != null && tracks.contains(player.current()))) player.togglePause();
            else if (!tracks.isEmpty()) player.play(tracks.getFirst(), 0, tracks);
        }));
        y += pb + 12;

        // Column headings.
        boolean showAlbum = x1 - x0 > 260;
        int colTitle = x0 + 26;
        int colAlbum = x0 + (x1 - x0) * 55 / 100;
        int colTime = x1 - 10;
        g.text(font, "#", x0 + 10, y, DIM, false);
        g.text(font, tr("cuemymusic.column.title"), colTitle, y, DIM, false);
        if (showAlbum) g.text(font, tr("cuemymusic.column.album"), colAlbum, y, DIM, false);
        clockIcon(g, colTime - 7, y, DIM);
        y += 11;
        g.fill(x0 + 8, y, x1 - 8, y + 1, 0xFF2A2A2A);
        y += 4;

        Track current = player.current();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            boolean visible = y + ROW > centerY0 && y < centerY1;
            if (visible) {
                boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + ROW && my >= centerY0 && my < centerY1;
                boolean isCurrent = t.equals(current);
                if (hover) g.fill(x0 + 4, y, x1 - 4, y + ROW, ROW_HOVER);
                if (isCurrent && player.isPlaying()) equalizerIcon(g, x0 + 9, y + 6, GREEN);
                else g.text(font, Integer.toString(i + 1), x0 + 10, y + 6, isCurrent ? GREEN : DIM, false);
                art(g, t.album().texture(), colTitle, y + 2, 16);
                int textX = colTitle + 20;
                int titleW = (showAlbum ? colAlbum - 6 : colTime - 30) - textX;
                g.text(font, clip(t.title(), titleW), textX, y + 2, isCurrent ? GREEN : WHITE, false);
                g.text(font, clip(t.artist(), titleW), textX, y + 11, DIM, false);
                if (showAlbum) g.text(font, clip(t.album().name(), colTime - 34 - colAlbum), colAlbum, y + 6, DIM, false);
                String len = Double.isNaN(t.seconds()) ? "-:--" : time(t.seconds());
                g.text(font, len, colTime - font.width(len), y + 6, DIM, false);
                int top = Math.max(y, centerY0), bottom = Math.min(y + ROW, centerY1);
                nextHits.add(new Hit(x0, top, x1, bottom, () -> player.play(t, 0, tracks)));
            }
            y += ROW;
        }
        return y + 8;
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
            String key = p.getKey();
            nextHits.add(new Hit(cx, y, cx + w, y + 13, () -> {
                cfg.eqGains = gains.clone();
                cfg.eqPreset = key;
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

    // ------------------------------------------------------------ now playing

    private void drawNowPlaying(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, PANEL);
        Track t = player.current();
        int pad = 8;
        int w = x1 - x0 - pad * 2;
        g.text(font, clip(t == null ? tr("cuemymusic.now_playing") : t.album().name(), w), x0 + pad, y0 + 7, WHITE, false);
        if (t == null) {
            g.textWithWordWrap(font, Component.translatable("cuemymusic.now_playing.none"), x0 + pad, y0 + 24, w, DIM, false);
            return;
        }
        int size = Math.min(w, y1 - y0 - 80);
        art(g, t.album().texture(), x0 + pad, y0 + 20, size);
        int y = y0 + 26 + size;
        bigText(g, clip(t.title(), (int) (w / 1.5)), x0 + pad, y, 1.5f, WHITE);
        y += 15;
        g.text(font, clip(t.artist(), w), x0 + pad, y, DIM, false);
        y += 14;

        // What the settings are doing to it.
        String fx = cfg.enabled
                ? tr("cuemymusic.now_playing.fx", signed(cfg.pitchSemitones), String.format("%.2f", cfg.speed),
                        tr(cfg.eqEnabled ? "cuemymusic.eq.on" : "cuemymusic.eq.off"))
                : tr("cuemymusic.now_playing.fx_off");
        if (y + 16 < y1) {
            g.fill(x0 + pad, y, x1 - pad, y + 15, 0xFF242424);
            g.text(font, clip(fx, w - 8), x0 + pad + 4, y + 4, DIM, false);
        }
    }

    // ------------------------------------------------------------- bottom bar

    private void drawBottomBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mx, int my) {
        Track t = player.current();
        // Current song on the left.
        int leftW = Math.min(170, (x1 - x0) / 4);
        if (t != null) {
            art(g, t.album().texture(), x0 + GAP + 2, y0 + 7, 28);
            int tx = x0 + GAP + 36;
            g.text(font, clip(t.title(), leftW - 36), tx, y0 + 12, WHITE, false);
            g.text(font, clip(t.artist(), leftW - 36), tx, y0 + 22, DIM, false);
        }

        // Controls in the middle.
        int cx = (x0 + x1) / 2;
        int cy = y0 + 5;
        int btn = 14;
        hoverable(g, cx - btn / 2 - 22, cy, 12, 12, mx, my, () -> player.previous());
        previousIcon(g, cx - btn / 2 - 20, cy + 2, 8, hoverColor(cx - btn / 2 - 22, cy, 12, 12, mx, my));
        roundButton(g, cx - btn / 2, cy - 1, btn, WHITE);
        if (player.isPlaying()) pauseIcon(g, cx - 3, cy + 2, 7, BLACK);
        else playIcon(g, cx - 2, cy + 2, 7, BLACK);
        nextHits.add(new Hit(cx - btn / 2, cy - 1, cx + btn / 2, cy - 1 + btn, player::togglePause));
        hoverable(g, cx + btn / 2 + 10, cy, 12, 12, mx, my, () -> player.next());
        nextIcon(g, cx + btn / 2 + 12, cy + 2, 8, hoverColor(cx + btn / 2 + 10, cy, 12, 12, mx, my));

        // Progress bar under the controls.
        int barW = Math.min(360, (x1 - x0) * 2 / 5);
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

        // Settings summary on the right.
        String fx = cfg.enabled ? tr("cuemymusic.bar.fx", signed(cfg.pitchSemitones), String.format("%.2f", cfg.speed))
                : tr("cuemymusic.bar.fx_off");
        int fw = font.width(fx);
        if (x1 - GAP - 4 - fw > bx1 + 40) {
            int fx0 = x1 - GAP - 4 - fw;
            g.text(font, fx, fx0, y0 + 17, cfg.enabled && (cfg.pitchSemitones != 0 || cfg.speed != 1) ? GREEN : DIM, false);
            nextHits.add(new Hit(fx0, y0 + 12, x1, y0 + 30, () -> open(View.SETTINGS, null)));
        }
    }

    private void hoverable(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, Runnable click) {
        nextHits.add(new Hit(x, y, x + w, y + h, click));
    }

    private static int hoverColor(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h ? WHITE : DIM;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            double x = event.x(), y = event.y();
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

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.onFraction().accept(dragging.fraction(event.x(), event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
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
        if (x >= centerX0 && x < centerX1 && y >= centerY0 && y < centerY1) {
            scroll = Math.clamp(scroll - vertical * 24, 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    // ---------------------------------------------------------------- drawing

    private void art(GuiGraphicsExtractor g, String texture, int x, int y, int size) {
        Identifier id = Identifier.withDefaultNamespace("textures/" + texture + ".png");
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, 16, 16, 16, 16);
    }

    private void bigText(GuiGraphicsExtractor g, String s, int x, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        g.pose().popMatrix();
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
