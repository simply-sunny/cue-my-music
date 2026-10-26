package mix.cuemymusic;

/**
 * Blending one song into the next: where each song's sound starts and ends
 * (leaving out silence), how loud it is, and the crossfade between them.
 * Plain Java, so it can be checked without the game.
 */
public final class Automix {
    /** Quieter than this, a frame counts as silence: -50 dBFS. */
    static final float SILENCE = 0.00316f;
    /** Every song is brought to this RMS level, -16 dBFS, as far as {@link #MAX_BOOST} and its peak allow. */
    static final double TARGET_RMS = 0.158;
    static final double MAX_BOOST = 2.0;
    static final double MIN_GAIN = 0.25;
    /** The longest crossfade, in seconds. */
    static final double FADE_SECONDS = 8;

    private Automix() {
    }

    /**
     * One song: its sample rate and channels, length in frames, the frames its
     * sound starts and ends at, and its loudness (RMS) and peak, from 0 to 1.
     */
    public record Profile(float rate, int channels, long frames, long start, long end, double rms, double peak) {
        /** The gain that brings this song to the target loudness without clipping. */
        public double gain() {
            if (rms <= 0) return 1;
            double g = Math.clamp(TARGET_RMS / rms, MIN_GAIN, MAX_BOOST);
            return peak > 0 ? Math.min(g, 0.98 / peak) : g;
        }

        public boolean blendsWith(Profile next) {
            return next != null && rate == next.rate && channels == next.channels;
        }
    }

    /** Builds a {@link Profile} from a song's samples, chunk by chunk. */
    public static final class Analyzer {
        private final float rate;
        private final int channels;
        private long frames;
        private long start = -1;
        private long end;
        private double sumSquares;
        private long counted;
        private double peak;

        public Analyzer(float rate, int channels) {
            this.rate = rate;
            this.channels = channels;
        }

        /** Interleaved samples; {@code count} is a whole number of frames. */
        public void accept(float[] samples, int count) {
            for (int i = 0; i + channels <= count; i += channels) {
                float loudest = 0;
                for (int c = 0; c < channels; c++) {
                    float v = samples[i + c];
                    loudest = Math.max(loudest, Math.abs(v));
                    sumSquares += v * v;
                }
                counted += channels;
                if (loudest > SILENCE) {
                    if (start < 0) start = frames;
                    end = frames + 1;
                }
                peak = Math.max(peak, loudest);
                frames++;
            }
        }

        public Profile finish() {
            if (start < 0) return new Profile(rate, channels, frames, 0, frames, 0, 0);
            return new Profile(rate, channels, frames, start, end, Math.sqrt(sumSquares / Math.max(1, counted)), peak);
        }
    }

    /** Crossfade length in frames from {@code from} into {@code to}: up to 8 seconds, at most a quarter of either song. */
    public static long fadeFrames(Profile from, Profile to) {
        long a = from.end() - from.start(), b = to.end() - to.start();
        return Math.max(0, Math.min((long) (FADE_SECONDS * from.rate()), Math.min(a, b) / 4));
    }

    /** The frame of {@code from} the crossfade into {@code to} starts at. */
    public static long fadeStart(Profile from, Profile to) {
        return from.end() - fadeFrames(from, to);
    }

    /** Equal-power fade: the outgoing song's gain at {@code t} from 0 to 1. */
    public static double fadeOut(double t) {
        return Math.cos(Math.clamp(t, 0, 1) * Math.PI / 2);
    }

    /** Equal-power fade: the incoming song's gain at {@code t} from 0 to 1. */
    public static double fadeIn(double t) {
        return Math.sin(Math.clamp(t, 0, 1) * Math.PI / 2);
    }
}
