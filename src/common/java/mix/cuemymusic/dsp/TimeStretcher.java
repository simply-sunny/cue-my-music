package mix.cuemymusic.dsp;

/**
 * Changes tempo without changing pitch, using WSOLA: the input is cut into
 * overlapping sequences, and each one is shifted (within a small seek window)
 * to where it lines up best with the tail of the previous one before they're
 * crossfaded. Sequence lengths follow SoundTouch's music defaults.
 */
final class TimeStretcher {
    private final int channels;
    private final float sampleRate;
    private final int overlap;
    private final float[] tail;
    private double tempo = 1;
    private int sequence;
    private int seek;
    private double skipCarry;
    private boolean primed;

    TimeStretcher(int channels, float sampleRate) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.overlap = Math.max(16, Math.round(sampleRate * 0.008f));
        this.tail = new float[overlap * channels];
        setTempo(1);
    }

    double tempo() {
        return tempo;
    }

    void setTempo(double tempo) {
        this.tempo = tempo;
        // Faster tempos get shorter sequences and seek windows (SoundTouch's auto settings).
        double t = Math.clamp(tempo, 0.5, 2.0);
        double seqMs = Math.clamp(125 - 50 / 1.5 * (t - 0.5), 40, 125);
        double seekMs = Math.clamp(25 - 10 / 1.5 * (t - 0.5), 15, 25);
        sequence = Math.max(2 * overlap + 1, (int) (seqMs * sampleRate / 1000));
        seek = Math.max(1, (int) (seekMs * sampleRate / 1000));
    }

    /** Frames of input needed before the next sequence can be produced. */
    private int needed() {
        return Math.max(sequence + seek, (int) Math.ceil((sequence - overlap) * tempo + skipCarry));
    }

    void process(FloatFifo in, FloatFifo out) {
        while (in.frames() >= needed()) {
            float[] src = in.array();
            int base = in.offset();
            int offset = 0;
            if (!primed) {
                int n = (sequence - overlap) * channels;
                out.put(src, base, sequence - overlap);
                System.arraycopy(src, base + n, tail, 0, tail.length);
                primed = true;
            } else {
                offset = bestOffset(src, base);
                int at = base + offset * channels;
                int dst = out.append(sequence - overlap);
                float[] o = out.array();
                for (int f = 0; f < overlap; f++) {
                    float w = (float) f / overlap;
                    for (int c = 0; c < channels; c++) {
                        int i = f * channels + c;
                        o[dst + i] = tail[i] * (1 - w) + src[at + i] * w;
                    }
                }
                int body = (sequence - 2 * overlap) * channels;
                System.arraycopy(src, at + overlap * channels, o, dst + overlap * channels, body);
                System.arraycopy(src, at + (sequence - overlap) * channels, tail, 0, tail.length);
            }
            double skip = (sequence - overlap) * tempo + skipCarry;
            int whole = (int) skip;
            skipCarry = skip - whole;
            in.consume(whole);
        }
    }

    /** Flushes what's left, padding with silence. */
    void finish(FloatFifo in, FloatFifo out) {
        in.putSilence(needed());
        process(in, out);
        in.clear();
    }

    void reset() {
        primed = false;
        skipCarry = 0;
    }

    /** Offset in [0, seek) whose overlap region best matches the previous tail. */
    private int bestOffset(float[] src, int base) {
        // Coarse pass every 4 frames, then refine around the winner.
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int off = 0; off < seek; off += 4) {
            double s = score(src, base + off * channels);
            if (s > bestScore) {
                bestScore = s;
                best = off;
            }
        }
        int lo = Math.max(0, best - 3);
        int hi = Math.min(seek - 1, best + 3);
        for (int off = lo; off <= hi; off++) {
            double s = score(src, base + off * channels);
            if (s > bestScore) {
                bestScore = s;
                best = off;
            }
        }
        return best;
    }

    private double score(float[] src, int at) {
        double corr = 0;
        double energy = 1e-9;
        int n = tail.length;
        for (int i = 0; i < n; i++) {
            float x = src[at + i];
            corr += tail[i] * x;
            energy += x * x;
        }
        return corr / Math.sqrt(energy);
    }
}
