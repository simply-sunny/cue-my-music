package mix.cuemymusic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Blending one song into the next: where each song's sound starts and ends
 * (leaving out silence), how loud it is, its tempo and key, and the
 * transition between two songs. A transition is a preset, or its parts set
 * one by one: the volume curves, a bass swap, a filter sweep, and an echo.
 * Plain Java, so it can be checked without the game.
 */
public final class Automix {
    /** Quieter than this, a frame counts as silence: -50 dBFS. */
    static final float SILENCE = 0.00316f;
    /** Every song is brought to this RMS level, -13 dBFS, as far as {@link #MAX_BOOST} and its peak allow. */
    static final double TARGET_RMS = 0.224;
    static final double MAX_BOOST = 2.0;
    static final double MIN_GAIN = 0.25;
    /** The automatic length: 16 beats, from 4 to 12 seconds, or 8 seconds when the tempo isn't known. */
    static final double AUTO_SECONDS = 8;
    static final double AUTO_BEATS = 16;
    static final double DROP_BEATS = 64;
    /** A cut's automatic length; it switches in the middle. */
    static final double CUT_SECONDS = 1;
    /** Waveform points per second, in each of {@link Profile}'s band envelopes. */
    public static final int WAVE_RATE = 25;
    /** Loudness points per second for finding the tempo. */
    static final int BEAT_RATE = 100;
    /**
     * Beatmatching speeds the next song up or slows it down by at most this
     * much (8%), so its beats land on the first song's. Past that, stretching
     * is heard, so the songs keep their own speed.
     */
    public static final double MAX_STRETCH = 0.08;
    /** Seconds a beatmatched song takes to glide back to its own speed once the transition is over. */
    public static final double GLIDE_SECONDS = 8;
    /** Two beat grids out by less than this many beats over a transition still sound together. */
    static final double DRIFT_BEATS = 0.25;

    private Automix() {
    }

    /** Named transitions, as in the editor's preset row. CUSTOM is one whose parts were set by hand. */
    public enum Preset { AUTO, FADE, RISE, BLEND, DROP, ECHO, CUT, CUSTOM }

    /** How the two songs' volumes cross. */
    public enum Volume {
        /** Equal-power crossfade over the whole transition. */
        SMOOTH,
        /** Straight lines. */
        LINEAR,
        /** The next song comes up over the first half, this one goes down over the second. */
        OVERLAP,
        /** A switch in the middle. */
        CUT,
        /** The next song drops in at full volume at once; this one holds for the first half, then fades out in a straight line. */
        DROP
    }

    /** Where the bass moves from one song to the other, if it does. */
    public enum Eq { NONE, START_BASS_SWAP, EARLY_BASS_SWAP, CENTER_BASS_SWAP, LATE_BASS_SWAP }

    /**
     * Filter sweeps. RISE is a high-pass filter in: the next song rising in
     * from its highs. FALL is a low-pass filter out: this one's highs closing
     * down. SWEEP is both. LOW_PASS_IN brings the next song in muffled and
     * opens it up, and LOW_PASS_IN_OUT does that while this one closes down.
     * The first names are kept as they are, since saved transitions use them.
     */
    public enum Filter {
        NONE, RISE, FALL, SWEEP, LOW_PASS_IN, LOW_PASS_IN_OUT;

        boolean highPassIn() {
            return this == RISE || this == SWEEP;
        }

        boolean lowPassIn() {
            return this == LOW_PASS_IN || this == LOW_PASS_IN_OUT;
        }

        boolean lowPassOut() {
            return this == FALL || this == SWEEP || this == LOW_PASS_IN_OUT;
        }
    }

    public enum Effect { NONE, ECHO_OUT }

    /** LOOP_OUT repeats the first song's bar at the start of the transition until it's gone, in place of its outro. */
    public enum Loop { NONE, LOOP_OUT }

    /** The parts of a transition. A part left out (as in a transition saved before it existed) is none. */
    public record Shape(Volume volume, Eq eq, Filter filter, Effect effect, Loop loop) {
        public Shape {
            if (volume == null) volume = Volume.SMOOTH;
            if (eq == null) eq = Eq.NONE;
            if (filter == null) filter = Filter.NONE;
            if (effect == null) effect = Effect.NONE;
            if (loop == null) loop = Loop.NONE;
        }

        public Shape(Volume volume, Eq eq, Filter filter, Effect effect) {
            this(volume, eq, filter, effect, Loop.NONE);
        }

        public Shape withVolume(Volume v) {
            return new Shape(v, eq, filter, effect, loop);
        }

        public Shape withEq(Eq v) {
            return new Shape(volume, v, filter, effect, loop);
        }

        public Shape withFilter(Filter v) {
            return new Shape(volume, eq, v, effect, loop);
        }

        public Shape withEffect(Effect v) {
            return new Shape(volume, eq, filter, v, loop);
        }

        public Shape withLoop(Loop v) {
            return new Shape(volume, eq, filter, effect, v);
        }
    }

    /** What each preset is made of. */
    public static Shape shape(Preset p) {
        return switch (p) {
            case RISE -> new Shape(Volume.SMOOTH, Eq.NONE, Filter.SWEEP, Effect.NONE);
            case BLEND -> new Shape(Volume.OVERLAP, Eq.CENTER_BASS_SWAP, Filter.NONE, Effect.NONE);
            case DROP -> new Shape(Volume.DROP, Eq.START_BASS_SWAP, Filter.NONE, Effect.NONE);
            case ECHO -> new Shape(Volume.SMOOTH, Eq.NONE, Filter.NONE, Effect.ECHO_OUT);
            case CUT -> new Shape(Volume.CUT, Eq.NONE, Filter.NONE, Effect.NONE);
            default -> new Shape(Volume.SMOOTH, Eq.NONE, Filter.NONE, Effect.NONE);
        };
    }

