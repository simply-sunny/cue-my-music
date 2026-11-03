package mix.cuemymusic.library;

import net.minecraft.resources.Identifier;

/**
 * Cover art: a texture (a block, or a resource pack's icon), a GUI sprite on a gradient
 * (the heart on Liked Songs), initials on a block (an artist with no art of their own), or
 * initials on a gradient when there's nothing to show.
 */
public sealed interface Art {
    record Texture(Identifier id, int width, int height) implements Art {
    }

    /** A block animated with frames, one above the other, 16 wide: its first frame. */
    record Sheet(Identifier id, int height) implements Art {
    }

    record Initials(String text, int top, int bottom) implements Art {
    }

    record Sprite(Identifier sprite, int top, int bottom) implements Art {
    }

    /** Initials over a block texture, darkened so they read. */
    record Stamp(String text, Identifier block) implements Art {
    }

    static Art block(String path) {
        return new Texture(Identifier.withDefaultNamespace("textures/" + path + ".png"), 16, 16);
    }

    static Art initials(String name) {
        int[] colors = PackNames.gradient(name);
        return new Initials(PackNames.initials(name), colors[0], colors[1]);
    }
}
