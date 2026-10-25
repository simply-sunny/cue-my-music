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

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import mix.cuemymusic.dsp.PitchTempoProcessor;
import mix.cuemymusic.dsp.TempoDetector;

/**
 * Wraps a streamed song and runs it through {@link PitchTempoProcessor},
 * reading the settings on every chunk so changes apply to the playing song.
 */
public final class CueStream implements FloatSampleSource {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final int CHUNK_FRAMES = 2048;

    /** Most recently started song, for the settings screen. */
    public static volatile NowPlaying nowPlaying;

    public record NowPlaying(String track, SoundSource source, TempoDetector detector) {
    }

    private final AudioStream source;
    private final SoundSource category;
    private final AudioFormat format;
    private final int channels;
    private final PitchTempoProcessor processor;
    private final TempoDetector detector;
    private final ArrayDeque<float[]> pending = new ArrayDeque<>();
    private float[] out = new float[CHUNK_FRAMES * 2];
    private boolean sourceDone;
    private boolean flushed;

    /** Wraps music and jukebox streams in 16-bit PCM; everything else is returned as is. */
    public static AudioStream wrap(AudioStream stream, SoundSource category, Identifier sound) {
        if (category != SoundSource.MUSIC && category != SoundSource.RECORDS) return stream;
        AudioFormat f = stream.getFormat();
        if (f.getSampleSizeInBits() != 16 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || f.isBigEndian()) {
            LOG.warn("Not processing {}: unsupported format {}", sound, f);
            return stream;
        }
        return new CueStream(stream, category, sound);
    }

    private CueStream(AudioStream source, SoundSource category, Identifier sound) {
        this.source = source;
        this.category = category;
        this.format = source.getFormat();
        this.channels = format.getChannels();
        this.processor = new PitchTempoProcessor(channels, format.getSampleRate());
        this.detector = new TempoDetector(channels, format.getSampleRate());
        String track = sound.getPath().replaceFirst("^sounds/", "").replaceFirst("\\.ogg$", "");
        nowPlaying = new NowPlaying(track, category, detector);

        // BPM mode needs the tempo before the first note; decode ahead to find it.
        // This runs on the stream-loading thread, not the sound thread.
        CueConfig cfg = CueConfig.INSTANCE;
        if (cfg.enabled && cfg.tempoMode == CueConfig.TempoMode.BPM && applies()) {
            try {
                while (!detector.isDone()) {
                    float[] chunk = pull();
                    if (chunk == null) break;
                    detector.feed(chunk, 0, chunk.length / channels);
                    pending.add(chunk);
                }
            } catch (IOException e) {
                LOG.warn("Couldn't pre-read {}", sound, e);
            }
            if (!detector.isDone()) detector.finish();
            LOG.info("{}: {}", track, Double.isNaN(detector.bpm()) ? "no clear beat"
                    : String.format("%.1f BPM", detector.bpm()));
        }
    }

    private boolean applies() {
        CueConfig cfg = CueConfig.INSTANCE;
        return category == SoundSource.MUSIC ? cfg.music : cfg.records;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public boolean readChunk(FloatConsumer consumer) throws IOException {
        while (true) {
            CueConfig cfg = CueConfig.INSTANCE;
            if (cfg.enabled && applies()) {
                processor.setParams(cfg.pitchRatio(), cfg.tempoFor(detector.bpm()));
            } else {
                processor.setParams(1, 1);
            }

            if (processor.available() > 0) {
                int frames = processor.take(out, 0, CHUNK_FRAMES);
                emit(consumer, out, frames * channels);
                return true;
            }
            if (sourceDone) {
                if (flushed) return false;
                processor.finish();
                flushed = true;
                continue;
            }

            float[] chunk = pending.isEmpty() ? pull() : pending.poll();
            if (chunk == null) {
                sourceDone = true;
                detector.finish();
                continue;
            }
            int frames = chunk.length / channels;
            detector.feed(chunk, 0, frames);
            if (processor.isIdentity() && processor.isEmpty()) {
                emit(consumer, chunk, chunk.length);
                return true;
            }
            processor.put(chunk, 0, frames);
        }
    }

    private static void emit(FloatConsumer consumer, float[] samples, int count) {
        for (int i = 0; i < count; i++) consumer.accept(samples[i]);
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
