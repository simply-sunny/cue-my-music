package mix.cuemymusic;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.dsp.PitchTempoProcessor;

/** Settings, stored as {@code config/cuemymusic/config.json}. Read live by the audio thread. */
public final class CueConfig {
    public static final CueConfig INSTANCE = new CueConfig();
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public volatile boolean enabled = true;
    public volatile int pitchSemitones = 0;
    public volatile double speed = 1.0;
    public volatile boolean eqEnabled = false;
    /** dB per band, see {@link Equalizer#FREQUENCIES}. Replaced whole, never mutated in place. */
    public volatile double[] eqGains = new double[Equalizer.FREQUENCIES.length];
    public volatile String eqPreset = "flat";
    /** Liked songs as sound file ids ("minecraft:music/game/sweden"), oldest first. Replaced whole, never mutated in place. */
    public volatile List<String> liked = List.of();
    /** Pages shown as a mix, by the player's key for them ("list:<id>", "liked"...). Replaced whole, never mutated in place. */
    public volatile List<String> mixPages = List.of();
    /** Pause the player's song while a new dimension loads, rather than carry on. */
    public volatile boolean pauseOnTravel = false;
    /** Pause the player's song while the game window isn't in front, and play it again when it is. */
    public volatile boolean pauseOnLostFocus = true;
    /** Next plays the transition into the next song, from the coming bar, rather than cutting to it. */
    public volatile boolean skipWithTransition = true;
    /** Lists play the song picked, then the rest in a random order. */
    public volatile boolean shuffle;
    /** When a list ends, songs like it carry on, as Spotify's Autoplay; off hands back to the game's music. */
    public volatile boolean autoplay = true;
    /** Playlists made in the player, in the order they were made. Replaced whole, never mutated in place. */
    public volatile List<Playlist> playlists = List.of();
    /** "off", "all" or "one". */
    public volatile String repeat = "off";
    /** Songs the game never picks for its own music, as sound file ids. They still play when picked in the player. */
    public volatile List<String> disliked = List.of();
    /** Library sections folded shut: "artists", "packs", "playlists". */
    public volatile List<String> collapsed = List.of();
    /**
     * Vanilla music: Cue My Music and its add-ons are off, and the game plays
     * its own music exactly as without them. Only the player's window stays,
     * to turn this back off.
     */
    public volatile boolean vanilla = false;
    /** Blend songs on add-on pages into each other, at matched loudness. */
    public volatile boolean automix = true;
    /** Seconds each song fades into the next one played through the player, where automix doesn't blend them; 0 for none. */
    public volatile int crossfadeSeconds = 4;
    /** Play songs on add-on pages from a separate process, so recordings of the game's audio leave them out. */
    public volatile boolean keepOutOfRecordings = true;
    /** Transitions set in the mix editor, by "first song>second song". Replaced whole, never mutated in place. */
    public volatile Map<String, Automix.Transition> transitions = Map.of();
    /** The player's page last open, as "VIEW" or "VIEW:key", so it opens there again. */
    public volatile String lastPage = "";
    /** When each list was last played, in epoch milliseconds, by the player's list key; for the library's Recents and Home. */
    public volatile Map<String, Long> listsPlayed = Map.of();
    /**
     * With vanilla music off, where songs come from: "fx", the game's music
     * with Cue My Music's effects, or "addons", songs from packs and add-ons
     * on the game's schedule. Vanilla music on is the third mode, pure vanilla.
     */
    public volatile String mode = "fx";
    /** The library's order: "default", "recents" or "alphabetical". */
    public volatile String librarySort = "default";
    /** The library panel's width in GUI units, or 0 to follow the window. Set by dragging its edge. */
    public volatile int libraryWidth = 0;
    /** The now playing panel's width in GUI units, 0 to follow the window, or -1 when pushed away to a thin strip. */
    public volatile int nowPlayingWidth = 0;
    /** The library's filter chip: "all", "playlists" or "artists". */
    public volatile String libraryFilter = "all";
    /** What Radio plays: "all" (the game's music and add-on songs), "game" or "addons". */
    public volatile String radioSource = "all";
    /** The mode key goes round all three modes; otherwise it flips between Vanilla + FX and Add-ons. */
    public volatile boolean modeKeyCyclesAll = false;
    /** Seconds between the game's songs, in place of its own Frequency roll; -1 keeps the game's. */
    public volatile int songGap = -1;
    /** Your changes to the game's track pools, by pool (a sound event such as "minecraft:music.game"). */
    public volatile Map<String, TrackPools.Edit> poolEdits = Map.of();
    /** A transition being edited and not saved yet, which plays in its place so it can be heard; not saved. */
    private transient volatile String previewKey;
    private transient volatile Automix.Transition preview;

