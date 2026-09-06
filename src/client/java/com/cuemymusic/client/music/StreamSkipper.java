package com.cuemymusic.client.music;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Bounded forward discard over a decoded sample stream.
 *
 * <p>Seeking reopens the same OGG and drops decoded PCM up to the target —
 * deliberately {@code O(target duration)}, with no full-track buffer. The
 * native float-sample read over-reads every request by a chunk
 * ({@code ChunkedSampleByteBuf(requested + 8192)} keeps consuming chunks
 * until the request is met), so the <em>returned</em> size counts toward
 * the target; counting only requested bytes would overshoot into the song.
 */
public final class StreamSkipper {
    /** Minimal read/close surface; production adapts {@code AudioStream}. */
    public interface ByteSource {
        ByteBuffer read(int size) throws IOException;

        void close() throws IOException;
    }

    private StreamSkipper() {
    }

    /**
     * Drops up to {@code bytesToSkip} decoded bytes, stopping early at end
     * of stream or when {@code budgetBytes} returned bytes have been
     * consumed. Returns the total returned bytes consumed. Never closes the
     * source: success keeps the live stream, failure is the caller's to
     * close.
     */
    public static long discard(ByteSource source, long bytesToSkip, int chunkBytes, long budgetBytes)
            throws IOException {
        if (bytesToSkip <= 0L) {
            return 0L;
        }
        int chunk = Math.max(1, chunkBytes);
        long remaining = bytesToSkip;
        long consumed = 0L;
        while (remaining > 0L && consumed < budgetBytes) {
            int request = (int) Math.min(chunk, Math.min(remaining, budgetBytes - consumed));
            if (request <= 0) {
                break;
            }
            ByteBuffer buffer = source.read(request);
            if (buffer == null || !buffer.hasRemaining()) {
                break;
            }
            long got = buffer.remaining();
            consumed += got;
            remaining -= got;
        }
        return consumed;
    }
}
