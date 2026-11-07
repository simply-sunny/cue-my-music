package mix.cuemymusic;

public final class PanelLayoutTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("automatic widths leave the middle at least its minimum", () -> {
            for (int w = 200; w <= 1200; w += 7) {
                PanelLayout.Widths r = PanelLayout.resolve(w, 0, 0);
                int gaps = PanelLayout.GAP * (r.nowPlaying() > 0 ? 4 : 3);
                int center = w - gaps - r.library() - r.nowPlaying();
                check(center >= Math.min(PanelLayout.CENTER_MIN, w - gaps - PanelLayout.ICON_W), "width " + w + " center " + center);
            }
        });
        run("a narrow window has no right panel", () -> check(PanelLayout.resolve(399, 0, 0).nowPlaying() == 0, "right"));
        run("a chosen width is kept when there is room", () -> {
            PanelLayout.Widths r = PanelLayout.resolve(1000, 200, 250);
            check(r.library() == 200 && r.nowPlaying() == 250, r.toString());
        });
        run("widths stay within their limits", () -> {
            check(PanelLayout.resolve(1000, 5000, 0).library() == PanelLayout.libraryMax(1000), "lib max");
            check(PanelLayout.resolve(1000, 0, 5000).nowPlaying() == PanelLayout.nowPlayingMax(1000), "now max");
            check(PanelLayout.resolve(1000, 70, 0).library() == PanelLayout.LIB_MIN, "lib min");
            check(PanelLayout.resolve(1000, 0, 100).nowPlaying() == PanelLayout.NOW_MIN, "now min");
        });
        run("dragging the library thin collapses it to pictures", () -> {
            check(PanelLayout.snapLibrary(40, 1000) == PanelLayout.ICON_W, "snap");
            check(PanelLayout.iconOnly(PanelLayout.ICON_W), "icons");
            check(!PanelLayout.iconOnly(PanelLayout.LIB_MIN + 20), "full");
        });
        run("pushing the right panel to the edge collapses it to a strip, which keeps its width", () -> {
            check(PanelLayout.clampNowPlaying(30, 1000) == PanelLayout.COLLAPSED, "snap");
            check(PanelLayout.resolve(1000, 0, PanelLayout.COLLAPSED).nowPlaying() == PanelLayout.NOW_STRIP, "strip");
            check(PanelLayout.resolve(420, 0, PanelLayout.COLLAPSED).nowPlaying() == PanelLayout.NOW_STRIP, "strip tight");
        });
        run("a tight window squeezes the right panel first, then the library", () -> {
            PanelLayout.Widths r = PanelLayout.resolve(420, 280, 300);
            check(r.nowPlaying() >= PanelLayout.NOW_MIN, "now floor");
            check(r.library() <= 280 && r.library() >= PanelLayout.ICON_W, "lib " + r.library());
        });
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void run(String name, Runnable test) {
        checks++;
        try {
            test.run();
            System.out.println("ok   " + name);
        } catch (AssertionError | RuntimeException e) {
            failures++;
            System.out.println("FAIL " + name + ": " + e.getMessage());
        }
    }
}
