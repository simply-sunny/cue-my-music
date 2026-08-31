package com.cuemymusic.data;

/**
 * Where a track originates from. Unified view across vanilla and local files.
 */
public enum SourceType {
    VANILLA,
    MUSIC_DISC,
    SPOTIFY,
    YOUTUBE,
    LOCAL_GENERIC;

    public boolean isLocalFile() {
        return this == SPOTIFY || this == YOUTUBE || this == LOCAL_GENERIC;
    }

    public boolean isVanilla() {
        return this == VANILLA || this == MUSIC_DISC;
    }
}
