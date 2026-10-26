package mix.cuemymusic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * Each automixed song's {@link Automix.Profile}, worked out once by decoding
 * the whole file on a background thread, and saved under
 * {@code config/cuemymusic-analysis} so it's read back next time instead of
 * worked out again. A saved profile holds the file's size and checksum, and
 * is redone if the file changed. Once the game has loaded, every add-on song
 * is looked at in the background, so tempos and keys are there when a page
 * shows them. Cleared from memory when resource packs reload.
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

    /** Looks at every add-on song once resources have loaded, on its own thread so songs asked for now don't wait. */
    private static final ExecutorService WARM_UP = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cuemymusic-automix-warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static volatile boolean warmed;

    private AutomixProfiles() {
    }

    /** Each client tick: once resources have loaded, starts looking at every add-on song not looked at yet. */
    static void tick(Minecraft mc) {
        if (warmed || !mc.isGameLoadFinished() || mc.gui.overlay() != null || !CueConfig.INSTANCE.automix || CueConfig.INSTANCE.vanilla) return;
        warmed = true;
        List<Identifier> songs = MusicLibrary.all().stream().filter(AutomixProfiles::applies).map(Track::location).toList();
        WARM_UP.execute(() -> {
            for (Identifier id : songs) {
                if (!warmed) return;
                if (STARTED.putIfAbsent(id, true) == null) analyse(id);
            }
        });
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
        warmed = false;
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
        byte[] file;
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(MusicLibrary.file(id))) {
            file = in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't read {} for automix", id, e);
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(file);
        long sum = crc.getValue();
        Path saved = savedPath(id);
        Automix.Profile p = load(saved, file.length, sum);
        if (p == null) {
            p = decode(id, file);
            if (p == null) return;
            save(saved, file.length, sum, p);
        }
        PROFILES.put(id, p);
    }

    private static Automix.Profile decode(Identifier id, byte[] file) {
        try (FloatSampleSource source = new JOrbisAudioStream(new ByteArrayInputStream(file))) {
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
            return analyzer.finish();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't analyse {} for automix", id, e);
            return null;
        }
    }

    /** Where a song's profile is saved: one file per song, named after its id. */
    private static Path savedPath(Identifier id) {
        String name = id.toString().replaceAll("[^a-z0-9_.-]", "_");
        return FabricLoader.getInstance().getConfigDir().resolve("cuemymusic-analysis").resolve(name + ".bin");
    }

    /** The saved profile, or null if there's none, it's from an older analysis, or the file has changed since. */
    private static Automix.Profile load(Path saved, long size, long sum) {
        if (!Files.isRegularFile(saved)) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(saved)))) {
            if (in.readLong() != size || in.readLong() != sum) return null;
            return Automix.Profile.read(in);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Couldn't read saved analysis {}", saved, e);
            return null;
        }
    }

    private static void save(Path saved, long size, long sum, Automix.Profile p) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                out.writeLong(size);
                out.writeLong(sum);
                p.write(out);
            }
            Files.createDirectories(saved.getParent());
            // Written whole and then moved into place, so a crash never leaves half a file.
            Path temp = saved.resolveSibling(saved.getFileName() + ".tmp");
            Files.write(temp, bytes.toByteArray());
            Files.move(temp, saved, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't save the analysis to {}", saved, e);
        }
    }
}
