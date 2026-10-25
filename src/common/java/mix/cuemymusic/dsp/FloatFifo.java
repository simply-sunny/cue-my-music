package mix.cuemymusic.dsp;

import java.util.Arrays;

/** Growable FIFO of interleaved float samples, addressed in frames. */
final class FloatFifo {
    private final int channels;
    private float[] data = new float[8192];
    private int start;
    private int end;

    FloatFifo(int channels) {
        this.channels = channels;
    }

    int frames() {
        return (end - start) / channels;
    }

    /** Raw backing array; frame {@code f} starts at {@code offset() + f * channels}. */
    float[] array() {
        return data;
    }

    int offset() {
        return start;
    }

    void put(float[] src, int srcOffset, int frames) {
        int n = frames * channels;
        ensureFree(n);
        System.arraycopy(src, srcOffset, data, end, n);
        end += n;
    }

    void putSilence(int frames) {
        int n = frames * channels;
        ensureFree(n);
        Arrays.fill(data, end, end + n, 0f);
        end += n;
    }

    /** Reserves {@code frames} at the tail and returns the array index to write them at. */
    int append(int frames) {
        int n = frames * channels;
        ensureFree(n);
        int at = end;
        end += n;
        return at;
    }

    void consume(int frames) {
        start += Math.min(frames * channels, end - start);
        if (start == end) {
            start = end = 0;
        }
    }

    /** Moves up to {@code maxFrames} into {@code dst}; returns frames moved. */
    int take(float[] dst, int dstOffset, int maxFrames) {
        int frames = Math.min(maxFrames, frames());
        System.arraycopy(data, start, dst, dstOffset, frames * channels);
        consume(frames);
        return frames;
    }

    void moveAllTo(FloatFifo other) {
        other.put(data, start, frames());
        clear();
    }

    void clear() {
        start = end = 0;
    }

    private void ensureFree(int n) {
        if (end + n <= data.length) return;
        int used = end - start;
        if (used + n <= data.length / 2) {
            System.arraycopy(data, start, data, 0, used);
        } else {
            float[] grown = new float[Math.max(data.length * 2, used + n + 4096)];
            System.arraycopy(data, start, grown, 0, used);
            data = grown;
        }
        start = 0;
        end = used;
    }
}
