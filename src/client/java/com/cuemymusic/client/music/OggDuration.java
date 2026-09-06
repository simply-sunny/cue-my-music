package com.cuemymusic.client.music;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalDouble;

import com.jcraft.jogg.Page;
import com.jcraft.jogg.SyncState;

/**
 * Track duration from the compressed OGG framing only.
 *
 * <p>Total samples come from the largest page granule position and the
 * sample rate from the Vorbis identification header. No PCM is decoded and
 * no soundtrack bytes are copied: the file is streamed once through the
 * framing layer ({@code jogg}, already a game dependency) with hard caps,
 * off the render thread. Anything malformed, truncated or uncapped yields
 * an honestly unknown duration — the scrub bar then disables instead of
 * guessing. Chained OGGs report the first link (scan stops at end-of-stream).
 */
public final class OggDuration {
    static final int READ_CHUNK = 8192;
    /** Hard ceiling on compressed bytes scanned for one duration probe. */
    static final long MAX_SCAN_BYTES = 64L << 20;
    private static final int MIN_ID_BODY = 30;

    private OggDuration() {
    }

    public static OptionalDouble scan(InputStream input) throws IOException {
        SyncState sync = new SyncState();
        sync.init();
        Page page = new Page();
        long maxGranule = -1L;
        int sampleRate = -1;
        long scanned = 0L;
        boolean bosSeen = false;
        boolean eosSeen = false;
        while (scanned < MAX_SCAN_BYTES) {
            int index = sync.buffer(READ_CHUNK);
            int read = input.read(sync.data, index, READ_CHUNK);
            if (read == -1) {
                break;
            }
            if (read > 0) {
                sync.wrote(read);
                scanned += read;
            }
            int paged;
            while ((paged = sync.pageout(page)) == 1) {
                if (page.bos() != 0 && !bosSeen) {
                    bosSeen = true;
                    sampleRate = identificationRate(page);
                }
                long granule = page.granulepos();
                if (granule > maxGranule) {
                    maxGranule = granule;
                }
                if (page.eos() != 0) {
                    eosSeen = true;
                    break;
                }
            }
            if (eosSeen) {
                break;
            }
        }
        // Only a clean end-of-stream yields a duration: a truncated file
        // must report unknown rather than a lowball partial granule.
        if (!eosSeen) {
            return OptionalDouble.empty();
        }
        return result(maxGranule, sampleRate);
    }

    private static OptionalDouble result(long maxGranule, int sampleRate) {
        if (sampleRate <= 0 || maxGranule < 0L) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(maxGranule / (double) sampleRate);
    }

    /** Sample rate from a beginning-of-stream page carrying the Vorbis ID header. */
    private static int identificationRate(Page page) {
        if (page.body_len < MIN_ID_BODY) {
            return -1;
        }
        byte[] body = page.body_base;
        int at = page.body;
        if (body[at] != 0x01 || body[at + 1] != 'v' || body[at + 2] != 'o' || body[at + 3] != 'r'
                || body[at + 4] != 'b' || body[at + 5] != 'i' || body[at + 6] != 's') {
            return -1;
        }
        return (body[at + 12] & 0xFF) | ((body[at + 13] & 0xFF) << 8) | ((body[at + 14] & 0xFF) << 16)
                | ((body[at + 15] & 0xFF) << 24);
    }
}
