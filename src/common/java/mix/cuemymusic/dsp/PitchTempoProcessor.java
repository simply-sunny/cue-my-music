package mix.cuemymusic.dsp;

/**
 * Shifts pitch and changes tempo independently, on interleaved float samples.
 *
 * <p>Tempo is stretched first by {@code tempo / pitch}, then the result is
 * resampled by {@code pitch}: the resample raises pitch and shortens the audio
 * by the same factor, so the net effect is the requested pitch and tempo.
 * Parameters can change between calls; changes apply from the next sequence.
 */
public final class PitchTempoProcessor {
    private static final double EPSILON = 1e-4;

    private final int channels;
    private final TimeStretcher stretcher;
    private final Resampler resampler;
    private final FloatFifo input;
    private final FloatFifo stretched;
    private final FloatFifo output;
    private double pitch = 1;
    private double tempo = 1;

    public PitchTempoProcessor(int channels, float sampleRate) {
        this.channels = channels;
        this.stretcher = new TimeStretcher(channels, sampleRate);
        this.resampler = new Resampler(channels);
        this.input = new FloatFifo(channels);
        this.stretched = new FloatFifo(channels);
        this.output = new FloatFifo(channels);
    }

    public static double semitonesToRatio(double semitones) {
        return Math.pow(2, semitones / 12);
    }

    public int channels() {
        return channels;
    }

    /** @param pitch frequency ratio (2 = one octave up); @param tempo speed ratio (1.2 = 20% faster) */
    public void setParams(double pitch, double tempo) {
        this.pitch = pitch;
        this.tempo = tempo;
    }

    public boolean isIdentity() {
        return Math.abs(pitch - 1) < EPSILON && Math.abs(tempo - 1) < EPSILON;
    }

    /** True when no samples are buffered anywhere in the chain. */
    public boolean isEmpty() {
        return input.frames() == 0 && stretched.frames() == 0 && output.frames() == 0;
    }

    public void put(float[] interleaved, int offset, int frames) {
        input.put(interleaved, offset, frames);
        run(false);
    }

    /** Flushes buffered input at end of stream. */
    public void finish() {
        run(true);
    }

    public int available() {
        return output.frames();
    }

    /** Moves up to {@code maxFrames} processed frames into {@code dst}; returns frames moved. */
    public int take(float[] dst, int offset, int maxFrames) {
        return output.take(dst, offset, maxFrames);
    }

    private void run(boolean flush) {
        double stretch = tempo / pitch;
        if (Math.abs(stretch - 1) > EPSILON) {
            if (stretcher.tempo() != stretch) stretcher.setTempo(stretch);
            if (flush) stretcher.finish(input, stretched);
            else stretcher.process(input, stretched);
        } else {
            stretcher.reset();
            input.moveAllTo(stretched);
        }

        if (Math.abs(pitch - 1) > EPSILON) {
            resampler.setRate(pitch);
            if (flush) resampler.finish(stretched, output);
            else resampler.process(stretched, output);
        } else {
            resampler.reset();
            stretched.moveAllTo(output);
        }
    }
}
