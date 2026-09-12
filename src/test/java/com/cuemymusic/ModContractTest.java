package com.cuemymusic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reduction contract: the shipped mod must contain only the deterministic
 * vanilla-music product plus the explicitly user-approved Pause transport
 * (boxed panel, Play/Pause, scrub bar, Previous/Next/End) — no
 * library/search/config/persistence/YouTube systems, required Mod Menu integration,
 * and no download scripts or catalogs.
 */
class ModContractTest {

    private static final Path PROJECT = Path.of(".").toAbsolutePath().normalize();
    private static final Path SRC = PROJECT.resolve("src");

    @Test void noObsoleteProductionSourcesRemain() throws Exception {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(SRC)) {
            offenders = files
                    .filter(Files::isRegularFile)
                    .map(SRC::relativize)
                    .map(Path::toString)
                    .filter(name -> {
                        String lower = name.toLowerCase();
                        return lower.contains("youtube") || lower.contains("download")
                                || lower.contains("jukebox") || lower.contains("playbackslider")
                                || lower.contains("trackdetail")
                                || lower.contains("persist") || lower.contains("musiclibrary")
                                || lower.contains("musictrack")
                                || lower.contains("bufferedplayback") || lower.contains("playbacklifecycle")
                                || lower.contains("nativeminecraftplayback")
                                || lower.contains("automatedclient")
                                || lower.contains("channelaccessor") || lower.contains("channelhandle")
                                || lower.contains("soundengineaccessor") || lower.contains("soundmanageraccessor")
                                || lower.contains("vanillatrackregistry");
                    })
                    .sorted()
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "obsolete sources remain: " + offenders);
    }

    @Test void noDownloadScriptsOrCatalogRemain() {
        assertFalse(Files.exists(PROJECT.resolve("download_music.py")), "download_music.py must be deleted");
        assertFalse(Files.exists(PROJECT.resolve("download_youtube.py")), "download_youtube.py must be deleted");
        assertFalse(Files.exists(SRC.resolve("main/resources/assets/cue_my_music/youtube_catalog.json")),
                "youtube_catalog.json must be deleted");
    }

    @Test void modMetadataRequiresAnyCompatibleModMenu() throws Exception {
        String json = Files.readString(SRC.resolve("main/resources/fabric.mod.json"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject entrypoints = root.getAsJsonObject("entrypoints");
        assertEquals("com.cuemymusic.client.CueMyMusicModMenu",
                entrypoints.getAsJsonArray("modmenu").get(0).getAsString());
        assertFalse(entrypoints.has("main"), "pure-client mod must not ship a common initializer");
        assertTrue(entrypoints.has("client"), "client entrypoint must remain");
        assertEquals("*", root.getAsJsonObject("depends").get("modmenu").getAsString());
    }

    @Test void mixinConfigListsOnlySurvivingMixins() throws Exception {
        String json = Files.readString(SRC.resolve("main/resources/cue_my_music.mixins.json"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<String> client = new java.util.ArrayList<>();
        root.getAsJsonArray("client").forEach(element -> client.add(element.getAsString()));
        // Four reduction survivors plus the three explicitly approved
        // transport mixins (offset-stream redirect + per-song pause gate +
        // audible-transition hooks + engine lookup). No channel-handle or
        // sound-manager/engine accessor mixins beyond these narrow names.
        assertEquals(
                List.of("ChannelAudibleMixin", "MusicManagerAccessor", "MusicManagerMixin",
                        "NestedSoundAccessor", "SoundBufferLibraryMixin", "SoundEngineProvider",
                        "SoundEngineTransportMixin", "WeighedSoundEventsAccessor"),
                client.stream().sorted().toList());
        assertFalse(client.stream().anyMatch(name -> name.contains("ChannelHandle")
                || name.contains("SoundEngineAccessor") || name.contains("SoundManagerAccessor")));
    }

    @Test void productionFileCountStaysSmall() throws Exception {
        long productionFiles;
        try (Stream<Path> files = Files.walk(SRC)) {
            productionFiles = files
                    .filter(Files::isRegularFile)
                    .map(SRC::relativize)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".java")
                            && (name.startsWith("main" + java.io.File.separator)
                                    || name.startsWith("client" + java.io.File.separator)))
                    .count();
        }
        // Minimal surface: reduction survivors, transport, two Mod Menu screen adapters,
        // and the approved track-weighting config (Task 1; catalog, settings screen,
        // and radial widget assertions arrive in Tasks 2, 5, and 7).
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/music/TrackWeightConfig.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/ui/TrackWeightScreen.java")));
        assertTrue(productionFiles <= 24, "expected approved production surface, found " + productionFiles);
    }
}
