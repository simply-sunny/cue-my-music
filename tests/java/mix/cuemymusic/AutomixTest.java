package mix.cuemymusic;

import java.util.Objects;

public final class AutomixTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("finds where the sound starts and ends, past silence", () -> {
            Automix.Analyzer a = new Automix.Analyzer(10, 2);
            // 3 silent frames, 4 at 0.5, 2 silent.
            float[] s = {0, 0, 0, 0, 0, 0, .5f, -.5f, .5f, -.5f, .5f, -.5f, .5f, -.5f, 0, 0, 0, 0};
            a.accept(s, s.length);
            Automix.Profile p = a.finish();
            same(p.frames(), 9L);
            same(p.start(), 3L);
            same(p.end(), 7L);
            same(p.peak(), 0.5);
        });
        run("chunks add up the same as one block", () -> {
            Automix.Analyzer a = new Automix.Analyzer(10, 1);
            a.accept(new float[] {0, 0, .2f}, 3);
            a.accept(new float[] {.2f, 0}, 2);
            Automix.Profile p = a.finish();
            same(p.start(), 2L);
            same(p.end(), 4L);
            same(p.frames(), 5L);
        });
        run("all silence counts as no sound", () -> {
            Automix.Analyzer a = new Automix.Analyzer(10, 1);
            a.accept(new float[] {0, 0.001f, 0}, 3);
            Automix.Profile p = a.finish();
            same(p.rms(), 0.0);
            same(p.gain(), 1.0);
        });
        run("quiet songs are raised, up to 2x; loud ones lowered", () -> {
            same(profile(0.01, 0.1).gain(), 2.0);
            close(profile(0.316, 0.9).gain(), 0.5);
        });
        run("a raised song never clips", () -> {
            close(profile(0.1, 0.8).gain(), 0.98 / 0.8);
        });
        run("the fade is 8 seconds at most, and a quarter of the shorter song", () -> {
            Automix.Profile long1 = new Automix.Profile(100, 2, 100_000, 0, 100_000, .1, .5);
            Automix.Profile short1 = new Automix.Profile(100, 2, 1_000, 0, 1_000, .1, .5);
            same(Automix.fadeFrames(long1, long1), 800L);
            same(Automix.fadeFrames(long1, short1), 250L);
            same(Automix.fadeStart(long1, long1), 99_200L);
        });
        run("the fade starts before the trailing silence, not the end of the file", () -> {
            Automix.Profile p = new Automix.Profile(100, 2, 100_000, 500, 90_000, .1, .5);
            same(Automix.fadeStart(p, p), 89_200L);
        });
        run("equal power: the two gains' squares add to one", () -> {
            for (double t = 0; t <= 1; t += 0.125) {
                double a = Automix.fadeOut(t), b = Automix.fadeIn(t);
                close(a * a + b * b, 1);
            }
            close(Automix.fadeOut(0), 1);
            close(Automix.fadeIn(0), 0);
            close(Automix.fadeOut(2), 0);
        });
        run("only songs with the same rate and channels blend", () -> {
            Automix.Profile a = new Automix.Profile(48000, 2, 1, 0, 1, 0, 0);
            same(a.blendsWith(new Automix.Profile(48000, 2, 1, 0, 1, 0, 0)), true);
            same(a.blendsWith(new Automix.Profile(44100, 2, 1, 0, 1, 0, 0)), false);
            same(a.blendsWith(new Automix.Profile(48000, 1, 1, 0, 1, 0, 0)), false);
            same(a.blendsWith(null), false);
        });

        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static Automix.Profile profile(double rms, double peak) {
        return new Automix.Profile(48000, 2, 1000, 0, 1000, rms, peak);
    }

    private static void same(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void close(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-3) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void run(String name, Runnable test) {
        checks++;
        try {
            test.run();
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t.getMessage());
        }
    }
}
