package com.cuemymusic.client.music;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Previous-playback must reproduce the exact vanilla delegate composition
 * (volume/pitch/stream transforms), and silence must be detected by
 * identifier, not by reference identity (delegates build copies).
 */
class MusicGraphComposeTest {

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
            // Delegation like the runtime delegate (composition is covered separately).
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

    private static String path(String name) {
        return "minecraft:sounds/" + name + ".ogg";
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

    @Test void composeMirrorsDelegateVolumePitchStreamTransforms() {
        Sound parent = file("music/parent-def", 0.5F, 2.0F, 3, false, false);
        Sound child = file("music/game/sweden", 0.8F, 1.5F, 9, true, true);
        Sound composed = MusicGraph.composeDefinition(parent, child);
        assertEquals(child.getLocation(), composed.getLocation());
        RandomSource random = RandomSource.create(1L);
        assertEquals(0.4F, composed.getVolume().sample(random), 0.0001F);
        assertEquals(3.0F, composed.getPitch().sample(random), 0.0001F);
        assertEquals(3, composed.getWeight());
        assertEquals(Sound.Type.FILE, composed.getType());
        assertTrue(composed.shouldStream(), "stream is the OR of child and parent");
        assertTrue(composed.shouldPreload(), "preload follows the child");
        assertEquals(16, composed.getAttenuationDistance(), "attenuation follows the child");
    }

    @Test void composePropagatesParentStreamWhenChildDoesNotStream() {
        Sound parent = file("music/parent-def", 1.0F, 1.0F, 1, true, false);
        Sound child = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        assertTrue(MusicGraph.composeDefinition(parent, child).shouldStream());
        Sound fileParent = file("music/parent-def", 1.0F, 1.0F, 1, false, false);
        assertFalse(MusicGraph.composeDefinition(fileParent, child).shouldStream());
    }

    @Test void resolveComposesThroughNesting() {
        Sound definition = file("music/nested-def", 0.5F, 1.0F, 2, false, false);
        WeighedSoundEvents sub = event("minecraft:music.sub",
                List.of(file("music/game/aria_math", 0.8F, 1.0F, 1, false, false)));
        NestedStub nested = new NestedStub("minecraft:music.sub", definition, sub);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(nested));
        Map<Identifier, WeighedSoundEvents> registry =
                Map.of(Identifier.parse("minecraft:music.sub"), sub);
        Optional<Sound> resolved = MusicGraph.resolveFile(root, path("music/game/aria_math"),
                registry::get,
                entry -> entry instanceof NestedStub stub ? stub.target : null,
                entry -> entry instanceof NestedStub stub ? stub.definition : null);
        assertTrue(resolved.isPresent());
        assertEquals(path("music/game/aria_math"), resolved.orElseThrow().getPath().toString());
        assertEquals(0.4F, resolved.orElseThrow().getVolume().sample(RandomSource.create(1L)), 0.0001F);
        assertEquals(2, resolved.orElseThrow().getWeight(), "weight comes from the delegate definition");
    }

