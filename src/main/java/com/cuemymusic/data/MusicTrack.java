package com.cuemymusic.data;

import java.util.Objects;

/**
 * Unified track model. Vanilla, disc, and local files share this shape.
 *
 * <p>Key state separation:
 * <ul>
 *   <li>enabled — may be used at all (global filter)</li>
 *   <li>ambientEligible — may be selected as background/ambient music</li>
 *   <li>preset membership — belongs to one or more presets (stored in preset, not here)</li>
 * </ul>
 */
public class MusicTrack {
    private final String id;
    private String title;
    private String artist;
    private SourceType sourceType;
    /** Optional subtype, e.g. minecraft sound event id like "minecraft:music.game" or disc id */
    private String sourceId;
    /** Absolute or relative path to local OGG file, nullable */
    private String localAudioPath;
    private String coverArtPath;
    private boolean enabled = true;
    private boolean ambientEligible = true;
    private boolean favorite = false;
    private Integer durationSeconds; // nullable

    public MusicTrack(String id, String title, String artist, SourceType sourceType) {
        this.id = Objects.requireNonNull(id);
        this.title = title;
        this.artist = artist;
        this.sourceType = sourceType;
    }

    // Copy constructor
    public MusicTrack(MusicTrack other) {
        this.id = other.id;
        this.title = other.title;
        this.artist = other.artist;
        this.sourceType = other.sourceType;
        this.sourceId = other.sourceId;
        this.localAudioPath = other.localAudioPath;
        this.coverArtPath = other.coverArtPath;
        this.enabled = other.enabled;
        this.ambientEligible = other.ambientEligible;
        this.favorite = other.favorite;
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
    public String getLocalAudioPath() { return localAudioPath; }
    public void setLocalAudioPath(String localAudioPath) { this.localAudioPath = localAudioPath; }
    public String getCoverArtPath() { return coverArtPath; }
    public void setCoverArtPath(String coverArtPath) { this.coverArtPath = coverArtPath; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAmbientEligible() { return ambientEligible; }
    public void setAmbientEligible(boolean ambientEligible) { this.ambientEligible = ambientEligible; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    /** Whether this track requires a local file to play */
    public boolean requiresLocalFile() {
        return sourceType.isLocalFile();
    }

    /** Whether the local file path looks present (string non-empty). Actual file existence checked elsewhere. */
    public boolean hasLocalPath() {
        return localAudioPath != null && !localAudioPath.isBlank();
    }

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