    /**
     * One transition as the player set it: a preset, or CUSTOM with its
     * {@code shape}. A negative number means automatic: {@code seconds} long,
     * starting {@code startBeforeEnd} seconds before the end of the first
     * song's sound, into the second song {@code enterAt} seconds from its start.
     * Beatmatching is on unless {@code beatmatchOff}, so a transition saved
     * before it existed has it on.
     */
    public record Transition(Preset preset, Shape shape, double seconds, double startBeforeEnd, double enterAt, boolean beatmatchOff) {
        public static final Transition AUTO = new Transition(Preset.AUTO, null, -1, -1, -1);

        public Transition(Preset preset, Shape shape, double seconds, double startBeforeEnd, double enterAt) {
            this(preset, shape, seconds, startBeforeEnd, enterAt, false);
        }

        public Transition withPreset(Preset p) {
            return new Transition(p, null, seconds, startBeforeEnd, enterAt, beatmatchOff);
        }

        /** Sets the parts by hand, which makes it a custom transition. */
        public Transition withShape(Shape s) {
            return new Transition(Preset.CUSTOM, s, seconds, startBeforeEnd, enterAt, beatmatchOff);
        }

        public Transition withSeconds(double v) {
            return new Transition(preset, shape, v, startBeforeEnd, enterAt, beatmatchOff);
        }

        public Transition withStart(double v) {
            return new Transition(preset, shape, seconds, v, enterAt, beatmatchOff);
        }

        public Transition withEnter(double v) {
            return new Transition(preset, shape, seconds, startBeforeEnd, v, beatmatchOff);
        }

        public Transition withBeatmatch(boolean on) {
            return new Transition(preset, shape, seconds, startBeforeEnd, enterAt, !on);
        }

        public boolean beatmatch() {
            return !beatmatchOff;
        }
    }

    /**
     * One song: its sample rate and channels, length in frames, the frames its
     * sound starts and ends at, its loudness (RMS) and peak, its tempo (0 if it
     * couldn't be told), its key in Camelot notation ("8B"; empty if unknown),
     * and its waveform at {@link #WAVE_RATE} points a second, split into bass,
     * mids and highs.
     */
    public record Profile(float rate, int channels, long frames, long start, long end, double rms, double peak, double bpm,
            String key, float[] low, float[] mid, float[] high, double downbeat) {
        public Profile(float rate, int channels, long frames, long start, long end, double rms, double peak) {
            this(rate, channels, frames, start, end, rms, peak, 0, "", new float[0], new float[0], new float[0]);
        }

        public Profile(float rate, int channels, long frames, long start, long end, double rms, double peak, double bpm,
                String key, float[] low, float[] mid, float[] high) {
            this(rate, channels, frames, start, end, rms, peak, bpm, key, low, mid, high, -1);
        }

        /** Seconds a beat lasts, or 0 with no tempo. */
        public double beat() {
            return bpm > 0 ? 60 / bpm : 0;
        }

        /** True if the song's bars are known: its tempo, and where one bar starts ({@link #downbeat}, in seconds). */
        public boolean hasBars() {
            return bpm > 0 && downbeat >= 0;
        }

        /** The first frame a bar starts at, at or after {@code frame}; {@code frame} itself if the bars aren't known. */
        public long barAtOrAfter(long frame) {
            if (!hasBars()) return frame;
            double bar = 4 * beat(), t = seconds(frame);
            return frame(downbeat + Math.ceil((t - downbeat) / bar - 1e-9) * bar);
        }

        /** The frame of the bar start nearest {@code frame}; {@code frame} itself if the bars aren't known. */
        public long barNearest(long frame) {
            if (!hasBars()) return frame;
            double bar = 4 * beat(), t = seconds(frame);
            return frame(downbeat + Math.round((t - downbeat) / bar) * bar);
        }

        /** The gain that brings this song to the target loudness without clipping. */
        public double gain() {
            if (rms <= 0) return 1;
            double g = Math.clamp(TARGET_RMS / rms, MIN_GAIN, MAX_BOOST);
            return peak > 1 ? g : peak > 0 ? Math.min(g, Math.max(1, 0.98 / peak)) : g;
        }

        public boolean blendsWith(Profile next) {
            return next != null && rate == next.rate && channels == next.channels;
        }

        public double seconds(long frame) {
            return frame / (double) rate;
        }

        public long frame(double seconds) {
            return Math.round(seconds * rate);
        }

        /**
         * Bump whenever analysis changes what a profile holds, so profiles saved
         * by an older version are worked out again instead of read.
         */
        public static final int FORMAT = 2;

        /** Writes this profile, for {@link #read} to read back. */
        public void write(DataOutputStream out) throws IOException {
            out.writeInt(FORMAT);
            out.writeFloat(rate);
            out.writeInt(channels);
            out.writeLong(frames);
            out.writeLong(start);
            out.writeLong(end);
            out.writeDouble(rms);
            out.writeDouble(peak);
            out.writeDouble(bpm);
            out.writeUTF(key);
            for (float[] band : new float[][] {low, mid, high}) {
                out.writeInt(band.length);
                for (float v : band) out.writeFloat(v);
            }
            out.writeDouble(downbeat);
        }

        /** A profile {@link #write} wrote, or null if an older version of the analysis wrote it. */
        public static Profile read(DataInputStream in) throws IOException {
            if (in.readInt() != FORMAT) return null;
            float rate = in.readFloat();
            int channels = in.readInt();
            long frames = in.readLong(), start = in.readLong(), end = in.readLong();
            double rms = in.readDouble(), peak = in.readDouble(), bpm = in.readDouble();
            String key = in.readUTF();
            float[][] bands = new float[3][];
            for (int b = 0; b < 3; b++) {
                int n = in.readInt();
                if (n < 0 || n > 1 << 20) throw new IOException("bad waveform length " + n);
                bands[b] = new float[n];
                for (int i = 0; i < n; i++) bands[b][i] = in.readFloat();
            }
            return new Profile(rate, channels, frames, start, end, rms, peak, bpm, key, bands[0], bands[1], bands[2], in.readDouble());
        }
    }

