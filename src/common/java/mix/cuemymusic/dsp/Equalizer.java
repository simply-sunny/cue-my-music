package mix.cuemymusic.dsp;

/**
 * Six-band equalizer on interleaved float samples: a low shelf, four peaking
 * bands and a high shelf (RBJ cookbook biquads). Gains can change between
 * calls; coefficients are rebuilt only when they do.
 */
public final class Equalizer {
    public static final double[] FREQUENCIES = {60, 150, 400, 1000, 2400, 15000};
    public static final double MAX_GAIN_DB = 12;
    private static final double PEAK_Q = 1.0;
    /** Butterworth Q, so the shelves rise without overshooting. */
    private static final double SHELF_Q = Math.sqrt(0.5);

    private final int channels;
    private final double sampleRate;
    private final double[] gains = new double[FREQUENCIES.length];
    // Per band: b0, b1, b2, a1, a2 (normalized by a0).
    private final double[][] coeffs = new double[FREQUENCIES.length][5];
    // Per band and channel: x1, x2, y1, y2.
    private final double[][][] state;
    private double headroom = 1;
    private boolean flat = true;

    public Equalizer(int channels, double sampleRate) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.state = new double[FREQUENCIES.length][channels][4];
        for (int b = 0; b < FREQUENCIES.length; b++) design(b);
    }

    /** Sets band gains in dB (clamped to ±12); a null array means flat. */
    public void setGains(double[] db) {
        boolean changed = false;
        double maxBoost = 0;
        boolean allZero = true;
        for (int b = 0; b < gains.length; b++) {
            double g = db == null || b >= db.length ? 0 : Math.clamp(db[b], -MAX_GAIN_DB, MAX_GAIN_DB);
            if (g != gains[b]) {
                gains[b] = g;
                design(b);
                changed = true;
            }
            maxBoost = Math.max(maxBoost, g);
            allZero &= g == 0;
        }
        if (changed) {
            // Pull the level down by half the largest boost so boosted bands clip less.
            headroom = Math.pow(10, -maxBoost / 2 / 20);
            if (allZero && !flat) reset();
            flat = allZero;
        }
    }

    public boolean isFlat() {
        return flat;
    }

    public void process(float[] samples, int offset, int frames) {
        if (flat) return;
        for (int b = 0; b < coeffs.length; b++) {
            if (gains[b] == 0) continue;
            double[] k = coeffs[b];
            for (int c = 0; c < channels; c++) {
                double[] s = state[b][c];
                double x1 = s[0], x2 = s[1], y1 = s[2], y2 = s[3];
                for (int f = 0; f < frames; f++) {
                    int i = offset + f * channels + c;
                    double x = samples[i];
                    double y = k[0] * x + k[1] * x1 + k[2] * x2 - k[3] * y1 - k[4] * y2;
                    x2 = x1;
                    x1 = x;
                    y2 = y1;
                    y1 = y;
                    samples[i] = (float) y;
                }
                s[0] = x1;
                s[1] = x2;
                s[2] = y1;
                s[3] = y2;
            }
        }
        if (headroom != 1) {
            for (int i = 0; i < frames * channels; i++) samples[offset + i] *= (float) headroom;
        }
    }

    public void reset() {
        for (double[][] band : state) for (double[] s : band) java.util.Arrays.fill(s, 0);
    }

    private void design(int band) {
        double a = Math.pow(10, gains[band] / 40);
        double w0 = 2 * Math.PI * Math.min(FREQUENCIES[band], sampleRate * 0.45) / sampleRate;
        double cos = Math.cos(w0);
        boolean shelf = band == 0 || band == FREQUENCIES.length - 1;
        double alpha = Math.sin(w0) / (2 * (shelf ? SHELF_Q : PEAK_Q));
        double b0, b1, b2, a0, a1, a2;
        if (shelf) {
            double sq = 2 * Math.sqrt(a) * alpha;
            double sign = band == 0 ? 1 : -1;
            // Low shelf (sign = 1) or high shelf (sign = -1).
            b0 = a * ((a + 1) - sign * (a - 1) * cos + sq);
            b1 = sign * 2 * a * ((a - 1) - sign * (a + 1) * cos);
            b2 = a * ((a + 1) - sign * (a - 1) * cos - sq);
            a0 = (a + 1) + sign * (a - 1) * cos + sq;
            a1 = -sign * 2 * ((a - 1) + sign * (a + 1) * cos);
            a2 = (a + 1) + sign * (a - 1) * cos - sq;
        } else {
            b0 = 1 + alpha * a;
            b1 = -2 * cos;
            b2 = 1 - alpha * a;
            a0 = 1 + alpha / a;
            a1 = -2 * cos;
            a2 = 1 - alpha / a;
        }
        double[] k = coeffs[band];
        k[0] = b0 / a0;
        k[1] = b1 / a0;
        k[2] = b2 / a0;
        k[3] = a1 / a0;
        k[4] = a2 / a0;
    }
}