    private CueConfig() {
    }

    /** A playlist: its songs as sound file ids, in the order they were added. */
    public record Playlist(String id, String name, List<String> songs) {
    }

    public Playlist playlist(String id) {
        for (Playlist p : playlists) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /** Makes an empty playlist and saves. Returns its id. */
    public String createPlaylist(String name) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        while (playlist(id) != null) id += "x";
        List<Playlist> next = new ArrayList<>(playlists);
        next.add(new Playlist(id, name, List.of()));
        playlists = List.copyOf(next);
        save();
        return id;
    }

    public void renamePlaylist(String id, String name) {
        replacePlaylist(id, p -> new Playlist(p.id(), name, p.songs()));
    }

    public void deletePlaylist(String id) {
        playlists = playlists.stream().filter(p -> !p.id().equals(id)).toList();
        save();
    }

    /** Adds the song unless it's already there. Returns false if it was. */
    public boolean addToPlaylist(String id, String song) {
        Playlist p = playlist(id);
        if (p == null || p.songs().contains(song)) return false;
        List<String> songs = new ArrayList<>(p.songs());
        songs.add(song);
        replacePlaylist(id, old -> new Playlist(old.id(), old.name(), List.copyOf(songs)));
        return true;
    }

    public void removeFromPlaylist(String id, String song) {
        replacePlaylist(id, p -> new Playlist(p.id(), p.name(), p.songs().stream().filter(s -> !s.equals(song)).toList()));
    }

    /** Moves {@code moving} to just before {@code before}, or to the end if that's null, keeping their order. */
    public void movePlaylistSongs(String id, java.util.Collection<String> moving, String before) {
        replacePlaylist(id, p -> new Playlist(p.id(), p.name(), List.copyOf(PlayOrder.moved(p.songs(), moving, before))));
    }

    private void replacePlaylist(String id, java.util.function.UnaryOperator<Playlist> change) {
        playlists = playlists.stream().map(p -> p.id().equals(id) ? change.apply(p) : p).toList();
        save();
    }

    public double pitchRatio() {
        return enabled ? PitchTempoProcessor.semitonesToRatio(pitchSemitones) : 1;
    }

    public double tempo() {
        return enabled ? speed : 1;
    }

    /** Band gains to apply right now, or null for none. */
    public double[] activeEq() {
        return enabled && eqEnabled ? eqGains : null;
    }

    public PlayOrder.Repeat repeatMode() {
        for (PlayOrder.Repeat r : PlayOrder.Repeat.values()) {
            if (r.name().equalsIgnoreCase(repeat)) return r;
        }
        return PlayOrder.Repeat.OFF;
    }

    public void setRepeat(PlayOrder.Repeat mode) {
        repeat = mode.name().toLowerCase(java.util.Locale.ROOT);
        save();
    }

    public boolean isMixPage(String key) {
        return mixPages.contains(key);
    }

    /** Shows the page as a mix, or as its songs, from now on, and saves. */
    public void setMixPage(String key, boolean on) {
        List<String> next = new ArrayList<>(mixPages);
        next.remove(key);
        if (on) next.add(key);
        mixPages = List.copyOf(next);
        save();
    }

    public boolean isLiked(String id) {
        return liked.contains(id);
    }

    /** Likes the song, or unlikes it if it's already liked, and saves. Liking takes away a dislike. */
    public void toggleLiked(String id) {
        List<String> next = new ArrayList<>(liked);
        if (!next.remove(id)) {
            next.add(id);
            disliked = disliked.stream().filter(d -> !d.equals(id)).toList();
        }
        liked = List.copyOf(next);
        save();
    }

