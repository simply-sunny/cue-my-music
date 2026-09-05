package com.cuemymusic.client.music;

import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import com.cuemymusic.data.YoutubeCatalogEntry;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class YoutubeTrackRegistry {
    private static final List<YoutubeCatalogEntry> CATALOG = new ArrayList<>();
    private static final Map<String, YoutubeCatalogEntry> BY_SLUG = new LinkedHashMap<>();

    static {
        try (var stream = YoutubeTrackRegistry.class.getResourceAsStream("/assets/cue_my_music/youtube_catalog.json")) {
            if (stream != null) {
                List<YoutubeCatalogEntry> list = new Gson().fromJson(
                        new InputStreamReader(stream, StandardCharsets.UTF_8),
                        new TypeToken<List<YoutubeCatalogEntry>>(){}.getType()
                );
                if (list != null) {
                    CATALOG.addAll(list);
                    for (var entry : list) {
                        BY_SLUG.put(entry.slug(), entry);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Cue My Music] Failed to load youtube catalog: " + e.getMessage());
        }
    }

    private YoutubeTrackRegistry() {}

    public static List<YoutubeCatalogEntry> getCatalog() {
        return List.copyOf(CATALOG);
    }

    public static Optional<YoutubeCatalogEntry> getBySlug(String slug) {
        return Optional.ofNullable(BY_SLUG.get(slug));
    }

    public static int trackCount() {
        return CATALOG.size();
    }

    public static void registerAll(MusicLibrary library) {
        for (var entry : CATALOG) {
            String id = "youtube:" + entry.slug();
            if (library.getTrack(id).isPresent()) continue;
            MusicTrack track = new MusicTrack(id, entry.title(), entry.artist(), SourceType.YOUTUBE);
            track.setSourceId("cue_my_music:youtube." + entry.slug());
            track.setEnabled(true);
            track.setAmbientEligible(false);
            library.addOrReplaceTrack(track);
        }
    }
}
