package com.cuemymusic.client.download;

import com.cuemymusic.client.music.YoutubeTrackRegistry;
import com.cuemymusic.data.YoutubeCatalogEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class YoutubePackManager {
    private final Path packRoot;
    private final Path soundsDir;
    private final Path soundsJsonPath;

    public YoutubePackManager(Path packRoot) {
        this.packRoot = packRoot;
        this.soundsDir = packRoot.resolve("assets/cue_my_music/sounds/youtube");
        this.soundsJsonPath = packRoot.resolve("assets/cue_my_music/sounds.json");
    }

    public Path getPackRoot() { return packRoot; }
    public Path getSoundsDir() { return soundsDir; }
    public Path getSoundFile(String slug) { return soundsDir.resolve(slug + ".ogg"); }

    public boolean isTrackReady(String slug) {
        Path file = getSoundFile(slug);
        try {
            return Files.exists(file) && Files.size(file) > 1024;
        } catch (IOException e) {
            return false;
        }
    }

    public void ensurePackStructure() throws IOException {
        Files.createDirectories(soundsDir);
        Path mcmeta = packRoot.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            String mcmetaJson = """
                {
                  "pack": {
                    "description": "Cue My Music YouTube Audio Pack",
                    "pack_format": 48
                  }
                }
                """;
            Files.writeString(mcmeta, mcmetaJson);
        }
        if (!Files.exists(soundsJsonPath)) {
            Files.createDirectories(soundsJsonPath.getParent());
            Files.writeString(soundsJsonPath, generateSoundsJson());
        }
    }

    public String generateSoundsJson() {
        JsonObject root = new JsonObject();
        List<YoutubeCatalogEntry> catalog = YoutubeTrackRegistry.getCatalog();
        for (var entry : catalog) {
            JsonObject event = new JsonObject();
            JsonArray sounds = new JsonArray();
            JsonObject soundObj = new JsonObject();
            soundObj.addProperty("name", "cue_my_music:youtube/" + entry.slug());
            soundObj.addProperty("stream", false);
            sounds.add(soundObj);
            event.add("sounds", sounds);
            root.add("youtube." + entry.slug(), event);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }
}
