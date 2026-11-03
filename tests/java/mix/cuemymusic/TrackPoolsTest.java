package mix.cuemymusic;

import java.util.List;
import java.util.Objects;

public final class TrackPoolsTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        List<String> game = List.of("a", "b", "c");
        run("no edit leaves the pool as the game has it", () -> same(TrackPools.apply(game, TrackPools.Edit.NONE), game));
        run("taking a song out leaves it out", () -> {
            TrackPools.Edit e = TrackPools.Edit.NONE.remove("b", true);
            same(TrackPools.apply(game, e), List.of("a", "c"));
        });
        run("putting a song in adds it at the end", () -> {
            TrackPools.Edit e = TrackPools.Edit.NONE.add("x", false);
            same(TrackPools.apply(game, e), List.of("a", "b", "c", "x"));
        });
        run("putting a taken-out song back undoes it", () -> {
            TrackPools.Edit e = TrackPools.Edit.NONE.remove("b", true).add("b", true);
            same(e.isEmpty(), true);
        });
        run("taking out a song that was put in undoes it", () -> {
            TrackPools.Edit e = TrackPools.Edit.NONE.add("x", false).remove("x", false);
            same(e.isEmpty(), true);
        });
        run("adding twice keeps one", () -> {
            TrackPools.Edit e = TrackPools.Edit.NONE.add("x", false).add("x", false);
            same(e.added(), List.of("x"));
        });
        run("nothing put in: always the game's draw", () -> {
            same(TrackPools.choose(3, 0, 0.99), -1);
        });
        run("a song put in is as likely as one of the game's", () -> {
            same(TrackPools.choose(3, 1, 0.0), -1);
            same(TrackPools.choose(3, 1, 0.74), -1);
            same(TrackPools.choose(3, 1, 0.76), 0);
            same(TrackPools.choose(0, 2, 0.6), 1);
        });
        System.out.println((checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static void run(String name, Runnable body) {
        try {
            body.run();
        } catch (AssertionError | RuntimeException e) {
            failures++;
            System.out.println("FAIL " + name + ": " + e.getMessage());
        }
    }

    private static void same(Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) throw new AssertionError("expected " + expected + " but was " + actual);
    }
}
