import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * Builds the mod icon from the game's own textures: the note block as the background,
 * three note particles tinted green, red and blue the way the game tints them, and the
 * player's controls along the bottom, drawn in the same style as the player screen.
 *
 * <p>Also builds the pause menu button's sprite: the notes from the "Now playing" toast,
 * tinted the way the toast tints them.
 *
 * <p>Usage: {@code java scripts/MakeIcon.java <minecraft jar> <icon.png> <button sprite.png>}
 */
public class MakeIcon {
    static final int SIZE = 512;
    /** The controls are drawn on a 64×64 grid, so they stay blocky like the rest. */
    static final int CELL = SIZE / 64;

    public static void main(String[] args) throws Exception {
        BufferedImage block, note, toastNotes;
        try (ZipFile jar = new ZipFile(args[0])) {
            block = read(jar, "assets/minecraft/textures/block/note_block.png");
            note = read(jar, "assets/minecraft/textures/particle/note.png");
            toastNotes = read(jar, "assets/minecraft/textures/gui/sprites/icon/music_notes.png");
        }
        // The toast's notes are an animation strip; the first frame, in the player's green.
        ImageIO.write(tint(toastNotes.getSubimage(0, 0, 16, 16), 0xFF1ED760), "png", new File(args[2]));
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        g.drawImage(block, 0, 0, SIZE, SIZE, null);

        // Tilted left, tilted right, then upright in the middle on top. Each gets a shadow so the overlap reads.
        int noteSize = 192;
        drawNote(g, tint(note, 0xFF4CE01F), 168, 200, -30, noteSize);
        drawNote(g, tint(note, 0xFFF0303A), 346, 200, 30, noteSize);
        drawNote(g, tint(note, 0xFF3A7BF5), 256, 186, 0, noteSize);

        drawControls(g);
        g.dispose();
        ImageIO.write(out, "png", new File(args[1]));
    }

    static BufferedImage read(ZipFile jar, String path) throws Exception {
        try (InputStream in = jar.getInputStream(jar.getEntry(path))) {
            return ImageIO.read(in);
        }
    }

    /** Multiplies the grey texture by {@code argb}, as the game does for note particles. */
    static BufferedImage tint(BufferedImage src, int argb) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int a = p >>> 24;
                int r = ((p >> 16) & 255) * ((argb >> 16) & 255) / 255;
                int gr = ((p >> 8) & 255) * ((argb >> 8) & 255) / 255;
                int b = (p & 255) * (argb & 255) / 255;
                out.setRGB(x, y, a << 24 | r << 16 | gr << 8 | b);
            }
        }
        return out;
    }

    static void drawNote(Graphics2D g, BufferedImage note, int cx, int cy, double degrees, int size) {
        BufferedImage shadow = tint(note, 0xFF000000);
        draw(g, shadow, cx + 12, cy + 12, degrees, size, 0.55f);
        draw(g, note, cx, cy, degrees, size, 1f);
    }

    static void draw(Graphics2D g, BufferedImage img, int cx, int cy, double degrees, int size, float alpha) {
        AffineTransform t = new AffineTransform();
        t.translate(cx, cy);
        t.rotate(Math.toRadians(degrees));
        t.scale(size / (double) img.getWidth(), size / (double) img.getHeight());
        t.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
        g.drawImage(img, t, null);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
    }

    /** Previous, pause and next over a progress bar, in the player screen's colours. */
    static void drawControls(Graphics2D g) {
        int panel = 0xF0121212, white = 0xFFFFFFFF, dim = 0xFFB3B3B3, track = 0xFF4D4D4D, black = 0xFF000000;
        // Panel with its corners knocked off, like the player's round buttons.
        cells(g, 4, 45, 56, 15, panel);
        cells(g, 3, 46, 58, 13, panel);

        // Pause button: white, round, two black bars.
        int bx = 29, by = 47, bs = 7;
        cells(g, bx + 1, by, bs - 2, bs, white);
        cells(g, bx, by + 1, bs, bs - 2, white);
        cells(g, bx + 2, by + 2, 1, 3, black);
        cells(g, bx + 4, by + 2, 1, 3, black);

        // Previous and next.
        triangle(g, 20, 47, 7, false, dim);
        cells(g, 19, 47, 1, 7, dim);
        triangle(g, 39, 47, 7, true, dim);
        cells(g, 43, 47, 1, 7, dim);

        // Progress bar, a third of the way through, with its knob.
        cells(g, 9, 56, 46, 1, track);
        cells(g, 9, 56, 16, 1, white);
        cells(g, 24, 55, 3, 3, white);
    }

    static void triangle(Graphics2D g, int x, int y, int h, boolean right, int color) {
        for (int i = 0; i < h; i++) {
            int w = Math.min(i, h - 1 - i) + 1;
            if (right) cells(g, x, y + i, w, 1, color);
            else cells(g, x + (h + 1) / 2 - w, y + i, w, 1, color);
        }
    }

    static void cells(Graphics2D g, int x, int y, int w, int h, int argb) {
        g.setColor(new java.awt.Color(argb, true));
        g.fillRect(x * CELL, y * CELL, w * CELL, h * CELL);
    }
}