    public boolean isDisliked(String id) {
        return disliked.contains(id);
    }

    /** Dislikes the song, or takes the dislike away, and saves. Disliking takes away a like. */
    public void toggleDisliked(String id) {
        List<String> next = new ArrayList<>(disliked);
        if (!next.remove(id)) {
            next.add(id);
            liked = liked.stream().filter(l -> !l.equals(id)).toList();
        }
        disliked = List.copyOf(next);
        save();
    }

    /** The transition from song {@code from} into song {@code to}: as set in the mix editor, or automatic. */
    public Automix.Transition transition(String from, String to) {
        Automix.Transition p = preview;
        if (p != null && (from + ">" + to).equals(previewKey)) return p;
        return savedTransition(from, to);
    }

    /** The transition as saved, leaving out one being edited. */
    public Automix.Transition savedTransition(String from, String to) {
        Automix.Transition t = transitions.get(from + ">" + to);
        return t != null ? t : Automix.Transition.AUTO;
    }

    /** Sets a transition. Setting it to automatic forgets it. */
    public synchronized void setTransition(String from, String to, Automix.Transition t) {
        Map<String, Automix.Transition> next = new HashMap<>(transitions);
        if (t.equals(Automix.Transition.AUTO)) next.remove(from + ">" + to);
        else next.put(from + ">" + to, t);
        transitions = Map.copyOf(next);
    }

    /** The steps the time between songs moves in, in seconds; -1 is the game's own. */
    public static final List<Integer> SONG_GAPS = List.of(-1, 0, 5, 15, 30, 60, 120, 300, 600, 1200);

    public TrackPools.Edit poolEdit(String pool) {
        return poolEdits.getOrDefault(pool, TrackPools.Edit.NONE);
    }

    public void setPoolEdit(String pool, TrackPools.Edit edit) {
        Map<String, TrackPools.Edit> next = new HashMap<>(poolEdits);
        if (edit == null || edit.isEmpty()) next.remove(pool);
        else next.put(pool, edit);
        poolEdits = Map.copyOf(next);
        save();
    }

    /** Plays {@code t} from {@code from} into {@code to} while it's edited, without saving it; null stops. */
    public void previewTransition(String from, String to, Automix.Transition t) {
        preview = null;
        previewKey = t == null ? null : from + ">" + to;
        preview = t;
    }

    /** Notes that the list named {@code key} started playing now. */
    public void listPlayed(String key) {
        Map<String, Long> next = new HashMap<>(listsPlayed);
        next.put(key, System.currentTimeMillis());
        // The 50 most recent are plenty for Recents and Home.
        while (next.size() > 50) next.remove(java.util.Collections.min(next.entrySet(), Map.Entry.comparingByValue()).getKey());
        listsPlayed = Map.copyOf(next);
        save();
    }

    public boolean isCollapsed(String section) {
        return collapsed.contains(section);
    }

    public void toggleCollapsed(String section) {
        List<String> next = new ArrayList<>(collapsed);
        if (!next.remove(section)) next.add(section);
        collapsed = List.copyOf(next);
        save();
    }

