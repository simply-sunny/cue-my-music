package mix.cuemymusic.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display names, initials, credits and colours for resource packs that add music. */
public final class PackNames {
    private static final Set<String> SMALL_WORDS = Set.of(
            "a", "an", "the", "of", "and", "or", "is", "in", "on", "for", "to", "by", "with", "at", "from", "my", "your");
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern VERSION = Pattern.compile("(?i)v?\\d[\\w.]*|\\[.*]|\\(.*\\)");
    /** "By: name" at the start of a line or after punctuation, running to the end of the line. */
    private static final Pattern AUTHOR = Pattern.compile(
            "(?im)(?:^|[.!|,\\-–]\\s*)by:?\\s+@?([\\p{L}\\p{N}_.\\- ]{2,32}?)[\\s.!]*$");

    private PackNames() {
    }

    /** "file/true-ending-dragon-music-v1.zip" becomes "True Ending Dragon Music v1". */
    public static String prettify(String raw) {
        String s = FORMATTING.matcher(raw).replaceAll("").trim();
        if (s.startsWith("file/")) s = s.substring("file/".length());
        s = s.replaceFirst("(?i)\\.zip$", "");
        // Only file-style names get dashes turned into spaces; real titles are left as written.
        if (!s.contains(" ")) s = s.replaceAll("[-_]+", " ");
        StringBuilder out = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            boolean lower = word.equals(word.toLowerCase(Locale.ROOT));
            if (lower && !VERSION.matcher(word).matches()) {
                out.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
                out.append(word, Character.charCount(word.codePointAt(0)), word.length());
            } else {
                out.append(word);
            }
        }
        return out.isEmpty() ? raw : out.toString();
    }

    /**
     * Up to three initials for cover art: one per word, or the first two
     * words' when there are more than three. Version tags and small words
     * like "the" don't count.
     */
    public static String initials(String name) {
        List<String> words = new ArrayList<>();
        for (String token : FORMATTING.matcher(name).replaceAll("").split("\\s+")) {
            if (token.isEmpty() || VERSION.matcher(token).matches()) continue;
            for (String w : token.split("[^\\p{L}\\p{N}]+")) {
                if (!w.isEmpty() && !SMALL_WORDS.contains(w.toLowerCase(Locale.ROOT))) words.add(w);
            }
        }
        if (words.isEmpty()) return "?";
        int n = words.size() > 3 ? 2 : words.size();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.appendCodePoint(Character.toUpperCase(words.get(i).codePointAt(0)));
        return out.toString();
    }

    /** The credit in a pack description ("New boss music\nBy: limesplatus"), or null. */
    public static String author(String description) {
        Matcher m = AUTHOR.matcher(FORMATTING.matcher(description).replaceAll(""));
        return m.find() ? m.group(1).trim() : null;
    }

    /** Two ARGB colours for a top-to-bottom gradient, picked from the name so a pack always gets the same one. */
    public static int[] gradient(String name) {
        float hue = Math.floorMod(name.hashCode(), 360) / 360f;
        return new int[] {hsv(hue, 0.55f, 0.80f), hsv(hue + 0.08f, 0.65f, 0.35f)};
    }

    private static int hsv(float h, float s, float v) {
        h = (h % 1 + 1) % 1 * 6;
        int i = (int) h;
        float f = h - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float[] rgb = switch (i) {
            case 0 -> new float[] {v, t, p};
            case 1 -> new float[] {q, v, p};
            case 2 -> new float[] {p, v, t};
            case 3 -> new float[] {p, q, v};
            case 4 -> new float[] {t, p, v};
            default -> new float[] {v, p, q};
        };
        return 0xFF000000 | Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
    }
}
