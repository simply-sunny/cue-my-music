package mix.cuemymusic.library;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import mix.cuemymusic.mixin.WeighedSoundEventsAccessor;

/**
 * Every background-music file the game can currently play: the vanilla ones
 * with credits from {@link Catalog}, plus anything resource packs add or
 * replace. Pack files are credited to the pack: its name as the album, its
 * icon as the art, and the "By ..." line of its description as the artist.
 *
 * <p>A pack can name each song itself with {@code assets/<namespace>/cuemymusic.json}:
 * <pre>{"tracks": {"namespace:music/file": {"title": "...", "artist": "...", "album": "..."}}}</pre>
 * Any field it leaves out falls back to the above.
 */
public final class MusicLibrary {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final String VANILLA = "vanilla";
    private static final Map<Identifier, Track> TRACKS = new LinkedHashMap<>();
    private static final Map<String, PackInfo> PACKS = new LinkedHashMap<>();
    /** Pack details by id, kept across reloads so icons are only loaded once. */
    private static final Map<String, PackInfo> PACK_CACHE = new HashMap<>();
    private static final Map<Identifier, Double> MEASURED = new ConcurrentHashMap<>();
    /** Each file's entry in sounds.json, with its volume and pitch, so the player plays it as the game would. */
    private static final Map<Identifier, Sound> SOUNDS = new HashMap<>();
    private static int iconCount;

    private MusicLibrary() {
    }

    /** Rebuilds the list from the loaded sound events. Call on the client thread. */
    public static synchronized void refresh() {
        Minecraft mc = Minecraft.getInstance();
        SoundManager sounds = mc.getSoundManager();
        ResourceManager resources = mc.getResourceManager();
        Set<Identifier> files = new HashSet<>();
        SOUNDS.clear();
        for (Identifier event : sounds.getAvailableSounds()) {
            if (!event.getPath().startsWith("music.")) continue;
            WeighedSoundEvents weighed = sounds.getSoundEvent(event);
            if (weighed == null) continue;
            for (Weighted<Sound> entry : ((WeighedSoundEventsAccessor) weighed).cuemymusic$list()) {
                if (entry instanceof Sound sound && sound.getType() == Sound.Type.FILE) {
                    files.add(sound.getLocation());
                    SOUNDS.putIfAbsent(sound.getLocation(), sound);
                }
            }
        }

        // Where each file actually comes from. Files that don't exist (packs written for older versions) are dropped.
        Map<Identifier, Resource> sources = new HashMap<>();
        for (Identifier id : files) {
            resources.getResource(file(id)).ifPresent(r -> sources.put(id, r));
        }

        TRACKS.clear();
        PACKS.clear();
        for (Map.Entry<String, Catalog.Entry> e : Catalog.TRACKS.entrySet()) {
            Identifier id = Identifier.withDefaultNamespace(e.getKey());
            Resource r = sources.get(id);
            if (r == null || !VANILLA.equals(r.sourcePackId())) continue;
            Catalog.Entry c = e.getValue();
            TRACKS.put(id, new Track(id, c.title(), c.artist(), c.album().name(), Art.block(c.album().texture()), null,
                    c.seconds()));
            sources.remove(id);
        }

        // Everything else: unknown game files, then pack files grouped by pack.
        List<Identifier> extra = new ArrayList<>(sources.keySet());
        extra.sort(Comparator.comparing((Identifier id) -> !VANILLA.equals(sources.get(id).sourcePackId()))
                .thenComparing(id -> sources.get(id).sourcePackId())
                .thenComparing(Identifier::toString));
        Map<String, JsonObject> credits = new HashMap<>();
        for (Identifier id : extra) {
            Resource r = sources.get(id);
            String title = Catalog.titleFromPath(id.getPath());
            double seconds = MEASURED.getOrDefault(id, Double.NaN);
            Track t;
            if (VANILLA.equals(r.sourcePackId())) {
                t = new Track(id, title, Catalog.UNKNOWN_ARTIST, Catalog.UNKNOWN_ALBUM.name(),
                        Art.block(Catalog.UNKNOWN_ALBUM.texture()), null, seconds);
            } else {
                PackInfo pack = pack(r.source());
                PACKS.putIfAbsent(pack.id(), pack);
                String artist = pack.author() != null ? pack.author() : Catalog.UNKNOWN_ARTIST;
                JsonObject named = credits.computeIfAbsent(id.getNamespace(), ns -> credits(resources, ns));
                JsonObject c = named.has(id.toString()) && named.get(id.toString()).isJsonObject()
                        ? named.getAsJsonObject(id.toString()) : new JsonObject();
                t = new Track(id, text(c, "title", title), text(c, "artist", artist), text(c, "album", pack.name()),
                        pack.art(), pack.id(), seconds);
            }
            TRACKS.put(id, t);
            if (Double.isNaN(t.seconds())) measure(t);
        }
    }

