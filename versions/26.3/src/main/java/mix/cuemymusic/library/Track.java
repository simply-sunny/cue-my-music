package mix.cuemymusic.library;

import net.minecraft.resources.Identifier;

/** A playable music file, e.g. {@code minecraft:music/game/sweden}. */
public final class Track {
    private final Identifier location;
    private final String title;
    private final String artist;
    private final String album;
    private final Art art;
    private final String packId;
    private volatile double seconds;

    Track(Identifier location, String title, String artist, String album, Art art, String packId, double seconds) {
        this.location = location;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.art = art;
        this.packId = packId;
        this.seconds = seconds;
    }

    public Identifier location() {
        return location;
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    /** The album for game music, or the resource pack's name for pack music. */
    public String album() {
        return album;
    }

    public Art art() {
        return art;
    }

    /** The resource pack the file comes from, or null for the game's own files. */
    public String packId() {
        return packId;
    }

    /** Length in seconds, or NaN while it's still being measured. */
    public double seconds() {
        return seconds;
    }

    void setSeconds(double seconds) {
        this.seconds = seconds;
    }

    /** Same file, same track: the library rebuilds its objects on every resource reload. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Track t && t.location.equals(location);
    }

    @Override
    public int hashCode() {
        return location.hashCode();
    }
}
