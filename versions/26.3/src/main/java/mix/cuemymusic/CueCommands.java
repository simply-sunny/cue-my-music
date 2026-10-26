package mix.cuemymusic;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * {@code /cmm play [song]}, {@code /cmm queue <song>}, {@code /cmm next},
 * {@code /cmm prev}, {@code /cmm pause}, {@code /cmm repeat [off|all|one]} and {@code /cmm dislike [song]}. Client-side, so they work on any server.
 * Songs are named by title; the chat suggests them as you type.
 */
final class CueCommands {
    /** The list key for a song played from a command: just that song, then the queue and the game. */
    private static final String COMMAND = "command";

    private CueCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("cmm")
                // Off with vanilla music on.
                .requires(source -> !mix.cuemymusic.CueConfig.INSTANCE.vanilla)
                .then(ClientCommands.literal("play")
                        .executes(CueCommands::resume)
                        .then(ClientCommands.argument("song", StringArgumentType.greedyString())
                                .suggests(CueCommands::suggestSongs)
                                .executes(c -> withSong(c, (src, t) -> {
                                    CuePlayer.INSTANCE.play(COMMAND, List.of(t), 0);
                                    src.sendFeedback(Component.translatable("cuemymusic.command.playing", t.title(), t.artist()));
                                }))))
                .then(ClientCommands.literal("queue")
                        .then(ClientCommands.argument("song", StringArgumentType.greedyString())
                                .suggests(CueCommands::suggestSongs)
                                .executes(c -> withSong(c, (src, t) -> {
                                    CuePlayer.INSTANCE.enqueue(t);
                                    src.sendFeedback(Component.translatable("cuemymusic.command.queued", t.title(), t.artist()));
                                }))))
                .then(ClientCommands.literal("next").executes(c -> {
                    CuePlayer.INSTANCE.next();
                    return nowPlaying(c.getSource());
                }))
                .then(ClientCommands.literal("prev").executes(c -> {
                    CuePlayer.INSTANCE.previous();
                    return nowPlaying(c.getSource());
                }))
                .then(ClientCommands.literal("pause").executes(CueCommands::pause))
                .then(ClientCommands.literal("dislike")
                        .executes(c -> {
                            Track t = CuePlayer.INSTANCE.current();
                            if (t == null) {
                                c.getSource().sendError(Component.translatable("cuemymusic.command.nothing"));
                                return 0;
                            }
                            return dislike(c.getSource(), t);
                        })
                        .then(ClientCommands.argument("song", StringArgumentType.greedyString())
                                .suggests(CueCommands::suggestSongs)
                                .executes(c -> withSong(c, CueCommands::dislike))))
                .then(ClientCommands.literal("repeat")
                        .executes(c -> repeat(c.getSource(), CueConfig.INSTANCE.repeatMode().cycle()))
                        .then(ClientCommands.literal("off").executes(c -> repeat(c.getSource(), PlayOrder.Repeat.OFF)))
                        .then(ClientCommands.literal("all").executes(c -> repeat(c.getSource(), PlayOrder.Repeat.ALL)))
                        .then(ClientCommands.literal("one").executes(c -> repeat(c.getSource(), PlayOrder.Repeat.ONE)))));
    }

    /** Dislikes the song, or takes the dislike away if it already has one. */
    private static int dislike(FabricClientCommandSource src, Track t) {
        if (!MusicLibrary.isGameMusic(t)) {
            src.sendError(Component.translatable("cuemymusic.command.not_game_music", t.title()));
            return 0;
        }
        boolean disliked = CuePlayer.INSTANCE.toggleDisliked(t);
        src.sendFeedback(Component.translatable(disliked ? "cuemymusic.command.disliked" : "cuemymusic.command.undisliked", t.title()));
        return 1;
    }

    private static int repeat(FabricClientCommandSource src, PlayOrder.Repeat mode) {
        CueConfig.INSTANCE.setRepeat(mode);
        src.sendFeedback(Component.translatable("cuemymusic.repeat." + mode.name().toLowerCase(Locale.ROOT)));
        return 1;
    }

    private static int resume(CommandContext<FabricClientCommandSource> c) {
        CuePlayer player = CuePlayer.INSTANCE;
        if (!player.isPlaying()) player.togglePause();
        return nowPlaying(c.getSource());
    }

    private static int pause(CommandContext<FabricClientCommandSource> c) {
        CuePlayer player = CuePlayer.INSTANCE;
        if (!player.isPlaying()) {
            c.getSource().sendError(Component.translatable("cuemymusic.command.not_playing"));
            return 0;
        }
        player.togglePause();
        c.getSource().sendFeedback(Component.translatable("cuemymusic.command.paused"));
        return 1;
    }

    private static int nowPlaying(FabricClientCommandSource src) {
        Track t = CuePlayer.INSTANCE.current();
        if (t == null) {
            src.sendError(Component.translatable("cuemymusic.command.nothing"));
            return 0;
        }
        src.sendFeedback(Component.translatable("cuemymusic.command.playing", t.title(), t.artist()));
        return 1;
    }

    private interface SongAction {
        void run(FabricClientCommandSource src, Track track);
    }

    private static int withSong(CommandContext<FabricClientCommandSource> c, SongAction action) {
        String name = StringArgumentType.getString(c, "song");
        List<Track> found = find(name);
        if (found.size() != 1) {
            c.getSource().sendError(Component.translatable(found.isEmpty() ? "cuemymusic.command.no_song" : "cuemymusic.command.several",
                    name, String.join(", ", found.stream().map(Track::title).distinct().limit(5).toList())));
            return 0;
        }
        Track t = found.getFirst();
        action.run(c.getSource(), t);
        return 1;
    }

    /** The first song with this title, ignoring case; else every song whose title contains it. */
    static List<Track> find(String name) {
        String q = name.trim().toLowerCase(Locale.ROOT);
        List<Track> all = MusicLibrary.all();
        for (Track t : all) {
            if (t.title().toLowerCase(Locale.ROOT).equals(q)) return List.of(t);
        }
        return all.stream().filter(t -> t.title().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    private static CompletableFuture<Suggestions> suggestSongs(CommandContext<FabricClientCommandSource> c, SuggestionsBuilder b) {
        String typed = b.getRemaining().toLowerCase(Locale.ROOT);
        MusicLibrary.all().stream()
                .map(Track::title)
                .distinct()
                .filter(title -> title.toLowerCase(Locale.ROOT).contains(typed))
                .sorted()
                .forEach(b::suggest);
        return b.buildFuture();
    }
}