    /**
     * A transition worked out for two songs: the preset it is, its parts, in
     * frames where it starts in each and its length, and the speed the next
     * song plays at through it so its beats meet the first song's (1 for its own).
     */
    public record Plan(Preset preset, Shape shape, long fadeStart, long fadeFrames, long enterAt, double stretch) {
        public Plan(Preset preset, Shape shape, long fadeStart, long fadeFrames, long enterAt) {
            this(preset, shape, fadeStart, fadeFrames, enterAt, 1);
        }
    }

    /**
     * The automatic preset for two songs, as a DJ would pick: Blend when their
     * beats stay together through a long overlap (beatmatched, or close enough
     * as they are) and the keys go together, Rise when the beats meet but the
     * keys clash (the filter hides the clash), Fade for a small tempo gap, and
     * Echo for a big one, so the first song trails off before the new tempo
     * comes in. Fade when a tempo isn't known, as in ambient music.
     */
    public static Preset autoPreset(Profile a, Profile b) {
        return autoPreset(a, b, true);
    }

    public static Preset autoPreset(Profile a, Profile b, boolean beatmatch) {
        if (a.bpm() <= 0 || b.bpm() <= 0) return Preset.FADE;
        double gap = tempoGap(a.bpm(), b.bpm());
        double left = beatmatch && stretch(a, b) != 1 ? 0 : gap;
        // Over a blend's 32 beats, beats this far apart drift by this many beats.
        if (left * 32 <= DRIFT_BEATS) return keysMix(a.key(), b.key()) ? Preset.BLEND : Preset.RISE;
        return gap <= 0.15 ? Preset.FADE : Preset.ECHO;
    }

    /**
     * The speed song {@code b} plays at so its beats meet {@code a}'s, counting
     * double and half time as the same: 1 if a tempo isn't known, they already
     * match, or it would take more than {@link #MAX_STRETCH}.
     */
    public static double stretch(Profile a, Profile b) {
        if (a.bpm() <= 0 || b.bpm() <= 0) return 1;
        double best = 1, bestGap = Double.MAX_VALUE;
        for (double m : new double[] {0.5, 1, 2}) {
            double r = a.bpm() / (b.bpm() * m);
            if (Math.abs(r - 1) < bestGap) {
                bestGap = Math.abs(r - 1);
                best = r;
            }
        }
        return bestGap < 0.002 || bestGap > MAX_STRETCH ? 1 : best;
    }

    /** How far apart two tempos are, as a fraction, counting double and half time as the same. */
    static double tempoGap(double a, double b) {
        double r = a / b, best = Double.MAX_VALUE;
        for (double m : new double[] {0.5, 1, 2}) best = Math.min(best, Math.abs(r / m - 1));
        return best;
    }

