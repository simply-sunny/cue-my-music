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
        run("a profile reads back as it was written; another format reads as none", () -> {
            Automix.Profile p = new Automix.Profile(48000, 2, 1_000_000, 1200, 990_000, .2, .9, 127.6, "4A",
                    new float[] {.1f, .5f}, new float[] {.2f}, new float[0], 0.42);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try {
                p.write(new java.io.DataOutputStream(bytes));
                byte[] data = bytes.toByteArray();
                Automix.Profile q = Automix.Profile.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(data)));
                same(q.rate(), p.rate());
                same(q.frames(), p.frames());
                same(q.end(), p.end());
                same(q.bpm(), p.bpm());
                same(q.key(), p.key());
                same(java.util.Arrays.toString(q.low()), java.util.Arrays.toString(p.low()));
                same(q.high().length, 0);
                same(q.downbeat(), 0.42);
                data[3]++;
                same(Automix.Profile.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))), null);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
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
            close(profile(0.448, 0.9).gain(), 0.5);
        });
        run("a raised song never clips", () -> {
            close(profile(0.1, 0.8).gain(), 0.98 / 0.8);
        });
        run("auto length: a blend 32 beats (8 to 24 s), a fade 16 (4 to 12 s), 8 s with no tempo; never over a quarter of a song", () -> {
            Automix.Profile slow = song(100, 100_000, 60), fast = song(100, 100_000, 240), none = song(100, 100_000, 0);
            same(Automix.plan(none, none, Automix.Transition.AUTO).fadeFrames(), 800L);
            same(Automix.plan(slow, slow, Automix.Transition.AUTO).fadeFrames(), 2400L);
            same(Automix.plan(fast, fast, Automix.Transition.AUTO).fadeFrames(), 800L);
            same(Automix.plan(song(100, 100_000, 90), song(100, 100_000, 100), Automix.Transition.AUTO).fadeFrames(), 1067L);
            same(Automix.plan(none, song(100, 1_000, 0), Automix.Transition.AUTO).fadeFrames(), 250L);
        });
        run("a crossfade is an equal-power fade over the song's last seconds, never over a quarter of it", () -> {
            Automix.Plan p = Automix.crossfade(100, 0, 10_000, 0, 4);
            same(p.preset(), Automix.Preset.FADE);
            same(p.shape().volume(), Automix.Volume.SMOOTH);
            same(p.fadeFrames(), 400L);
            same(p.fadeStart(), 9_600L);
            same(p.enterAt(), 0L);
            // Past the silence at either end, and into the next song where its sound starts.
            Automix.Plan q = Automix.crossfade(100, 500, 9_000, 120, 4);
            same(q.fadeStart(), 8_600L);
            same(q.enterAt(), 120L);
            Automix.Plan shortSong = Automix.crossfade(100, 0, 1_000, 0, 4);
            same(shortSong.fadeFrames(), 250L);
            same(shortSong.fadeStart(), 750L);
        });
        run("auto start lines up both songs' bars", () -> {
            Automix.Profile a = new Automix.Profile(100, 2, 100_000, 0, 100_000, .1, .5, 120, "", new float[0], new float[0], new float[0], 0.3);
            Automix.Profile b = new Automix.Profile(100, 2, 100_000, 0, 100_000, .1, .5, 120, "", new float[0], new float[0], new float[0], 0.5);
            Automix.Plan p = Automix.plan(a, b, Automix.Transition.AUTO);
            same(p.fadeFrames(), 1600L);
            same(p.enterAt(), 0L);
            // B's first bar is 50 frames in; A's bar there: bars at 30 + 200k.
            same(Math.floorMod(p.fadeStart() + 50 - 30, 200L), 0L);
            if (Math.abs(p.fadeStart() - 98_400) > 100) throw new AssertionError("moved too far: " + p.fadeStart());
        });
        run("a long fade-out: the transition ends where the music fades, not in the quiet tail", () -> {
            float[] low = new float[80 * 25];
            for (int i = 0; i < low.length; i++) low[i] = i < 60 * 25 ? 1f : 1f - (i - 60 * 25) / (20f * 25);
            Automix.Profile a = new Automix.Profile(100, 2, 8_000, 0, 8_000, .1, .5, 0, "", low, new float[low.length], new float[low.length]);
            long end = Automix.musicalEnd(a);
            if (end < 7_100 || end > 7_350) throw new AssertionError("ends at " + end);
            Automix.Plan p = Automix.plan(a, a, Automix.Transition.AUTO);
            same(p.fadeStart() + p.fadeFrames(), end);
            same(Automix.musicalEnd(song(100, 8_000, 0)), 8_000L);
        });
        run("finds where bars start from the hits", () -> {
            float[] e = new float[60 * 100];
            java.util.Arrays.fill(e, 0.01f);
            for (int k = 0; 30 + k * 50 + 3 < e.length; k++) {
                float hit = k % 4 == 0 ? 0.6f : 0.3f;
                for (int j = 0; j < 3; j++) e[30 + k * 50 + j] = hit / (j + 1);
            }
            close(Automix.bpm(e), 120);
            double db = Automix.downbeat(e, 120);
            if (Math.abs(db - 0.3) > 0.03) throw new AssertionError("downbeat at " + db);
            same(Automix.downbeat(e, 0), -1.0);
        });
        run("keys that mix: same, relative, one step; tempo gaps count double time", () -> {
            same(Automix.keysMix("4A", "4A"), true);
            same(Automix.keysMix("4A", "4B"), true);
            same(Automix.keysMix("4A", "5A"), true);
            same(Automix.keysMix("12A", "1A"), true);
            same(Automix.keysMix("4A", "6A"), false);
            same(Automix.keysMix("4A", "5B"), false);
            same(Automix.keysMix("", "5B"), true);
            close(Automix.tempoGap(90, 180), 0);
            close(Automix.tempoGap(100, 110), 1 / 11.0);
        });
        run("auto transition ends at the end of the first song's sound and starts the second past its silence", () -> {
            Automix.Profile a = new Automix.Profile(100, 2, 100_000, 500, 90_000, .1, .5);
            Automix.Profile b = new Automix.Profile(100, 2, 100_000, 700, 99_000, .1, .5);
            Automix.Plan p = Automix.plan(a, b, Automix.Transition.AUTO);
            same(p.fadeStart(), 89_200L);
            same(p.enterAt(), 700L);
        });
        run("set values win: length, start before the end, and where the next song comes in", () -> {
            Automix.Profile a = song(100, 100_000, 0);
            Automix.Transition t = new Automix.Transition(Automix.Preset.ECHO, null, 4, 10, 30);
            Automix.Plan p = Automix.plan(a, a, t);
            same(p.preset(), Automix.Preset.ECHO);
            same(p.shape(), Automix.shape(Automix.Preset.ECHO));
            same(p.fadeFrames(), 400L);
            same(p.fadeStart(), 99_000L);
            same(p.enterAt(), 3_000L);
        });
        run("a cut lasts a second and switches in its middle", () -> {
            Automix.Profile a = song(48000, 10_000_000, 0);
            same(Automix.plan(a, a, Automix.Transition.AUTO.withPreset(Automix.Preset.CUT)).fadeFrames(), 48_000L);
            Automix.Shape cut = Automix.shape(Automix.Preset.CUT);
            close(Automix.volumeOut(cut, 0.45), 1);
            close(Automix.volumeIn(cut, 0.55), 1);
        });
        run("auto preset: blend for close tempos and keys, rise for a key clash, fade for a small tempo gap, echo for a big one", () -> {
            same(Automix.autoPreset(song(1, 1, 120), song(1, 1, 124)), Automix.Preset.BLEND);
            same(Automix.autoPreset(song(1, 1, 90), song(1, 1, 178)), Automix.Preset.BLEND);
            same(Automix.autoPreset(keyed(120, "4A"), keyed(122, "5A")), Automix.Preset.BLEND);
            same(Automix.autoPreset(keyed(120, "4A"), keyed(122, "9B")), Automix.Preset.RISE);
            same(Automix.autoPreset(song(1, 1, 100), song(1, 1, 110)), Automix.Preset.FADE);
            same(Automix.autoPreset(song(1, 1, 90), song(1, 1, 120)), Automix.Preset.ECHO);
            same(Automix.autoPreset(song(1, 1, 0), song(1, 1, 120)), Automix.Preset.FADE);
        });
        run("parts set by hand make a custom transition and are used as set", () -> {
            Automix.Profile a = song(100, 100_000, 0);
            Automix.Shape s = Automix.shape(Automix.Preset.FADE).withEq(Automix.Eq.LATE_BASS_SWAP);
            Automix.Transition t = Automix.Transition.AUTO.withShape(s);
            same(t.preset(), Automix.Preset.CUSTOM);
            same(Automix.plan(a, a, t).shape(), s);
            same(Automix.plan(a, a, t.withPreset(Automix.Preset.RISE)).shape(), Automix.shape(Automix.Preset.RISE));
        });
        run("every volume curve starts on the first song and ends on the second", () -> {
            for (Automix.Volume v : Automix.Volume.values()) {
                for (Automix.Effect e : Automix.Effect.values()) {
                    Automix.Shape s = new Automix.Shape(v, Automix.Eq.NONE, Automix.Filter.NONE, e);
                    close(Automix.volumeOut(s, 0), 1);
                    close(Automix.volumeOut(s, 1), 0);
                    close(Automix.volumeIn(s, 0), 0);
                    close(Automix.volumeIn(s, 1), 1);
                }
            }
        });
        run("a drop: the next song in at full at once, this one's bass gone, holding half way, then a straight fade; 16 bars", () -> {
            Automix.Shape d = Automix.shape(Automix.Preset.DROP);
            close(Automix.volumeIn(d, 0.01), 1);
            close(Automix.bassIn(d, 0.01), 1);
            close(Automix.bassOut(d, 0.01), 0);
            close(Automix.volumeOut(d, 0.4), 1);
            close(Automix.volumeOut(d, 0.75), 0.5);
            Automix.Profile a = song(100, 1_000_000, 128);
            same(Automix.plan(a, a, Automix.Transition.AUTO.withPreset(Automix.Preset.DROP)).fadeFrames(), 3000L);
        });
        run("two loud songs together are squeezed under full scale, quiet ones untouched", () -> {
            close(Automix.limit(0.5), 0.5);
            close(Automix.limit(-0.8), -0.8);
            same(Automix.limit(1.6) <= 1, true);
            same(Automix.limit(-3) >= -1, true);
            same(Automix.limit(1.2) > Automix.limit(1.0), true);
        });
        run("a bass swap hands the bass over at its point, and only there", () -> {
            Automix.Shape s = Automix.shape(Automix.Preset.FADE).withEq(Automix.Eq.EARLY_BASS_SWAP);
            close(Automix.bassOut(s, 0.2), 1);
            close(Automix.bassIn(s, 0.2), 0);
            close(Automix.bassOut(s, 0.3), 0);
            close(Automix.bassIn(s, 0.3), 1);
            close(Automix.bassIn(Automix.shape(Automix.Preset.FADE), 0), 1);
        });
        run("finds the tempo of a steady beat, shown between 70 and 140", () -> {
            float[] env = new float[Automix.BEAT_RATE * 40];
            for (int i = 0; i < env.length; i++) env[i] = i % 50 < 5 ? 0.8f : 0.1f;
            close(Automix.bpm(env), 120);
            // 160 BPM shows as 80.
            for (int i = 0; i < env.length; i++) env[i] = (i * 4) % 150 < 12 ? 0.8f : 0.1f;
            close(Automix.bpm(env), 80);
            same(Automix.bpm(new float[Automix.BEAT_RATE * 40]), 0.0);
        });
        run("Camelot keys: C major 8B, A minor 8A, G major 9B, E minor 9A", () -> {
            same(Automix.camelot(0, true), "8B");
            same(Automix.camelot(9, false), "8A");
            same(Automix.camelot(7, true), "9B");
            same(Automix.camelot(4, false), "9A");
            same(Automix.camelot(3, true), "5B");
            same(Automix.camelot(0, false), "5A");
        });
        run("finds the key of a chord progression", () -> {
            // C, F, G, C major chords: C major.
            same(chords(new int[][] {{0, 4, 7}, {5, 9, 0}, {7, 11, 2}, {0, 4, 7}}), "8B");
            // Am, Dm, E, Am: A minor.
            same(chords(new int[][] {{9, 0, 4}, {2, 5, 9}, {4, 8, 11}, {9, 0, 4}}), "8A");
            same(Automix.key(new double[12]), "");
        });
        run("the waveform splits bass, mids and highs", () -> {
            same(bands(60)[0] > 0.3, true);
            same(bands(1000)[1] > 0.3, true);
            same(bands(8000)[2] > 0.3, true);
            same(bands(60)[2] < 0.05, true);
            same(bands(8000)[0] < 0.05, true);
        });
        run("reads exact frame counts from blocks of any size", () -> {
            java.util.Random r = new java.util.Random(1);
            int[] next = {0};
            Automix.FrameReader reader = new Automix.FrameReader(() -> {
                if (next[0] >= 10_000) return null;
                int n = Math.min(10_000 - next[0], 1 + r.nextInt(3000));
                float[] block = new float[n * 2];
                for (int i = 0; i < n; i++) block[i * 2] = block[i * 2 + 1] = next[0] + i;
                next[0] += n;
                return block;
            }, 2);
            try {
                same(reader.skip(123), 123L);
                int frame = 123;
                float[] chunk;
                while ((chunk = reader.read(2048)) != null) {
                    if (chunk.length != 4096 && frame + chunk.length / 2 != 10_000) throw new AssertionError("short read at " + frame);
                    for (int i = 0; i < chunk.length / 2; i++) same(chunk[i * 2], (float) (frame + i));
                    frame += chunk.length / 2;
                }
                same(frame, 10_000);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        run("no mix of parts jumps while mixing blocks of any size", () -> {
            for (Automix.Volume v : Automix.Volume.values()) {
                for (Automix.Eq e : Automix.Eq.values()) {
                    for (Automix.Filter f : Automix.Filter.values()) {
                        for (Automix.Effect x : Automix.Effect.values()) {
                            Automix.Shape s = new Automix.Shape(v, e, f, x);
                            double jump = mixJump(s);
                            if (jump > 0.12) throw new AssertionError(s + ": a jump of " + jump);
                        }
                    }
                }
            }
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

    private static Automix.Profile keyed(double bpm, String key) {
        return new Automix.Profile(1, 2, 1, 0, 1, .1, .5, bpm, key, new float[0], new float[0], new float[0]);
    }

    private static Automix.Profile song(float rate, long frames, double bpm) {
        return new Automix.Profile(rate, 2, frames, 0, frames, .1, .5, bpm, "", new float[0], new float[0], new float[0]);
    }

    /** The key found in two seconds of each chord, as sine tones over three octaves. */
    private static String chords(int[][] progression) {
        Automix.Analyzer a = new Automix.Analyzer(48000, 1);
        long at = 0;
        for (int round = 0; round < 2; round++) {
            for (int[] chord : progression) {
                float[] block = new float[96_000];
                for (int i = 0; i < block.length; i++, at++) {
                    double v = 0;
                    for (int pc : chord) {
                        for (int octave = 3; octave <= 5; octave++) {
                            double hz = 440 * Math.pow(2, (pc - 9) / 12.0 + octave - 4);
                            v += Math.sin(2 * Math.PI * hz * at / 48000) * (pc == chord[0] ? 0.08 : 0.05);
                        }
                    }
                    block[i] = (float) v;
                }
                a.accept(block, block.length);
            }
        }
        return a.finish().key();
    }

    /** The mean level of a sine in each waveform band: bass, mids, highs. */
    private static double[] bands(double hz) {
        Automix.Analyzer a = new Automix.Analyzer(48000, 1);
        float[] block = new float[48_000];
        for (int i = 0; i < block.length; i++) block[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / 48000));
        a.accept(block, block.length);
        Automix.Profile p = a.finish();
        double[] out = new double[3];
        float[][] b = {p.low(), p.mid(), p.high()};
        for (int k = 0; k < 3; k++) {
            // Past the first tenth of a second, while the filters settle.
            for (int i = 3; i < b[k].length; i++) out[k] += b[k][i];
            out[k] /= b[k].length - 3;
        }
        return out;
    }

    /** A stereo sine, in blocks of random sizes, through a FrameReader as the stream reads it. */
    private static Automix.FrameReader sine(double hz, int seed) {
        java.util.Random r = new java.util.Random(seed);
        long[] at = {0};
        return new Automix.FrameReader(() -> {
            int n = 1 + r.nextInt(3000);
            float[] block = new float[n * 2];
            for (int i = 0; i < n; i++) block[i * 2] = block[i * 2 + 1] = (float) (0.5 * Math.sin(2 * Math.PI * hz * (at[0] + i) / 48000));
            at[0] += n;
            return block;
        }, 2);
    }

    /** The biggest step between neighbouring output samples across a 2-second transition. */
    private static double mixJump(Automix.Shape shape) {
        Automix.Plan plan = new Automix.Plan(Automix.Preset.CUSTOM, shape, 0, 96_000, 0);
        Automix.Blend blend = new Automix.Blend(plan, 2, 48000, 1, 1, 120);
        Automix.FrameReader a = sine(220, 2), b = sine(330, 3);
        double prev = 0, most = 0;
        try {
            while (!blend.done()) {
                float[] out = new float[2048 * 2];
                blend.process(a.read(2048), b.read(2048), out, 2048);
                for (int i = 0; i < 2048; i++) {
                    most = Math.max(most, Math.abs(out[i * 2] - prev));
                    prev = out[i * 2];
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return most;
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
