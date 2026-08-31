package com.cuemymusic.data;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A named filter/playlist over the library. Tracks are referenced by id.
 * Presets can be built-in (C418 Only etc.) or user-created.
 */
public class MusicPreset {
    private final String id;
    private String name;
    private String description;
    private boolean builtIn;
    private final Set<String> trackIds = new LinkedHashSet<>();

    /** Optional predicate-style preset: defined by filter rather than explicit ids. If true, the UI treats trackIds as overrides. */
    private boolean dynamic = false;

    public MusicPreset(String id, String name, boolean builtIn) {
        this.id = Objects.requireNonNull(id);
        this.name = name;
        this.builtIn = builtIn;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public Set<String> getTrackIds() { return trackIds; }
    public boolean isDynamic() { return dynamic; }
    public void setDynamic(boolean dynamic) { this.dynamic = dynamic; }

    public void addTrack(String trackId) { trackIds.add(trackId); }
    public void removeTrack(String trackId) { trackIds.remove(trackId); }
    public boolean contains(String trackId) { return trackIds.contains(trackId); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MusicPreset that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}
