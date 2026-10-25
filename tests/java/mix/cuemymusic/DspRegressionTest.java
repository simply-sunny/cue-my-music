package mix.cuemymusic;

import java.util.Random;

import mix.cuemymusic.dsp.PitchTempoProcessor;
import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.library.PackNames;

public final class DspRegressionTest {
    private static final float RATE = 48000;
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("identity passes samples through unchanged", () -> {
            float[] in = sine(2, 1, 440);
            float[] out = process(in, 1, 0, 1, 4096);
            require(out.length == in.length, "length " + out.length + " != " + in.length);
            for (int i = 0; i < in.length; i++) require(in[i] == out[i], "sample " + i + " changed");
        });
        run("x1.2 speed shortens without changing pitch", () -> check(1, 0, 1.2, 440));
        run("x0.8 speed lengthens without changing pitch", () -> check(1, 0, 0.8, 440));
        run("-2 semitones keeps length", () -> check(1, -2, 1, 440));
        run("+5 semitones keeps length", () -> check(1, 5, 1, 440));
        run("-2 semitones at x1.2 speed", () -> check(2, -2, 1.2, 440));
        run("+3 semitones at x0.75 speed", () -> check(2, 3, 0.75, 330));
        run("x1.0833 speed keeps pitch", () -> check(1, 0, 130.0 / 120, 440));
        run("stereo channels stay separate", () -> {
            float[] in = new float[(int) (RATE * 4) * 2];
            for (int f = 0; f < in.length / 2; f++) {
                in[2 * f] = (float) Math.sin(2 * Math.PI * 440 * f / RATE) * 0.5f;
                in[2 * f + 1] = (float) Math.sin(2 * Math.PI * 660 * f / RATE) * 0.5f;
            }
            float[] out = process(in, 2, -2, 1.2, 1000);
            double r = PitchTempoProcessor.semitonesToRatio(-2);
            near(frequency(out, 2, 0), 440 * r, 0.02);
            near(frequency(out, 2, 1), 660 * r, 0.02);
        });
        run("odd chunk sizes and live parameter changes stay finite", () -> {
            PitchTempoProcessor p = new PitchTempoProcessor(2, RATE);
            float[] in = sine(2, 6, 440);
            float[] buf = new float[8192];
            int frames = in.length / 2;
            int pos = 0;
            int chunk = 1;
            long produced = 0;
            while (pos < frames) {
                int n = Math.min(chunk, frames - pos);
                p.setParams(PitchTempoProcessor.semitonesToRatio((pos / 9000) % 5 - 2), 0.8 + (pos / 7000 % 4) * 0.15);
                p.put(in, pos * 2, n);
                pos += n;
                chunk = chunk * 7 % 3001 + 1;
                int got;
                while ((got = p.take(buf, 0, buf.length / 2)) > 0) {
                    for (int i = 0; i < got * 2; i++) require(Float.isFinite(buf[i]) && Math.abs(buf[i]) <= 1.01f, "bad sample");
                    produced += got;
                }
            }
            require(produced > frames / 2, "too little output: " + produced);
        });
        run("flat equalizer leaves samples alone", () -> {
            float[] in = sine(2, 1, 440);
            float[] out = in.clone();
            Equalizer eq = new Equalizer(2, RATE);
            eq.setGains(new double[6]);
            eq.process(out, 0, out.length / 2);
            for (int i = 0; i < in.length; i++) require(in[i] == out[i], "sample " + i + " changed");
        });
        // Boosts are offset by half the largest boost as headroom, so +12 dB nets +6 dB.
        run("+12 dB at 1 kHz nets +6 dB at 1 kHz", () -> near(eqGainDb(new double[] {0, 0, 0, 12, 0, 0}, 1000), 6, 0.08));
        run("+12 dB at 1 kHz barely touches 100 Hz", () -> near(eqGainDb(new double[] {0, 0, 0, 12, 0, 0}, 100), -6, 0.1));
        run("-12 dB at 1 kHz cuts 1 kHz by 12 dB", () -> near(eqGainDb(new double[] {0, 0, 0, -12, 0, 0}, 1000), -12, 0.05));
        // Shelves reach half their gain at the corner and the full gain well past it.
        run("+12 dB low shelf: +6 dB at 15 Hz, half at 60 Hz", () -> {
            nearDb(eqGainDb(new double[] {12, 0, 0, 0, 0, 0}, 15), 6, 1);
            nearDb(eqGainDb(new double[] {12, 0, 0, 0, 0, 0}, 60), 0, 1);
        });
        run("+12 dB high shelf: +6 dB at 22 kHz, half at 15 kHz", () -> {
            nearDb(eqGainDb(new double[] {0, 0, 0, 0, 0, 12}, 22000), 6, 1);
            nearDb(eqGainDb(new double[] {0, 0, 0, 0, 0, 12}, 15000), 0, 1);
        });
        run("every band at +12 stays finite", () -> {
            float[] noise = new float[(int) RATE * 2];
            Random rng = new Random(2);
            for (int i = 0; i < noise.length; i++) noise[i] = rng.nextFloat() * 2 - 1;
            Equalizer eq = new Equalizer(2, RATE);
            eq.setGains(new double[] {12, 12, 12, 12, 12, 12});
            eq.process(noise, 0, noise.length / 2);
            for (float v : noise) require(Float.isFinite(v) && Math.abs(v) < 20, "unstable: " + v);
        });

