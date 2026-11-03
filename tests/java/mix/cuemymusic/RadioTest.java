package mix.cuemymusic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class RadioTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("never plays a disliked song", () -> {
            List<Radio.Song> pool = List.of(song("a", false, false), song("b", false, true), song("c", false, false));
            for (Radio.Song s : new Radio(1).next(pool, null, List.of(), 50)) same(s.id().equals("b"), false);
        });
        run("nothing comes round again until most of the others have played", () -> {
            List<Radio.Song> pool = new ArrayList<>();
            for (int i = 0; i < 9; i++) pool.add(song("s" + i, false, false));
            List<Radio.Song> picks = new Radio(2).next(pool, null, List.of(), 6);
            same(new HashSet<>(picks).size(), 6);
        });
        run("goes on without end, even from one song", () -> {
            same(new Radio(3).next(List.of(song("a", false, false)), null, List.of(), 5).size(), 5);
            same(new Radio(3).next(List.of(song("a", false, false), song("b", false, false)), null, List.of(), 30).size(), 30);
        });
        run("never the same song twice in a row while there are others", () -> {
            List<Radio.Song> picks = new Radio(4).next(List.of(song("a", false, false), song("b", false, false)), null, List.of(), 20);
            for (int i = 1; i < picks.size(); i++) same(picks.get(i).equals(picks.get(i - 1)), false);
        });
        run("liked songs, and songs by the seed's artist, come up more", () -> {
            List<Radio.Song> pool = new ArrayList<>();
            for (int i = 0; i < 20; i++) pool.add(song("s" + i, i < 5, false));
            int liked = 0, picked = 0;
            Radio r = new Radio(5);
            List<String> played = new ArrayList<>();
            for (int round = 0; round < 200; round++) {
                for (Radio.Song s : r.next(pool, null, played, 1)) {
                    played.add(s.id());
                    picked++;
                    if (s.liked()) liked++;
                }
                if (played.size() > 8) played.removeFirst();
            }
            // A quarter of the songs are liked; they should be well over a quarter of the plays.
            same(liked > picked * 0.35, true);
            Radio.Song seed = new Radio.Song("x", "Seed", "", 0, "", false, false, 0, 0);
            Radio.Song same = new Radio.Song("y", "Seed", "", 0, "", false, false, 0, 0);
            same(Radio.score(same, seed, null) > Radio.score(song("z", false, false), seed, null), true);
        });
        run("songs you skip come up less than songs you play through", () -> {
            Radio.Song played = new Radio.Song("p", "", "", 0, "", false, false, 9, 1);
            Radio.Song skipped = new Radio.Song("s", "", "", 0, "", false, false, 1, 9);
            same(Radio.score(played, null, null) > Radio.score(skipped, null, null), true);
        });
        run("an empty pool gives nothing", () -> same(new Radio(6).next(List.of(), null, List.of(), 5).size(), 0));

        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static Radio.Song song(String id, boolean liked, boolean disliked) {
        return new Radio.Song(id, "", "", 0, "", liked, disliked, 0, 0);
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
