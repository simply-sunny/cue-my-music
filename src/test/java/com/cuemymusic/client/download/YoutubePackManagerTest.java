package com.cuemymusic.client.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubePackManagerTest {

    @Test
    void packStructureCreatedAndValid(@TempDir Path tempDir) throws Exception {
        var packRoot = tempDir.resolve("cue-my-music-pack");
        var manager = new YoutubePackManager(packRoot);

        manager.ensurePackStructure();

        assertTrue(Files.exists(packRoot.resolve("pack.mcmeta")), "pack.mcmeta must exist");
        Path soundsJson = packRoot.resolve("assets/cue_my_music/sounds.json");
        assertTrue(Files.exists(soundsJson), "sounds.json must exist");
        assertTrue(Files.isDirectory(manager.getSoundsDir()), "sounds directory must exist");

        String json = Files.readString(soundsJson);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(114, obj.keySet().size(), "sounds.json must contain all 114 sound events");

        // Verify a specific event
        assertTrue(obj.has("youtube.20_million_villager_song"));
        var entry = obj.getAsJsonObject("youtube.20_million_villager_song");
        var sounds = entry.getAsJsonArray("sounds");
        assertEquals(1, sounds.size());
        var soundObj = sounds.get(0).getAsJsonObject();
        assertEquals("cue_my_music:youtube/20_million_villager_song", soundObj.get("name").getAsString());
        assertEquals(false, soundObj.get("stream").getAsBoolean());
    }

    @Test
    void isTrackReadyChecksSize(@TempDir Path tempDir) throws Exception {
        var packRoot = tempDir.resolve("cue-my-music-pack");
        var manager = new YoutubePackManager(packRoot);
        manager.ensurePackStructure();

        assertFalse(manager.isTrackReady("test_slug"));

        Path testFile = manager.getSoundFile("test_slug");
        Files.write(testFile, new byte[500]); // < 1024 bytes
        assertFalse(manager.isTrackReady("test_slug"));

        Files.write(testFile, new byte[2048]); // > 1024 bytes
        assertTrue(manager.isTrackReady("test_slug"));
    }
}
