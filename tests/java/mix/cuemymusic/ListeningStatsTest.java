package mix.cuemymusic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public final class ListeningStatsTest {
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("a song counts as played after 30 seconds, once", () -> {
            ListeningStats s = new ListeningStats();
            s.started("a");
            listen(s, "a", 29);
            same(s.songs().get("a").plays, 0);
            listen(s, "a", 2);
            same(s.songs().get("a").plays, 1);
            listen(s, "a", 60);
            same(s.songs().get("a").plays, 1);
        });
        run("playing the same song again counts again", () -> {
            ListeningStats s = new ListeningStats();
            s.started("a");
            listen(s, "a", 40);
            s.started("a");
            listen(s, "a", 40);
            same(s.songs().get("a").plays, 2);
        });
        run("a short song that finishes counts as played", () -> {
            ListeningStats s = new ListeningStats();
            s.started("a");
            listen(s, "a", 12);
            s.finished("a");
            same(s.songs().get("a").plays, 1);
        });
        run("moving on early is a skip, moving on late isn't", () -> {
            ListeningStats s = new ListeningStats();
            s.started("a");
            listen(s, "a", 5);
            s.skipped("a");
            s.started("b");
            listen(s, "b", 45);
            s.skipped("b");
            same(s.songs().get("a").skips, 1);
            same(s.songs().get("b").skips, 0);
            same(s.mostSkipped(), "a");
        });
        run("time goes to the hour, day, dimension, and who picked", () -> {
            ListeningStats s = new ListeningStats();
            s.listened("a", 10, NOON, "minecraft:the_nether", true, false);
            s.listened("a", 5, NOON.withHour(21), "minecraft:overworld", false, true);
            same(s.hours()[12], 10.0);
            same(s.peakHour(), 12);
            same(s.day(NOON.toLocalDate()), 15.0);
            same(s.dimensions().get("minecraft:the_nether"), 10.0);
            same(s.pickedSeconds(), 10.0);
            same(s.gameSeconds(), 5.0);
            same(s.effectSeconds(), 5.0);
        });
        run("top songs: most played, then most time", () -> {
            ListeningStats s = new ListeningStats();
            for (String id : List.of("a", "b", "b", "c")) {
                s.started(id);
                listen(s, id, id.equals("c") ? 100 : 31);
            }
            same(s.top(2), List.of("b", "c"));
        });
        run("streaks", () -> {
            ListeningStats s = new ListeningStats();
            for (int d : new int[] {1, 2, 3, 5, 6}) s.listened("a", 1, NOON.withDayOfMonth(d), null, true, false);
            same(s.longestStreak(), 3);
            same(s.currentStreak(LocalDate.of(2026, 10, 6)), 2);
            same(s.currentStreak(LocalDate.of(2026, 10, 7)), 2);
            same(s.currentStreak(LocalDate.of(2026, 10, 8)), 0);
        });

        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static void listen(ListeningStats s, String song, double seconds) {
        s.listened(song, seconds, NOON, "minecraft:overworld", true, false);
    }

    private static void same(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void run(String name, Runnable test) {
        checks++;
        try {
            test.run();
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t.getMessage());
        }
    }
}
