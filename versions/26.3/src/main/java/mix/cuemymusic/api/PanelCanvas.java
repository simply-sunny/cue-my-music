package mix.cuemymusic.api;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import mix.cuemymusic.library.Art;
import mix.cuemymusic.library.Track;

/**
 * Draws a {@link CuePanel}'s page in the player's style. Pages are laid out
 * top to bottom: each call draws at {@link #y()} and moves it down, except
 * {@link #button}, which draws at a given spot so buttons can share a line.
 * Everything is redrawn each frame, so draw from current state.
 */
public interface PanelCanvas {
    /** The left and right edges of the page's content, inside its padding. */
    int left();

    int right();

    /** The top of the next line. */
    int y();

    /** Moves down by {@code pixels}. */
    void space(int pixels);

    int mouseX();

    int mouseY();

    Font font();

    /** For drawing anything else. Clipped to the page. */
    GuiGraphicsExtractor graphics();

    /** The big header every page has: art, a small line above the title, the title, and a line below. */
    void header(Art art, String kind, String title, String summary, int color);

    /** A heading, like the player's section titles. */
    void heading(String text);

    /** Wrapped text at the current line. */
    void text(Component text, int color);

    /**
     * A one-line text field across {@code width} pixels from {@code left()},
     * the height of a {@link #button}. The same {@code key} keeps the same
     * field, and what's typed in it, while the page stays open. Enter calls
     * {@code onSubmit}. Returns the current text. Doesn't move the line.
     */
    String textField(String key, Component hint, int width, Consumer<String> onSubmit);

    /** Replaces a text field's text, such as to clear it after a submit. */
    void setText(String key, String text);

    enum ButtonStyle { PRIMARY, SECONDARY, DANGER }

    /** A button at {@code x} on the current line. Returns its width. Doesn't move the line. */
    int button(int x, String label, ButtonStyle style, boolean enabled, Runnable click);

    int buttonWidth(String label);

    int buttonHeight();

    /** A progress bar across the page. {@code fraction} below 0 shows an animated bar for unknown progress. */
    void progress(double fraction, String label);

    /** A song list like the player's own pages, with the big play button. {@code listKey} names the list to the player. */
    void tracks(String listKey, List<Track> tracks);

    /** A short message at the bottom of the player, like "Added to queue". */
    void toast(String text);
}
