package mix.cuemymusic.library;

/**
 * Length of an Ogg Vorbis file, from the sample rate in its identification
 * header and the granule position of its last page. Used for tracks that
 * aren't in {@link Catalog}.
 */
public final class OggDuration {
    private OggDuration() {
    }

    /** Seconds, or NaN if the data isn't a readable Ogg Vorbis file. */
    public static double seconds(byte[] ogg) {
        int rate = sampleRate(ogg);
        if (rate <= 0) return Double.NaN;
        for (int i = ogg.length - 27; i >= 0; i--) {
            if (ogg[i] == 'O' && ogg[i + 1] == 'g' && ogg[i + 2] == 'g' && ogg[i + 3] == 'S') {
                long granule = le64(ogg, i + 6);
                if (granule > 0) return granule / (double) rate;
            }
        }
        return Double.NaN;
    }

    private static int sampleRate(byte[] ogg) {
        // Identification header: packet type 1, "vorbis", version (4), channels (1), rate (4).
        for (int i = 0; i + 16 < ogg.length && i < 4096; i++) {
            if (ogg[i] == 1 && ogg[i + 1] == 'v' && ogg[i + 2] == 'o' && ogg[i + 3] == 'r'
                    && ogg[i + 4] == 'b' && ogg[i + 5] == 'i' && ogg[i + 6] == 's') {
                return (int) le32(ogg, i + 12);
            }
        }
        return -1;
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    private static long le64(byte[] b, int at) {
        return le32(b, at) | le32(b, at + 4) << 32;
    }
}
