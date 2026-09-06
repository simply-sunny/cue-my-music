package com.cuemymusic.client.music;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Integration over the actual vanilla selection path: real
 * {@code WeighedSoundEvents.getSound} choices (the exact call the director
 * uses) must be valid members of the exact eligibility walk — 30 Next picks
 * across game/Nether/End-style pools, including nested events.
 */
class VanillaSelectionTest {

    /** Delegating double: real weighted recursion into the sub-event. */
    private static final class NestedStub implements Weighted<Sound> {
        final Identifier target;
        final WeighedSoundEvents subEvent;

        NestedStub(String target, WeighedSoundEvents subEvent) {
            this.target = Identifier.parse(target);
            this.subEvent = subEvent;
        }

        @Override public int getWeight() { return subEvent.getWeight(); }

        @Override public Sound getSound(RandomSource random) {
            return subEvent.getSound(random);
        }

        @Override public void preloadIfRequired(net.minecraft.client.sounds.SoundEngine engine) {
        }
    }

    private static Sound file(String name, int weight) {
        return new Sound(
                Identifier.parse("minecraft:" + name),
                ConstantFloat.of(1.0F),
                ConstantFloat.of(1.0F),
                weight,
                Sound.Type.FILE,
                false,
                false,
                16);
    }

    private static WeighedSoundEvents event(String id, List<Weighted<Sound>> entries) {
        WeighedSoundEvents event = new WeighedSoundEvents(Identifier.parse(id), null);
        for (Weighted<Sound> entry : entries) {
            event.addSound(entry);
        }
        return event;
    }

    private record Pools(WeighedSoundEvents game, WeighedSoundEvents nether, WeighedSoundEvents end,
            Map<Identifier, WeighedSoundEvents> registry) {
    }

    private Pools pools() {
        WeighedSoundEvents creative = event("minecraft:music.game.creative", List.of(
                file("music/game/aria_math", 1), file("music/game/dreiton", 1)));
        WeighedSoundEvents game = event("minecraft:music.game", List.of(
                file("music/game/sweden", 3), file("music/game/clark", 1),
                new NestedStub("minecraft:music.game.creative", creative)));
        WeighedSoundEvents netherWastes = event("minecraft:music.nether.nether_wastes", List.of(
                file("music/nether/rubedo", 1), file("music/nether/chrysopoeia", 1)));
        WeighedSoundEvents nether = event("minecraft:music.nether", List.of(
                file("music/nether/ballad", 2),
                new NestedStub("minecraft:music.nether.nether_wastes", netherWastes)));
        WeighedSoundEvents endPool = event("minecraft:music.end", List.of(
                file("music/end/alpha", 1), file("music/end/boss", 1), file("music/end/the_end", 1)));
        Map<Identifier, WeighedSoundEvents> registry = Map.of(
                Identifier.parse("minecraft:music.game.creative"), creative,
                Identifier.parse("minecraft:music.nether.nether_wastes"), netherWastes);
        return new Pools(game, nether, endPool, registry);
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

    private java.util.function.Function<Weighted<Sound>, Identifier> nestedOf() {
        return entry -> entry instanceof NestedStub stub ? stub.target : null;
    }

    @Test void thirtyNextPicksAreValidEligibleMembersInEveryContext() {
        Pools pools = pools();
        long session = 20260906L;
        List<WeighedSoundEvents> contexts = List.of(pools.game(), pools.nether(), pools.end());
        List<String> contextIds = List.of("minecraft:music.game", "minecraft:music.nether", "minecraft:music.end");
        for (int c = 0; c < contexts.size(); c++) {
            WeighedSoundEvents context = contexts.get(c);
            Set<String> eligible = MusicGraph.eligibleFiles(context, pools.registry()::get, nestedOf());
            assertFalse(eligible.isEmpty());
            Set<String> picked = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                long seed = MusicPlanner.selectionSeed(session, contextIds.get(c), i);
                Sound chosen = context.getSound(RandomSource.create(seed));
                assertFalse(MusicGraph.isSilence(chosen), "non-degenerate pools must never select silence");
                assertTrue(eligible.contains(chosen.getPath().toString()),
                        "actual vanilla choice must be an exact eligible member");
                picked.add(chosen.getPath().toString());
            }
            assertTrue(picked.size() >= 2,
                    "30 weighted picks in " + contextIds.get(c) + " must vary, got " + picked);
        }
    }

    @Test void actualSelectionIsDeterministicAcrossRuns() {
        Pools pools = pools();
        for (int i = 0; i < 30; i++) {
            long seed = MusicPlanner.selectionSeed(7L, "minecraft:music.nether", i);
            Sound first = pools.nether().getSound(RandomSource.create(seed));
            Sound second = pools.nether().getSound(RandomSource.create(seed));
            assertEquals(first.getPath().toString(), second.getPath().toString());
        }
    }

    @Test void nestedFilesAreReachableThroughActualSelection() {
        Pools pools = pools();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long seed = MusicPlanner.selectionSeed(11L, "minecraft:music.game", i);
            seen.add(pools.game().getSound(RandomSource.create(seed)).getPath().toString());
        }
        assertTrue(seen.stream().anyMatch(path -> path.contains("aria_math")),
                "nested creative files must be reachable, saw " + seen);
    }
}
