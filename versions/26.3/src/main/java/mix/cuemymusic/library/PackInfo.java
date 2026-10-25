package mix.cuemymusic.library;

/** A resource pack (or mod) that adds or replaces music files. {@code author} is null when the description doesn't credit anyone. */
public record PackInfo(String id, String name, String author, Art art) {
}
