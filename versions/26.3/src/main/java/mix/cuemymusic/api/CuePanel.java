package mix.cuemymusic.api;

import java.util.List;

import mix.cuemymusic.library.Art;
import mix.cuemymusic.library.Track;

/**
 * A page an add-on adds to the player. It gets its own entry in the library,
 * above the artists, and draws itself in the middle panel with a
 * {@link PanelCanvas}. Register it with {@link CuePanels#register} from a
 * client initializer.
 */
public interface CuePanel {
    /** Unique among panels, such as your mod id. */
    String id();

    /** The library entry's name, and the page's title. */
    String title();

    /** The library entry's second line. */
    String subtitle();

    Art icon();

    /** Draws the page, top to bottom. Called every frame while it's open, on the client thread. */
    void draw(PanelCanvas canvas);

    /** A resource pack this page stands in for. Its own pack entry is left out of the library. */
    default String packId() {
        return null;
    }

    /** Extra right-click menu items for a song in this page's track list. */
    default List<Action> songActions(Track track) {
        return List.of();
    }

    record Action(String label, Runnable run) {
    }
}