    @Test void resolveFindsDirectFilesUncomposed() {
        Sound direct = file("music/game/sweden", 0.7F, 1.2F, 4, false, true);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(direct));
        Optional<Sound> resolved = MusicGraph.resolveFile(root, path("music/game/sweden"),
                id -> null, entry -> null, entry -> null);
        assertTrue(resolved.isPresent());
        assertSame(direct, resolved.orElseThrow());
    }

    @Test void weightedLeavesMultiplyConditionalNestedProbability() {
        Sound a = file("music/game/a", 1.0F, 1.0F, 3, false, false);
        Sound b = file("music/game/b", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(a, b));
        List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(
                root, id -> null, entry -> null, entry -> null);
        assertEquals(0.75, leaves.get(0).nativeProbability(), 1e-9);
        assertEquals(0.25, leaves.get(1).nativeProbability(), 1e-9);
    }

    @Test void weightedLeavesMultiplyAcrossNestedEvents() {
        Sound direct = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Sound subA = file("music/game/aria_math", 1.0F, 1.0F, 3, false, false);
        Sound subB = file("music/game/dreiton", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents sub = event("minecraft:music.game.creative", List.of(subA, subB));
        Sound definition = file("music/nested-def", 1.0F, 1.0F, 3, false, false);
        NestedStub nested = new NestedStub("minecraft:music.game.creative", definition, sub);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(direct, nested));
        Map<Identifier, WeighedSoundEvents> registry =
                Map.of(Identifier.parse("minecraft:music.game.creative"), sub);
        List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(root, registry::get,
                entry -> entry instanceof NestedStub stub ? stub.target : null,
                entry -> entry instanceof NestedStub stub ? stub.definition : null);
        // Root total is 1 + 3 = 4: direct takes 1/4, nested takes 3/4 split 3:1 inside.
        assertEquals(3, leaves.size());
        assertEquals(0.25, leaves.get(0).nativeProbability(), 1e-9);
        assertEquals(0.75 * 0.75, leaves.get(1).nativeProbability(), 1e-9);
        assertEquals(0.75 * 0.25, leaves.get(2).nativeProbability(), 1e-9);
    }

    @Test void weightedLeavesKeepDuplicateResourcePathsSeparate() {
        Sound first = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Sound second = file("music/game/sweden", 1.0F, 1.0F, 2, false, false);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(first, second));
        List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(
                root, id -> null, entry -> null, entry -> null);
        assertEquals(2, leaves.size(), "duplicate branches must survive as separate leaves");
        assertEquals(leaves.get(0).sound().getPath().toString(),
                leaves.get(1).sound().getPath().toString());
        assertEquals(1.0 / 3.0, leaves.get(0).nativeProbability(), 1e-9);
        assertEquals(2.0 / 3.0, leaves.get(1).nativeProbability(), 1e-9);
    }

    @Test void weightedLeavesComposeVolumeAndPitchThroughNesting() {
        Sound definition = file("music/nested-def", 0.5F, 2.0F, 1, false, false);
        Sound child = file("music/game/aria_math", 0.8F, 1.5F, 1, false, false);
        WeighedSoundEvents sub = event("minecraft:music.sub", List.of(child));
        NestedStub nested = new NestedStub("minecraft:music.sub", definition, sub);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(nested));
        Map<Identifier, WeighedSoundEvents> registry =
                Map.of(Identifier.parse("minecraft:music.sub"), sub);
        List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(root, registry::get,
                entry -> entry instanceof NestedStub stub ? stub.target : null,
                entry -> entry instanceof NestedStub stub ? stub.definition : null);
        assertEquals(1, leaves.size());
        assertEquals(1.0, leaves.get(0).nativeProbability(), 1e-9);
        RandomSource random = RandomSource.create(1L);
        assertEquals(0.4F, leaves.get(0).sound().getVolume().sample(random), 0.0001F);
        assertEquals(3.0F, leaves.get(0).sound().getPitch().sample(random), 0.0001F);
    }

    @Test void weightedLeavesExcludeSilence() {
        WeighedSoundEvents root = event("minecraft:music.game", List.of(
                SoundManager.EMPTY_SOUND, file("music/game/sweden", 1.0F, 1.0F, 1, false, false)));
        List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(
                root, id -> null, entry -> null, entry -> null);
        assertEquals(1, leaves.size(), "silence must be absent");
        assertEquals(path("music/game/sweden"), leaves.get(0).sound().getPath().toString());
    }

    @Test void weightedLeavesTerminateOnCycles() {
        Sound direct = file("music/game/sweden", 1.0F, 1.0F, 1, false, false);
        Sound definition = file("music/nested-def", 1.0F, 1.0F, 1, false, false);
        WeighedSoundEvents root = event("minecraft:music.game", List.of(direct));
        NestedStub self = new NestedStub("minecraft:music.game", definition, root);
        // Rebuild with the self-referencing delegate included.
        WeighedSoundEvents cyclic = event("minecraft:music.game", List.of(direct, self));
        Map<Identifier, WeighedSoundEvents> registry =
                Map.of(Identifier.parse("minecraft:music.game"), cyclic);
        List<MusicGraph.WeightedLeaf> leaves = MusicGraph.weightedLeaves(cyclic, registry::get,
                entry -> entry instanceof NestedStub stub ? stub.target : null,
                entry -> entry instanceof NestedStub stub ? stub.definition : null);
        assertDoesNotThrow(() -> leaves);
        assertTrue(leaves.stream().anyMatch(leaf ->
                leaf.sound().getPath().toString().equals(path("music/game/sweden"))));
    }

    @Test void silenceDetectedByIdentifierNotReference() {
        assertTrue(MusicGraph.isSilence(SoundManager.EMPTY_SOUND));
        assertTrue(MusicGraph.isSilence(SoundManager.INTENTIONALLY_EMPTY_SOUND));
        // Delegates build copies: same identifier, different reference.
        Sound copy = new Sound(SoundManager.EMPTY_SOUND.getLocation(),
                ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, false, false, 16);
        assertNotSame(SoundManager.EMPTY_SOUND, copy);
        assertTrue(MusicGraph.isSilence(copy), "identifier comparison must catch nested copies");
        assertFalse(MusicGraph.isSilence(file("music/game/sweden", 1.0F, 1.0F, 1, false, false)));
    }
}
