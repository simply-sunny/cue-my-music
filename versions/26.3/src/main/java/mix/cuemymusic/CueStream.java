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
import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * Wraps a streamed music track: skips to its start offset, then runs it
 * through {@link PitchTempoProcessor} and {@link Equalizer}, reading the
 * settings on every chunk so changes apply to the playing track.
 *
 * <p>The game decodes a few seconds ahead, so a change is heard only once
 * the audio already handed over has played. Each stream notes the frame a
 * change starts at, so the player can show when it will be heard.
 *
 * <p>Songs on an add-on's page can be automixed: brought to the same
 * loudness, and blended into the next one with a crossfade, which this
 * stream then carries on playing. They can also be played outside the game
 * ({@link OutsideAudio}), so the game itself plays silence in their place.
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

    private Reader source;
    private final AudioFormat format;
    private final int channels;
    private final long id = CuePlayer.nextStreamId();
    private final PitchTempoProcessor processor;
    private final Equalizer eq;
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
    /** Played by {@link OutsideAudio}; the game gets silence. */
    private final boolean outside;

    // Automix. The song playing, its profile (null if it isn't automixed), gain, and how far into it the source is.
    private Track song;
    private Automix.Profile profile;
    private double gain = 1;
    private long songFrame;
    // The song being blended in, while a transition runs.
    private Reader next;
    private Track nextSong;
    private Automix.Profile nextProfile;
    private long nextFrame;
    private Automix.Blend blend;
    private boolean handedOver;

    /** Wraps a 16-bit PCM music stream; anything else is returned as is. */
    public static AudioStream wrap(AudioStream stream, Identifier sound, double startSeconds, SoundInstance instance) {
        AudioFormat f = stream.getFormat();
        if (f.getSampleSizeInBits() != 16 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || f.isBigEndian()) {
            LOG.warn("Not processing {}: unsupported format {}", sound, f);
            return stream;
        }
        return new CueStream(stream, sound, startSeconds, instance);
    }

    /** Seconds the next stream to start fades in over; 0 for none. */
    private static volatile double pendingFadeIn;
    private long fadeInFrames;
    private long fadedFrames;

    /** Makes the next stream to start fade in over {@code seconds}, as after a dimension change. */
    static void fadeInNext(double seconds) {
        pendingFadeIn = seconds;
    }

    private CueStream(AudioStream source, Identifier sound, double startSeconds, SoundInstance instance) {
        this.source = new Reader(source);
        this.format = source.getFormat();
        this.channels = format.getChannels();
        this.rate = format.getSampleRate();
        fadeInFrames = (long) (pendingFadeIn * rate);
        pendingFadeIn = 0;
        this.processor = new PitchTempoProcessor(channels, format.getSampleRate());
        this.eq = new Equalizer(channels, format.getSampleRate());
        Identifier track = sound.withPath(p -> p.replaceFirst("^sounds/", "").replaceFirst("\\.ogg$", ""));
        song = MusicLibrary.get(track);
        if (AutomixProfiles.applies(song)) {
            profile = AutomixProfiles.get(song);
            if (profile != null) gain = profile.gain();
        }

        // Decode and drop everything before the start offset. Runs on the stream-loading thread.
        long skip = (long) (startSeconds * format.getSampleRate());
        try {
            songFrame = this.source.skip(skip);
            if (songFrame < skip) sourceDone = true;
        } catch (IOException e) {
            LOG.warn("Couldn't seek {} to {}s", sound, startSeconds, e);
        }
        outside = song != null && CueConfig.INSTANCE.keepOutOfRecordings && !MusicLibrary.isGameMusic(song)
                && OutsideAudio.begin(id, (int) rate, channels);
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

            float[] chunk = pull();
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
        for (int f = 0; f < frames && fadedFrames < fadeInFrames; f++, fadedFrames++) {
            double s = Math.sin(Math.PI / 2 * fadedFrames / fadeInFrames);
            for (int c = 0; c < channels; c++) samples[f * channels + c] *= (float) (s * s);
        }
        if (outside) {
            OutsideAudio.play(id, samples, frames * channels);
            for (int i = 0; i < frames * channels; i++) consumer.accept(0);
        } else {
            for (int i = 0; i < frames * channels; i++) consumer.accept(samples[i]);
        }
        if (startNanos == 0) startNanos = System.nanoTime();
        emittedFrames += frames;
    }

    /**
     * The next block of interleaved samples, or null at the end. Automixed
     * songs come out at their gain, and blend into the next song as its
     * transition says (see {@link Automix.Blend}).
     */
    private float[] pull() throws IOException {
        if (next == null && profile != null) {
            long start = startOfFade();
            if (songFrame >= start) beginFade();
            else if (start - songFrame < CHUNK_FRAMES) {
                // Stops exactly where the transition starts.
                return plain((int) (start - songFrame));
            }
        }
        if (next == null) return plain(CHUNK_FRAMES);
        // Both songs, frame for frame: exactly as many from each, so neither skips or stalls.
        float[] a = source.read(CHUNK_FRAMES);
        float[] b = next.read(CHUNK_FRAMES);
        float[] mixed = new float[CHUNK_FRAMES * channels];
        blend.process(a, b, mixed, CHUNK_FRAMES);
        songFrame += a == null ? 0 : a.length / channels;
        nextFrame += b == null ? 0 : b.length / channels;
        // The next song takes over as the one playing at the middle of the transition.
        if (blend.progress() >= 0.5) handOver();
        if (blend.done() || (a == null && b == null)) endFade();
        return mixed;
    }

    /** Up to {@code frames} of the playing song alone, at its gain. */
    private float[] plain(int frames) throws IOException {
        float[] chunk = source.read(frames);
        if (chunk == null) return null;
        songFrame += chunk.length / channels;
        if (gain != 1) {
            for (int i = 0; i < chunk.length; i++) chunk[i] *= (float) gain;
        }
        return chunk;
    }

    /** The plan for blending into the song after this one, or null if there's nothing to blend into. */
    private Automix.Plan nextPlan() {
        Track n = CuePlayer.INSTANCE.automixNext();
        if (n == null || n.equals(song) || !AutomixProfiles.applies(n)) return null;
        Automix.Profile p = AutomixProfiles.get(n);
        if (!profile.blendsWith(p)) return null;
        return Automix.plan(profile, p, CueConfig.INSTANCE.transition(song.location().toString(), n.location().toString()));
    }

    /** The frame of the playing song where the transition into the next starts, or never. */
    private long startOfFade() {
        Automix.Plan plan = nextPlan();
        return plan == null ? Long.MAX_VALUE : plan.fadeStart();
    }

    private void beginFade() {
        Automix.Plan plan = nextPlan();
        if (plan == null) return;
        Track n = CuePlayer.INSTANCE.automixNext();
        Automix.Profile p = AutomixProfiles.get(n);
        try {
            next = new Reader(AutomixProfiles.open(n.location()));
            nextFrame = next.skip(plan.enterAt());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't open {} to automix", n.location(), e);
            next = null;
            profile = null;
            return;
        }
        nextSong = n;
        nextProfile = p;
        blend = new Automix.Blend(plan, channels, rate, gain, p.gain(), profile.bpm());
        handedOver = false;
    }

    /** Tells the player the song blended in is the one playing now, as of when this audio is heard. */
    private void handOver() {
        if (handedOver) return;
        handedOver = true;
        long heardAt = startNanos == 0 ? System.nanoTime() : startNanos + (long) (emittedFrames / (double) rate * 1e9);
        CuePlayer.INSTANCE.automixed(id, nextSong, nextFrame / (double) rate, heardAt);
    }

    /** The fade is over: the song blended in is now the one playing. */
    private void endFade() throws IOException {
        handOver();
        source.close();
        source = next;
        song = nextSong;
        profile = nextProfile;
        gain = profile.gain();
        songFrame = nextFrame;
        next = null;
        nextSong = null;
        nextProfile = null;
        blend = null;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (active == this) active = null;
            if (outside) OutsideAudio.end(id);
            CuePlayer.INSTANCE.streamClosed(id);
        }
        source.close();
        if (next != null) next.close();
    }

    /** A decoder that hands out exactly the number of frames asked for. */
    private static final class Reader {
        private final AudioStream source;
        private final int channels;
        private final Automix.FrameReader frames;

        Reader(AudioStream source) {
            this.source = source;
            this.channels = source.getFormat().getChannels();
            this.frames = new Automix.FrameReader(this::decode, channels);
        }

        /** {@code count} frames, fewer only at the end, or null at the end. */
        float[] read(int count) throws IOException {
            return frames.read(count);
        }

        /** Drops {@code count} frames. Returns how many there were. */
        long skip(long count) throws IOException {
            return frames.skip(count);
        }

        private float[] decode() throws IOException {
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

        void close() throws IOException {
            source.close();
        }
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
