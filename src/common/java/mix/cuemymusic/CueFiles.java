package mix.cuemymusic;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The mod's folder in {@code config}. Everything it saves goes in here. */
public final class CueFiles {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static boolean moved;

    private CueFiles() {
    }

    public static synchronized Path dir() {
        Path config = FabricLoader.getInstance().getConfigDir();
        if (!moved) {
            moved = true;
            moveOld(config);
        }
        return config.resolve("cuemymusic");
    }

    /** Moves what older versions left directly in {@code config}. A file already in the folder wins. */
    static void moveOld(Path config) {
        Path dir = config.resolve("cuemymusic");
        move(config.resolve("cuemymusic.json"), dir.resolve("config.json"));
        move(config.resolve("cuemymusic-stats.json"), dir.resolve("stats.json"));
        move(config.resolve("cuemymusic-analysis"), dir.resolve("analysis"));
    }

    private static void move(Path from, Path to) {
        if (!Files.exists(from) || Files.exists(to)) return;
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
        } catch (IOException e) {
            LOG.warn("Couldn't move {} to {}", from, to, e);
        }
    }
}
