package mix.cuemymusic;

/**
 * A voice said over the music, as a radio DJ talks over a song's start: the
 * music dips under the voice, the voice plays, and the music comes back up.
 * Fed the music block by block, after the effects, so the voice keeps its
 * own pitch and speed. Plain Java, so it can be checked without the game.
 */
public final class VoiceMix {
    /** How loud the music is under the voice: about -10 dB. */
    static final double DUCK = 0.32;
    /** The music dips over this long before the voice starts, and comes back over the longer time after it ends. */
    static final double DOWN_SECONDS = 0.4;
    static final double UP_SECONDS = 0.9;

    private final float[] voice;
    private final long lead;
    private final long up;
    private long frame;

    /**
     * {@code mono} is the voice at {@code voiceRate}, said at {@code volume}
     * (1 as recorded); {@code rate} is the music's sample rate.
     */
    public VoiceMix(float[] mono, float voiceRate, float rate, double volume) {
        voice = resample(mono, voiceRate, rate, volume);
        lead = Math.round(DOWN_SECONDS * rate);
        up = Math.round(UP_SECONDS * rate);
    }

    /** True once the voice has been said and the music is back up. */
    public boolean done() {
        return frame >= lead + voice.length + up;
    }

    /** Seconds from the start until the music is back up. */
    public double seconds(float rate) {
        return (lead + voice.length + up) / (double) rate;
    }

    /** Mixes the voice into {@code frames} frames of interleaved music, in place. */
    public void process(float[] samples, int frames, int channels) {
        for (int f = 0; f < frames && !done(); f++, frame++) {
            double duck = duck(frame);
            long v = frame - lead;
            float said = v >= 0 && v < voice.length ? voice[(int) v] : 0;
            for (int c = 0; c < channels; c++) {
                int i = f * channels + c;
                samples[i] = Automix.limit(samples[i] * duck + said);
            }
        }
    }

    /** The music's level at {@code frame}: down to {@link #DUCK} before the voice, back up after it. */
    double duck(long frame) {
        if (frame < lead) return 1 - (1 - DUCK) * smooth(frame / (double) lead);
        long after = frame - lead - voice.length;
        if (after < 0) return DUCK;
        if (after < up) return DUCK + (1 - DUCK) * smooth(after / (double) up);
        return 1;
    }

    private static double smooth(double t) {
        double s = Math.sin(Math.PI / 2 * Math.clamp(t, 0, 1));
        return s * s;
    }

    /** The voice at the music's rate, by linear interpolation, at {@code volume}. */
    static float[] resample(float[] mono, float from, float to, double volume) {
        if (mono.length == 0) return mono;
        int n = (int) Math.max(1, Math.round(mono.length * (double) to / from));
        float[] out = new float[n];
        double step = from / (double) to;
        for (int i = 0; i < n; i++) {
            double at = i * step;
            int j = (int) at;
            double frac = at - j;
            float a = mono[Math.min(j, mono.length - 1)], b = mono[Math.min(j + 1, mono.length - 1)];
            out[i] = (float) ((a + (b - a) * frac) * volume);
        }
        return out;
    }
}
