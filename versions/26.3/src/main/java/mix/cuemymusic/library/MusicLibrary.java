package mix.cuemymusic.library;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import mix.cuemymusic.mixin.WeighedSoundEventsAccessor;

/**
 * Every background-music file the game can currently play: the vanilla ones
 * with credits from {@link Catalog}, plus anything resource packs add, which
 * get a title from their file name and a length measured on first sight.
 */
public final class MusicLibrary {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final Map<Identifier, Track> TRACKS = new LinkedHashMap<>();
    private static final Map<Identifier, Double> MEASURED = new java.util.concurrent.ConcurrentHashMap<>();

    private MusicLibrary() {
    }

    /** Rebuilds the list from the loaded sound events. Call on the client thread. */
    public static synchronized void refresh() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        Set<Identifier> files = new HashSet<>();
        for (Identifier event : sounds.getAvailableSounds()) {
            String path = event.getPath();
            if (!path.startsWith("music.")) continue;
            WeighedSoundEvents weighed = sounds.getSoundEvent(event);
            if (weighed == null) continue;
            for (Weighted<Sound> entry : ((WeighedSoundEventsAccessor) weighed).cuemymusic$list()) {
                if (entry instanceof Sound sound && sound.getType() == Sound.Type.FILE) files.add(sound.getLocation());
            }
        }

        TRACKS.clear();
        for (Map.Entry<String, Catalog.Entry> e : Catalog.TRACKS.entrySet()) {
            Identifier id = Identifier.withDefaultNamespace(e.getKey());
            if (!files.remove(id)) continue;
            Catalog.Entry c = e.getValue();
            TRACKS.put(id, new Track(id, c.title(), c.artist(), c.album(), c.seconds()));
        }
        List<Identifier> extra = new ArrayList<>(files);
        extra.sort(Comparator.comparing(Identifier::toString));
        for (Identifier id : extra) {
            Track t = new Track(id, Catalog.titleFromPath(id.getPath()), Catalog.UNKNOWN_ARTIST, Catalog.UNKNOWN_ALBUM,
                    MEASURED.getOrDefault(id, Double.NaN));
            TRACKS.put(id, t);
            if (Double.isNaN(t.seconds())) measure(t);
        }
    }

    public static synchronized List<Track> all() {
        if (TRACKS.isEmpty()) refresh();
        return List.copyOf(TRACKS.values());
    }

    public static synchronized List<Track> byArtist(String artist) {
        return all().stream().filter(t -> t.artist().equals(artist)).toList();
    }

    public static synchronized boolean hasUnknown() {
        return all().stream().anyMatch(t -> t.artist().equals(Catalog.UNKNOWN_ARTIST));
    }

    /** The track for a sound file, creating an uncredited one if it isn't known. */
    public static synchronized Track get(Identifier location) {
        Track t = TRACKS.get(location);
        if (t != null) return t;
        Catalog.Entry c = Catalog.TRACKS.get(location.getPath());
        t = c != null
                ? new Track(location, c.title(), c.artist(), c.album(), c.seconds())
                : new Track(location, Catalog.titleFromPath(location.getPath()), Catalog.UNKNOWN_ARTIST,
                        Catalog.UNKNOWN_ALBUM, MEASURED.getOrDefault(location, Double.NaN));
        if (Double.isNaN(t.seconds())) measure(t);
        return t;
    }

    private static void measure(Track track) {
        Identifier file = track.location().withPath(p -> "sounds/" + p + ".ogg");
        CompletableFuture.runAsync(() -> {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
            if (res.isEmpty()) return;
            try (InputStream in = res.get().open()) {
                double s = OggDuration.seconds(in.readAllBytes());
                if (!Double.isNaN(s)) {
                    MEASURED.put(track.location(), s);
                    track.setSeconds(s);
                }
            } catch (Exception e) {
                LOG.warn("Couldn't measure {}", file, e);
            }
        });
    }
}
