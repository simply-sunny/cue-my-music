package com.cuemymusic.client.music;

import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;

/**
 * Populates {@link MusicLibrary} with vanilla and disc tracks on client init.
 * First 10 vanilla tracks carry sourceId suffix ".c418" for C418 detection
 * ({@code MusicLibrary.isC418} checks {@code sourceId.endsWith(".c418")}).
 * Vanilla: ambientEligible true; discs: false (Discs Everywhere preset opts in).
 */
public final class VanillaTrackRegistry {
    private VanillaTrackRegistry() {}

    public static void registerAll(MusicLibrary library) {
        // ---- Vanilla — 20 (10 C418, 10 Lena Raine/post-C418) ----
        // C418 era — sourceId ends with .c418
        addVanilla(library, "vanilla:hal1", "Minecraft", "C418", "minecraft:music.game.c418");
        addVanilla(library, "vanilla:sweden", "Sweden", "C418", "minecraft:music.game.sweden.c418");
        addVanilla(library, "vanilla:clark", "Clark", "C418", "minecraft:music.game.clark.c418");
        addVanilla(library, "vanilla:danny", "Danny", "C418", "minecraft:music.game.danny.c418");
        addVanilla(library, "vanilla:wethands", "Wet Hands", "C418", "minecraft:music.game.wet_hands.c418");
        addVanilla(library, "vanilla:dryhands", "Dry Hands", "C418", "minecraft:music.game.dry_hands.c418");
        addVanilla(library, "vanilla:miceonvenus", "Mice on Venus", "C418", "minecraft:music.game.mice_on_venus.c418");
        addVanilla(library, "vanilla:subwooferlullaby", "Subwoofer Lullaby", "C418", "minecraft:music.game.subwoofer_lullaby.c418");
        addVanilla(library, "vanilla:livingmice", "Living Mice", "C418", "minecraft:music.game.living_mice.c418");
        addVanilla(library, "vanilla:haggstrom", "Haggstrom", "C418", "minecraft:music.game.haggstrom.c418");
        // Lena Raine era — no .c418 suffix
        addVanilla(library, "vanilla:floatingdream", "Floating Dream", "Lena Raine", "minecraft:music.overworld.floating_dream");
        addVanilla(library, "vanilla:comfortingmemories", "Comforting Memories", "Lena Raine", "minecraft:music.overworld.comforting_memories");
        addVanilla(library, "vanilla:infiniteamethyst", "Infinite Amethyst", "Lena Raine", "minecraft:music.overworld.infinite_amethyst");
        addVanilla(library, "vanilla:standtall", "Stand Tall", "Lena Raine", "minecraft:music.nether.nether_wastes");
        addVanilla(library, "vanilla:wending", "Wending", "Lena Raine", "minecraft:music.nether.warped_forest");
        addVanilla(library, "vanilla:anordinarylife", "An Ordinary Life", "Lena Raine", "minecraft:music.creative.comfort");
        addVanilla(library, "vanilla:ancestry", "Ancestry", "Lena Raine", "minecraft:music.creative.ancestry");
        addVanilla(library, "vanilla:aerie", "Aerie", "Lena Raine", "minecraft:music.overworld.aerie");
        addVanilla(library, "vanilla:firebugs", "Firebugs", "Lena Raine", "minecraft:music.nether.crimson_forest");
        addVanilla(library, "vanilla:labyrinthine", "Labyrinthine", "Lena Raine", "minecraft:music.creative.labyrinthine");

        // ---- Discs — 15, ambientEligible false by default ----
        addDisc(library, "disc:13", "13", "C418", "minecraft:music_disc.13");
        addDisc(library, "disc:cat", "cat", "C418", "minecraft:music_disc.cat");
        addDisc(library, "disc:blocks", "blocks", "C418", "minecraft:music_disc.blocks");
        addDisc(library, "disc:chirp", "chirp", "C418", "minecraft:music_disc.chirp");
        addDisc(library, "disc:far", "far", "C418", "minecraft:music_disc.far");
        addDisc(library, "disc:mall", "mall", "C418", "minecraft:music_disc.mall");
        addDisc(library, "disc:mellohi", "mellohi", "C418", "minecraft:music_disc.mellohi");
        addDisc(library, "disc:stal", "stal", "C418", "minecraft:music_disc.stal");
        addDisc(library, "disc:strad", "strad", "C418", "minecraft:music_disc.strad");
        addDisc(library, "disc:ward", "ward", "C418", "minecraft:music_disc.ward");
        addDisc(library, "disc:11", "11", "C418", "minecraft:music_disc.11");
        addDisc(library, "disc:wait", "wait", "C418", "minecraft:music_disc.wait");
        addDisc(library, "disc:pigstep", "Pigstep", "Lena Raine", "minecraft:music_disc.pigstep");
        addDisc(library, "disc:otherside", "otherside", "Lena Raine", "minecraft:music_disc.otherside");
        addDisc(library, "disc:relic", "Relic", "Aaron Cherof", "minecraft:music_disc.relic");
    }

    private static void addVanilla(MusicLibrary l, String id, String title, String artist, String sourceId) {
        var t = new MusicTrack(id, title, artist, SourceType.VANILLA);
        t.setSourceId(sourceId); t.setEnabled(true); t.setAmbientEligible(true);
        l.addOrReplaceTrack(t);
    }
    private static void addDisc(MusicLibrary l, String id, String title, String artist, String sourceId) {
        var t = new MusicTrack(id, title, artist, SourceType.MUSIC_DISC);
        t.setSourceId(sourceId); t.setEnabled(true); t.setAmbientEligible(false);
        l.addOrReplaceTrack(t);
    }
}
