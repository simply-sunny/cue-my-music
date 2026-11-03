package mix.cuemymusic;

import java.util.List;
import java.util.Objects;

public final class UiAuditTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        run("a cut-off heading is found, a cut-off song row isn't", () -> {
            UiAudit.Page p = new UiAudit.Page("Radio");
            p.text("heading", "Minecraft - Volume Alpha", "Minecraft - Vol...");
            p.text("row", "A very long song name", "A very long...");
            same(rules(p), List.of(UiAudit.Rule.CUT_OFF));
        });
        run("Radio titled Radio is said twice", () -> {
            UiAudit.Page p = new UiAudit.Page("Radio");
            p.kind = "Radio";
            p.title = "RADIO";
            same(rules(p), List.of(UiAudit.Rule.SAID_TWICE));
        });
        run("next up the same as playing, with repeat one", () -> {
            UiAudit.Page p = new UiAudit.Page("Radio");
            p.fact("playing", "Living Mice");
            p.fact("next up", "Living Mice");
            p.fact("repeat", "one");
            p.fact("endless", true);
            p.fact("list", "Radio");
            same(rules(p), List.of(UiAudit.Rule.NEXT_IS_NOW, UiAudit.Rule.ENDLESS_REPEAT));
        });
        run("an add-on choice in Vanilla + FX is out of mode", () -> {
            UiAudit.Page p = new UiAudit.Page("Radio");
            p.fact("mode", "fx");
            p.choice("Radio's Plays", "game");
            p.choice("Radio's Plays", "all (game + addons)");
            p.choice("Radio's Plays", "addons");
            same(rules(p), List.of(UiAudit.Rule.OUT_OF_MODE, UiAudit.Rule.OUT_OF_MODE));
        });
        run("an add-on's pool among the game's places", () -> {
            UiAudit.Page p = new UiAudit.Page("Library");
            p.fact("mode", "fx");
            p.row("pools", "Survival", "a", "game");
            p.row("pools", "Downloads", "b", "addon");
            same(rules(p), List.of(UiAudit.Rule.WRONG_PLACE));
        });
        run("a section where every row looks the same", () -> {
            UiAudit.Page p = new UiAudit.Page("Library");
            for (String n : List.of("Title screen", "Survival", "Creative", "Underwater")) p.row("Track pools", n, "note_block", "game");
            same(rules(p), List.of(UiAudit.Rule.ALL_ALIKE));
        });
        run("one picture for two kinds of thing, across pages", () -> {
            UiAudit.Page a = new UiAudit.Page("Library");
            a.row("Track pools", "Survival", "note_block", "game");
            a.row("Bottom", "Your Stats", "note_block", "");
            same(UiAudit.check(List.of(a)).stream().map(UiAudit.Finding::rule).toList(), List.of(UiAudit.Rule.SAME_PICTURE));
        });
        run("a bare number readout has no words; one with a word is fine", () -> {
            UiAudit.Page p = new UiAudit.Page("Bottom bar");
            p.text("readout", "-3 x0.80", "-3 x0.80");
            p.text("readout", "Pitch -3", "Pitch -3");
            same(rules(p), List.of(UiAudit.Rule.NO_WORDS));
        });
        run("the same name twice in a section", () -> {
            UiAudit.Page p = new UiAudit.Page("Library");
            p.row("Artists", "C418", "a", "game");
            p.row("Artists", "c418", "b", "game");
            same(rules(p), List.of(UiAudit.Rule.TWICE_IN_A_LIST));
        });
        run("buttons drawn over each other overlap; ones side by side don't", () -> {
            UiAudit.Page p = new UiAudit.Page("Scotty Apex");
            p.box("Add all to queue", 100, 10, 200, 28);
            p.box("Search", 190, 10, 206, 28);
            p.box("Mix", 210, 10, 240, 28);
            same(rules(p), List.of(UiAudit.Rule.OVERLAP));
        });
        run("a page with nothing wrong has nothing found", () -> {
            UiAudit.Page p = new UiAudit.Page("Liked Songs");
            p.kind = "Playlist";
            p.title = "Liked Songs";
            p.text("heading", "Liked Songs", "Liked Songs");
            same(rules(p), List.of());
        });
        run("the report groups by rule and says why", () -> {
            UiAudit.Page p = new UiAudit.Page("Radio");
            p.kind = "Radio";
            p.title = "Radio";
            String report = UiAudit.report(UiAudit.check(List.of(p)), 1);
            same(report.contains("## Said twice (1)"), true);
            same(report.contains(UiAudit.Rule.SAID_TWICE.why), true);
        });
        System.out.println((checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static List<UiAudit.Rule> rules(UiAudit.Page p) {
        return UiAudit.check(p).stream().map(UiAudit.Finding::rule).toList();
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
