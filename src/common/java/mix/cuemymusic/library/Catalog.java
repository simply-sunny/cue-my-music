package mix.cuemymusic.library;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Credits and lengths for the background music that ships with 26.3, so they
 * don't have to be worked out at runtime. Keyed by sound path, e.g.
 * {@code music/game/sweden}. Credits follow the Minecraft Wiki's Music page;
 * lengths were measured from the 26.3 assets.
 */
public final class Catalog {
    public record Album(String name, String texture) {
    }

    public record Entry(String title, String artist, Album album, double seconds) {
    }

    public static final Album VOLUME_ALPHA = new Album("Minecraft - Volume Alpha", "block/grass_block_side");
    public static final Album VOLUME_BETA = new Album("Minecraft - Volume Beta", "block/mossy_cobblestone");
    // Released with the game update but not on a soundtrack album.
    public static final Album UPDATE_AQUATIC = new Album("Update Aquatic", "block/dark_prismarine");
    public static final Album UNRELEASED = new Album("Unreleased", "block/dragon_egg");
    public static final Album NETHER_UPDATE = new Album("Nether Update", "block/crimson_nylium");
    public static final Album CAVES_AND_CLIFFS = new Album("Caves & Cliffs", "block/amethyst_block");
    public static final Album WILD_UPDATE = new Album("The Wild Update", "block/mud");
    public static final Album TRAILS_AND_TALES = new Album("Trails & Tales", "block/cherry_leaves");
    public static final Album TRICKY_TRIALS = new Album("Tricky Trials", "block/chiseled_copper");
    public static final Album CHASE_THE_SKIES = new Album("Chase the Skies", "block/blue_ice");
    public static final Album CHAOS_CUBED = new Album("Chaos Cubed", "block/chiseled_sulfur");
    /** For tracks added by resource packs. */
    public static final Album UNKNOWN_ALBUM = new Album("Resource pack", "block/note_block");

    public static final String C418 = "C418";
    public static final String LENA_RAINE = "Lena Raine";
    public static final String AARON_CHEROF = "Aaron Cherof";
    public static final String KUMI_TANIOKA = "Kumi Tanioka";
    public static final String AMOS_RODDY = "Amos Roddy";
    public static final String FINGERSPIT = "fingerspit";
    public static final String UNKNOWN_ARTIST = "Unknown artist";

    /** Sidebar order. */
    public static final List<String> ARTISTS = List.of(C418, LENA_RAINE, AARON_CHEROF, KUMI_TANIOKA, AMOS_RODDY, FINGERSPIT);

    /** Each artist's picture: the art of an album they're known for. */
    public static final Map<String, Album> ARTIST_ART = Map.of(
            C418, VOLUME_ALPHA,
            LENA_RAINE, NETHER_UPDATE,
            AARON_CHEROF, TRAILS_AND_TALES,
            KUMI_TANIOKA, CAVES_AND_CLIFFS,
            AMOS_RODDY, CHASE_THE_SKIES,
            FINGERSPIT, CHAOS_CUBED,
            UNKNOWN_ARTIST, UNKNOWN_ALBUM);

    public static final Map<String, Entry> TRACKS = new LinkedHashMap<>();

