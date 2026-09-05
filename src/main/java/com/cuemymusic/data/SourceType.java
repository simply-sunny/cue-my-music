package com.cuemymusic.data;

public enum SourceType {
    VANILLA,
    MUSIC_DISC,
    YOUTUBE;

    public boolean isVanilla() {
        return this == VANILLA || this == MUSIC_DISC;
    }
}
