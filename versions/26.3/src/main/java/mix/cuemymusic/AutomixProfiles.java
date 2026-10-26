package mix.cuemymusic;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * Each automixed song's {@link Automix.Profile}, worked out once by decoding
 * the whole file on a background thread. Cleared when resource packs reload.
 */
public final class AutomixProfiles {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final Map<Identifier, Automix.Profile> PROFILES = new ConcurrentHashMap<>();
    /** Songs being analysed, or that couldn't be. */
    private static final Map<Identifier, Boolean> STARTED = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cuemymusic-automix");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private AutomixProfiles() {
    }

    /** True if automix applies to {@code track}: it's on, and the song is on an add-on's page. */
    static boolean applies(Track track) {
        return track != null && CueConfig.INSTANCE.automix && !MusicLibrary.isGameMusic(track);
    }

    /** The song's profile, or null if it isn't ready yet; then it's worked out in the background. */
    static Automix.Profile get(Track track) {
        Identifier id = track.location();
        Automix.Profile p = PROFILES.get(id);
        if (p == null && STARTED.putIfAbsent(id, true) == null) WORKER.execute(() -> analyse(id));
        return p;
    }

    public static void clear() {
        PROFILES.clear();
        STARTED.clear();
    }

    /** Opens the song's file for decoding, from the start. */
    static FloatSampleSource open(Identifier track) throws IOException {
        InputStream in = Minecraft.getInstance().getResourceManager().open(MusicLibrary.file(track));
        try {
            return new JOrbisAudioStream(in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    private static void analyse(Identifier id) {
        try (FloatSampleSource source = open(id)) {
            Automix.Analyzer analyzer = new Automix.Analyzer(source.getFormat().getSampleRate(), source.getFormat().getChannels());
            float[][] buffer = {new float[8192]};
            int[] size = {0};
            boolean more = true;
            while (more) {
                size[0] = 0;
                more = source.readChunk(v -> {
                    if (size[0] == buffer[0].length) buffer[0] = java.util.Arrays.copyOf(buffer[0], size[0] * 2);
                    buffer[0][size[0]++] = v;
                });
                analyzer.accept(buffer[0], size[0]);
            }
            PROFILES.put(id, analyzer.finish());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't analyse {} for automix", id, e);
        }
    }
}
