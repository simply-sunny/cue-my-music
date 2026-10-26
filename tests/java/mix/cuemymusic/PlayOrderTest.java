package mix.cuemymusic;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class PlayOrderTest {
    private static final List<String> ALBUM = List.of("a", "b", "c", "d");
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("a list plays through in order, then hands back to the game", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            same(o.play("album", ALBUM, 1), "b");
            plays(o, "c", "d", null);
            same(o.listKey(), null);
        });
        run("queued songs play first, then the list resumes after the interrupted song", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            o.enqueue("x");
            o.enqueue("y");
            plays(o, "x", "y", "b", "c", "d", null);
        });
        run("queuing in the middle of the list resumes after the song before the queue", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            plays(o, "b");
            o.enqueue("x");
            plays(o, "x", "c");
        });
        run("starting a list keeps the queue, and it plays first", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.enqueue("x");
            same(o.play("album", ALBUM, 2), "c");
            same(o.queue(), List.of("x"));
            plays(o, "x", "d", null);
        });
        run("starting a song from another list switches lists", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            same(o.play("other", List.of("p", "q"), 0), "p");
            same(o.listKey(), "other");
            plays(o, "q", null);
        });
        run("a queued song the list reaches plays both times", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            o.enqueue("c");
            plays(o, "c", "b", "c", "d", null);
        });
        run("queue-all appends in order", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.enqueue("x");
            o.enqueueAll(ALBUM);
            same(o.queue(), List.of("x", "a", "b", "c", "d"));
        });
        run("with no list, the queue plays and then hands back", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.enqueue("x");
            plays(o, "x", null);
        });
        run("previous walks the list only", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 2);
            o.enqueue("x");
            same(o.previous(), "b");
            same(o.previous(), "a");
            same(o.previous(), null);
            same(o.queue(), List.of("x"));
        });
        run("previous from a queued song goes back to the list song it interrupted", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 1);
            o.enqueue("x");
            plays(o, "x");
            same(o.previous(), "b");
            plays(o, "c");
        });
        run("playing a queued song now resumes the list after it", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            o.enqueue("x");
            o.enqueue("y");
            same(o.playQueued(1), "y");
            plays(o, "x", "b");
        });
        run("the list's next song shows only with an empty queue", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            same(o.listNext(), "b");
            o.enqueue("x");
            same(o.listNext(), null);
            plays(o, "x");
            same(o.listNext(), "b");
            o.play("album", ALBUM, 3);
            same(o.listNext(), null);
        });
        run("unqueue and move", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.enqueueAll(ALBUM);
            o.moveQueued(0, 2);
            same(o.queue(), List.of("b", "c", "a", "d"));
            o.unqueue(3);
            o.unqueue(9);
            same(o.queue(), List.of("b", "c", "a"));
        });
        run("ending the list keeps the queue", () -> {
            PlayOrder<String> o = new PlayOrder<>();
            o.play("album", ALBUM, 0);
            o.enqueue("x");
            o.endList();
            plays(o, "x", null);
        });

        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    /** Each call to next() returns the songs in order. */
    private static void plays(PlayOrder<String> o, String... songs) {
        String[] got = new String[songs.length];
        for (int i = 0; i < songs.length; i++) got[i] = o.next();
        same(Arrays.asList(got), Arrays.asList(songs));
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
