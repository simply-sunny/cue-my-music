package com.cuemymusic.client.music;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Dynamic weighted catalog over the loaded sound graph.
 *
 * <p>Pools are the loaded music events (paths equal to {@code music} or
 * beginning {@code music.}); every non-silence file becomes an occurrence
 * carrying its exact native selection probability. Occurrences sharing a
 * resource id group into one track whose probability sums its occurrences,
 * so duplicate candidates are never discarded. Display credit mirrors the
 * widget: the native translation when present, otherwise the final path
 * segment with a null composer. No hardcoded track list is involved.
 */
public final class WeightedMusicCatalog {
    /** One concrete file candidate with its native selection probability. */
    public record Occurrence(String resourceId, Sound sound, double nativeProbability) {
    }

    /** Occurrences sharing a resource id, probability summed. */
    public record Track(String resourceId, String title, String composer,
            double nativeProbability, List<Occurrence> occurrences) {
    }

    /** One music event: its grouped tracks plus every flat occurrence. */
    public record Pool(String id, List<Track> tracks, List<Occurrence> occurrences) {
    }

    private final List<Pool> pools;
    private final Map<String, Pool> byId;

    private WeightedMusicCatalog(List<Pool> pools) {
        this.pools = List.copyOf(pools);
        Map<String, Pool> index = new LinkedHashMap<>();
        for (Pool pool : pools) {
            index.put(pool.id(), pool);
        }
        this.byId = Map.copyOf(index);
    }

    /** Catalog with no pools. */
    public static WeightedMusicCatalog empty() {
        return new WeightedMusicCatalog(List.of());
    }

    /**
     * Discovers every loaded music pool via the public event-registry key
     * set, resolving each event through the public accessor.
     */
    public static WeightedMusicCatalog discover(SoundManager sounds) {
        return fromEvents(sounds.getAvailableSounds(), sounds::getSoundEvent,
                MusicGraph::nestedEventId, MusicGraph::nestedDefinition);
    }

