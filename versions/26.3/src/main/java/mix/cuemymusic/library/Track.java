package mix.cuemymusic.library;

import net.minecraft.resources.Identifier;

/** A playable music file, e.g. {@code minecraft:music/game/sweden}. */
public final class Track {
    private final Identifier location;
    private final String title;
    private final String artist;
    private final Catalog.Album album;
    private volatile double seconds;

    Track(Identifier location, String title, String artist, Catalog.Album album, double seconds) {
        this.location = location;
        this.title = title;
        this.artist = artist;
        this.album = album;
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

    public Catalog.Album album() {
        return album;
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
