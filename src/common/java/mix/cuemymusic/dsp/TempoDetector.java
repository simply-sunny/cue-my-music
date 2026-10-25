package mix.cuemymusic.dsp;

/**
 * Estimates a song's BPM from its first {@value #ANALYSIS_SECONDS} seconds.
 *
 * <p>Builds an onset envelope (rises in short-time energy), autocorrelates it,
 * and picks the strongest lag between 60 and 200 BPM, weighted toward 120.
 * Returns NaN when no beat stands out, which is common for ambient tracks.
 */
public final class TempoDetector {
    public static final int ANALYSIS_SECONDS = 30;
    private static final int MIN_SECONDS = 8;
    private static final double MIN_BPM = 60;
    private static final double MAX_BPM = 200;
    /** Peak-to-mean autocorrelation ratio below which there is no clear beat. */
    private static final double MIN_CONFIDENCE = 1.5;
    /** Strongest onset must rise by at least this fraction of the average level. */
    private static final double MIN_ONSET = 0.25;

    private final int channels;
    private final float sampleRate;
    private final int hop;
    private final float[] energy;
    private int hops;
    private double acc;
    private int accFrames;
    private boolean done;
    private double bpm = Double.NaN;

    public TempoDetector(int channels, float sampleRate) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.hop = Math.max(1, Math.round(sampleRate * 0.005f));
        this.energy = new float[(int) (ANALYSIS_SECONDS * sampleRate / hop) + 1];
    }

    /** True once the analysis window is full (or {@link #finish()} was called). */
    public boolean isDone() {
        return done;
    }

    public void feed(float[] interleaved, int offset, int frames) {
        if (done) return;
        for (int f = 0; f < frames; f++) {
            float mono = 0;
            int at = offset + f * channels;
            for (int c = 0; c < channels; c++) mono += interleaved[at + c];
            mono /= channels;
            acc += mono * mono;
            if (++accFrames == hop) {
                energy[hops++] = (float) (acc / hop);
                acc = 0;
                accFrames = 0;
                if (hops == energy.length) {
                    finish();
                    return;
                }
            }
        }
    }

    /** Ends analysis early, e.g. when the song is shorter than the window. */
    public void finish() {
        if (done) return;
        done = true;
        bpm = analyze();
    }

    /** Detected BPM, or NaN if not done yet or no clear beat. */
    public double bpm() {
        return bpm;
    }

    private double analyze() {
        double hopSeconds = hop / (double) sampleRate;
        if (hops * hopSeconds < MIN_SECONDS) return Double.NaN;

        // Onset envelope: positive change in RMS, so loud beats outweigh quiet off-beats.
        int n = hops - 1;
        double[] raw = new double[n];
        double strongest = 0;
        double meanRms = 0;
        for (int i = 0; i < n; i++) {
            raw[i] = Math.max(0, Math.sqrt(energy[i + 1]) - Math.sqrt(energy[i]));
            strongest = Math.max(strongest, raw[i]);
            meanRms += Math.sqrt(energy[i]);
        }
        meanRms /= n;
        if (meanRms <= 0 || strongest < MIN_ONSET * meanRms) return Double.NaN;

        // Smooth it so beats that fall between hops still line up, then remove the mean.
        double[] onset = new double[n];
        double[] kernel = {1, 2, 3, 2, 1};
        double mean = 0;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = 0; k < kernel.length; k++) {
                int j = i + k - kernel.length / 2;
                if (j >= 0 && j < n) s += raw[j] * kernel[k];
            }
            onset[i] = s / 9;
            mean += onset[i];
        }
        mean /= n;
        for (int i = 0; i < n; i++) onset[i] -= mean;

        int minLag = (int) Math.floor(60 / (MAX_BPM * hopSeconds));
        int maxLag = (int) Math.ceil(60 / (MIN_BPM * hopSeconds));
        if (maxLag * 3 >= n) return Double.NaN;

        double[] ac = new double[3 * maxLag + 3];
        for (int lag = 1; lag < ac.length; lag++) {
            double s = 0;
            for (int i = 0; i + lag < n; i++) s += onset[i] * onset[i + lag];
            ac[lag] = s / (n - lag);
        }

        int bestLag = -1;
        double best = Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            // Comb over 2x and 3x the period so the true beat beats its 2:3 and 1:2 relatives.
            double s = comb(ac, lag);
            double bpmAt = 60 / (lag * hopSeconds);
            double octaves = Math.log(bpmAt / 120) / Math.log(2);
            double weighted = s * Math.exp(-0.5 * octaves * octaves);
            sum += Math.abs(s);
            if (weighted > best) {
                best = weighted;
                bestLag = lag;
            }
        }
        // A beat half as long that's nearly as strong means we locked onto every other beat.
        int half = Math.round(bestLag / 2f);
        if (half >= minLag && ac[half] > 0.8 * ac[bestLag]) bestLag = half;

        double meanAbs = sum / (maxLag - minLag + 1);
        double peak = comb(ac, bestLag);
        if (meanAbs <= 0 || peak / meanAbs < MIN_CONFIDENCE) return Double.NaN;

        // Parabolic interpolation around the peak for sub-hop precision.
        double lag = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double a = ac[bestLag - 1], b = ac[bestLag], c = ac[bestLag + 1];
            double denom = a - 2 * b + c;
            if (denom < 0) lag += 0.5 * (a - c) / denom;
        }
        return 60 / (lag * hopSeconds);
    }

    private static double comb(double[] ac, int lag) {
        return ac[lag] + 0.5 * ac[2 * lag] + 0.33 * ac[3 * lag];
    }
}
