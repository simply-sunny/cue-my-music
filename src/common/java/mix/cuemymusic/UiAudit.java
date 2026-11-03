package mix.cuemymusic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds things on the player's pages that don't make sense: words cut off,
 * the same thing said twice, different things drawn with the same picture,
 * choices a mode rules out, and so on. While an audit runs, the player
 * notes what each page shows (its texts, its rows, and a few facts such as
 * the mode and the song playing); {@link #check} then holds each page up to
 * {@link Rule}s and says what it found, in plain words.
 */
public final class UiAudit {
    /** A kind of thing that doesn't make sense, and why it doesn't. */
    public enum Rule {
        CUT_OFF("Cut off", "A heading, button or label doesn't fit, so its words end in \"...\". Names in long lists may; headings and buttons shouldn't."),
        SAID_TWICE("Said twice", "The same words twice in a row, such as a page's kind and its title. The second says nothing new."),
        SAME_PICTURE("Same picture", "Different kinds of thing share a picture, so it doesn't tell them apart."),
        ALL_ALIKE("All alike", "Every row in a section has the same picture, so the pictures tell you nothing."),
        NEXT_IS_NOW("Next is now", "Next up shows the song that's playing now."),
        OUT_OF_MODE("Out of mode", "A choice for music the mode leaves out. The mode already says where music comes from."),
        WRONG_PLACE("Wrong place", "Something in a section it doesn't belong to, such as an add-on's songs among the game's places."),
        ENDLESS_REPEAT("Repeat on endless", "Repeat is on while something that never ends plays, where it can't mean anything."),
        NO_WORDS("No words", "A number or sign with no word to say what it is."),
        EMPTY("Empty", "A section, list or page with nothing in it, and nothing to say what would go there."),
        TWICE_IN_A_LIST("Twice in a list", "The same name twice in one section."),
        OVERLAP("Overlap", "Two buttons or labels drawn over each other, so neither can be read or clicked properly.");

        public final String title, why;

        Rule(String title, String why) {
            this.title = title;
            this.why = why;
        }
    }

    /** A text on a page: what it says in full, what fitted, and what it is ("heading", "button", "row", "readout"...). */
    public record Text(String role, String full, String shown) {
    }

    /** A row in a section: its name, its picture, and where its music comes from ("game", "addon" or ""). */
    public record Row(String section, String title, String picture, String origin) {
    }

    /** Where a button or label was drawn, to find ones drawn over each other. */
    public record Box(String name, int x0, int y0, int x1, int y1) {
        boolean overlaps(Box o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }
    }

    /** What one page showed. */
    public static final class Page {
        public final String name;
        public final List<Text> texts = new ArrayList<>();
        public final List<Row> rows = new ArrayList<>();
        public final List<Box> boxes = new ArrayList<>();
        /** Choices offered on the page, by what they choose: "radio source" → "addons". */
        public final List<String[]> choices = new ArrayList<>();
        public final Map<String, String> facts = new LinkedHashMap<>();
        /** A page's kind, title and summary, as its header shows them. */
        public String kind = "", title = "", summary = "";

        public Page(String name) {
            this.name = name;
        }

        public void text(String role, String full, String shown) {
            texts.add(new Text(role, full, shown));
        }

        public void row(String section, String title, String picture, String origin) {
            rows.add(new Row(section, title, picture, origin));
        }

        public void box(String name, int x0, int y0, int x1, int y1) {
            boxes.add(new Box(name, x0, y0, x1, y1));
        }

        public void choice(String what, String option) {
            choices.add(new String[] {what, option});
        }

        public void fact(String key, Object value) {
            facts.put(key, String.valueOf(value));
        }

        public String fact(String key) {
            return facts.getOrDefault(key, "");
        }
    }

    /** Something found: the rule, the page, and what in particular. */
    public record Finding(Rule rule, String page, String what) {
    }

    /** The texts' roles that should never be cut off. */
    private static final Set<String> MUST_FIT = Set.of("heading", "button", "label", "title");
    private static final Pattern NO_LETTERS = Pattern.compile("[^\\p{L}]*|[-+]?\\d+\\s*[x×]\\s*\\d+([.,]\\d+)?");

    private UiAudit() {
    }

    /** Everything that doesn't make sense on {@code pages}, each found once even if it's on many pages. */
    public static List<Finding> check(List<Page> pages) {
        Map<String, Finding> found = new LinkedHashMap<>();
        // Pictures across every page: which kinds of thing each stands for.
        Map<String, Set<String>> pictureUse = new HashMap<>();
        for (Page p : pages) {
            for (Finding f : check(p)) found.putIfAbsent(f.rule() + "|" + f.what(), f);
            for (Row r : p.rows) {
                if (!r.picture().isEmpty()) pictureUse.computeIfAbsent(r.picture(), k -> new LinkedHashSet<>()).add(r.section());
            }
        }
        for (Map.Entry<String, Set<String>> e : pictureUse.entrySet()) {
            if (e.getValue().size() > 1) {
                String what = "One picture for " + String.join(", ", e.getValue());
                found.putIfAbsent(Rule.SAME_PICTURE + "|" + what, new Finding(Rule.SAME_PICTURE, "Library", what));
            }
        }
        return List.copyOf(found.values());
    }

    /** What doesn't make sense on one page. */
    public static List<Finding> check(Page p) {
        List<Finding> out = new ArrayList<>();
        for (Text t : p.texts) {
            if (MUST_FIT.contains(t.role()) && !t.full().equals(t.shown()) && !t.full().isBlank()) {
                out.add(new Finding(Rule.CUT_OFF, p.name, t.role() + " \"" + t.full() + "\" shows as \"" + t.shown() + "\""));
            }
            if (t.role().equals("readout") && NO_LETTERS.matcher(t.shown().strip()).matches() && !t.shown().isBlank()) {
                out.add(new Finding(Rule.NO_WORDS, p.name, "\"" + t.shown() + "\" says nothing about what it is"));
            }
        }
        if (!p.kind.isBlank() && same(p.kind, p.title)) {
            out.add(new Finding(Rule.SAID_TWICE, p.name, "kind and title are both \"" + p.title + "\""));
        }
        if (!p.summary.isBlank() && same(p.summary, p.title)) {
            out.add(new Finding(Rule.SAID_TWICE, p.name, "title and summary are both \"" + p.title + "\""));
        }
        String now = p.fact("playing"), next = p.fact("next up");
        if (!now.isEmpty() && now.equals(next)) {
            String why = p.fact("repeat").equals("one") ? " (repeat one is on)" : "";
            out.add(new Finding(Rule.NEXT_IS_NOW, p.name, "\"" + now + "\" is both playing and next up" + why));
        }
        if (p.fact("endless").equals("true") && !p.fact("repeat").isEmpty() && !p.fact("repeat").equals("off")) {
            out.add(new Finding(Rule.ENDLESS_REPEAT, p.name, "repeat " + p.fact("repeat") + " while " + p.fact("list") + " plays"));
        }
        String mode = p.fact("mode");
        for (String[] c : p.choices) {
            boolean outside = mode.equals("fx") && c[1].contains("addons") || mode.equals("addons") && c[1].contains("game");
            if (outside) out.add(new Finding(Rule.OUT_OF_MODE, p.name, c[0] + " offers \"" + c[1] + "\" in " + mode + " mode"));
        }
        Map<String, List<Row>> sections = new LinkedHashMap<>();
        for (Row r : p.rows) sections.computeIfAbsent(r.section(), k -> new ArrayList<>()).add(r);
        for (Map.Entry<String, List<Row>> e : sections.entrySet()) {
            List<Row> rows = e.getValue();
            Set<String> names = new LinkedHashSet<>();
            Set<String> pictures = new LinkedHashSet<>();
            for (Row r : rows) {
                if (!names.add(r.title().toLowerCase(Locale.ROOT))) {
                    out.add(new Finding(Rule.TWICE_IN_A_LIST, p.name, "\"" + r.title() + "\" twice in " + e.getKey()));
                }
                pictures.add(r.picture());
                boolean addOnAmongGame = mode.equals("fx") && r.origin().equals("addon");
                boolean gameAmongAddOns = mode.equals("addons") && r.origin().equals("game");
                if (addOnAmongGame || gameAmongAddOns) {
                    out.add(new Finding(Rule.WRONG_PLACE, p.name, "\"" + r.title() + "\" (" + r.origin() + ") in " + e.getKey()));
                }
            }
            if (rows.size() >= 4 && pictures.size() == 1) {
                out.add(new Finding(Rule.ALL_ALIKE, p.name, "all " + rows.size() + " rows in " + e.getKey() + " share one picture"));
            }
        }
        for (int i = 0; i < p.boxes.size(); i++) {
            for (int j = i + 1; j < p.boxes.size(); j++) {
                Box a = p.boxes.get(i), b = p.boxes.get(j);
                if (a.overlaps(b)) out.add(new Finding(Rule.OVERLAP, p.name, "\"" + a.name() + "\" and \"" + b.name() + "\" are drawn over each other"));
            }
        }
        for (Map.Entry<String, String> f : p.facts.entrySet()) {
            if (f.getKey().startsWith("count ") && f.getValue().equals("0")) {
                out.add(new Finding(Rule.EMPTY, p.name, f.getKey().substring("count ".length()) + " is shown with nothing in it"));
            }
        }
        return out;
    }

    private static boolean same(String a, String b) {
        return a.strip().equalsIgnoreCase(b.strip());
    }

    /** The findings as a Markdown report, grouped by rule, each rule with why it doesn't make sense. */
    public static String report(List<Finding> findings, int pages) {
        StringBuilder b = new StringBuilder("# Cue My Music: things that don't make sense\n\n");
        b.append(findings.size()).append(" found on ").append(pages).append(" pages.\n");
        Map<Rule, List<Finding>> byRule = new LinkedHashMap<>();
        for (Rule r : Rule.values()) byRule.put(r, new ArrayList<>());
        for (Finding f : findings) byRule.get(f.rule()).add(f);
        for (Map.Entry<Rule, List<Finding>> e : byRule.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            b.append("\n## ").append(e.getKey().title).append(" (").append(e.getValue().size()).append(")\n\n");
            b.append(e.getKey().why).append("\n\n");
            for (Finding f : e.getValue()) b.append("- **").append(f.page()).append("**: ").append(f.what()).append('\n');
        }
        return b.toString();
    }
}
