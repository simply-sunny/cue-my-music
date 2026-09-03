package com.cuemymusic.data;

import java.util.Objects;

public class MusicTrack {
    private final String id;
    private String title;
    private String artist;
    private SourceType sourceType;
    private String sourceId;
    private String jukeboxSongId;
    private boolean enabled = true;
    private boolean ambientEligible = true;
    private Integer durationSeconds;

    public MusicTrack(String id, String title, String artist, SourceType sourceType) {
        this.id = Objects.requireNonNull(id);
        this.title = title;
        this.artist = artist;
        this.sourceType = sourceType;
    }

    public MusicTrack(MusicTrack other) {
        this.id = other.id;
        this.title = other.title;
        this.artist = other.artist;
        this.sourceType = other.sourceType;
        this.sourceId = other.sourceId;
        this.jukeboxSongId = other.jukeboxSongId;
        this.enabled = other.enabled;
        this.ambientEligible = other.ambientEligible;
        this.durationSeconds = other.durationSeconds;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }
    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getJukeboxSongId() { return jukeboxSongId; }
    public void setJukeboxSongId(String jukeboxSongId) { this.jukeboxSongId = jukeboxSongId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAmbientEligible() { return ambientEligible; }
    public void setAmbientEligible(boolean ambientEligible) { this.ambientEligible = ambientEligible; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MusicTrack that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "MusicTrack{id='" + id + "', title='" + title + "', source=" + sourceType + ", enabled=" + enabled + "}";
    }
}
