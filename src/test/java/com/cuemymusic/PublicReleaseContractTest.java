package com.cuemymusic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PublicReleaseContractTest {
    @Test void readmeDocumentsFeaturesAndControlsAccurately() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains("Fuzzy search by title/artist/id"));
        assertFalse(readme.contains("source filtering"));
        assertTrue(readme.contains("displays current time and duration"));
        assertFalse(readme.contains("time appears on hover/drag"));
    }

    @Test void publicReleaseMetadataIsAccurate() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        String props = Files.readString(Path.of("gradle.properties"));
        String metadata = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        assertTrue(readme.contains("Minecraft 26.2"));
        assertTrue(readme.contains("Fabric API"));
        assertFalse(readme.contains("Spotify"));
        assertFalse(readme.contains("YouTube"));
        assertTrue(props.contains("version=1.0.0-pre-queue"));
        assertTrue(metadata.contains("\"environment\": \"client\""));
    }
}