        run("pack names read like titles", () -> {
            same(PackNames.prettify("file/true-ending-dragon-music-v1.zip"), "True Ending Dragon Music v1");
            same(PackNames.prettify("golden-days-music-1.20.x"), "Golden Days Music 1.20.x");
            same(PackNames.prettify("[V1.2] Original Pokemon Battle Music.zip"), "[V1.2] Original Pokemon Battle Music");
            same(PackNames.prettify("§6C4Music"), "C4Music");
        });
        run("pack initials skip versions and small words", () -> {
            same(PackNames.initials("True Ending Dragon Music v1"), "TE");
            same(PackNames.initials("Golden Days Music 1.20.x"), "GDM");
            same(PackNames.initials("[V1.2] Original Pokemon Battle Music"), "OP");
            same(PackNames.initials("The End Music"), "EM");
            same(PackNames.initials("v2"), "?");
        });
        run("pack credits come from the description", () -> {
            same(PackNames.author("New Ender Dragon boss music\nBy: limesplatus"), "limesplatus");
            same(PackNames.author("§6Golden Days §8- §bMusic \n§8by PoeticRainbow"), "PoeticRainbow");
            same(PackNames.author("Original Pokemon Battle Music, In Cobblemon! By §a@realDunkin"), "realDunkin");
            same(PackNames.author("§cRemoves background music not made by C418 from playing in-game"), null);
        });

        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static void check(int channels, double semitones, double speed, double hz) {
        double seconds = 6;
        float[] in = sine(channels, seconds, hz);
        float[] out = process(in, channels, semitones, speed, 4096);
        double outSeconds = out.length / channels / RATE;
        // Flushing pads up to ~150 ms of silence at the end.
        near(outSeconds, seconds / speed, 0.04);
        near(frequency(out, channels, 0), hz * PitchTempoProcessor.semitonesToRatio(semitones), 0.015);
    }

    private static float[] process(float[] in, int channels, double semitones, double speed, int chunkFrames) {
        PitchTempoProcessor p = new PitchTempoProcessor(channels, RATE);
        p.setParams(PitchTempoProcessor.semitonesToRatio(semitones), speed);
        float[] out = new float[in.length * 3 + 48000 * channels];
        int written = 0;
        int frames = in.length / channels;
        for (int pos = 0; pos < frames; pos += chunkFrames) {
            p.put(in, pos * channels, Math.min(chunkFrames, frames - pos));
            written += p.take(out, written * channels, Integer.MAX_VALUE) ;
        }
        p.finish();
        written += p.take(out, written * channels, Integer.MAX_VALUE);
        return java.util.Arrays.copyOf(out, written * channels);
    }

    /** Positive-going zero crossings per second, measured over the middle half. */
    private static double frequency(float[] samples, int channels, int channel) {
        int frames = samples.length / channels;
        int from = frames / 4, to = frames * 3 / 4;
        int crossings = 0, first = -1, last = -1;
        for (int f = from + 1; f < to; f++) {
            if (samples[(f - 1) * channels + channel] < 0 && samples[f * channels + channel] >= 0) {
                if (first < 0) first = f;
                last = f;
                crossings++;
            }
        }
        return (crossings - 1) * RATE / (last - first);
    }

    /** Steady-state gain in dB of the equalizer at {@code hz}, measured on a mono sine. */
    private static double eqGainDb(double[] gains, double hz) {
        float[] x = sine(1, 2, hz);
        double before = rms(x, x.length / 2);
        Equalizer eq = new Equalizer(1, RATE);
        eq.setGains(gains);
        eq.process(x, 0, x.length);
        return 20 * Math.log10(rms(x, x.length / 2) / before);
    }

    private static double rms(float[] x, int from) {
        double s = 0;
        for (int i = from; i < x.length; i++) s += x[i] * x[i];
        return Math.sqrt(s / (x.length - from));
    }

    private static float[] sine(int channels, double seconds, double hz) {
        int frames = (int) (seconds * RATE);
        float[] out = new float[frames * channels];
        for (int f = 0; f < frames; f++) {
            float v = (float) Math.sin(2 * Math.PI * hz * f / RATE) * 0.5f;
            for (int c = 0; c < channels; c++) out[f * channels + c] = v;
        }
        return out;
    }

    private static void nearDb(double actual, double expected, double toleranceDb) {
        require(Math.abs(actual - expected) <= toleranceDb, String.format("expected %.2f dB, got %.2f dB", expected, actual));
    }

    private static void near(double actual, double expected, double relative) {
        require(Math.abs(actual - expected) <= Math.max(Math.abs(expected) * relative, 0.3),
                String.format("expected %.4f, got %.4f", expected, actual));
    }

    private static void same(Object actual, Object expected) {
        require(java.util.Objects.equals(actual, expected), "expected " + expected + ", got " + actual);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
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
