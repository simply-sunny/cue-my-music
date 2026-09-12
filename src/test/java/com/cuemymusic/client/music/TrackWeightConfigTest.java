package com.cuemymusic.client.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;

class TrackWeightConfigTest {

    @Test void defaultsAndImmutableUpdates() {
        TrackWeightConfig base = TrackWeightConfig.defaults();
        TrackWeightConfig changed = base.withMultiplier("minecraft:music.game", "minecraft:music/game/sweden", 2.0);
        assertTrue(base.antiRepeat());
        assertEquals(1.0, base.multiplier("minecraft:music.game", "minecraft:music/game/sweden"));
        assertEquals(2.0, changed.multiplier("minecraft:music.game", "minecraft:music/game/sweden"));
    }

    @Test void validationClampsFiniteValuesAndDefaultsMalformedValues(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("cue-my-music.json");
        Files.writeString(file, "{\"version\":1,\"antiRepeat\":false,\"weights\":{\"p\":{\"high\":99,\"low\":-2,\"bad\":\"x\"}}}");
        TrackWeightConfig config = TrackWeightConfig.load(file);
        assertFalse(config.antiRepeat());
        assertEquals(10.0, config.multiplier("p", "high"));
        assertEquals(0.0, config.multiplier("p", "low"));
        assertEquals(1.0, config.multiplier("p", "bad"));
    }

    @Test void saveRoundTripsUnknownEntriesAtomically(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("cue-my-music.json");
        TrackWeightConfig expected = TrackWeightConfig.defaults()
                .withMultiplier("pack:music.custom", "pack:music/custom/song", 3.5);
        expected.save(file);
        assertEquals(expected, TrackWeightConfig.load(file));
        assertFalse(Files.exists(temp.resolve("cue-my-music.json.tmp")));
    }

    @Test void malformedTopLevelJsonReturnsDefaultsWithoutRewriting(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("cue-my-music.json");
        String malformed = "not json {{{";
        Files.writeString(file, malformed);
        TrackWeightConfig config = TrackWeightConfig.load(file);
        assertEquals(TrackWeightConfig.defaults(), config);
        assertEquals(malformed, Files.readString(file));
    }

    @Test void unsupportedVersionReturnsDefaults(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("cue-my-music.json");
        Files.writeString(file, "{\"version\":999,\"antiRepeat\":false,\"weights\":{}}");
        assertEquals(TrackWeightConfig.defaults(), TrackWeightConfig.load(file));
    }

    @Test void toJsonEmitsVersionAndRetainsUnknownEntries() {
        TrackWeightConfig config = TrackWeightConfig.defaults()
                .withMultiplier("pack:music.custom", "pack:music/custom/song", 3.5);
        JsonObject root = JsonParser.parseString(config.toJson()).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertTrue(root.get("antiRepeat").getAsBoolean());
        assertEquals(3.5, root.getAsJsonObject("weights")
                .getAsJsonObject("pack:music.custom")
                .get("pack:music/custom/song").getAsDouble());
    }

    @Test void constructorDeepCopiesAndWithersPreserveSiblings() {
        Map<String, Map<String, Double>> outer = new HashMap<>();
        Map<String, Double> inner = new HashMap<>();
        inner.put("a", 2.0);
        outer.put("pool", inner);
        TrackWeightConfig config = new TrackWeightConfig(true, outer);
        outer.clear();
        inner.put("a", 9.0);
        assertEquals(2.0, config.multiplier("pool", "a"));

        TrackWeightConfig merged = config
                .withPoolMultipliers("pool", Map.of("b", 3.0))
                .withAntiRepeat(false);
        assertEquals(2.0, merged.multiplier("pool", "a"));
        assertEquals(3.0, merged.multiplier("pool", "b"));
        assertFalse(merged.antiRepeat());
        assertEquals(1.0, config.multiplier("pool", "b"));
        assertTrue(config.antiRepeat());
    }

    @Test void malformedEntriesAreIndependentPerPoolAndTrack(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("cue-my-music.json");
        Files.writeString(file, """
                {"version":1,"antiRepeat":true,"weights":{\
                "good":{"track":2.0},\
                "mixed":{"ok":3.0,"broken":"x"},\
                "broken-pool":[1,2],\
                "also-good":{"other":4.0}}}""");
        TrackWeightConfig config = TrackWeightConfig.load(file);
        assertEquals(2.0, config.multiplier("good", "track"));
        assertEquals(3.0, config.multiplier("mixed", "ok"));
        assertEquals(1.0, config.multiplier("mixed", "broken"));
        assertEquals(4.0, config.multiplier("also-good", "other"));
    }

    @Test void nonBooleanAntiRepeatFallsBackToDefault(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("cue-my-music.json");
        Files.writeString(file, "{\"version\":1,\"antiRepeat\":\"yes\",\"weights\":{}}");
        assertTrue(TrackWeightConfig.load(file).antiRepeat());
    }

    @Test void missingFileLoadsDefaults(@TempDir Path temp) {
        assertEquals(TrackWeightConfig.defaults(),
                TrackWeightConfig.load(temp.resolve("cue-my-music.json")));
    }

    @Test void weightsViewIsDeeplyImmutable() {
        TrackWeightConfig config = TrackWeightConfig.defaults()
                .withMultiplier("pool", "track", 2.0);
        assertThrows(UnsupportedOperationException.class, () -> config.weights().put("x", Map.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> config.weights().get("pool").put("x", 1.0));
        Map<String, Map<String, Double>> exposed = new LinkedHashMap<>(config.weights());
        assertEquals(config, new TrackWeightConfig(config.antiRepeat(), exposed));
    }
}
