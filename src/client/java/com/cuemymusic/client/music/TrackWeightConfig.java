package com.cuemymusic.client.music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Immutable track-weighting settings and their atomic JSON persistence.
 *
 * <p>Weights are keyed by opaque pool/track identifier strings and act as
 * multipliers in the range {@code [0.0, 10.0]}; unknown entries default to
 * {@code 1.0} and are retained verbatim across load/save round-trips.
 */
public record TrackWeightConfig(boolean antiRepeat, Map<String, Map<String, Double>> weights) {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/weights");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int VERSION = 1;

    /** Upper bound applied to stored multipliers. */
    public static final double MAX_MULTIPLIER = 10.0;

    /** Default multiplier for unknown or malformed entries. */
    public static final double DEFAULT_MULTIPLIER = 1.0;

    public TrackWeightConfig {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        weights.forEach((pool, tracks) -> {
            Map<String, Double> valid = new LinkedHashMap<>();
            tracks.forEach((track, value) -> valid.put(track, normalize(value)));
            copy.put(pool, Map.copyOf(valid));
        });
        weights = Map.copyOf(copy);
    }

    private static double normalize(Double value) {
        return value == null || !Double.isFinite(value) ? DEFAULT_MULTIPLIER : Math.clamp(value, 0.0, MAX_MULTIPLIER);
    }

    public static TrackWeightConfig defaults() {
        return new TrackWeightConfig(true, Map.of());
    }

    public static Path defaultPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("cue-my-music.json");
    }

    public double multiplier(String poolId, String resourceId) {
        return weights.getOrDefault(poolId, Map.of()).getOrDefault(resourceId, DEFAULT_MULTIPLIER);
    }

    public TrackWeightConfig withMultiplier(String poolId, String resourceId, double value) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        weights.forEach((pool, tracks) -> copy.put(pool, new LinkedHashMap<>(tracks)));
        copy.computeIfAbsent(poolId, key -> new LinkedHashMap<>()).put(resourceId, value);
        return new TrackWeightConfig(antiRepeat, copy);
    }

    public TrackWeightConfig withPoolMultipliers(String poolId, Map<String, Double> multipliers) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        weights.forEach((pool, tracks) -> copy.put(pool, new LinkedHashMap<>(tracks)));
        copy.computeIfAbsent(poolId, key -> new LinkedHashMap<>()).putAll(multipliers);
        return new TrackWeightConfig(antiRepeat, copy);
    }

    public TrackWeightConfig withAntiRepeat(boolean value) {
        return new TrackWeightConfig(value, weights);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("antiRepeat", antiRepeat);
        JsonObject serialized = new JsonObject();
        weights.forEach((pool, tracks) -> {
            JsonObject entries = new JsonObject();
            tracks.forEach(entries::addProperty);
            serialized.add(pool, entries);
        });
        root.add("weights", serialized);
        return GSON.toJson(root);
    }

    public void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, toJson(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Best effort: a stale temp file must never fail the save itself.
            }
        }
    }

    public static TrackWeightConfig load(Path path) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new JsonSyntaxException("track weights root must be a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("version")) {
                JsonElement version = root.get("version");
                if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                        || version.getAsInt() != VERSION) {
                    throw new JsonSyntaxException("unsupported track weights version: " + version);
                }
            }
            boolean repeat = true;
            if (root.has("antiRepeat")) {
                JsonElement flag = root.get("antiRepeat");
                if (flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()) {
                    repeat = flag.getAsBoolean();
                }
            }
            Map<String, Map<String, Double>> loaded = new LinkedHashMap<>();
            JsonElement weightsElement = root.get("weights");
            if (weightsElement != null && weightsElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> pool : weightsElement.getAsJsonObject().entrySet()) {
                    if (pool.getValue() == null || !pool.getValue().isJsonObject()) {
                        continue;
                    }
                    Map<String, Double> tracks = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> track : pool.getValue().getAsJsonObject().entrySet()) {
                        try {
                            JsonElement value = track.getValue();
                            if (value != null && value.isJsonPrimitive()
                                    && value.getAsJsonPrimitive().isNumber()) {
                                tracks.put(track.getKey(), value.getAsDouble());
                            }
                        } catch (NumberFormatException | UnsupportedOperationException ignored) {
                            // Malformed track entry: omit it so it defaults to 1x
                            // without dropping valid sibling tracks or pools.
                        }
                    }
                    loaded.put(pool.getKey(), tracks);
                }
            }
            return new TrackWeightConfig(repeat, loaded);
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            LOGGER.warn("Loading default track weights; could not read {}: {}", path, e.toString());
            return defaults();
        }
    }
}
