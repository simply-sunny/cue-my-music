package mix.cuemymusic;

/**
 * How the player's three columns share the window: the library on the left,
 * now playing on the right, and the middle taking whatever is left. Widths are
 * in GUI units. A width of 0 means "automatic", which follows the window.
 */
public final class PanelLayout {
    public static final int GAP = 4;
    /** The library with only pictures, as Spotify's collapsed library. */
    public static final int ICON_W = 36;
    /** Below this the library shows pictures only; dragging under {@link #SNAP} collapses it. */
    public static final int COMPACT_BELOW = 92;
    private static final int SNAP = 64;
    public static final int LIB_MIN = 92;
    public static final int NOW_MIN = 110;
    /** The now playing panel pushed all the way out: a thin strip with an arrow. Stored as this width pref. */
    public static final int COLLAPSED = -1;
    public static final int NOW_STRIP = 16;
    private static final int NOW_SNAP = 80;
    /** The middle never gets narrower than this: the other panels give way first. */
    public static final int CENTER_MIN = 150;
    /** Windows narrower than this have no right panel. */
    public static final int RIGHT_FROM = 400;

    public record Widths(int library, int nowPlaying) {
    }

    private PanelLayout() {
    }

    public static int libraryMax(int screenW) {
        return Math.max(LIB_MIN, Math.min(280, screenW * 2 / 5));
    }

    public static int nowPlayingMax(int screenW) {
        return Math.max(NOW_MIN, Math.min(360, screenW * 2 / 5));
    }

    /** A dragged library width: under {@link #SNAP} it collapses to pictures, otherwise it stays within its limits. */
    public static int snapLibrary(int raw, int screenW) {
        if (raw < SNAP) return ICON_W;
        return Math.clamp(raw, LIB_MIN, libraryMax(screenW));
    }

    /** A dragged now playing width: pushed under {@link #NOW_SNAP} it collapses, as {@link #COLLAPSED}. */
    public static int clampNowPlaying(int raw, int screenW) {
        if (raw < NOW_SNAP) return COLLAPSED;
        return Math.clamp(raw, NOW_MIN, nowPlayingMax(screenW));
    }

    public static boolean iconOnly(int libraryWidth) {
        return libraryWidth < COMPACT_BELOW;
    }

    public static int autoLibrary(int screenW) {
        return Math.clamp(screenW / 5, 100, 170);
    }

    public static int autoNowPlaying(int screenW) {
        return Math.clamp(screenW / 5, 110, 170);
    }

    /** The widths to draw at: chosen ones kept within limits, and squeezed so the middle keeps {@link #CENTER_MIN}. */
    public static Widths resolve(int screenW, int libraryPref, int nowPref) {
        int lib = libraryPref <= 0 ? autoLibrary(screenW) : snapLibrary(libraryPref, screenW);
        int now = screenW < RIGHT_FROM ? 0 : nowPref == COLLAPSED ? NOW_STRIP : nowPref == 0 ? autoNowPlaying(screenW)
                : clampNowPlaying(nowPref, screenW) == COLLAPSED ? NOW_STRIP : clampNowPlaying(nowPref, screenW);
        int gaps = GAP * (now > 0 ? 4 : 3);
        int over = CENTER_MIN - (screenW - gaps - lib - now);
        if (over > 0 && now > 0) {
            int take = Math.min(over, Math.max(0, now - NOW_MIN));
            now -= take;
            over -= take;
        }
        if (over > 0) {
            // The library gives up what it can, then collapses.
            int take = Math.min(over, Math.max(0, lib - LIB_MIN));
            lib -= take;
            over -= take;
            if (over > 0 && lib > ICON_W) lib = ICON_W;
        }
        return new Widths(lib, now);
    }
}
