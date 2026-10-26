package mix.cuemymusic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.dsp.PitchTempoProcessor;

/**
 * Wraps a streamed music track: skips to its start offset, then runs it
 * through {@link PitchTempoProcessor} and {@link Equalizer}, reading the
 * settings on every chunk so changes apply to the playing track.
 *
 * <p>The game decodes a few seconds ahead, so a change is heard only once
 * the audio already handed over has played. Each stream notes the frame a
 * change starts at, so the player can show when it will be heard.
 */
public final class CueStream implements FloatSampleSource {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final int CHUNK_FRAMES = 2048;
    /** The music stream that's playing, or null. */
    private static volatile CueStream active;

    /** The settings that shape the sound. Equal when they sound the same. */
    public record Params(double pitch, double tempo, double[] eq) {
        public static Params now() {
            CueConfig cfg = CueConfig.INSTANCE;
            return new Params(cfg.pitchRatio(), cfg.tempo(), cfg.activeEq());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Params p && pitch == p.pitch && tempo == p.tempo && Arrays.equals(eq, p.eq);
        }

        @Override
        public int hashCode() {
            return Double.hashCode(pitch) * 31 + Double.hashCode(tempo) * 17 + Arrays.hashCode(eq);
        }
    }

    private record Change(long frame, Params params) {
    }

    private final AudioStream source;
    private final AudioFormat format;
    private final int channels;
    private final long id = CuePlayer.nextStreamId();
    private final PitchTempoProcessor processor;
    private final Equalizer eq;
    private float[] pendingFirst;
    private final float[] out = new float[CHUNK_FRAMES * 8];
    private boolean sourceDone;
    private boolean flushed;
    private boolean closed;
    private final float rate;
    /** When the first audio was handed over, which is about when it started playing; 0 before that. */
    private volatile long startNanos;
    private volatile long emittedFrames;
    private Params applied;
    /** The latest settings changes, oldest first, with the frame each starts at. */
    private final ArrayDeque<Change> changes = new ArrayDeque<>();

    /** Wraps a 16-bit PCM music stream; anything else is returned as is. */
    public static AudioStream wrap(AudioStream stream, Identifier sound, double startSeconds, SoundInstance instance) {
        AudioFormat f = stream.getFormat();
        if (f.getSampleSizeInBits() != 16 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || f.isBigEndian()) {
            LOG.warn("Not processing {}: unsupported format {}", sound, f);
            return stream;
        }
        return new CueStream(stream, sound, startSeconds, instance);
    }

    private CueStream(AudioStream source, Identifier sound, double startSeconds, SoundInstance instance) {
        this.source = source;
        this.format = source.getFormat();
        this.channels = format.getChannels();
        this.rate = format.getSampleRate();
        this.processor = new PitchTempoProcessor(channels, format.getSampleRate());
        this.eq = new Equalizer(channels, format.getSampleRate());

        // Decode and drop everything before the start offset. Runs on the stream-loading thread.
        long skip = (long) (startSeconds * format.getSampleRate()) * channels;
        try {
            while (skip > 0) {
                float[] chunk = pull();
                if (chunk == null) {
                    sourceDone = true;
                    break;
                }
                if (chunk.length > skip) {
                    pendingFirst = Arrays.copyOfRange(chunk, (int) (skip - skip % channels), chunk.length);
                }
                skip -= chunk.length;
            }
        } catch (IOException e) {
            LOG.warn("Couldn't seek {} to {}s", sound, startSeconds, e);
        }
        Identifier track = sound.withPath(p -> p.replaceFirst("^sounds/", "").replaceFirst("\\.ogg$", ""));
        if (CuePlayer.INSTANCE.streamStarted(id, track, startSeconds, instance)) active = this;
    }

    /** The settings being heard right now, or null with no music playing. */
    public static Params heard() {
        CueStream s = active;
        if (s == null || s.startNanos == 0) return null;
        long playing = (long) ((System.nanoTime() - s.startNanos) / 1e9 * s.rate);
        synchronized (s.changes) {
            Params heard = null;
            for (Change c : s.changes) {
                if (c.frame() > playing) break;
                heard = c.params();
            }
            return heard;
        }
    }

    /**
     * When {@code target} will be heard, as {@link System#nanoTime}: after the
     * audio decoded before it. 0 if it already is, or no music is playing.
     */
    public static long audibleAt(Params target) {
        CueStream s = active;
        if (s == null || s.startNanos == 0) return 0;
        long frame;
        synchronized (s.changes) {
            Change last = s.changes.peekLast();
            // Not read yet: it starts right after what's been handed over so far.
            frame = last != null && last.params().equals(target) ? last.frame() : s.emittedFrames;
        }
        long at = s.startNanos + (long) (frame / (double) s.rate * 1e9);
        return at > System.nanoTime() ? at : 0;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public boolean readChunk(FloatConsumer consumer) throws IOException {
        while (true) {
            Params p = Params.now();
            if (!p.equals(applied)) {
                applied = p;
                synchronized (changes) {
                    changes.addLast(new Change(emittedFrames, p));
                    if (changes.size() > 16) changes.removeFirst();
                }
            }
            processor.setParams(p.pitch(), p.tempo());
            eq.setGains(p.eq());

            if (processor.available() > 0) {
                int frames = processor.take(out, 0, CHUNK_FRAMES);
                emit(consumer, out, frames);
                return true;
            }
            if (sourceDone) {
                if (flushed) return false;
                processor.finish();
                flushed = true;
                continue;
            }

            float[] chunk = pendingFirst != null ? pendingFirst : pull();
            pendingFirst = null;
            if (chunk == null) {
                sourceDone = true;
                continue;
            }
            int frames = chunk.length / channels;
            if (processor.isIdentity() && processor.isEmpty()) {
                emit(consumer, chunk, frames);
                return true;
            }
            processor.put(chunk, 0, frames);
        }
    }

    private void emit(FloatConsumer consumer, float[] samples, int frames) {
        eq.process(samples, 0, frames);
        for (int i = 0; i < frames * channels; i++) consumer.accept(samples[i]);
        if (startNanos == 0) startNanos = System.nanoTime();
        emittedFrames += frames;
    }

    /** Next block of interleaved samples from the source, or null at end of stream. */
    private float[] pull() throws IOException {
        if (source instanceof FloatSampleSource floats) {
            Collector c = new Collector();
            while (c.size == 0) {
                if (!floats.readChunk(c)) break;
            }
            return c.size == 0 ? null : Arrays.copyOf(c.data, c.size - c.size % channels);
        }
        ByteBuffer bytes = source.read(CHUNK_FRAMES * channels * 2);
        if (bytes == null || bytes.remaining() < channels * 2) return null;
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes.remaining() / 2 / channels * channels];
        for (int i = 0; i < samples.length; i++) samples[i] = bytes.getShort() / 32768f;
        return samples;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (active == this) active = null;
            CuePlayer.INSTANCE.streamClosed(id);
        }
        source.close();
    }

    private static final class Collector implements FloatConsumer {
        float[] data = new float[CHUNK_FRAMES * 2];
        int size;

        @Override
        public void accept(float v) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = v;
        }
    }
}
