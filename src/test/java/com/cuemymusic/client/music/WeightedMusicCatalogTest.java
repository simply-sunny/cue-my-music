package com.cuemymusic.client.music;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic catalog over the loaded weighted graph: music pools group
 * duplicate resource paths into single tracks without discarding
 * candidates, and non-music events never become pools.
 */
class WeightedMusicCatalogTest {

    /** Test double standing in for the runtime nested-event delegate. */
    static final class NestedStub implements Weighted<Sound> {
        final Identifier target;
        final Sound definition;
        final WeighedSoundEvents subEvent;

        NestedStub(String target, Sound definition, WeighedSoundEvents subEvent) {
            this.target = Identifier.parse(target);
            this.definition = definition;
            this.subEvent = subEvent;
        }

        @Override public int getWeight() { return definition.getWeight(); }

        @Override public Sound getSound(RandomSource random) {
            return subEvent.getSound(random);
        }

        @Override public void preloadIfRequired(net.minecraft.client.sounds.SoundEngine engine) {
        }
    }

    private static Sound file(String name, float volume, float pitch, int weight,
            boolean stream, boolean preload) {
        return new Sound(
                Identifier.parse("minecraft:" + name),
                ConstantFloat.of(volume),
                ConstantFloat.of(pitch),
                weight,
                Sound.Type.FILE,
                stream,
                preload,
                16);
    }

    private static WeighedSoundEvents event(String id, List<Weighted<Sound>> entries) {
        WeighedSoundEvents event = new WeighedSoundEvents(Identifier.parse(id), null);
        for (Weighted<Sound> entry : entries) {
            event.addSound(entry);
        }
        return event;
    }

    private static Function<Weighted<Sound>, Identifier> nestedOf() {
        return entry -> entry instanceof NestedStub stub ? stub.target : null;
    }

    private static Function<Weighted<Sound>, Sound> definitionOf() {
        return entry -> entry instanceof NestedStub stub ? stub.definition : null;
    }

    private WeightedMusicCatalog catalogOf(Map<Identifier, WeighedSoundEvents> registry) {
        Collection<Identifier> ids = registry.keySet();
        return WeightedMusicCatalog.fromEvents(ids, registry::get, nestedOf(), definitionOf());
    }

