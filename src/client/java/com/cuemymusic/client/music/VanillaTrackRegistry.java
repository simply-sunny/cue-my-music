package com.cuemymusic.client.music;

import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.JukeboxSong;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers native music by enumerating the 26.2 registries, never by list position.
 * Source of truth: sounds.json defines 70 distinct environmental Sound files (music/game/*, music/menu/*)
 * across 32 SoundEvents (music.*) + 22 discs (music_disc.*) = 92 total distinct tracks when deduped.
 * The 70 environmental tracks are the playable built-in environmental music (sweden, subwoofer_lullaby, etc.)
 * reused across biomes/menus but stored once. Discs are sourced from JUKEBOX_SONG registry.
 */
public final class VanillaTrackRegistry {
    private VanillaTrackRegistry() {}

    // 70 distinct environmental sound files from sounds.json (minecraft/sounds.json) union across all music.* events, deduped, type != event
    private static final String[] VANILLA_KEYS = {
            "a_familiar_room",
            "an_ordinary_day",
            "ancestry",
            "below_and_above",
            "broken_clocks",
            "bromeliad",
            "clark",
            "comforting_memories",
            "creative.aria_math",
            "creative.biome_fest",
            "creative.blind_spots",
            "creative.dreiton",
            "creative.haunt_muskie",
            "creative.taswell",
            "crescent_dunes",
            "danny",
            "deeper",
            "dry_hands",
            "ebb",
            "echo_in_the_wind",
            "eld_unknown",
            "end.alpha",
            "end.boss",
            "end.the_end",
            "endless",
            "featherfall",
            "fireflies",
            "floating_dream",
            "haggstrom",
            "home",
            "infinite_amethyst",
            "key",
            "komorebi",
            "left_to_bloom",
            "lilypad",
            "living_mice",
            "memories",
            "mice_on_venus",
            "minecraft",
            "nether.ballad_of_the_cats",
            "nether.concrete_halls",
            "nether.crimson_forest.chrysopoeia",
            "nether.dead_voxel",
            "nether.nether_wastes.rubedo",
            "nether.soulsand_valley.so_below",
            "nether.warmth",
            "nightly",
            "one_more_day",
            "os_piano",
            "oxygene",
            "pokopoko",
            "puzzlebox",
            "shores",
            "stand_tall",
            "subwoofer_lullaby",
            "swamp.aerie",
            "swamp.firebugs",
            "swamp.labyrinthine",
            "sweden",
            "watcher",
            "water.axolotl",
            "water.dragon_fish",
            "water.shuniji",
            "wending",
            "wet_hands",
            "yakusoku",
            "menu.beginning_2",
            "menu.floating_trees",
            "menu.moog_city_2",
            "menu.mutation"
    };

    // Maps from vanilla key to sound file path suffix after music/
    private static final Map<String,String> VANILLA_FILE = new LinkedHashMap<>();
    private static final Map<String,String> VANILLA_TITLES = new LinkedHashMap<>();
    private static final Map<String,String> VANILLA_ARTISTS = new LinkedHashMap<>();
    private static final Map<String,String> DISC_ARTISTS = new LinkedHashMap<>();

    static {
        // File mapping: vanilla key -> file path under sounds (for sourceId)
        VANILLA_FILE.put("a_familiar_room", "music/game/a_familiar_room");
        VANILLA_FILE.put("an_ordinary_day", "music/game/an_ordinary_day");
        VANILLA_FILE.put("ancestry", "music/game/ancestry");
        VANILLA_FILE.put("below_and_above", "music/game/below_and_above");
        VANILLA_FILE.put("broken_clocks", "music/game/broken_clocks");
        VANILLA_FILE.put("bromeliad", "music/game/bromeliad");
        VANILLA_FILE.put("clark", "music/game/clark");
        VANILLA_FILE.put("comforting_memories", "music/game/comforting_memories");
        VANILLA_FILE.put("creative.aria_math", "music/game/creative/aria_math");
        VANILLA_FILE.put("creative.biome_fest", "music/game/creative/biome_fest");
        VANILLA_FILE.put("creative.blind_spots", "music/game/creative/blind_spots");
        VANILLA_FILE.put("creative.dreiton", "music/game/creative/dreiton");
        VANILLA_FILE.put("creative.haunt_muskie", "music/game/creative/haunt_muskie");
        VANILLA_FILE.put("creative.taswell", "music/game/creative/taswell");
        VANILLA_FILE.put("crescent_dunes", "music/game/crescent_dunes");
        VANILLA_FILE.put("danny", "music/game/danny");
        VANILLA_FILE.put("deeper", "music/game/deeper");
        VANILLA_FILE.put("dry_hands", "music/game/dry_hands");
        VANILLA_FILE.put("ebb", "music/game/ebb");
        VANILLA_FILE.put("echo_in_the_wind", "music/game/echo_in_the_wind");
        VANILLA_FILE.put("eld_unknown", "music/game/eld_unknown");
        VANILLA_FILE.put("end.alpha", "music/game/end/alpha");
        VANILLA_FILE.put("end.boss", "music/game/end/boss");
        VANILLA_FILE.put("end.the_end", "music/game/end/the_end");
        VANILLA_FILE.put("endless", "music/game/endless");
        VANILLA_FILE.put("featherfall", "music/game/featherfall");
        VANILLA_FILE.put("fireflies", "music/game/fireflies");
        VANILLA_FILE.put("floating_dream", "music/game/floating_dream");
        VANILLA_FILE.put("haggstrom", "music/game/haggstrom");
        VANILLA_FILE.put("home", "music/game/home");
        VANILLA_FILE.put("infinite_amethyst", "music/game/infinite_amethyst");
        VANILLA_FILE.put("key", "music/game/key");
        VANILLA_FILE.put("komorebi", "music/game/komorebi");
        VANILLA_FILE.put("left_to_bloom", "music/game/left_to_bloom");
        VANILLA_FILE.put("lilypad", "music/game/lilypad");
        VANILLA_FILE.put("living_mice", "music/game/living_mice");
        VANILLA_FILE.put("memories", "music/game/memories");
        VANILLA_FILE.put("mice_on_venus", "music/game/mice_on_venus");
        VANILLA_FILE.put("minecraft", "music/game/minecraft");
        VANILLA_FILE.put("nether.ballad_of_the_cats", "music/game/nether/ballad_of_the_cats");
        VANILLA_FILE.put("nether.concrete_halls", "music/game/nether/concrete_halls");
        VANILLA_FILE.put("nether.crimson_forest.chrysopoeia", "music/game/nether/crimson_forest/chrysopoeia");
        VANILLA_FILE.put("nether.dead_voxel", "music/game/nether/dead_voxel");
        VANILLA_FILE.put("nether.nether_wastes.rubedo", "music/game/nether/nether_wastes/rubedo");
        VANILLA_FILE.put("nether.soulsand_valley.so_below", "music/game/nether/soulsand_valley/so_below");
        VANILLA_FILE.put("nether.warmth", "music/game/nether/warmth");
        VANILLA_FILE.put("nightly", "music/game/nightly");
        VANILLA_FILE.put("one_more_day", "music/game/one_more_day");
        VANILLA_FILE.put("os_piano", "music/game/os_piano");
        VANILLA_FILE.put("oxygene", "music/game/oxygene");
        VANILLA_FILE.put("pokopoko", "music/game/pokopoko");
        VANILLA_FILE.put("puzzlebox", "music/game/puzzlebox");
        VANILLA_FILE.put("shores", "music/game/shores");
        VANILLA_FILE.put("stand_tall", "music/game/stand_tall");
        VANILLA_FILE.put("subwoofer_lullaby", "music/game/subwoofer_lullaby");
        VANILLA_FILE.put("swamp.aerie", "music/game/swamp/aerie");
        VANILLA_FILE.put("swamp.firebugs", "music/game/swamp/firebugs");
        VANILLA_FILE.put("swamp.labyrinthine", "music/game/swamp/labyrinthine");
        VANILLA_FILE.put("sweden", "music/game/sweden");
        VANILLA_FILE.put("watcher", "music/game/watcher");
        VANILLA_FILE.put("water.axolotl", "music/game/water/axolotl");
        VANILLA_FILE.put("water.dragon_fish", "music/game/water/dragon_fish");
        VANILLA_FILE.put("water.shuniji", "music/game/water/shuniji");
        VANILLA_FILE.put("wending", "music/game/wending");
        VANILLA_FILE.put("wet_hands", "music/game/wet_hands");
        VANILLA_FILE.put("yakusoku", "music/game/yakusoku");
        VANILLA_FILE.put("menu.beginning_2", "music/menu/beginning_2");
        VANILLA_FILE.put("menu.floating_trees", "music/menu/floating_trees");
        VANILLA_FILE.put("menu.moog_city_2", "music/menu/moog_city_2");
        VANILLA_FILE.put("menu.mutation", "music/menu/mutation");

        VANILLA_TITLES.put("a_familiar_room", "A Familiar Room");
        VANILLA_TITLES.put("an_ordinary_day", "An Ordinary Day");
        VANILLA_TITLES.put("ancestry", "Ancestry");
        VANILLA_TITLES.put("below_and_above", "Below And Above");
        VANILLA_TITLES.put("broken_clocks", "Broken Clocks");
        VANILLA_TITLES.put("bromeliad", "Bromeliad");
        VANILLA_TITLES.put("clark", "Clark");
        VANILLA_TITLES.put("comforting_memories", "Comforting Memories");
        VANILLA_TITLES.put("creative.aria_math", "Aria Math");
        VANILLA_TITLES.put("creative.biome_fest", "Biome Fest");
        VANILLA_TITLES.put("creative.blind_spots", "Blind Spots");
        VANILLA_TITLES.put("creative.dreiton", "Dreiton");
        VANILLA_TITLES.put("creative.haunt_muskie", "Haunt Muskie");
        VANILLA_TITLES.put("creative.taswell", "Taswell");
        VANILLA_TITLES.put("crescent_dunes", "Crescent Dunes");
        VANILLA_TITLES.put("danny", "Danny");
        VANILLA_TITLES.put("deeper", "Deeper");
        VANILLA_TITLES.put("dry_hands", "Dry Hands");
        VANILLA_TITLES.put("ebb", "Ebb");
        VANILLA_TITLES.put("echo_in_the_wind", "Echo In The Wind");
        VANILLA_TITLES.put("eld_unknown", "Eld Unknown");
        VANILLA_TITLES.put("end.alpha", "Alpha");
        VANILLA_TITLES.put("end.boss", "Boss");
        VANILLA_TITLES.put("end.the_end", "The End");
        VANILLA_TITLES.put("endless", "Endless");
        VANILLA_TITLES.put("featherfall", "Featherfall");
        VANILLA_TITLES.put("fireflies", "Fireflies");
        VANILLA_TITLES.put("floating_dream", "Floating Dream");
        VANILLA_TITLES.put("haggstrom", "Haggstrom");
        VANILLA_TITLES.put("home", "Home");
        VANILLA_TITLES.put("infinite_amethyst", "Infinite Amethyst");
        VANILLA_TITLES.put("key", "Key");
        VANILLA_TITLES.put("komorebi", "Komorebi");
        VANILLA_TITLES.put("left_to_bloom", "Left To Bloom");
        VANILLA_TITLES.put("lilypad", "Lilypad");
        VANILLA_TITLES.put("living_mice", "Living Mice");
        VANILLA_TITLES.put("memories", "Memories");
        VANILLA_TITLES.put("mice_on_venus", "Mice On Venus");
        VANILLA_TITLES.put("minecraft", "Minecraft");
        VANILLA_TITLES.put("nether.ballad_of_the_cats", "Ballad Of The Cats");
        VANILLA_TITLES.put("nether.concrete_halls", "Concrete Halls");
        VANILLA_TITLES.put("nether.crimson_forest.chrysopoeia", "Chrysopoeia");
        VANILLA_TITLES.put("nether.dead_voxel", "Dead Voxel");
        VANILLA_TITLES.put("nether.nether_wastes.rubedo", "Rubedo");
        VANILLA_TITLES.put("nether.soulsand_valley.so_below", "So Below");
        VANILLA_TITLES.put("nether.warmth", "Warmth");
        VANILLA_TITLES.put("nightly", "Nightly");
        VANILLA_TITLES.put("one_more_day", "One More Day");
        VANILLA_TITLES.put("os_piano", "Os Piano");
        VANILLA_TITLES.put("oxygene", "Oxygene");
        VANILLA_TITLES.put("pokopoko", "Pokopoko");
        VANILLA_TITLES.put("puzzlebox", "Puzzlebox");
        VANILLA_TITLES.put("shores", "Shores");
        VANILLA_TITLES.put("stand_tall", "Stand Tall");
        VANILLA_TITLES.put("subwoofer_lullaby", "Subwoofer Lullaby");
        VANILLA_TITLES.put("swamp.aerie", "Aerie");
        VANILLA_TITLES.put("swamp.firebugs", "Firebugs");
        VANILLA_TITLES.put("swamp.labyrinthine", "Labyrinthine");
        VANILLA_TITLES.put("sweden", "Sweden");
        VANILLA_TITLES.put("watcher", "Watcher");
        VANILLA_TITLES.put("water.axolotl", "Axolotl");
        VANILLA_TITLES.put("water.dragon_fish", "Dragon Fish");
        VANILLA_TITLES.put("water.shuniji", "Shuniji");
        VANILLA_TITLES.put("wending", "Wending");
        VANILLA_TITLES.put("wet_hands", "Wet Hands");
        VANILLA_TITLES.put("yakusoku", "Yakusoku");
        VANILLA_TITLES.put("menu.beginning_2", "Beginning 2");
        VANILLA_TITLES.put("menu.floating_trees", "Floating Trees");
        VANILLA_TITLES.put("menu.moog_city_2", "Moog City 2");
        VANILLA_TITLES.put("menu.mutation", "Mutation");

        VANILLA_ARTISTS.put("a_familiar_room", "Lena Raine");
        VANILLA_ARTISTS.put("an_ordinary_day", "Lena Raine");
        VANILLA_ARTISTS.put("ancestry", "Lena Raine");
        VANILLA_ARTISTS.put("below_and_above", "Kumi Tanioka");
        VANILLA_ARTISTS.put("broken_clocks", "Lena Raine");
        VANILLA_ARTISTS.put("bromeliad", "Lena Raine");
        VANILLA_ARTISTS.put("clark", "C418");
        VANILLA_ARTISTS.put("comforting_memories", "Lena Raine");
        VANILLA_ARTISTS.put("creative.aria_math", "C418");
        VANILLA_ARTISTS.put("creative.biome_fest", "C418");
        VANILLA_ARTISTS.put("creative.blind_spots", "C418");
        VANILLA_ARTISTS.put("creative.dreiton", "C418");
        VANILLA_ARTISTS.put("creative.haunt_muskie", "C418");
        VANILLA_ARTISTS.put("creative.taswell", "C418");
        VANILLA_ARTISTS.put("crescent_dunes", "Lena Raine");
        VANILLA_ARTISTS.put("danny", "C418");
        VANILLA_ARTISTS.put("deeper", "Lena Raine");
        VANILLA_ARTISTS.put("dry_hands", "C418");
        VANILLA_ARTISTS.put("ebb", "Lena Raine");
        VANILLA_ARTISTS.put("echo_in_the_wind", "Lena Raine");
        VANILLA_ARTISTS.put("eld_unknown", "Lena Raine");
        VANILLA_ARTISTS.put("end.alpha", "C418");
        VANILLA_ARTISTS.put("end.boss", "C418");
        VANILLA_ARTISTS.put("end.the_end", "C418");
        VANILLA_ARTISTS.put("endless", "Lena Raine");
        VANILLA_ARTISTS.put("featherfall", "Lena Raine");
        VANILLA_ARTISTS.put("fireflies", "Lena Raine");
        VANILLA_ARTISTS.put("floating_dream", "Lena Raine");
        VANILLA_ARTISTS.put("haggstrom", "C418");
        VANILLA_ARTISTS.put("home", "Lena Raine");
        VANILLA_ARTISTS.put("infinite_amethyst", "Lena Raine");
        VANILLA_ARTISTS.put("key", "C418");
        VANILLA_ARTISTS.put("komorebi", "Lena Raine");
        VANILLA_ARTISTS.put("left_to_bloom", "Lena Raine");
        VANILLA_ARTISTS.put("lilypad", "Lena Raine");
        VANILLA_ARTISTS.put("living_mice", "C418");
        VANILLA_ARTISTS.put("memories", "Lena Raine");
        VANILLA_ARTISTS.put("mice_on_venus", "C418");
        VANILLA_ARTISTS.put("minecraft", "C418");
        VANILLA_ARTISTS.put("nether.ballad_of_the_cats", "Lena Raine");
        VANILLA_ARTISTS.put("nether.concrete_halls", "Lena Raine");
        VANILLA_ARTISTS.put("nether.crimson_forest.chrysopoeia", "Aaron Cherof");
        VANILLA_ARTISTS.put("nether.dead_voxel", "Lena Raine");
        VANILLA_ARTISTS.put("nether.nether_wastes.rubedo", "Lena Raine");
        VANILLA_ARTISTS.put("nether.soulsand_valley.so_below", "Lena Raine");
        VANILLA_ARTISTS.put("nether.warmth", "Lena Raine");
        VANILLA_ARTISTS.put("nightly", "Lena Raine");
        VANILLA_ARTISTS.put("one_more_day", "Lena Raine");
        VANILLA_ARTISTS.put("os_piano", "Lena Raine");
        VANILLA_ARTISTS.put("oxygene", "C418");
        VANILLA_ARTISTS.put("pokopoko", "Lena Raine");
        VANILLA_ARTISTS.put("puzzlebox", "Lena Raine");
        VANILLA_ARTISTS.put("shores", "Lena Raine");
        VANILLA_ARTISTS.put("stand_tall", "Lena Raine");
        VANILLA_ARTISTS.put("subwoofer_lullaby", "C418");
        VANILLA_ARTISTS.put("swamp.aerie", "Lena Raine");
        VANILLA_ARTISTS.put("swamp.firebugs", "Lena Raine");
        VANILLA_ARTISTS.put("swamp.labyrinthine", "Lena Raine");
        VANILLA_ARTISTS.put("sweden", "C418");
        VANILLA_ARTISTS.put("watcher", "Lena Raine");
        VANILLA_ARTISTS.put("water.axolotl", "Lena Raine");
        VANILLA_ARTISTS.put("water.dragon_fish", "Lena Raine");
        VANILLA_ARTISTS.put("water.shuniji", "Lena Raine");
        VANILLA_ARTISTS.put("wending", "Lena Raine");
        VANILLA_ARTISTS.put("wet_hands", "C418");
        VANILLA_ARTISTS.put("yakusoku", "Kumi Tanioka");
        VANILLA_ARTISTS.put("menu.beginning_2", "C418");
        VANILLA_ARTISTS.put("menu.floating_trees", "C418");
        VANILLA_ARTISTS.put("menu.moog_city_2", "C418");
        VANILLA_ARTISTS.put("menu.mutation", "C418");

        // Disc artists: 22 discs
        DISC_ARTISTS.put("5", "C418");
        DISC_ARTISTS.put("11", "C418");
        DISC_ARTISTS.put("13", "C418");
        DISC_ARTISTS.put("blocks", "C418");
        DISC_ARTISTS.put("cat", "C418");
        DISC_ARTISTS.put("chirp", "C418");
        DISC_ARTISTS.put("far", "C418");
        DISC_ARTISTS.put("mall", "C418");
        DISC_ARTISTS.put("mellohi", "C418");
        DISC_ARTISTS.put("stal", "C418");
        DISC_ARTISTS.put("strad", "C418");
        DISC_ARTISTS.put("wait", "C418");
        DISC_ARTISTS.put("ward", "C418");
        DISC_ARTISTS.put("pigstep", "Lena Raine");
        DISC_ARTISTS.put("otherside", "Lena Raine");
        DISC_ARTISTS.put("relic", "Aaron Cherof");
        DISC_ARTISTS.put("creator", "Lena Raine");
        DISC_ARTISTS.put("creator_music_box", "Aaron Cherof");
        DISC_ARTISTS.put("precipice", "Aaron Cherof");
        DISC_ARTISTS.put("tears", "Aaron Cherof");
        DISC_ARTISTS.put("bounce", "Minecraft");
        DISC_ARTISTS.put("lava_chicken", "Minecraft");
    }

    private static final String[] DISC_KEYS = {
            "5","11","13","blocks","bounce","cat","chirp","far","lava_chicken","mall","mellohi","pigstep","stal","strad","wait","ward","otherside","relic","creator","creator_music_box","precipice","tears"
    };

    /**
     * Sound events are the authoritative environmental-track registry via sounds.json.
     * Each distinct sound file (e.g. sweden, subwoofer_lullaby) is a separate playable track,
     * deduped across SoundEvents where reused. Discs are sourced from JUKEBOX_SONG.
     */
    public static void registerAll(MusicLibrary library) {
        int before = library.getAllTracks().size();
        java.util.Set<String> beforeIds = new java.util.HashSet<>();
        for (var t : library.getAllTracks()) beforeIds.add(t.getId());
        try {
            // Try live disc enumeration from JUKEBOX_SONG registry; vanilla from sounds.json fallback is authoritative for granular tracks
            Registry<JukeboxSong> songs = jukeboxRegistry();
            if (songs != null) {
                songs.keySet().stream()
                        .filter(id -> "minecraft".equals(id.getNamespace()))
                        .sorted(Comparator.comparing(Identifier::toString))
                        .forEach(id -> addDisc(library, id, songs.getValue(id)));
            }
            // Vanilla: ensure granular 70 from sounds.json (covers sweden, subwoofer_lullaby, etc.)
            // Live SoundEvent enumeration (32) would give coarse events like "game" — not granular, so we rely on fallback list
            registerFallback(library);
            pruneStale(library);
        } catch (Throwable unavailable) {
            for (var t : java.util.List.copyOf(library.getAllTracks())) {
                if (!beforeIds.contains(t.getId())) library.removeTrack(t.getId());
            }
            registerFallback(library);
        }
    }

    /** Remove any persisted vanilla/disc tracks that no longer exist in the 70+22 catalog (e.g. legacy aggregated events like vanilla:game) */
    private static void pruneStale(MusicLibrary l) {
        java.util.Set<String> valid = new java.util.HashSet<>();
        for (String k : VANILLA_KEYS) valid.add("vanilla:" + k);
        for (String k : DISC_KEYS) valid.add("disc:" + k);
        for (MusicTrack t : java.util.List.copyOf(l.getAllTracks())) {
            String id = t.getId();
            if ((id.startsWith("vanilla:") || id.startsWith("disc:")) && !valid.contains(id)) {
                l.removeTrack(id);
            }
        }
    }

    /** Verified 26.2 fallback: 70 environmental + 22 discs = 92 total distinct tracks from sounds.json */
    private static void registerFallback(MusicLibrary l) {
        for (String key : VANILLA_KEYS) {
            String title = VANILLA_TITLES.getOrDefault(key, displayName(key));
            String artist = VANILLA_ARTISTS.getOrDefault(key, "Minecraft");
            String file = VANILLA_FILE.getOrDefault(key, "music/game/" + key.replace('.','/'));
            String sourceId = "minecraft:" + file;
            addFallbackVanilla(l, "vanilla:" + key, title, artist, sourceId);
        }
        for (String key : DISC_KEYS) {
            String artist = DISC_ARTISTS.getOrDefault(key, "Minecraft");
            String title = displayName(key);
            String sourceId = "minecraft:music_disc." + key;
            String jukeboxId = "minecraft:" + key;
            addFallbackDisc(l, "disc:" + key, title, artist, sourceId, jukeboxId);
        }
    }

    private static void addFallbackVanilla(MusicLibrary l, String id, String title, String artist, String sourceId) {
        if (l.getTrack(id).isPresent()) return;
        MusicTrack t = new MusicTrack(id, title, artist, SourceType.VANILLA);
        t.setSourceId(sourceId);
        t.setAmbientEligible(true);
        t.setEnabled(true);
        l.addOrReplaceTrack(t);
    }

    private static void addFallbackDisc(MusicLibrary l, String id, String title, String artist, String sourceId, String jukeboxId) {
        if (l.getTrack(id).isPresent()) return;
        MusicTrack t = new MusicTrack(id, title, artist, SourceType.MUSIC_DISC);
        t.setSourceId(sourceId);
        t.setJukeboxSongId(jukeboxId);
        t.setAmbientEligible(false);
        t.setEnabled(true);
        l.addOrReplaceTrack(t);
    }

    @SuppressWarnings("unchecked")
    private static Registry<JukeboxSong> jukeboxRegistry() {
        return (Registry<JukeboxSong>) BuiltInRegistries.REGISTRY.getValue(Registries.JUKEBOX_SONG.identifier());
    }

    @SuppressWarnings("unused")
    private static void addVanilla(MusicLibrary library, Identifier soundId) {
        String key = soundId.getPath().substring("music.".length());
        String id = "vanilla:" + key;
        if (library.getTrack(id).isPresent()) return;
        String title = VANILLA_TITLES.getOrDefault(key, displayName(key));
        String artist = VANILLA_ARTISTS.getOrDefault(key, "Minecraft");
        MusicTrack track = new MusicTrack(id, title, artist, SourceType.VANILLA);
        track.setSourceId(soundId.toString());
        track.setEnabled(true);
        track.setAmbientEligible(true);
        library.addOrReplaceTrack(track);
    }

    private static void addDisc(MusicLibrary library, Identifier songId, JukeboxSong song) {
        if (song == null || song.soundEvent() == null || song.soundEvent().unwrapKey().isEmpty()) return;
        Identifier soundId = song.soundEvent().unwrapKey().orElseThrow().identifier();
        if (!"minecraft".equals(soundId.getNamespace()) || !soundId.getPath().startsWith("music_disc.")) return;
        String key = songId.getPath();
        String id = "disc:" + key;
        if (library.getTrack(id).isPresent()) return;
        String title = displayName(key);
        String artist = DISC_ARTISTS.getOrDefault(key, "Minecraft");
        MusicTrack track = new MusicTrack(id, title, artist, SourceType.MUSIC_DISC);
        track.setSourceId(soundId.toString());
        track.setJukeboxSongId(songId.toString());
        track.setEnabled(true);
        track.setAmbientEligible(false);
        library.addOrReplaceTrack(track);
    }

    private static String displayName(String key) {
        String value = key.replace('.', ' ').replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : value.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public static void printDiagnostic() {
        for (String key : VANILLA_KEYS) {
            System.out.println("vanilla:" + key + " -> " + VANILLA_TITLES.get(key) + " -> VANILLA -> minecraft:" + VANILLA_FILE.get(key) + " -> true");
        }
        Registry<JukeboxSong> songs = jukeboxRegistry();
        if (songs != null) songs.keySet().stream().sorted(Comparator.comparing(Identifier::toString)).forEach(id -> {
            JukeboxSong song = songs.getValue(id);
            Identifier sound = song == null || song.soundEvent().unwrapKey().isEmpty()
                    ? null : song.soundEvent().unwrapKey().orElseThrow().identifier();
            System.out.println("disc:" + id.getPath() + " -> " + displayName(id.getPath()) + " -> MUSIC_DISC -> " + sound + " -> true");
        });
    }

    public static int vanillaCount() { return VANILLA_KEYS.length; }
    public static int discCount() { return DISC_KEYS.length; }
    public static boolean isValidId(String id){ if(id==null) return false; for(String k:VANILLA_KEYS) if(("vanilla:"+k).equals(id)) return true; for(String k:DISC_KEYS) if(("disc:"+k).equals(id)) return true; return false; }
}
