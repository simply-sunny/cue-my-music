package mix.cuemymusic;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/** Loads and saves {@link ListeningStats} as config/cuemymusic-stats.json, apart from the settings. */
final class StatsFile {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Saved at most this often while listening, and whenever the player closes or the game quits. */
    private static final long SAVE_EVERY_NANOS = 60_000_000_000L;
    private static long savedAt;

    private StatsFile() {
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("cuemymusic-stats.json");
    }

    static void load() {
        Path path = path();
        if (!Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path)) {
            ListeningStats loaded = GSON.fromJson(r, ListeningStats.class);
            if (loaded != null) ListeningStats.INSTANCE.copyFrom(loaded);
        } catch (IOException | JsonParseException e) {
            LOG.warn("Couldn't read {}, starting stats over", path, e);
        }
    }

    static void save() {
        savedAt = System.nanoTime();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                synchronized (ListeningStats.INSTANCE) {
                    GSON.toJson(ListeningStats.INSTANCE, w);
                }
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Couldn't save {}", path, e);
        }
    }

    /** Saves if it's been a minute since the last save. */
    static void saveNowAndThen() {
        if (System.nanoTime() - savedAt > SAVE_EVERY_NANOS) save();
    }
}