    private WeightedMusicCatalog catalogWithDuplicateSwedenPaths() {
        Sound first = file("music/game/sweden", 1.0F, 1.0F, 3, false, false);
        Sound second = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(first, second));
        Map<Identifier, WeighedSoundEvents> registry =
                Map.of(Identifier.parse("minecraft:music.game"), root);
        return catalogOf(registry);
    }

    @BeforeEach void injectTestListProvider() {
        MusicGraph.entriesProvider = event -> {
            try {
                Field field = WeighedSoundEvents.class.getDeclaredField("list");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Weighted<Sound>> entries = (List<Weighted<Sound>>) field.get(event);
                return entries;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Test void groupsDuplicateOccurrencesWithoutDiscardingCandidates() {
        WeightedMusicCatalog catalog = catalogWithDuplicateSwedenPaths();
        WeightedMusicCatalog.Pool pool = catalog.pool("minecraft:music.game").orElseThrow();
        WeightedMusicCatalog.Track sweden = pool.tracks().stream()
                .filter(track -> track.resourceId().endsWith("sweden.ogg")).findFirst().orElseThrow();
        assertEquals(2, sweden.occurrences().size());
        assertEquals(sweden.occurrences().stream().mapToDouble(WeightedMusicCatalog.Occurrence::nativeProbability).sum(),
                sweden.nativeProbability(), 1e-9);
    }

    @Test void onlyMusicEventIdsBecomePools() {
        WeighedSoundEvents game = event("minecraft:music.game",
                List.of(file("music/game/sweden", 1.0F, 1.0F, 1, false, false)));
        WeighedSoundEvents exact = event("minecraft:music",
                List.of(file("music/menu", 1.0F, 1.0F, 1, false, false)));
        WeighedSoundEvents block = event("minecraft:block.stone",
                List.of(file("block/stone", 1.0F, 1.0F, 1, false, false)));
        WeighedSoundEvents lookalike = event("minecraft:musical.chairs",
                List.of(file("musical/chairs", 1.0F, 1.0F, 1, false, false)));
        Map<Identifier, WeighedSoundEvents> registry = Map.of(
                Identifier.parse("minecraft:music.game"), game,
                Identifier.parse("minecraft:music"), exact,
                Identifier.parse("minecraft:block.stone"), block,
                Identifier.parse("minecraft:musical.chairs"), lookalike);
        WeightedMusicCatalog catalog = catalogOf(registry);
        assertTrue(catalog.pool("minecraft:music.game").isPresent());
        assertTrue(catalog.pool("minecraft:music").isPresent());
        assertTrue(catalog.pool("minecraft:block.stone").isEmpty());
        assertTrue(catalog.pool("minecraft:musical.chairs").isEmpty());
        assertEquals(2, catalog.pools().size());
    }

    @Test void poolsAndTracksSortDeterministically() {
        WeighedSoundEvents game = event("minecraft:music.game", List.of(
                file("music/game/sweden", 1.0F, 1.0F, 1, false, false),
                file("music/game/clark", 1.0F, 1.0F, 1, false, false)));
        WeighedSoundEvents end = event("minecraft:music.end",
                List.of(file("music/end/alpha", 1.0F, 1.0F, 1, false, false)));
        Map<Identifier, WeighedSoundEvents> registry = Map.of(
                Identifier.parse("minecraft:music.game"), game,
                Identifier.parse("minecraft:music.end"), end);
        WeightedMusicCatalog first = catalogOf(registry);
        WeightedMusicCatalog second = catalogOf(registry);
        assertEquals(
                first.pools().stream().map(WeightedMusicCatalog.Pool::id).toList(),
                second.pools().stream().map(WeightedMusicCatalog.Pool::id).toList());
        assertEquals(
                List.of("minecraft:music.end", "minecraft:music.game"),
                first.pools().stream().map(WeightedMusicCatalog.Pool::id).toList(),
                "pools sort by id");
        WeightedMusicCatalog.Pool gamePool = first.pool("minecraft:music.game").orElseThrow();
        List<String> titles = gamePool.tracks().stream()
                .map(WeightedMusicCatalog.Track::title).toList();
        assertEquals(titles.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), titles,
                "tracks sort case-insensitively by title");
        assertEquals(gamePool.tracks(), first.pool("minecraft:music.game").orElseThrow().tracks(),
                "repeated builds agree exactly");
    }

    @Test void fallbackMetadataUsesFinalPathSegmentWithNullComposer() {
        Sound fictional = file("music/game/zzqfictional", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(fictional));
        Map<Identifier, WeighedSoundEvents> registry =
                Map.of(Identifier.parse("minecraft:music.game"), root);
        WeightedMusicCatalog.Track track = catalogOf(registry).pool("minecraft:music.game").orElseThrow()
                .tracks().get(0);
        assertTrue(track.title().contains("zzqfictional"),
                "fallback title derives from the resource path, got " + track.title());
        assertNull(track.composer(), "resource fallback has no composer");
    }

    @Test void translationCreditPreferredWhenPresent() {
        WeightedMusicCatalog.Track sweden = catalogWithDuplicateSwedenPaths()
                .pool("minecraft:music.game").orElseThrow()
                .tracks().stream()
                .filter(track -> track.resourceId().endsWith("sweden.ogg")).findFirst().orElseThrow();
        assertEquals("Sweden", sweden.title());
        assertEquals("C418", sweden.composer());
    }

    private static Occurrence occurrence(String resourceId, double nativeProbability) {
        Sound sound = file(resourceId, 1.0F, 1.0F, 1, false, false);
        return new Occurrence(resourceId, sound, nativeProbability);
    }

    private static Pool pool(Occurrence... occurrences) {
        return poolWithId("minecraft:music.test", occurrences);
    }

    private static Pool poolWithId(String id, Occurrence... occurrences) {
        List<Occurrence> occList = List.of(occurrences);
        Map<String, List<Occurrence>> byResource = new LinkedHashMap<>();
        for (Occurrence occ : occList) {
            byResource.computeIfAbsent(occ.resourceId(), k -> new ArrayList<>()).add(occ);
        }
        List<Track> tracks = new ArrayList<>();
        for (Map.Entry<String, List<Occurrence>> entry : byResource.entrySet()) {
            double total = entry.getValue().stream().mapToDouble(Occurrence::nativeProbability).sum();
            tracks.add(new Track(entry.getKey(), entry.getKey(), null, total, List.copyOf(entry.getValue())));
        }
        return new Pool(id, List.copyOf(tracks), occList);
    }

    @Test void chancesMultiplyNativeProbabilityAndNormalizeByResource() {
        Pool pool = pool(occurrence("a", .75), occurrence("b", .25));
        TrackWeightConfig config = TrackWeightConfig.defaults().withMultiplier(pool.id(), "b", 3.0);
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, null);
        assertEquals(.5, chances.get("a"), 1e-9);
        assertEquals(.5, chances.get("b"), 1e-9);
    }

    @Test void nativePreservedAtAllDefaultMultipliers() {
        Pool pool = pool(occurrence("a", .75), occurrence("b", .25));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, null);
        assertEquals(.75, chances.get("a"), 1e-9);
        assertEquals(.25, chances.get("b"), 1e-9);
    }

    @Test void deterministicRepeatability() {
        Pool pool = pool(occurrence("a", .6), occurrence("b", .4));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        Optional<Occurrence> first = WeightedMusicCatalog.select(pool, config, null, 42L);
        Optional<Occurrence> second = WeightedMusicCatalog.select(pool, config, null, 42L);
        assertEquals(first, second);
        List<Occurrence> proj1 = WeightedMusicCatalog.project(pool, config, null, 42L, 0L, 10);
        List<Occurrence> proj2 = WeightedMusicCatalog.project(pool, config, null, 42L, 0L, 10);
        assertEquals(proj1, proj2);
    }

    @Test void duplicatePathAggregation() {
        Pool pool = pool(occurrence("a", .25), occurrence("a", .25), occurrence("b", .50));
        TrackWeightConfig config = TrackWeightConfig.defaults().withMultiplier(pool.id(), "a", 2.0);
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, null);
        assertEquals(2.0 / 3.0, chances.get("a"), 1e-9);
        assertEquals(1.0 / 3.0, chances.get("b"), 1e-9);
    }

    @Test void immediateAntiRepeatExcludesPreviousTrackWhenAlternativeExists() {
        Pool pool = pool(occurrence("a", .5), occurrence("b", .5));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, "a");
        assertEquals(0.0, chances.get("a"), 1e-9);
        assertEquals(1.0, chances.get("b"), 1e-9);

        for (long seed = 0; seed < 50; seed++) {
            Occurrence selected = WeightedMusicCatalog.select(pool, config, "a", seed).orElseThrow();
            assertEquals("b", selected.resourceId());
        }
    }

    @Test void antiRepeatDisabledAllowsRepeatingPreviousTrack() {
        Pool pool = pool(occurrence("a", .99), occurrence("b", .01));
        TrackWeightConfig config = TrackWeightConfig.defaults().withAntiRepeat(false);
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, "a");
        assertEquals(.99, chances.get("a"), 1e-9);
        assertEquals(.01, chances.get("b"), 1e-9);
    }

    @Test void antiRepeatUsesSoleEnabledTrackAsFallback() {
        Pool pool = pool(occurrence("only", 1.0));
        assertEquals("only", WeightedMusicCatalog.select(pool,
                TrackWeightConfig.defaults(), "only", 7L).orElseThrow().resourceId());
    }

    @Test void antiRepeatFallsBackWhenAllAlternativesMuted() {
        Pool pool = pool(occurrence("a", .5), occurrence("b", .5));
        TrackWeightConfig config = TrackWeightConfig.defaults().withMultiplier(pool.id(), "b", 0.0);
        assertEquals("a", WeightedMusicCatalog.select(pool, config, "a", 7L).orElseThrow().resourceId());
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, "a");
        assertEquals(1.0, chances.get("a"), 1e-9);
        assertEquals(0.0, chances.get("b"), 1e-9);
    }

    @Test void mutedPoolHasNoSelectionOrProjection() {
        Pool pool = pool(occurrence("a", .5), occurrence("b", .5));
        TrackWeightConfig muted = TrackWeightConfig.defaults()
                .withPoolMultipliers(pool.id(), Map.of("a", 0.0, "b", 0.0));
        assertTrue(WeightedMusicCatalog.select(pool, muted, null, 1L).isEmpty());
        assertTrue(WeightedMusicCatalog.project(pool, muted, null, 1L, 0L, 5).isEmpty());
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, muted, null);
        assertEquals(0.0, chances.get("a"), 1e-9);
        assertEquals(0.0, chances.get("b"), 1e-9);
    }

    @Test void projectAdvancesPreviousResourceWithNoAdjacentRepeats() {
        Pool pool = pool(occurrence("a", .5), occurrence("b", .5), occurrence("c", .5));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        List<Occurrence> projected = WeightedMusicCatalog.project(pool, config, null, 12345L, 0L, 30);
        assertEquals(30, projected.size());
        for (int i = 0; i < projected.size() - 1; i++) {
            assertNotEquals(projected.get(i).resourceId(), projected.get(i + 1).resourceId(),
                    "adjacent projected tracks must not repeat at index " + i);
        }
        List<Occurrence> projectedWithPrev = WeightedMusicCatalog.project(pool, config, "a", 12345L, 0L, 5);
        assertNotEquals("a", projectedWithPrev.get(0).resourceId());
    }

    @Test void projectDoesNotMutatePlannerOrConfig() {
        Pool pool = pool(occurrence("a", .5), occurrence("b", .5));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        MusicPlanner planner = new MusicPlanner();
        long sessionSeedBefore = planner.getSessionSeed();
        Map<String, Map<String, Double>> weightsBefore = config.weights();
        boolean antiRepeatBefore = config.antiRepeat();

        List<Occurrence> projected = WeightedMusicCatalog.project(pool, config, null, planner.getSessionSeed(), 0L, 10);
        assertEquals(10, projected.size());
        assertEquals(sessionSeedBefore, planner.getSessionSeed());
        assertEquals(weightsBefore, config.weights());
        assertEquals(antiRepeatBefore, config.antiRepeat());
    }

    @Test void projectZeroOrNegativeCountReturnsEmpty() {
        Pool pool = pool(occurrence("a", 1.0));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        assertTrue(WeightedMusicCatalog.project(pool, config, null, 1L, 0L, 0).isEmpty());
        assertTrue(WeightedMusicCatalog.project(pool, config, null, 1L, 0L, -5).isEmpty());
    }

    @Test void chancesPreservesTrackListOrder() {
        Pool pool = pool(occurrence("z", .3), occurrence("a", .3), occurrence("m", .4));
        TrackWeightConfig config = TrackWeightConfig.defaults();
        Map<String, Double> chances = WeightedMusicCatalog.chances(pool, config, null);
        List<String> keys = new ArrayList<>(chances.keySet());
        List<String> expectedKeys = pool.tracks().stream().map(Track::resourceId).toList();
        assertEquals(expectedKeys, keys);
    }

    @Test void emptyCatalogHasNoPools() {
        assertTrue(WeightedMusicCatalog.empty().pools().isEmpty());
        assertTrue(WeightedMusicCatalog.empty().pool("minecraft:music.game").isEmpty());
    }
}
