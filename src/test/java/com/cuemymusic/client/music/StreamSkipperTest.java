package com.cuemymusic.client.music;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded PCM discard contract: skipping forward reopens the stream and
 * drops decoded bytes up to the target without buffering the track. The
 * native float-sample read over-reads every request by a chunk, so the full
 * returned size (not the requested size) counts toward the target.
 */
class StreamSkipperTest {

    /** Minimal read/close surface mirroring AudioStream for pure tests. */
    private static class FakeSource implements StreamSkipper.ByteSource {
        private final Queue<byte[]> chunks = new ArrayDeque<>();
        private final int overread;
        boolean closed;

        FakeSource(int overread, byte[]... chunks) {
            this.overread = overread;
            for (byte[] chunk : chunks) {
                this.chunks.add(chunk);
            }
        }

        @Override
        public ByteBuffer read(int requested) {
            byte[] next = chunks.poll();
            if (next == null) {
                return ByteBuffer.allocate(0);
            }
            byte[] out = new byte[next.length + overread];
            System.arraycopy(next, 0, out, 0, next.length);
            return ByteBuffer.wrap(out);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test void exactReadsReachTarget() throws IOException {
        FakeSource source = new FakeSource(0, new byte[100], new byte[100]);
        assertEquals(200L, StreamSkipper.discard(source, 200L, 100, 1_000_000L));
        assertFalse(source.closed, "successful discard must not close the live stream");
    }

    @Test void overreadBytesCountTowardTarget() throws IOException {
        // Request 100, native layer returns 100 + 64 overread each time.
        FakeSource source = new FakeSource(64, new byte[100], new byte[100]);
        // 164 counted after the first read; the second read overshoots 200
        // and the full 164 counts: total 328 of returned bytes consumed.
        assertEquals(328L, StreamSkipper.discard(source, 200L, 100, 1_000_000L));
    }

    @Test void stopsAtEndOfStream() throws IOException {
        FakeSource source = new FakeSource(0, new byte[50]);
        assertEquals(50L, StreamSkipper.discard(source, 10_000L, 100, 1_000_000L));
    }

    @Test void budgetCapsRunawayDiscards() throws IOException {
        FakeSource source = new FakeSource(0, new byte[100], new byte[100], new byte[100]);
        assertEquals(200L, StreamSkipper.discard(source, 10_000L, 100, 200L));
    }

    @Test void zeroTargetReadsNothing() throws IOException {
        FakeSource source = new FakeSource(0, new byte[100]) {
            @Override
            public ByteBuffer read(int requested) {
                throw new AssertionError("must not read for a zero target");
            }
        };
        assertEquals(0L, StreamSkipper.discard(source, 0L, 100, 1_000_000L));
    }

    @Test void nullReadEndsDiscard() throws IOException {
        StreamSkipper.ByteSource source = new StreamSkipper.ByteSource() {
            @Override
            public ByteBuffer read(int requested) {
                return null;
            }

            @Override
            public void close() {
            }
        };
        assertEquals(0L, StreamSkipper.discard(source, 500L, 100, 1_000_000L));
    }

    @Test void ioFailurePropagatesForCallerToClose() {
        StreamSkipper.ByteSource source = new StreamSkipper.ByteSource() {
            @Override
            public ByteBuffer read(int requested) throws IOException {
                throw new IOException("drive gone");
            }

            @Override
            public void close() {
            }
        };
        assertThrows(IOException.class, () -> StreamSkipper.discard(source, 500L, 100, 1_000_000L));
    }
}
