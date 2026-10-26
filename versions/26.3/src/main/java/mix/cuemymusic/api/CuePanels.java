package mix.cuemymusic.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Add-on pages, in the order they were registered. */
public final class CuePanels {
    private static final List<CuePanel> PANELS = new CopyOnWriteArrayList<>();

    private CuePanels() {
    }

    public static void register(CuePanel panel) {
        if (get(panel.id()) != null) throw new IllegalArgumentException("A Cue My Music panel called " + panel.id() + " already exists");
        PANELS.add(panel);
    }

    public static List<CuePanel> all() {
        return List.copyOf(PANELS);
    }

    public static CuePanel get(String id) {
        for (CuePanel p : PANELS) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }
}
