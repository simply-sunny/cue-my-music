package com.cuemymusic.client.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubeDownloaderTest {

    @Test
    void statusTransitions(@TempDir Path tempDir) throws Exception {
        var packManager = new YoutubePackManager(tempDir);
        packManager.ensurePackStructure();

        var downloader = new YoutubeDownloader(packManager, cmd -> 0); // Mock successful runner

        assertEquals(YoutubeDownloader.DownloadStatus.MISSING, downloader.getStatus("20_million_villager_song"));

        // Simulate ready file
        Files.write(packManager.getSoundFile("20_million_villager_song"), new byte[2048]);
        assertEquals(YoutubeDownloader.DownloadStatus.READY, downloader.getStatus("20_million_villager_song"));
    }

    @Test
    void buildCommandContainsExpectedArguments(@TempDir Path tempDir) {
        var packManager = new YoutubePackManager(tempDir);
        var downloader = new YoutubeDownloader(packManager, cmd -> 0);

        List<String> cmd = downloader.buildCommand("20_million_villager_song", "The 20 Million Villager Song");
        assertEquals("yt-dlp", cmd.get(0));
        assertTrue(cmd.contains("-x"));
        assertTrue(cmd.contains("--audio-format"));
        assertTrue(cmd.contains("ogg"));
        assertTrue(cmd.contains("ytsearch1:The 20 Million Villager Song"));
        assertTrue(cmd.stream().anyMatch(s -> s.endsWith("20_million_villager_song.ogg")));
    }
}