    static {
        add("music/game/key", "Key", C418, VOLUME_ALPHA, 61.09);
        add("music/game/subwoofer_lullaby", "Subwoofer Lullaby", C418, VOLUME_ALPHA, 209.19);
        add("music/game/living_mice", "Living Mice", C418, VOLUME_ALPHA, 188.00);
        add("music/game/haggstrom", "Haggstrom", C418, VOLUME_ALPHA, 191.05);
        add("music/game/minecraft", "Minecraft", C418, VOLUME_ALPHA, 260.54);
        add("music/game/oxygene", "Oxygène", C418, VOLUME_ALPHA, 69.82);
        add("music/game/mice_on_venus", "Mice on Venus", C418, VOLUME_ALPHA, 280.89);
        add("music/game/dry_hands", "Dry Hands", C418, VOLUME_ALPHA, 68.12);
        add("music/game/wet_hands", "Wet Hands", C418, VOLUME_ALPHA, 89.71);
        add("music/game/clark", "Clark", C418, VOLUME_ALPHA, 201.59);
        add("music/game/sweden", "Sweden", C418, VOLUME_ALPHA, 229.77);
        add("music/game/danny", "Danny", C418, VOLUME_ALPHA, 255.26);

        add("music/game/end/alpha", "Alpha", C418, VOLUME_BETA, 603.00);
        add("music/game/nether/dead_voxel", "Dead Voxel", C418, VOLUME_BETA, 303.80);
        add("music/game/creative/blind_spots", "Blind Spots", C418, VOLUME_BETA, 332.05);
        add("music/menu/moog_city_2", "Moog City 2", C418, VOLUME_BETA, 183.65);
        add("music/game/nether/concrete_halls", "Concrete Halls", C418, VOLUME_BETA, 259.46);
        add("music/game/creative/biome_fest", "Biome Fest", C418, VOLUME_BETA, 382.94);
        add("music/menu/mutation", "Mutation", C418, VOLUME_BETA, 194.59);
        add("music/game/creative/haunt_muskie", "Haunt Muskie", C418, VOLUME_BETA, 361.68);
        add("music/game/nether/warmth", "Warmth", C418, VOLUME_BETA, 239.47);
        add("music/menu/floating_trees", "Floating Trees", C418, VOLUME_BETA, 244.75);
        add("music/game/creative/aria_math", "Aria Math", C418, VOLUME_BETA, 309.50);
        add("music/game/nether/ballad_of_the_cats", "Ballad of the Cats", C418, VOLUME_BETA, 280.00);
        add("music/game/creative/taswell", "Taswell", C418, VOLUME_BETA, 515.00);
        add("music/menu/beginning_2", "Beginning 2", C418, VOLUME_BETA, 182.60);
        add("music/game/creative/dreiton", "Dreiton", C418, VOLUME_BETA, 497.00);
        add("music/game/end/the_end", "The End", C418, VOLUME_BETA, 904.53);

        add("music/game/water/axolotl", "Axolotl", C418, UPDATE_AQUATIC, 303.14);
        add("music/game/water/dragon_fish", "Dragon Fish", C418, UPDATE_AQUATIC, 372.25);
        add("music/game/water/shuniji", "Shuniji", C418, UPDATE_AQUATIC, 244.20);
        add("music/game/end/boss", "Boss", C418, UNRELEASED, 344.15);

        add("music/game/nether/crimson_forest/chrysopoeia", "Chrysopoeia", LENA_RAINE, NETHER_UPDATE, 303.42);
        add("music/game/nether/nether_wastes/rubedo", "Rubedo", LENA_RAINE, NETHER_UPDATE, 312.02);
        add("music/game/nether/soulsand_valley/so_below", "So Below", LENA_RAINE, NETHER_UPDATE, 318.83);

        add("music/game/stand_tall", "Stand Tall", LENA_RAINE, CAVES_AND_CLIFFS, 308.40);
        add("music/game/left_to_bloom", "Left to Bloom", LENA_RAINE, CAVES_AND_CLIFFS, 342.96);
        add("music/game/ancestry", "Ancestry", LENA_RAINE, CAVES_AND_CLIFFS, 343.51);
        add("music/game/wending", "Wending", LENA_RAINE, CAVES_AND_CLIFFS, 314.74);
        add("music/game/infinite_amethyst", "Infinite Amethyst", LENA_RAINE, CAVES_AND_CLIFFS, 271.79);
        add("music/game/one_more_day", "One More Day", LENA_RAINE, CAVES_AND_CLIFFS, 278.55);
        add("music/game/floating_dream", "Floating Dream", KUMI_TANIOKA, CAVES_AND_CLIFFS, 205.20);
        add("music/game/comforting_memories", "Comforting Memories", KUMI_TANIOKA, CAVES_AND_CLIFFS, 275.20);
        add("music/game/an_ordinary_day", "An Ordinary Day", KUMI_TANIOKA, CAVES_AND_CLIFFS, 331.36);

        add("music/game/swamp/firebugs", "Firebugs", LENA_RAINE, WILD_UPDATE, 312.72);
        add("music/game/swamp/aerie", "Aerie", LENA_RAINE, WILD_UPDATE, 296.79);
        add("music/game/swamp/labyrinthine", "Labyrinthine", LENA_RAINE, WILD_UPDATE, 324.60);

        add("music/game/echo_in_the_wind", "Echo in the Wind", AARON_CHEROF, TRAILS_AND_TALES, 296.18);
        add("music/game/a_familiar_room", "A Familiar Room", AARON_CHEROF, TRAILS_AND_TALES, 241.20);
        add("music/game/bromeliad", "Bromeliad", AARON_CHEROF, TRAILS_AND_TALES, 312.28);
        add("music/game/crescent_dunes", "Crescent Dunes", AARON_CHEROF, TRAILS_AND_TALES, 248.35);

        add("music/game/featherfall", "Featherfall", AARON_CHEROF, TRICKY_TRIALS, 344.08);
        add("music/game/watcher", "Watcher", AARON_CHEROF, TRICKY_TRIALS, 332.15);
        add("music/game/puzzlebox", "Puzzlebox", AARON_CHEROF, TRICKY_TRIALS, 299.63);
        add("music/game/komorebi", "komorebi", KUMI_TANIOKA, TRICKY_TRIALS, 287.65);
        add("music/game/pokopoko", "pokopoko", KUMI_TANIOKA, TRICKY_TRIALS, 304.55);
        add("music/game/yakusoku", "yakusoku", KUMI_TANIOKA, TRICKY_TRIALS, 271.01);
        add("music/game/deeper", "Deeper", LENA_RAINE, TRICKY_TRIALS, 303.44);
        add("music/game/eld_unknown", "Eld Unknown", LENA_RAINE, TRICKY_TRIALS, 296.55);
        add("music/game/endless", "Endless", LENA_RAINE, TRICKY_TRIALS, 402.15);

        add("music/game/lilypad", "Lilypad", AMOS_RODDY, CHASE_THE_SKIES, 235.88);
        add("music/game/below_and_above", "Below and Above", AMOS_RODDY, CHASE_THE_SKIES, 212.38);
        add("music/game/os_piano", "O's Piano", AMOS_RODDY, CHASE_THE_SKIES, 275.13);
        add("music/game/broken_clocks", "Broken Clocks", AMOS_RODDY, CHASE_THE_SKIES, 213.75);
        add("music/game/fireflies", "Fireflies", AMOS_RODDY, CHASE_THE_SKIES, 155.54);

        add("music/game/memories", "Memories", FINGERSPIT, CHAOS_CUBED, 245.29);
        add("music/game/ebb", "Ebb", FINGERSPIT, CHAOS_CUBED, 306.49);
        add("music/game/home", "Home", FINGERSPIT, CHAOS_CUBED, 380.21);
        add("music/game/shores", "Shores", FINGERSPIT, CHAOS_CUBED, 320.00);
        add("music/game/nightly", "Nightly", FINGERSPIT, CHAOS_CUBED, 302.14);
    }

    private Catalog() {
    }

    private static void add(String path, String title, String artist, Album album, double seconds) {
        TRACKS.put(path, new Entry(title, artist, album, seconds));
    }

    /** Readable title for a track that isn't in the catalog: {@code music/game/my_song} → "My Song". */
    public static String titleFromPath(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : name.split("[_\\-]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? path : out.toString();
    }
}