    /**
     * Test seam: builds the catalog from an explicit id collection and
     * lookups instead of the live sound manager.
     */
    static WeightedMusicCatalog fromEvents(
            Collection<Identifier> eventIds,
            Function<Identifier, WeighedSoundEvents> eventLookup,
            Function<Weighted<Sound>, Identifier> nestedEventOf,
            Function<Weighted<Sound>, Sound> nestedDefinitionOf) {
        List<Identifier> musicIds = eventIds.stream()
                .filter(id -> id.getPath().equals("music") || id.getPath().startsWith("music."))
                .sorted()
                .toList();
        List<Pool> pools = new ArrayList<>();
        for (Identifier eventId : musicIds) {
            WeighedSoundEvents event = eventLookup.apply(eventId);
            if (event == null) {
                continue;
            }
            List<MusicGraph.WeightedLeaf> leaves =
                    MusicGraph.weightedLeaves(event, eventLookup, nestedEventOf, nestedDefinitionOf);
            Map<String, List<Occurrence>> byResource = new LinkedHashMap<>();
            List<Occurrence> occurrences = new ArrayList<>();
            for (MusicGraph.WeightedLeaf leaf : leaves) {
                String resourceId = leaf.sound().getPath().toString();
                Occurrence occurrence =
                        new Occurrence(resourceId, leaf.sound(), leaf.nativeProbability());
                occurrences.add(occurrence);
                byResource.computeIfAbsent(resourceId, key -> new ArrayList<>()).add(occurrence);
            }
            List<Track> tracks = new ArrayList<>();
            for (Map.Entry<String, List<Occurrence>> grouped : byResource.entrySet()) {
                List<Occurrence> groupedOccurrences = grouped.getValue();
                double total = 0.0;
                for (Occurrence occurrence : groupedOccurrences) {
                    total += occurrence.nativeProbability();
                }
                Sound first = groupedOccurrences.get(0).sound();
                String key = first.getLocation().toShortLanguageKey().replace('/', '.');
                Language language = Language.getInstance();
                String title;
                String composer;
                if (language.has(key)) {
                    MusicDirector.TrackInfo credit =
                            MusicDirector.splitCredit(language.getOrDefault(key));
                    title = credit.title();
                    composer = credit.artist();
                } else {
                    String path = first.getPath().getPath();
                    int slash = path.lastIndexOf('/');
                    title = slash >= 0 ? path.substring(slash + 1) : path;
                    composer = null;
                }
                tracks.add(new Track(grouped.getKey(), title, composer, total,
                        List.copyOf(groupedOccurrences)));
            }
            tracks.sort(Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Track::resourceId));
            pools.add(new Pool(eventId.toString(), List.copyOf(tracks), List.copyOf(occurrences)));
        }
        return new WeightedMusicCatalog(pools);
    }

    /** All pools, sorted by id. */
    public List<Pool> pools() {
        return pools;
    }

    /** Pool by event id string, when present. */
    public Optional<Pool> pool(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    private record AdjustedOccurrence(Occurrence occurrence, double weight) {
    }

    private static List<AdjustedOccurrence> adjustedOccurrences(
            Pool pool, TrackWeightConfig config, String previousResourceId) {
        if (pool == null || pool.occurrences() == null || pool.occurrences().isEmpty()) {
            return List.of();
        }
        List<Occurrence> occurrences = pool.occurrences();
        int size = occurrences.size();
        double[] weights = new double[size];
        boolean hasDifferentPositiveResource = false;
        for (int i = 0; i < size; i++) {
            Occurrence occ = occurrences.get(i);
            double prob = occ.nativeProbability();
            if (Double.isFinite(prob) && prob > 0.0) {
                double mult = config != null
                        ? config.multiplier(pool.id(), occ.resourceId())
                        : TrackWeightConfig.DEFAULT_MULTIPLIER;
                if (Double.isFinite(mult) && mult > 0.0) {
                    double w = prob * mult;
                    if (Double.isFinite(w) && w > 0.0) {
                        weights[i] = w;
                        if (previousResourceId != null && !occ.resourceId().equals(previousResourceId)) {
                            hasDifferentPositiveResource = true;
                        }
                    }
                }
            }
        }
        boolean antiRepeat = config != null && config.antiRepeat();
        if (antiRepeat && previousResourceId != null && hasDifferentPositiveResource) {
            for (int i = 0; i < size; i++) {
                if (occurrences.get(i).resourceId().equals(previousResourceId)) {
                    weights[i] = 0.0;
                }
            }
        }
        List<AdjustedOccurrence> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new AdjustedOccurrence(occurrences.get(i), weights[i]));
        }
        return result;
    }

    /**
     * Selects one occurrence from the pool according to native probabilities,
     * configured multipliers, and Anti-Repeat state.
     */
    public static Optional<Occurrence> select(
            Pool pool, TrackWeightConfig config, String previousResourceId, long seed) {
        if (pool == null) {
            return Optional.empty();
        }
        List<AdjustedOccurrence> adjusted = adjustedOccurrences(pool, config, previousResourceId);
        double total = 0.0;
        for (AdjustedOccurrence entry : adjusted) {
            total += entry.weight();
        }
        if (!Double.isFinite(total) || total <= 0.0) {
            return Optional.empty();
        }
        double threshold = RandomSource.create(seed).nextDouble() * total;
        double accumulated = 0.0;
        Occurrence lastPositive = null;
        for (AdjustedOccurrence entry : adjusted) {
            if (entry.weight() <= 0.0) {
                continue;
            }
            lastPositive = entry.occurrence();
            accumulated += entry.weight();
            if (accumulated > threshold) {
                return Optional.of(entry.occurrence());
            }
        }
        return Optional.ofNullable(lastPositive);
    }

    /**
     * Computes the normalized chance of each track in the pool according to
     * native probabilities, configured multipliers, and Anti-Repeat state.
     */
    public static Map<String, Double> chances(
            Pool pool, TrackWeightConfig config, String previousResourceId) {
        if (pool == null) {
            return Map.of();
        }
        List<AdjustedOccurrence> adjusted = adjustedOccurrences(pool, config, previousResourceId);
        Map<String, Double> result = new LinkedHashMap<>();
        if (pool.tracks() != null) {
            for (Track track : pool.tracks()) {
                result.put(track.resourceId(), 0.0);
            }
        }
        for (AdjustedOccurrence entry : adjusted) {
            result.putIfAbsent(entry.occurrence().resourceId(), 0.0);
        }
        double total = 0.0;
        for (AdjustedOccurrence entry : adjusted) {
            if (entry.weight() > 0.0) {
                total += entry.weight();
                result.put(entry.occurrence().resourceId(),
                        result.get(entry.occurrence().resourceId()) + entry.weight());
            }
        }
        if (Double.isFinite(total) && total > 0.0) {
            for (Map.Entry<String, Double> entry : result.entrySet()) {
                entry.setValue(entry.getValue() / total);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Projects upcoming selections by simulating deterministic choices forward,
     * advancing local previous resource state without mutating any planner or config.
     */
    public static List<Occurrence> project(
            Pool pool, TrackWeightConfig config, String previousResourceId,
            long sessionSeed, long startIndex, int count) {
        if (pool == null || count <= 0) {
            return List.of();
        }
        List<Occurrence> projected = new ArrayList<>(count);
        String currentPrevious = previousResourceId;
        for (int step = 0; step < count; step++) {
            long index = startIndex + step;
            long seed = MusicPlanner.selectionSeed(sessionSeed, pool.id(), index);
            Optional<Occurrence> choice = select(pool, config, currentPrevious, seed);
            if (choice.isEmpty()) {
                break;
            }
            Occurrence occurrence = choice.get();
            projected.add(occurrence);
            currentPrevious = occurrence.resourceId();
        }
        return List.copyOf(projected);
    }
}
