package com.cuemymusic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAudioDriverContractTest {
    @Test
    void clientAudioDriverIsOptInAndExcludedFromJar() throws Exception {
        var source = Files.readString(Path.of("src/client/java/com/cuemymusic/client/CueMyMusicClient.java"));
        assertTrue(source.contains("cuemymusic.autotest"));
        assertTrue(source.contains("Class.forName"));
        var gradle = Files.readString(Path.of("build.gradle"));
        assertTrue(gradle.contains("com/cuemymusic/client/testing/**"));
    }

    @Test
    void jarExcludesDriverClassIfJarExists() throws Exception {
        var jarPath = Path.of("build/libs/cue-my-music-1.0.0.jar");
        if (Files.exists(jarPath)) {
            try (var jar = new java.util.jar.JarFile(jarPath.toFile())) {
                var entry = jar.getEntry("com/cuemymusic/client/testing/AutomatedClientAudioDriver.class");
                org.junit.jupiter.api.Assertions.assertNull(entry, "Production jar must not contain AutomatedClientAudioDriver");
            }
        }
    }

    @Test
    void productionJarExcludesTestingEntriesAndManifest() throws Exception {
        var jarPath = Path.of("build/libs/cue-my-music-1.0.0-pre-queue.jar");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(jarPath), "Production jar must exist - run :jar before :test: " + jarPath);
        try (var jar = new java.util.jar.JarFile(jarPath.toFile())) {
            var hasTestingEntry = jar.stream().anyMatch(e -> e.getName().contains("client/testing"));
            org.junit.jupiter.api.Assertions.assertFalse(hasTestingEntry, "Production jar must not contain client/testing entries");
            var manifest = jar.getManifest();
            org.junit.jupiter.api.Assertions.assertNotNull(manifest, "Manifest must exist");
            var val = manifest.getMainAttributes().getValue("Fabric-Loom-Client-Only-Entries");
            if (val != null) {
                org.junit.jupiter.api.Assertions.assertFalse(val.contains("client/testing"),
                        "Manifest Fabric-Loom-Client-Only-Entries must not contain client/testing, but was: " + val);
            }
        }
    }

    @Test
    void driverEnumHasExactFourteenPhases() {
        var states = com.cuemymusic.client.testing.AutomatedClientAudioDriver.State.values();
        org.junit.jupiter.api.Assertions.assertEquals(14, states.length);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(
                "WAIT_READY", "BASELINE", "PLAY_A", "WAIT_A", "SEEK_A", "WAIT_SEEK",
                "SWITCH_B", "WAIT_B", "PAUSE_B", "WAIT_PAUSE", "RESUME_B", "WAIT_RESUME", "FINISH", "FAILED"
        ), java.util.Arrays.stream(states).map(Enum::name).toList());
    }

    @Test
    void formatEventJsonMatchesExpectedSchema() {
        String jsonReady = com.cuemymusic.client.testing.AutomatedClientAudioDriver.formatEventJson(
                "WAIT_READY", 1700000000000L, 12345L, null, null, true, null);
        org.junit.jupiter.api.Assertions.assertEquals(
                "{\"phase\":\"WAIT_READY\",\"epochMs\":1700000000000,\"pid\":12345,\"trackId\":null,\"positionSeconds\":null,\"success\":true,\"error\":null}",
                jsonReady);

        String jsonSeek = com.cuemymusic.client.testing.AutomatedClientAudioDriver.formatEventJson(
                "SEEK_A", 1700000005000L, null, "vanilla:sweden", 60.0, true, null);
        org.junit.jupiter.api.Assertions.assertEquals(
                "{\"phase\":\"SEEK_A\",\"epochMs\":1700000005000,\"pid\":null,\"trackId\":\"vanilla:sweden\",\"positionSeconds\":60.0,\"success\":true,\"error\":null}",
                jsonSeek);

        String jsonFail = com.cuemymusic.client.testing.AutomatedClientAudioDriver.formatEventJson(
                "FAILED", 1700000010000L, null, null, null, false, "Seek failed: timeout");
        org.junit.jupiter.api.Assertions.assertEquals(
                "{\"phase\":\"FAILED\",\"epochMs\":1700000010000,\"pid\":null,\"trackId\":null,\"positionSeconds\":null,\"success\":false,\"error\":\"Seek failed: timeout\"}",
                jsonFail);
    }
}
