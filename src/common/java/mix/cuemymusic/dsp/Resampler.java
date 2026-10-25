package mix.cuemymusic.dsp;

/**
 * Streaming Catmull-Rom resampler. A rate above 1 reads the input faster,
 * which shortens it and raises the pitch by the same factor.
 */
final class Resampler {
    private final int channels;
    private double rate = 1;
    private double pos = 1;

    Resampler(int channels) {
        this.channels = channels;
    }

    void setRate(double rate) {
        this.rate = rate;
    }

    void process(FloatFifo in, FloatFifo out) {
        // Interpolating at pos needs frames floor(pos)-1 .. floor(pos)+2.
        int available = in.frames();
        if (available < 4) return;
        float[] src = in.array();
        int base = in.offset();
        int count = 0;
        double p = pos;
        while ((int) p + 2 < available) {
            p += rate;
            count++;
        }
        if (count == 0) return;
        int dst = out.append(count);
        float[] o = out.array();
        for (int k = 0; k < count; k++) {
            int i = (int) pos;
            float t = (float) (pos - i);
            int a = base + (i - 1) * channels;
            for (int c = 0; c < channels; c++) {
                float y0 = src[a + c];
                float y1 = src[a + channels + c];
                float y2 = src[a + 2 * channels + c];
                float y3 = src[a + 3 * channels + c];
                o[dst + k * channels + c] = y1 + 0.5f * t * (y2 - y0
                        + t * (2 * y0 - 5 * y1 + 4 * y2 - y3
                        + t * (3 * (y1 - y2) + y3 - y0)));
            }
            pos += rate;
        }
        int drop = (int) pos - 1;
        in.consume(drop);
        pos -= drop;
    }

    void finish(FloatFifo in, FloatFifo out) {
        in.putSilence(3);
        process(in, out);
        in.clear();
        pos = 1;
    }

    void reset() {
        pos = 1;
    }
}
