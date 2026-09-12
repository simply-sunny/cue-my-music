package com.cuemymusic.client.music;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test void emptyCatalogHasNoPools() {
        assertTrue(WeightedMusicCatalog.empty().pools().isEmpty());
        assertTrue(WeightedMusicCatalog.empty().pool("minecraft:music.game").isEmpty());
    }
}
