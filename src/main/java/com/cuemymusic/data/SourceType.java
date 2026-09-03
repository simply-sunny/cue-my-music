package com.cuemymusic.data;

public enum SourceType {
    VANILLA,
    MUSIC_DISC;

    public boolean isVanilla() {
        return this == VANILLA || this == MUSIC_DISC;
    }
}