    public static synchronized List<Track> all() {
        if (TRACKS.isEmpty()) refresh();
        return List.copyOf(TRACKS.values());
    }

    /** Game music credited to {@code artist}. */
    public static synchronized List<Track> byArtist(String artist) {
        return all().stream().filter(t -> t.packId() == null && t.artist().equals(artist)).toList();
    }

    public static synchronized List<Track> byPack(String packId) {
        return all().stream().filter(t -> packId.equals(t.packId())).toList();
    }

    /** Packs that add music, in the order their tracks are listed. */
    public static synchronized List<PackInfo> packs() {
        all();
        return List.copyOf(PACKS.values());
    }

    public static synchronized PackInfo pack(String packId) {
        all();
        return PACKS.get(packId);
    }

    /** True if some game file isn't in the catalog (a newer game version, say). */
    public static synchronized boolean hasUnknown() {
        return !byArtist(Catalog.UNKNOWN_ARTIST).isEmpty();
    }

    /** The sounds.json entry for a music file, or null if no music event lists it. */
    public static synchronized Sound sound(Identifier location) {
        if (TRACKS.isEmpty()) refresh();
        return SOUNDS.get(location);
    }

    /** Liked songs, most recently liked first. Songs whose files are gone are skipped. */
    public static synchronized List<Track> liked(List<String> ids) {
        all();
        List<Track> out = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            Identifier id = Identifier.tryParse(ids.get(i));
            Track t = id == null ? null : TRACKS.get(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** The {@code tracks} object of a namespace's {@code cuemymusic.json}, or empty if there's none or it's broken. */
    private static JsonObject credits(ResourceManager resources, String namespace) {
        Optional<Resource> file = resources.getResource(Identifier.fromNamespaceAndPath(namespace, "cuemymusic.json"));
        if (file.isEmpty()) return new JsonObject();
        try (var reader = file.get().openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject() && root.getAsJsonObject().get("tracks") instanceof JsonObject tracks) return tracks;
        } catch (Exception e) {
            LOG.warn("Couldn't read {}:cuemymusic.json", namespace, e);
        }
        return new JsonObject();
    }

    private static String text(JsonObject o, String field, String fallback) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        String s = e.getAsString().strip();
        return s.isEmpty() ? fallback : s;
    }

    /** The tracks for these sound file ids, in order, skipping any that aren't loaded. */
    public static synchronized List<Track> tracks(List<String> ids) {
        all();
        List<Track> out = new ArrayList<>();
        for (String s : ids) {
            Identifier id = Identifier.tryParse(s);
            Track t = id == null ? null : TRACKS.get(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** The track for a sound file, creating an uncredited one if it isn't known. */
    public static synchronized Track get(Identifier location) {
        Track t = TRACKS.get(location);
        if (t != null) return t;
        Catalog.Entry c = Catalog.TRACKS.get(location.getPath());
        t = c != null
                ? new Track(location, c.title(), c.artist(), c.album().name(), Art.block(c.album().texture()), null, c.seconds())
                : new Track(location, Catalog.titleFromPath(location.getPath()), Catalog.UNKNOWN_ARTIST,
                        Catalog.UNKNOWN_ALBUM.name(), Art.block(Catalog.UNKNOWN_ALBUM.texture()), null,
                        MEASURED.getOrDefault(location, Double.NaN));
        if (Double.isNaN(t.seconds())) measure(t);
        return t;
    }

    private static Identifier file(Identifier track) {
        return track.withPath(p -> "sounds/" + p + ".ogg");
    }

    private static PackInfo pack(PackResources source) {
        String id = source.packId();
        PackInfo cached = PACK_CACHE.get(id);
        if (cached != null) return cached;
        Pack pack = Minecraft.getInstance().getResourcePackRepository().getPack(id);
        String raw = pack != null ? pack.getTitle().getString() : source.location().title().getString();
        String name = PackNames.prettify(raw.isBlank() ? id : raw);
        String author = pack != null ? PackNames.author(pack.getDescription().getString()) : null;
        PackInfo info = new PackInfo(id, name, author, icon(source, name));
        PACK_CACHE.put(id, info);
        return info;
    }

    /** The pack's pack.png as a texture, or initials on a gradient if it has none. */
    private static Art icon(PackResources source, String name) {
        IoSupplier<InputStream> png = source.getRootResource("pack.png");
        if (png != null) {
            try (InputStream in = png.get()) {
                NativeImage image = NativeImage.read(in);
                Identifier id = Identifier.fromNamespaceAndPath("cuemymusic", "pack_icon/" + iconCount++);
                int w = image.getWidth(), h = image.getHeight();
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "cuemymusic " + name, image));
                return new Art.Texture(id, w, h);
            } catch (Exception e) {
                LOG.warn("Couldn't load the icon of {}", source.packId(), e);
            }
        }
        return Art.initials(name);
    }

    private static void measure(Track track) {
        Identifier file = file(track.location());
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
