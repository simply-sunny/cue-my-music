package com.cuemymusic.client.music;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.cuemymusic.mixin.NestedSoundAccessor;
import com.cuemymusic.mixin.WeighedSoundEventsAccessor;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.util.valueproviders.MultipliedFloats;

/**
 * Exact membership over the loaded weighted sound graph.
 *
 * <p>Direct {@link Sound} entries contribute their actual file path.
 * Anything else is treated as a nested event delegate: {@code nestedEventOf}
 * maps it to the nested event id (null when it is not a delegate) and the
 * walk recurses via {@code eventLookup}. The walk never samples, so results
 * are exact and stable; visited events guard against cycles.
 */
public final class MusicGraph {
    private MusicGraph() {
    }

    /**
     * Source of graph entries. Production reads via the narrow mixin
     * accessor; tests inject an explicit provider instead.
     */
    static Function<WeighedSoundEvents, List<Weighted<Sound>>> entriesProvider =
            event -> ((WeighedSoundEventsAccessor) event).cueMyMusic$entries();

    /**
     * Maps a graph entry to its nested event id, or null for direct file
     * sounds. Backed by the narrow runtime-delegate accessor, so no sampling
     * is involved.
     */
    public static Identifier nestedEventId(Weighted<Sound> entry) {
        if (entry instanceof Sound) {
            return null;
        }
        if (entry instanceof NestedSoundAccessor nested) {
            return nested.cueMyMusic$nestedEventId();
        }
        return null;
    }

    /**
     * The delegate's own sound definition (carries the parent volume, pitch
     * and weight used in composition), or null for non-delegate entries.
     */
    public static Sound nestedDefinition(Weighted<Sound> entry) {
        if (entry instanceof NestedSoundAccessor nested) {
            return nested.cueMyMusic$nestedDefinition();
        }
        return null;
    }

    public static Set<String> eligibleFiles(
            WeighedSoundEvents root,
            Function<Identifier, WeighedSoundEvents> eventLookup,
            Function<Weighted<Sound>, Identifier> nestedEventOf) {
        Set<String> files = new HashSet<>();
        collectInto(root, eventLookup, nestedEventOf, new HashSet<>(), files);
        return Set.copyOf(files);
    }

    /**
     * Resolves an actual file to the exact {@link Sound} vanilla would play
     * for it, reproducing the delegate volume/pitch/stream composition at
     * every nesting level (mirrors
     * {@code SoundManager$Preparations$1.getSound}). Direct files resolve to
     * themselves.
     */
    public static Optional<Sound> resolveFile(
            WeighedSoundEvents root,
            String filePath,
            Function<Identifier, WeighedSoundEvents> eventLookup,
            Function<Weighted<Sound>, Identifier> nestedEventOf,
            Function<Weighted<Sound>, Sound> nestedDefinitionOf) {
        return resolveInto(root, filePath, eventLookup, nestedEventOf, nestedDefinitionOf, new HashSet<>());
    }

    /**
     * Exact mirror of the runtime nested-event delegate composition: child
     * location, multiplied child/parent volume and pitch, parent weight,
     * always file type, streamed if either side streams, child preload and
     * attenuation.
     */
    public static Sound composeDefinition(Sound parentDefinition, Sound child) {
        return new Sound(
                child.getLocation(),
                new MultipliedFloats(
                        child.getVolume(), parentDefinition.getVolume()),
                new MultipliedFloats(
                        child.getPitch(), parentDefinition.getPitch()),
                parentDefinition.getWeight(),
                Sound.Type.FILE,
                child.shouldStream() || parentDefinition.shouldStream(),
                child.shouldPreload(),
                child.getAttenuationDistance());
    }

    /**
     * Silence check by identifier, matching vanilla {@code SoundManager}
     * behavior. Delegates build copies, so reference identity would miss
     * nested silence.
     */
    public static boolean isSilence(Sound sound) {
        Identifier location = sound.getLocation();
        return location.equals(SoundManager.EMPTY_SOUND_LOCATION)
                || location.equals(SoundManager.INTENTIONALLY_EMPTY_SOUND_LOCATION);
    }

    private static void collectInto(
            WeighedSoundEvents event,
            Function<Identifier, WeighedSoundEvents> eventLookup,
            Function<Weighted<Sound>, Identifier> nestedEventOf,
            Set<WeighedSoundEvents> visited,
            Set<String> files) {
        if (event == null || !visited.add(event)) {
            return;
        }
        for (Weighted<Sound> entry : entriesOf(event)) {
            if (entry instanceof Sound sound) {
                files.add(sound.getPath().toString());
                continue;
            }
            Identifier nested = nestedEventOf.apply(entry);
            if (nested != null) {
                collectInto(eventLookup.apply(nested), eventLookup, nestedEventOf, visited, files);
            }
        }
    }

    private static Optional<Sound> resolveInto(
            WeighedSoundEvents event,
            String filePath,
            Function<Identifier, WeighedSoundEvents> eventLookup,
            Function<Weighted<Sound>, Identifier> nestedEventOf,
            Function<Weighted<Sound>, Sound> nestedDefinitionOf,
            Set<WeighedSoundEvents> visited) {
        if (event == null || !visited.add(event)) {
            return Optional.empty();
        }
        // Direct files first so a hit in the current event wins over nested ones.
        for (Weighted<Sound> entry : entriesOf(event)) {
            if (entry instanceof Sound sound && sound.getPath().toString().equals(filePath)) {
                return Optional.of(sound);
            }
        }
        for (Weighted<Sound> entry : entriesOf(event)) {
            if (entry instanceof Sound) {
                continue;
            }
            Identifier nested = nestedEventOf.apply(entry);
            if (nested != null) {
                Optional<Sound> found = resolveInto(eventLookup.apply(nested), filePath, eventLookup,
                        nestedEventOf, nestedDefinitionOf, visited);
                if (found.isPresent()) {
                    Sound definition = nestedDefinitionOf.apply(entry);
                    return Optional.of(
                            definition == null ? found.orElseThrow() : composeDefinition(definition, found.orElseThrow()));
                }
            }
        }
        return Optional.empty();
    }

    private static List<Weighted<Sound>> entriesOf(WeighedSoundEvents event) {
        // WeighedSoundEvents does not override equals, so visited sets are identity-keyed.
        return entriesProvider.apply(event);
    }
}