    /**
     * True if two Camelot keys mix well: the same key, its relative major or
     * minor (same number), or one step round the wheel (same letter). True if
     * either isn't known.
     */
    static boolean keysMix(String a, String b) {
        if (a == null || b == null || a.length() < 2 || b.length() < 2) return true;
        try {
            int na = Integer.parseInt(a.substring(0, a.length() - 1)), nb = Integer.parseInt(b.substring(0, b.length() - 1));
            boolean sameLetter = a.charAt(a.length() - 1) == b.charAt(b.length() - 1);
            int step = Math.floorMod(na - nb, 12);
            return na == nb || (sameLetter && (step == 1 || step == 11));
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * The automatic length for a transition, in beats of the first song: a
     * Blend 8 bars (8 to 24 seconds), an Echo 2 bars (3 to 8), a Drop 16 bars
     * (15 to 40), a Cut a second, and anything else 4 bars (4 to 12). With no
     * tempo, a fixed length.
     */
    public static double autoSeconds(Profile a, Shape shape, Preset preset) {
        if (shape.volume() == Volume.CUT) return CUT_SECONDS;
        if (shape.volume() == Volume.DROP) return dropSeconds(a);
        double beat = a.beat();
        if (preset == Preset.BLEND) return beat > 0 ? wholeBars(a, Math.clamp(32 * beat, 8, 24)) : 12;
        if (preset == Preset.ECHO) return beat > 0 ? wholeBars(a, Math.clamp(8 * beat, 3, 8)) : 5;
        // At least 6 seconds: shorter, a fade between two songs that don't share a beat sounds rushed.
        return beat > 0 ? wholeBars(a, Math.clamp(AUTO_BEATS * beat, 6, 12)) : AUTO_SECONDS;
    }

    /** {@code seconds} as the nearest whole number of bars, at least one, so lengths read and sound as bars. */
    static double wholeBars(Profile a, double seconds) {
        double bar = 4 * a.beat();
        return Math.max(1, Math.round(seconds / bar)) * bar;
    }

    /** A drop's automatic length: 16 bars (64 beats) of the first song, 15 to 40 seconds, or 30 with no tempo. */
    public static double dropSeconds(Profile a) {
        return a.bpm() > 0 ? wholeBars(a, Math.clamp(DROP_BEATS * 60 / a.bpm(), 15, 40)) : 30;
    }

    /**
     * Where a song's music really ends, before a long fade-out or quiet tail:
     * the end of the last second still at least 40% as loud as the song
     * usually is. At most 30 seconds before the end of its sound; the end of
     * its sound if there's no waveform.
     */
    static long musicalEnd(Profile p) {
        return MUSICAL_ENDS.computeIfAbsent(p, Automix::findMusicalEnd);
    }

    /**
     * {@link #musicalEnd} and {@link #musicalStart} of each profile, worked out
     * once: each sorts the whole song's loudness, and the mix view plans every
     * transition it shows on every frame.
     */
    private static final java.util.Map<Profile, Long> MUSICAL_ENDS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()),
            MUSICAL_STARTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static long findMusicalEnd(Profile p) {
        int n = Math.min(p.low().length, Math.min(p.mid().length, p.high().length));
        int s = (int) Math.floor(p.seconds(p.start()) * WAVE_RATE), t = Math.min(n, (int) Math.ceil(p.seconds(p.end()) * WAVE_RATE));
        if (t - s < WAVE_RATE * 20) return p.end();
        float[] e = new float[t - s];
        for (int i = s; i < t; i++) e[i - s] = p.low()[i] + p.mid()[i] + p.high()[i];
        float[] sorted = e.clone();
        Arrays.sort(sorted);
        double usual = sorted[(int) (sorted.length * 0.7)];
        if (usual <= 0) return p.end();
        double run = 0;
        int last = -1;
        for (int i = 0; i < e.length; i++) {
            run += e[i] - (i >= WAVE_RATE ? e[i - WAVE_RATE] : 0);
            if (i >= WAVE_RATE - 1 && run / WAVE_RATE >= 0.4 * usual) last = i;
        }
        if (last < 0) return p.end();
        long at = p.frame((s + last + 1) / (double) WAVE_RATE);
        return Math.clamp(at, Math.max(p.start(), p.end() - p.frame(30)), p.end());
    }

    /**
     * Where a song's music really starts, past a near-silent intro: the start
     * of the first second at least a fifth as loud as the song usually is, on
     * a bar if they're known. Only past an intro of 3 to 20 seconds; otherwise,
     * or with no waveform, the start of its sound. A quiet intro under the
     * first song's fade sounds like a gap.
     */
    static long musicalStart(Profile p) {
        return MUSICAL_STARTS.computeIfAbsent(p, Automix::findMusicalStart);
    }

    private static long findMusicalStart(Profile p) {
        int n = Math.min(p.low().length, Math.min(p.mid().length, p.high().length));
        int s = (int) Math.floor(p.seconds(p.start()) * WAVE_RATE), t = Math.min(n, (int) Math.ceil(p.seconds(p.end()) * WAVE_RATE));
        if (t - s < WAVE_RATE * 40) return p.start();
        float[] e = new float[t - s];
        for (int i = s; i < t; i++) e[i - s] = p.low()[i] + p.mid()[i] + p.high()[i];
        float[] sorted = e.clone();
        Arrays.sort(sorted);
        double usual = sorted[(int) (sorted.length * 0.7)];
        if (usual <= 0) return p.start();
        double run = 0;
        for (int i = 0; i < Math.min(e.length, WAVE_RATE * 21); i++) {
            run += e[i] - (i >= WAVE_RATE ? e[i - WAVE_RATE] : 0);
            if (i >= WAVE_RATE - 1 && run / WAVE_RATE >= 0.2 * usual) {
                int first = i - WAVE_RATE + 1;
                if (first < WAVE_RATE * 3 || first > WAVE_RATE * 20) return p.start();
                long at = p.frame((s + first) / (double) WAVE_RATE);
                // Onto the bar it's in, so it comes in on the beat.
                long bar = p.barNearest(at);
                return Math.clamp(bar > at ? bar - p.frame(4 * p.beat()) : bar, p.start(), p.end());
            }
        }
        return p.start();
    }

    /**
     * Works out {@code t} for songs {@code a} into {@code b}. Never longer
     * than a quarter of the shorter song's sound. Left automatic, the
     * transition ends where the first song's music ends (see
     * {@link #musicalEnd}), the second song comes in where its music starts
     * (see {@link #musicalStart}), and the start moves by under a bar so both
     * songs' bars line up. With beatmatching on, the second song plays at the
     * first one's tempo through the transition.
     */
    public static Plan plan(Profile a, Profile b, Transition t) {
        Preset preset = t.preset() == Preset.AUTO ? autoPreset(a, b, t.beatmatch()) : t.preset();
        Shape shape = preset == Preset.CUSTOM && t.shape() != null ? t.shape() : shape(preset);
        double stretch = t.beatmatch() && shape.volume() != Volume.CUT ? stretch(a, b) : 1;
        double seconds = t.seconds() >= 0 ? t.seconds() : autoSeconds(a, shape, preset);
        long most = Math.min(a.end() - a.start(), b.end() - b.start()) / 4;
        long fadeFrames = Math.clamp(a.frame(seconds), 1, Math.max(1, most));
        long enterAt = t.enterAt() >= 0 ? Math.clamp(b.frame(t.enterAt()), 0, Math.max(0, b.frames() - 1)) : musicalStart(b);
        long fadeStart;
        if (t.startBeforeEnd() >= 0) {
            fadeStart = a.end() - a.frame(t.startBeforeEnd());
        } else {
            fadeStart = musicalEnd(a) - fadeFrames;
            if (a.hasBars() && shape.volume() != Volume.CUT) {
                // The second song's first bar starts this long after it comes in (sooner if it's sped up);
                // a bar of the first song should start there too.
                long lead = b.hasBars() ? a.frame(b.seconds(b.barAtOrAfter(enterAt) - enterAt) / stretch) : 0;
                fadeStart = a.barNearest(fadeStart + lead) - lead;
            }
        }
        fadeStart = Math.clamp(fadeStart, a.start(), Math.max(a.start(), a.frames() - 1));
        return new Plan(preset, shape, fadeStart, fadeFrames, enterAt, stretch);
    }

    /** Frames of song {@code a} a loop out repeats: a bar, or 2 seconds with no tempo; never over half the transition. */
    public static long loopFrames(Profile a, long fadeFrames) {
        double bar = a.bpm() > 0 ? 4 * a.beat() : 2;
        return Math.max(1, Math.min(a.frame(bar), fadeFrames / 2));
    }

    /**
     * A plain crossfade, for songs that aren't automixed: an equal-power fade
     * over the last {@code seconds} of a song whose sound runs from frame
     * {@code start} to {@code end}, into the next song at {@code enterAt}.
     * Never longer than a quarter of the song.
     */
    public static Plan crossfade(float rate, long start, long end, long enterAt, double seconds) {
        long fadeFrames = Math.clamp(Math.round(seconds * rate), 1, Math.max(1, (end - start) / 4));
        return new Plan(Preset.FADE, shape(Preset.FADE), Math.max(start, end - fadeFrames), fadeFrames, enterAt);
    }

    // Curves, from t = 0 at the start of the transition to 1 at its end.

    public static double fadeOut(double t) {
        return Math.cos(Math.clamp(t, 0, 1) * Math.PI / 2);
    }

    public static double fadeIn(double t) {
        return Math.sin(Math.clamp(t, 0, 1) * Math.PI / 2);
    }

    /** {@code t} rescaled so {@code from} is 0 and {@code to} is 1, clamped. */
    static double span(double t, double from, double to) {
        return Math.clamp((t - from) / (to - from), 0, 1);
    }

    /** The outgoing song's volume. */
    public static double volumeOut(Shape s, double t) {
        double v = switch (s.volume()) {
            case LINEAR -> 1 - Math.clamp(t, 0, 1);
            case OVERLAP -> fadeOut(span(t, 0.5, 1));
            case CUT -> fadeOut(span(t, 0.49, 0.51));
            case DROP -> 1 - span(t, 0.5, 1);
            default -> fadeOut(t);
        };
        // An echo-out cuts the song itself early; the echo carries on.
        return s.effect() == Effect.ECHO_OUT ? v * (1 - span(t, 0.15, 0.25)) : v;
    }

    /** The incoming song's volume. */
    public static double volumeIn(Shape s, double t) {
        return switch (s.volume()) {
            case LINEAR -> Math.clamp(t, 0, 1);
            case OVERLAP -> fadeIn(span(t, 0, 0.5));
            case CUT -> fadeIn(span(t, 0.49, 0.51));
            // In over the first few hundredths of a second, so it doesn't click.
            case DROP -> span(t, 0, 0.002);
            default -> fadeIn(t);
        };
    }

    private static double swapPoint(Eq eq) {
        return switch (eq) {
            case EARLY_BASS_SWAP -> 0.25;
            case LATE_BASS_SWAP -> 0.75;
            default -> 0.5;
        };
    }

    /** How much of the outgoing song's bass is left, before its volume. */
    public static double bassOut(Shape s, double t) {
        if (s.eq() == Eq.NONE) return 1;
        if (s.eq() == Eq.START_BASS_SWAP) return 1 - span(t, 0, 0.002);
        double p = swapPoint(s.eq());
        return 1 - span(t, p - 0.025, p + 0.025);
    }

    /** How much of the incoming song's bass is in, before its volume. */
    public static double bassIn(Shape s, double t) {
        if (s.eq() == Eq.NONE || s.eq() == Eq.START_BASS_SWAP) return 1;
        double p = swapPoint(s.eq());
        return span(t, p - 0.025, p + 0.025);
    }

    /** How open the outgoing song's low-pass filter is, from 1 (not filtered) to 0 (closed down to 400 Hz). */
    public static double filterOut(Shape s, double t) {
        return s.filter().lowPassOut() ? 1 - span(t, 0.3, 1) : 1;
    }

    /** How open the incoming song's filter is, from 0 (only its highs, or only its lows) to 1 (not filtered). */
    public static double filterIn(Shape s, double t) {
        return s.filter().highPassIn() || s.filter().lowPassIn() ? span(t, 0, 0.8) : 1;
    }

    /**
     * How much both songs are turned down while they overlap at full volume,
     * so an overlap or drop doesn't come out louder than either song: half way
     * to equal power, about 1.5 dB at two songs at full volume.
     */
    static double overlapTrim(double va, double vb) {
        double sum = va * va + vb * vb;
        return sum <= 1 ? 1 : Math.pow(sum, -0.25);
    }

    // Analysis.

    /** Builds a {@link Profile} from a song's samples, chunk by chunk. */
    public static final class Analyzer {
        private static final int FFT = 16384;
        private final float rate;
        private final int channels;
        private final int waveHop;
        private final int beatHop;
        private long frames;
        private long start = -1;
        private long end;
        private double sumSquares;
        private long counted;
        private double peak;
        // Waveform: the loudest point of each band per hop.
        private final Svf lowSplit = new Svf();
        private final Svf highSplit = new Svf();
        private float[] low = new float[256], mid = new float[256], high = new float[256];
        private int wavePoints;
        private float hopLow, hopMid, hopHigh;
        private int waveFrames;
        // Tempo: loudness per hop.
        private float[] energy = new float[1024];
        private int beatPoints;
        private double beatSum;
        private int beatFrames;
        // Key: the strength of each pitch class, from FFTs of the mono signal.
        private final double[] chroma = new double[12];
        private final double[] fftIn = new double[FFT];
        private int fftFill;

        public Analyzer(float rate, int channels) {
            this.rate = rate;
            this.channels = channels;
            this.waveHop = Math.max(1, Math.round(rate / WAVE_RATE));
            this.beatHop = Math.max(1, Math.round(rate / BEAT_RATE));
            lowSplit.set(250, rate);
            highSplit.set(2500, rate);
        }

        /** Interleaved samples; {@code count} is a whole number of frames. */
        public void accept(float[] samples, int count) {
            for (int i = 0; i + channels <= count; i += channels) {
                float loudest = 0, sum = 0;
                for (int c = 0; c < channels; c++) {
                    float v = samples[i + c];
                    loudest = Math.max(loudest, Math.abs(v));
                    sumSquares += v * v;
                    sum += v;
                }
                counted += channels;
                if (loudest > SILENCE) {
                    if (start < 0) start = frames;
                    end = frames + 1;
                }
                peak = Math.max(peak, loudest);
                frames++;
                float mono = sum / channels;

                float lo = lowSplit.low(mono), hi = highSplit.high(mono);
                hopLow = Math.max(hopLow, Math.abs(lo));
                hopHigh = Math.max(hopHigh, Math.abs(hi));
                hopMid = Math.max(hopMid, Math.abs(mono - lo - hi));
                if (++waveFrames == waveHop) {
                    if (wavePoints == low.length) {
                        low = Arrays.copyOf(low, wavePoints * 2);
                        mid = Arrays.copyOf(mid, wavePoints * 2);
                        high = Arrays.copyOf(high, wavePoints * 2);
                    }
                    low[wavePoints] = hopLow;
                    mid[wavePoints] = hopMid;
                    high[wavePoints++] = hopHigh;
                    hopLow = hopMid = hopHigh = 0;
                    waveFrames = 0;
                }

                beatSum += mono * mono;
                if (++beatFrames == beatHop) {
                    if (beatPoints == energy.length) energy = Arrays.copyOf(energy, beatPoints * 2);
                    energy[beatPoints++] = (float) Math.sqrt(beatSum / beatHop);
                    beatSum = 0;
                    beatFrames = 0;
                }

                fftIn[fftFill++] = mono;
                if (fftFill == FFT) {
                    addChroma(fftIn, rate, chroma);
                    fftFill = 0;
                }
            }
        }

        public Profile finish() {
            float[] l = Arrays.copyOf(low, wavePoints), m = Arrays.copyOf(mid, wavePoints), h = Arrays.copyOf(high, wavePoints);
            if (start < 0) return new Profile(rate, channels, frames, 0, frames, 0, 0, 0, "", l, m, h);
            float[] e = Arrays.copyOf(energy, beatPoints);
            double tempo = bpm(e);
            return new Profile(rate, channels, frames, start, end, Math.sqrt(sumSquares / Math.max(1, counted)), peak,
                    tempo, key(chroma), l, m, h, downbeat(e, tempo));
        }
    }

    /**
     * The tempo from loudness at {@link #BEAT_RATE} points a second: the beat
     * length whose rises in loudness line up best, counting the beats at twice
     * and half that length too, between 50 and 200 BPM, then halved or doubled
     * into 70 to 140 as DJ software shows it. 0 if no beat stands out.
     */
    static double bpm(float[] energy) {
        int n = energy.length;
        if (n < BEAT_RATE * 10) return 0;
        double[] flux = flux(energy);
        int maxLag = BEAT_RATE * 5;
        double[] ac = new double[maxLag + 2];
        for (int lag = 1; lag <= maxLag + 1; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += flux[i] * flux[i - lag];
            ac[lag] = s / (n - lag);
        }
        double best = 0, total = 0;
        double bestBpm = 0;
        int candidates = 0;
        for (double bpm = 50; bpm <= 200; bpm += 0.5) {
            double lag = BEAT_RATE * 60 / bpm;
            double s = at(ac, lag) + 0.5 * at(ac, lag * 2) + 0.5 * at(ac, lag / 2) + 0.25 * at(ac, lag * 4);
            total += s;
            candidates++;
            if (s > best) {
                best = s;
                bestBpm = bpm;
            }
        }
        if (best <= 0 || best < 1.2 * total / candidates) return 0;
        // Fine-tune around the best, a tenth of a BPM at a time.
        double fine = bestBpm, fineScore = 0;
        for (double bpm = bestBpm - 0.5; bpm <= bestBpm + 0.5; bpm += 0.1) {
            double lag = BEAT_RATE * 60 / bpm;
            double s = at(ac, lag) + 0.5 * at(ac, lag * 2) + 0.5 * at(ac, lag / 2);
            if (s > fineScore) {
                fineScore = s;
                fine = bpm;
            }
        }
        while (fine >= 140) fine /= 2;
        while (fine < 70) fine *= 2;
        return Math.round(fine * 10) / 10.0;
    }

    /** Rises in loudness, on a log scale, less their local average: where beats and other hits are. */
    private static double[] flux(float[] energy) {
        int n = energy.length;
        double[] onset = new double[n];
        for (int i = 1; i < n; i++) onset[i] = Math.max(0, Math.log1p(100 * energy[i]) - Math.log1p(100 * energy[i - 1]));
        double[] flux = new double[n];
        int w = BEAT_RATE / 4;
        double run = 0;
        for (int i = 0; i < n; i++) {
            run += onset[i] - (i >= 2 * w ? onset[i - 2 * w] : 0);
            int c = i - w;
            if (c >= 0) flux[c] = Math.max(0, onset[c] - run / Math.min(i + 1, 2 * w));
        }
        return flux;
    }

    /**
     * Where a bar starts, in seconds from the song's start, for a song at
     * {@code bpm}: first the beats, at the offset where the hits line up best
     * on a grid of that tempo; then which of each four beats starts the bar,
     * the one with the strongest hits. -1 with no tempo.
     */
    static double downbeat(float[] energy, double bpm) {
        if (bpm <= 0 || energy.length < BEAT_RATE * 10) return -1;
        double[] flux = flux(energy);
        double lag = BEAT_RATE * 60 / bpm;
        double bestPhase = 0, bestScore = -1;
        for (double phase = 0; phase < lag; phase += 0.5) {
            double sum = 0;
            for (double i = phase; i < flux.length - 1; i += lag) sum += at(flux, i);
            if (sum > bestScore) {
                bestScore = sum;
                bestPhase = phase;
            }
        }
        int bestBeat = 0;
        double strongest = -1;
        for (int b = 0; b < 4; b++) {
            double sum = 0;
            for (double i = bestPhase + b * lag; i < flux.length - 1; i += 4 * lag) sum += at(flux, i);
            if (sum > strongest) {
                strongest = sum;
                bestBeat = b;
            }
        }
        return (bestPhase + bestBeat * lag) / BEAT_RATE;
    }

    private static double at(double[] a, double i) {
        int k = (int) i;
        if (k < 1 || k + 1 >= a.length) return 0;
        double f = i - k;
        return a[k] * (1 - f) + a[k + 1] * f;
    }

    // How strongly each note of the scale belongs to a major or minor key. Of the sets tried,
    // this one agreed best with the keys Spotify shows for the same songs.
    private static final double[] MAJOR = {0.748, 0.060, 0.488, 0.082, 0.670, 0.460, 0.096, 0.715, 0.104, 0.366, 0.057, 0.400};
    private static final double[] MINOR = {0.712, 0.084, 0.474, 0.618, 0.049, 0.460, 0.105, 0.747, 0.404, 0.067, 0.133, 0.330};

    /** Adds one block's pitch-class strengths (energy near each note from 65 Hz to 2 kHz) to {@code chroma}. */
    static void addChroma(double[] block, float rate, double[] chroma) {
        int n = block.length;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = block[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        fft(re, im);
        double[] frame = new double[12];
        for (int k = (int) (65.0 * n / rate); k <= (int) (2000.0 * n / rate); k++) {
            double semitone = 12 * Math.log(k * rate / n / 440) / Math.log(2);
            long nearest = Math.round(semitone);
            // Only bins near a note count, so the spread between notes doesn't blur the key.
            if (Math.abs(semitone - nearest) > 0.3) continue;
            frame[Math.floorMod((int) nearest + 9, 12)] += re[k] * re[k] + im[k] * im[k];
        }
        double sum = 0;
        for (double v : frame) sum += v;
        if (sum <= 0) return;
        // Each block counts the same, so loud passages don't drown out the rest.
        for (int i = 0; i < 12; i++) chroma[i] += frame[i] / sum;
    }

    /** The key that best fits the pitch-class strengths, in Camelot notation. Empty if there's nothing. */
    static String key(double[] chroma) {
        double sum = 0;
        for (double v : chroma) sum += v;
        if (sum <= 0) return "";
        double best = -2;
        int bestPc = 0;
        boolean bestMajor = true;
        for (int pc = 0; pc < 12; pc++) {
            for (boolean major : new boolean[] {true, false}) {
                double r = correlation(chroma, major ? MAJOR : MINOR, pc);
                if (r > best) {
                    best = r;
                    bestPc = pc;
                    bestMajor = major;
                }
            }
        }
        return camelot(bestPc, bestMajor);
    }

    /** Pitch class 0 is C. C major is 8B, A minor 8A; each fifth up is one more. */
    static String camelot(int pc, boolean major) {
        int root = major ? pc : (pc + 3) % 12;
        return ((7 * root + 7) % 12 + 1) + (major ? "B" : "A");
    }

    private static double correlation(double[] x, double[] profile, int shift) {
        double mx = 0, my = 0;
        for (int i = 0; i < 12; i++) {
            mx += x[(i + shift) % 12];
            my += profile[i];
        }
        mx /= 12;
        my /= 12;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < 12; i++) {
            double a = x[(i + shift) % 12] - mx, b = profile[i] - my;
            sxy += a * b;
            sxx += a * a;
            syy += b * b;
        }
        return sxy / Math.sqrt(sxx * syy + 1e-12);
    }

    /** In-place radix-2 FFT; the length is a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    /** Gives exactly as many frames as asked for from a decoder that hands out blocks of any size. */
    public static final class FrameReader {
        /** The next block of interleaved samples, or null at the end. */
        public interface Blocks {
            float[] next() throws IOException;
        }

        private final Blocks blocks;
        private final int channels;
        private float[] buffered = new float[0];
        private int offset;
        private boolean done;

        public FrameReader(Blocks blocks, int channels) {
            this.blocks = blocks;
            this.channels = channels;
        }

        /** {@code frames} frames, fewer only at the end, or null once nothing is left. */
        public float[] read(int frames) throws IOException {
            float[] out = new float[frames * channels];
            int filled = 0;
            while (filled < out.length) {
                if (offset >= buffered.length) {
                    float[] block = done ? null : blocks.next();
                    if (block == null) {
                        done = true;
                        break;
                    }
                    buffered = block;
                    offset = 0;
                    continue;
                }
                int n = Math.min(out.length - filled, buffered.length - offset);
                System.arraycopy(buffered, offset, out, filled, n);
                offset += n;
                filled += n;
            }
            if (filled == 0) return null;
            return filled == out.length ? out : Arrays.copyOf(out, filled);
        }

        /** Drops {@code frames} frames. Returns how many there were. */
        public long skip(long frames) throws IOException {
            long skipped = 0;
            while (skipped < frames) {
                float[] chunk = read((int) Math.min(frames - skipped, 1 << 15));
                if (chunk == null) break;
                skipped += chunk.length / channels;
            }
            return skipped;
        }
    }

    /**
     * Mixes one transition, block by block: the outgoing song {@code a} and the
     * incoming {@code b}, each at its loudness gain, through the bass split,
     * filters, echo and volume curves of the plan's shape.
     */
    public static final class Blend {
        private final Plan plan;
        private final Shape shape;
        private final int channels;
        private final float rate;
        private final double gainA;
        private final double gainB;
        private long frame;
        private final Svf[] bassA, bassB, sweepA, sweepB;
        private final float[][] delay;
        private int delayPos;
        // A loop out: the bar it repeats, and a little past it to cross back into its start from.
        private final float[] loop;
        private final int loopFrames;
        private final int seam;

        public Blend(Plan plan, int channels, float rate, double gainA, double gainB, double bpmA) {
            this(plan, channels, rate, gainA, gainB, bpmA, 0);
        }

        /** {@code loopFrames} is how much of {@code a} a loop out repeats (see {@link #loopFrames}); 0 picks 2 seconds. */
        public Blend(Plan plan, int channels, float rate, double gainA, double gainB, double bpmA, long loopFrames) {
            this.plan = plan;
            this.shape = plan.shape();
            this.channels = channels;
            this.rate = rate;
            this.gainA = gainA;
            this.gainB = gainB;
            bassA = filters(channels, 200, rate);
            bassB = filters(channels, 200, rate);
            sweepA = filters(channels, 20000, rate);
            sweepB = filters(channels, shape.filter().lowPassIn() ? 300 : 1500, rate);
            // One beat of echo, or 3/8 of a second with no tempo.
            double echo = bpmA > 0 ? 60 / bpmA : 0.375;
            delay = shape.effect() == Effect.ECHO_OUT ? new float[channels][(int) Math.max(rate / 10, echo * rate)] : null;
            if (shape.loop() == Loop.LOOP_OUT) {
                long n = loopFrames > 0 ? loopFrames : Math.round(2 * rate);
                this.loopFrames = (int) Math.max(2, Math.min(n, Math.max(2, plan.fadeFrames() / 2)));
                seam = Math.max(1, Math.min(this.loopFrames / 4, Math.round(rate * 0.02f)));
                loop = new float[(this.loopFrames + seam) * channels];
            } else {
                this.loopFrames = 0;
                seam = 0;
                loop = null;
            }
        }

        private static Svf[] filters(int channels, double cutoff, float rate) {
            Svf[] f = new Svf[channels];
            for (int c = 0; c < channels; c++) {
                f[c] = new Svf();
                f[c].set(cutoff, rate);
            }
            return f;
        }

        /** How far through, from 0 to 1. */
        public double progress() {
            return Math.min(1, frame / (double) plan.fadeFrames());
        }

        public boolean done() {
            return frame >= plan.fadeFrames();
        }

        /**
         * Mixes {@code frames} frames of {@code a} and {@code b} into {@code out}.
         * Either may be shorter than that (a song that ended); the rest is silence.
         */
        public void process(float[] a, float[] b, float[] out, int frames) {
            boolean split = shape.eq() != Eq.NONE;
            boolean fall = shape.filter().lowPassOut();
            boolean rise = shape.filter().highPassIn(), open = shape.filter().lowPassIn();
            for (int f = 0; f < frames; f++) {
                long at = frame + f;
                double t = at / (double) plan.fadeFrames();
                if (f % 32 == 0) {
                    for (int c = 0; c < channels; c++) {
                        if (fall) sweepA[c].set(20000 * Math.pow(400 / 20000.0, span(t, 0.3, 1)), rate);
                        if (rise) sweepB[c].set(1500 * Math.pow(20 / 1500.0, span(t, 0, 0.8)), rate);
                        if (open) sweepB[c].set(300 * Math.pow(20000 / 300.0, span(t, 0, 0.8)), rate);
                    }
                }
                double outV = volumeOut(shape, t), inV = volumeIn(shape, t), trim = overlapTrim(outV, inV);
                double va = outV * gainA * trim, vb = inV * gainB * trim;
                double la = bassOut(shape, t), lb = bassIn(shape, t);
                for (int c = 0; c < channels; c++) {
                    int i = f * channels + c;
                    float xa = a != null && i < a.length ? a[i] : 0, xb = b != null && i < b.length ? b[i] : 0;
                    if (loop != null) xa = looped(at, c, xa);
                    float ya = xa, yb = xb;
                    if (split) {
                        float lowA = bassA[c].low(xa), lowB = bassB[c].low(xb);
                        ya = (float) (lowA * la + (xa - lowA));
                        yb = (float) (lowB * lb + (xb - lowB));
                    }
                    if (fall) ya = sweepA[c].low(ya);
                    if (rise) yb = sweepB[c].high(yb);
                    if (open) yb = sweepB[c].low(yb);
                    double mixed = ya * va + yb * vb;
                    if (delay != null) {
                        float[] line = delay[c];
                        float wet = line[delayPos];
                        // Fed until the song cuts out, then left to ring down, gone by the end.
                        line[delayPos] = (float) (ya * gainA * (1 - span(t, 0.15, 0.25)) + wet * 0.55);
                        mixed += wet * 0.6 * fadeOut(span(t, 0.7, 1));
                    }
                    out[i] = limit(mixed);
                }
                if (delay != null) delayPos = (delayPos + 1) % delay[0].length;
            }
            frame += frames;
        }

        /**
         * The first song's sample in a loop out: as it plays through the bar
         * (kept as it goes, and a little past it), then that bar over and over.
         * Each time round, the start fades in over what followed the bar's end,
         * so the seam doesn't click.
         */
        private float looped(long at, int c, float live) {
            if (at < loopFrames + seam) loop[(int) at * channels + c] = live;
            if (at < loopFrames) return live;
            int q = (int) ((at - loopFrames) % loopFrames);
            if (q >= seam) return loop[q * channels + c];
            double w = (q + 0.5) / seam;
            return (float) (loop[q * channels + c] * Math.sin(w * Math.PI / 2) + loop[(loopFrames + q) * channels + c] * Math.cos(w * Math.PI / 2));
        }
    }

    /** Above this, a transition's mix is squeezed so two songs at full volume together don't clip. */
    static final double LIMIT_KNEE = 0.8;

    /** Leaves quieter samples alone and bends louder ones smoothly towards, never past, full scale. */
    static float limit(double x) {
        double m = Math.abs(x);
        if (m <= LIMIT_KNEE) return (float) x;
        double room = 1 - LIMIT_KNEE;
        return (float) Math.copySign(LIMIT_KNEE + room * Math.tanh((m - LIMIT_KNEE) / room), x);
    }

    /** A state-variable filter (Simper's form) with low-pass and high-pass outputs that stays stable while it's swept. */
    static final class Svf {
        private static final double K = Math.sqrt(2);
        private double ic1, ic2, a1, a2, a3, lp, bp;

        Svf() {
            set(1000, 48000);
        }

        void set(double cutoff, double rate) {
            double g = Math.tan(Math.PI * Math.min(cutoff, rate * 0.45) / rate);
            a1 = 1 / (1 + g * (g + K));
            a2 = g * a1;
            a3 = g * a2;
        }

        float low(float x) {
            step(x);
            return (float) lp;
        }

        float high(float x) {
            step(x);
            return (float) (x - K * bp - lp);
        }

        private void step(double x) {
            double v3 = x - ic2;
            double v1 = a1 * ic1 + a2 * v3;
            double v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1;
            ic2 = 2 * v2 - ic2;
            lp = v2;
            bp = v1;
        }
    }
}
