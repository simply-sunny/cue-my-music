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
        // and the 6 approved track-weighting production files:
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/music/TrackWeightConfig.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/music/TrackPreviewController.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/ui/TrackWeightScreen.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/ui/RadialWeightWidget.java")));
        assertTrue(Files.exists(SRC.resolve("client/java/com/cuemymusic/client/ui/TrackPreviewPlayer.java")));
        assertTrue(productionFiles <= 26, "expected approved production surface, found " + productionFiles);
    }

    @Test void configAndCatalogAllowedOnlyForTrackWeightingSubsystem() throws Exception {
        List<String> filesWithConfigOrCatalog;
        try (Stream<Path> files = Files.walk(SRC)) {
            filesWithConfigOrCatalog = files
                    .filter(Files::isRegularFile)
                    .map(SRC::relativize)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".java")
                            && (name.startsWith("main" + java.io.File.separator)
                                    || name.startsWith("client" + java.io.File.separator)))
                    .filter(name -> {
                        String lower = name.toLowerCase();
                        return lower.contains("config") || lower.contains("catalog");
                    })
                    .sorted()
                    .toList();
        }
        assertEquals(
                List.of(
                        Path.of("client/java/com/cuemymusic/client/music/TrackWeightConfig.java").toString(),
                        Path.of("client/java/com/cuemymusic/client/music/WeightedMusicCatalog.java").toString()),
                filesWithConfigOrCatalog);
    }

    @Test void playbackRateUsesOwnedChannelWithoutDspOrPreviewWrapping() throws Exception {
        Path music = SRC.resolve("client/java/com/cuemymusic/client/music");
        assertFalse(Files.exists(music.resolve("WsolaAudioStream.java")));

        String engine = Files.readString(music.resolve("EngineTransport.java"));
        assertTrue(engine.contains("cueMyMusic$setInstancePitch"));

        String audible = Files.readString(SRC.resolve(
                "client/java/com/cuemymusic/mixin/ChannelAudibleMixin.java"));
        assertTrue(audible.contains("TaggedStream"));
        assertTrue(audible.contains("playbackRate"));

        String buffers = Files.readString(SRC.resolve(
                "client/java/com/cuemymusic/mixin/SoundBufferLibraryMixin.java"));
        assertFalse(buffers.contains("WsolaAudioStream"));

        try (Stream<Path> files = Files.walk(SRC.resolve("client/java"))) {
            String production = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (java.io.IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(production.contains("SourceMode"));
            assertFalse(production.contains("Crossfade"));
            assertFalse(production.contains("pitchSemitones"));
        }
    }

    @Test void radialWeightWidgetRemainsRendererAgnostic() throws Exception {
        Path path = SRC.resolve("client/java/com/cuemymusic/client/ui/RadialWeightWidget.java");
        assertTrue(Files.exists(path), "RadialWeightWidget.java must exist");
        String source = Files.readString(path);
        assertFalse(source.contains("RenderSystem"), "RadialWeightWidget must not use RenderSystem");
        assertFalse(source.contains("com.mojang.blaze3d.systems"), "RadialWeightWidget must not import blaze3d systems");
        assertFalse(source.contains("org.lwjgl"), "RadialWeightWidget must not import LWJGL / GL");
        assertFalse(source.contains("GL11") || source.contains("GL20") || source.contains("GL30"),
                "RadialWeightWidget must not use GL");
        assertFalse(source.contains("net.fabricmc.fabric.api.client.rendering"),
                "RadialWeightWidget must not use Fabric rendering API");
        assertFalse(source.contains("net.fabricmc.fabric.api.renderer"),
                "RadialWeightWidget must not use Fabric renderer API");
        assertFalse(source.contains("RenderPipeline"), "RadialWeightWidget must not use RenderPipeline");
        assertFalse(source.contains("Shader"), "RadialWeightWidget must not use Shaders");
        assertFalse(source.toLowerCase().contains("vulkan"), "RadialWeightWidget must not use Vulkan");
        assertFalse(source.toLowerCase().contains("metal"), "RadialWeightWidget must not use Metal");
    }
}
