package mix.cuemymusic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.dsp.PitchTempoProcessor;

/**
 * Wraps a streamed music track: skips to its start offset, then runs it
 * through {@link PitchTempoProcessor} and {@link Equalizer}, reading the
 * settings on every chunk so changes apply to the playing track.
 */
public final class CueStream implements FloatSampleSource {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final int CHUNK_FRAMES = 2048;

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

    /** Wraps a 16-bit PCM music stream; anything else is returned as is. */
    public static AudioStream wrap(AudioStream stream, Identifier sound, double startSeconds) {
        AudioFormat f = stream.getFormat();
        if (f.getSampleSizeInBits() != 16 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || f.isBigEndian()) {
            LOG.warn("Not processing {}: unsupported format {}", sound, f);
            return stream;
        }
        return new CueStream(stream, sound, startSeconds);
    }

    private CueStream(AudioStream source, Identifier sound, double startSeconds) {
        this.source = source;
        this.format = source.getFormat();
        this.channels = format.getChannels();
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
        CuePlayer.INSTANCE.streamStarted(id, track, startSeconds);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public boolean readChunk(FloatConsumer consumer) throws IOException {
        while (true) {
            CueConfig cfg = CueConfig.INSTANCE;
            processor.setParams(cfg.pitchRatio(), cfg.tempo());
            eq.setGains(cfg.activeEq());

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
