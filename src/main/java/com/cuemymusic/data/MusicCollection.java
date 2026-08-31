package com.cuemymusic.data;

import java.util.function.Predicate;

/**
 * Sidebar grouping. A Collection is a filtered view over the library (All, Vanilla, Discs, etc.).
 * Unlike presets (user playlists), collections are fixed predicates.
 */
public enum MusicCollection {
    ALL("All Tracks", t -> true),
    VANILLA("Vanilla", t -> t.getSourceType() == SourceType.VANILLA),
    MUSIC_DISCS("Music Discs", t -> t.getSourceType() == SourceType.MUSIC_DISC),
    SPOTIFY("Spotify", t -> t.getSourceType() == SourceType.SPOTIFY),
    YOUTUBE("YouTube", t -> t.getSourceType() == SourceType.YOUTUBE),
    LOCAL("Local", t -> t.getSourceType() == SourceType.LOCAL_GENERIC),
    FAVORITES("Favorites", MusicTrack::isFavorite);

    private final String displayName;
    private final Predicate<MusicTrack> predicate;

    MusicCollection(String displayName, Predicate<MusicTrack> predicate) {
        this.displayName = displayName;
        this.predicate = predicate;
    }

    public String getDisplayName() { return displayName; }

    public boolean matches(MusicTrack track) {
        return predicate.test(track);
    }
}