    public void clamp() {
        pitchSemitones = Math.clamp(pitchSemitones, -12, 12);
        speed = Math.clamp(Double.isFinite(speed) ? Math.round(speed * 20) / 20.0 : 1, 0.5, 2.0);
        crossfadeSeconds = Math.clamp(crossfadeSeconds, 0, 12);
        double[] g = new double[Equalizer.FREQUENCIES.length];
        for (int i = 0; i < g.length; i++) {
            double v = eqGains != null && i < eqGains.length && Double.isFinite(eqGains[i]) ? eqGains[i] : 0;
            g[i] = Math.clamp(v, -Equalizer.MAX_GAIN_DB, Equalizer.MAX_GAIN_DB);
        }
        eqGains = g;
        if (eqPreset == null) eqPreset = "custom";
        liked = liked == null ? List.of() : liked.stream().filter(Objects::nonNull).distinct().toList();
        disliked = disliked == null ? List.of() : disliked.stream().filter(Objects::nonNull).distinct().toList();
        collapsed = collapsed == null ? List.of() : collapsed.stream().filter(Objects::nonNull).distinct().toList();
        if (transitions == null) transitions = Map.of();
        if (!List.of("all", "game", "addons").contains(radioSource)) radioSource = "all";
        if (lastPage == null) lastPage = "";
        Map<String, Long> played = new HashMap<>();
        if (listsPlayed != null) listsPlayed.forEach((k, v) -> {
            if (k != null && v != null) played.put(k, v);
        });
        listsPlayed = Map.copyOf(played);
        if (!List.of("fx", "addons").contains(mode)) mode = "fx";
        if (!SONG_GAPS.contains(songGap)) songGap = -1;
        Map<String, TrackPools.Edit> edits = new HashMap<>();
        if (poolEdits != null) poolEdits.forEach((k, v) -> {
            if (k != null && v != null && !v.isEmpty()) edits.put(k, new TrackPools.Edit(v.removed(), v.added()));
        });
        poolEdits = Map.copyOf(edits);
        if (!List.of("default", "recents", "alphabetical").contains(librarySort)) librarySort = "default";
        if (!List.of("all", "playlists", "artists").contains(libraryFilter)) libraryFilter = "all";
        libraryWidth = libraryWidth <= 0 ? 0 : Math.clamp(libraryWidth, PanelLayout.ICON_W, 600);
        nowPlayingWidth = nowPlayingWidth == 0 ? 0 : nowPlayingWidth < 0 ? PanelLayout.COLLAPSED : Math.clamp(nowPlayingWidth, PanelLayout.NOW_MIN, 600);
        Map<String, Automix.Transition> kept = new HashMap<>();
        transitions.forEach((k, v) -> {
            if (k != null && v != null && v.preset() != null) kept.put(k, v);
        });
        transitions = Map.copyOf(kept);
        repeat = repeatMode().name().toLowerCase(java.util.Locale.ROOT);
        playlists = playlists == null ? List.of() : playlists.stream()
                .filter(p -> p != null && p.id() != null)
                .map(p -> new Playlist(p.id(), p.name() == null || p.name().isBlank() ? "Playlist" : p.name(),
                        p.songs() == null ? List.of() : p.songs().stream().filter(Objects::nonNull).distinct().toList()))
                .toList();
    }

    private static Path path() {
        return CueFiles.dir().resolve("config.json");
    }

    public void load() {
        Path path = path();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                CueConfig loaded = GSON.fromJson(r, CueConfig.class);
                if (loaded != null) copyFrom(loaded);
            } catch (IOException | JsonParseException e) {
                LOG.warn("Couldn't read {}, using defaults", path, e);
            }
        }
        clamp();
        save();
    }

    public void save() {
        clamp();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            LOG.warn("Couldn't save {}", path, e);
        }
    }

    private void copyFrom(CueConfig o) {
        enabled = o.enabled;
        pitchSemitones = o.pitchSemitones;
        speed = o.speed;
        eqEnabled = o.eqEnabled;
        eqGains = o.eqGains;
        eqPreset = o.eqPreset;
        liked = o.liked;
        pauseOnTravel = o.pauseOnTravel;
        pauseOnLostFocus = o.pauseOnLostFocus;
        skipWithTransition = o.skipWithTransition;
        shuffle = o.shuffle;
        autoplay = o.autoplay;
        automix = o.automix;
        crossfadeSeconds = o.crossfadeSeconds;
        vanilla = o.vanilla;
        keepOutOfRecordings = o.keepOutOfRecordings;
        repeat = o.repeat;
        mixPages = o.mixPages == null ? List.of() : o.mixPages;
        playlists = o.playlists;
        disliked = o.disliked;
        collapsed = o.collapsed;
        transitions = o.transitions;
        radioSource = o.radioSource;
        lastPage = o.lastPage;
        listsPlayed = o.listsPlayed;
        mode = o.mode;
        librarySort = o.librarySort;
        libraryFilter = o.libraryFilter == null ? "all" : o.libraryFilter;
        libraryWidth = o.libraryWidth;
        nowPlayingWidth = o.nowPlayingWidth;
        modeKeyCyclesAll = o.modeKeyCyclesAll;
        songGap = o.songGap;
        poolEdits = o.poolEdits;
    }
}
