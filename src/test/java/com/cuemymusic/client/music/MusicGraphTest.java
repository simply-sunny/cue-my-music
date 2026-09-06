package com.cuemymusic.client.music;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Exact graph-membership contract: eligibility is computed by walking the
 * loaded weighted graph (including nested event delegates), never by
 * probabilistic sampling.
 */
class MusicGraphTest {

    /** Test double standing in for the runtime nested-event delegate. */
    private static final class NestedStub implements Weighted<Sound> {
        private final Identifier target;
        private final int weight;

        private NestedStub(String target, int weight) {
            this.target = Identifier.parse(target);
            this.weight = weight;
        }

        @Override public int getWeight() { return weight; }

        @Override public Sound getSound(RandomSource random) {
            throw new UnsupportedOperationException("membership walk must not sample");
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

    /** Actual file path vanilla derives for a FILE sound name. */
    private static String path(String name) {
        return "minecraft:sounds/" + name + ".ogg";
    }

    private static WeighedSoundEvents event(String id, List<Weighted<Sound>> entries) {
        WeighedSoundEvents event = new WeighedSoundEvents(Identifier.parse(id), null);
        for (Weighted<Sound> entry : entries) {
            event.addSound(entry);
        }
        return event;
    }

    private Function<Weighted<Sound>, Identifier> nestedOf() {
        return entry -> entry instanceof NestedStub stub ? stub.target : null;
    }

    @BeforeEach void injectTestListProvider() {
        // Production reads entries via the mixin accessor (not applied under
        // unit tests); tests inject the same field read explicitly.
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

    @Test void flatEventListsEveryActualFile() {
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                file("music/game/a", 1), file("music/game/b", 2), file("music/game/c", 1)));
        Set<String> files = MusicGraph.eligibleFiles(root, id -> null, nestedOf());
        assertEquals(Set.of(path("music/game/a"), path("music/game/b"), path("music/game/c")), files);
    }

    @Test void nestedEventsExpandExactlyWithoutSampling() {
        WeighedSoundEvents creative = event("minecraft:music.game.creative", List.of(
                file("music/game/aria_math", 1), file("music/game/dreiton", 1)));
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                file("music/game/sweden", 1), new NestedStub("minecraft:music.game.creative", 1)));
        java.util.Map<Identifier, WeighedSoundEvents> registry =
                java.util.Map.of(Identifier.parse("minecraft:music.game.creative"), creative);
        Set<String> first = MusicGraph.eligibleFiles(root, registry::get, nestedOf());
        assertEquals(Set.of(
                path("music/game/sweden"),
                path("music/game/aria_math"),
                path("music/game/dreiton")), first);
        // Deterministic: repeated walks agree exactly.
        assertEquals(first, MusicGraph.eligibleFiles(root, registry::get, nestedOf()));
    }

    @Test void cyclicNestingTerminatesAndMissingTargetsAreIgnored() {
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                file("music/game/sweden", 1),
                new NestedStub("minecraft:music.game", 1),
                new NestedStub("minecraft:music.missing", 1)));
        java.util.Map<Identifier, WeighedSoundEvents> registry =
                java.util.Map.of(Identifier.parse("minecraft:music.game"), root);
        Set<String> files = MusicGraph.eligibleFiles(root, registry::get, nestedOf());
        assertEquals(Set.of(path("music/game/sweden")), files);
    }

    @Test void samplingNeverOccursDuringMembershipWalk() {
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                file("music/game/sweden", 1), new NestedStub("minecraft:music.game.creative", 5)));
        java.util.Map<Identifier, WeighedSoundEvents> registry = java.util.Map.of(
                Identifier.parse("minecraft:music.game.creative"),
                event("minecraft:music.game.creative", List.of(file("music/game/aria_math", 1))));
        // Would throw via NestedStub.getSound if the walk sampled.
        assertDoesNotThrow(() -> MusicGraph.eligibleFiles(root, registry::get, nestedOf()));
        assertDoesNotThrow(() -> MusicGraph.resolveFile(root, path("music/game/aria_math"), registry::get,
                nestedOf(), entry -> null));
    }

    @Test void resolveFileLocatesNestedLeafAndRejectsUnknown() {
        WeighedSoundEvents creative = event("minecraft:music.game.creative", List.of(
                file("music/game/aria_math", 1)));
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                file("music/game/sweden", 1), new NestedStub("minecraft:music.game.creative", 1)));
        java.util.Map<Identifier, WeighedSoundEvents> registry =
                java.util.Map.of(Identifier.parse("minecraft:music.game.creative"), creative);
        Optional<Sound> found = MusicGraph.resolveFile(root, path("music/game/aria_math"), registry::get,
                nestedOf(), entry -> null);
        assertTrue(found.isPresent());
        assertEquals(path("music/game/aria_math"), found.orElseThrow().getPath().toString());
        assertTrue(MusicGraph.resolveFile(root, path("music/game/removed"), registry::get,
                nestedOf(), entry -> null).isEmpty());
    }

    @Test void nullRootYieldsEmptyMembership() {
        assertEquals(Set.of(), MusicGraph.eligibleFiles(null, id -> null, entry -> null));
        assertTrue(MusicGraph.resolveFile(null, path("music/game/sweden"), id -> null, entry -> null,
                entry -> null).isEmpty());
    }

    @Test void membershipIsStableAcrossRepeatedSelections() {
        // Guard against Monte Carlo style membership: 64 walks must all agree.
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                file("music/game/a", 1), file("music/game/b", 9), new NestedStub("minecraft:music.sub", 3)));
        java.util.Map<Identifier, WeighedSoundEvents> registry = java.util.Map.of(
                Identifier.parse("minecraft:music.sub"),
                event("minecraft:music.sub", List.of(file("music/sub/x", 1))));
        Set<String> expected = Set.of(path("music/game/a"), path("music/game/b"), path("music/sub/x"));
        Set<Set<String>> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            seen.add(MusicGraph.eligibleFiles(root, registry::get, nestedOf()));
        }
        assertEquals(Set.of(expected), seen);
    }
}
