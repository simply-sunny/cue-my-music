package com.cuemymusic.data;

import com.cuemymusic.persistence.PersistedLibraryState;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central in-memory index. Owns tracks and presets.
 * Thread-confined to client thread for now (no sync).
 */
public class MusicLibrary {
    private final Map<String, MusicTrack> tracks = new LinkedHashMap<>();
    private final Map<String, MusicPreset> presets = new LinkedHashMap<>();
    private String activePresetId = "my_mix";

    public MusicLibrary() {
        initBuiltInPresets();
    }

    private void initBuiltInPresets() {
        presets.put("c418_only", preset("c418_only", "C418 Only", "Only C418-era tracks", true));
        presets.put("new_music_only", preset("new_music_only", "New Music Only", "Post-C418 music", true));
        presets.put("discs_everywhere", preset("discs_everywhere", "Discs Everywhere", "Music discs as ambient", true));
        presets.put("my_mix", preset("my_mix", "My Mix", "Your custom mix", false));
        presets.put("vanilla", preset("vanilla", "Vanilla", "All vanilla music", true));
        presets.put("discs", preset("discs", "Music Discs", "All discs", true));
    }

    private static MusicPreset preset(String id, String name, String desc, boolean builtIn) {
        var p = new MusicPreset(id, name, builtIn);
        p.setDescription(desc);
        return p;
    }

    // ---- Track management ----

    public void addOrReplaceTrack(MusicTrack track) {
        tracks.put(track.getId(), track);
        rebuildBuiltInPresets();
    }

    public void addTracks(Collection<MusicTrack> list) {
        for (var t : list) tracks.put(t.getId(), t);
        rebuildBuiltInPresets();
    }

    public void removeTrack(String id) {
        tracks.remove(id);
        for (var p : presets.values()) p.removeTrack(id);
    }

    public Optional<MusicTrack> getTrack(String id) {
        return Optional.ofNullable(tracks.get(id));
    }

    public List<MusicTrack> getAllTracks() {
        return List.copyOf(tracks.values());
    }

    public List<MusicTrack> getTracksForCollection(MusicCollection collection) {
        return tracks.values().stream().filter(collection::matches).collect(Collectors.toList());
    }

    public List<MusicTrack> getTracksForPreset(String presetId) {
        var preset = presets.get(presetId);
        if (preset == null) return List.of();
        // If preset is dynamic or empty (built-in auto), synthesize from predicate
        if (presetId.equals("c418_only")) {
            return tracks.values().stream()
                    .filter(t -> t.getSourceType() == SourceType.VANILLA && isC418(t))
                    .collect(Collectors.toList());
        }
        if (presetId.equals("new_music_only")) {
            return tracks.values().stream()
                    .filter(t -> t.getSourceType() == SourceType.VANILLA && !isC418(t))
                    .collect(Collectors.toList());
        }
        if (presetId.equals("vanilla")) {
            return tracks.values().stream().filter(t -> t.getSourceType() == SourceType.VANILLA).collect(Collectors.toList());
        }
        if (presetId.equals("discs") || presetId.equals("discs_everywhere")) {
            return tracks.values().stream().filter(t -> t.getSourceType() == SourceType.MUSIC_DISC).collect(Collectors.toList());
        }
        // explicit preset
        return preset.getTrackIds().stream()
                .map(tracks::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean isC418(MusicTrack t) {
        // Heuristic: C418 tracks contain known prefixes. We'll use sourceId or title markers.
        String sid = t.getSourceId() != null ? t.getSourceId() : "";
        String title = t.getTitle() != null ? t.getTitle().toLowerCase(Locale.ROOT) : "";
        // Known C418 disc/game music ids start with specific names; we store sourceId like "minecraft:music.game.c418" etc.
        // Fallback: treat tracks with "c418" in sourceId as C418, or known titles
        if (sid.toLowerCase(Locale.ROOT).contains("c418")) return true;
        // Common C418 vanilla titles
        Set<String> c418Titles = Set.of("aria math", "danny", "dry hands", "wet hands", "minecraft", "clark", "chris", "excuse", "sweden", "oxygene", "volume alpha");
        for (String known : c418Titles) if (title.contains(known)) return true;
        // If no hint, treat first half as C418 for demo; here we default to true for older ids containing "game" without new composer marker
        // We mark vanilla tracks added via VanillaTrackRegistry with explicit c418 flag via sourceId suffix ".c418"
        return sid.endsWith(".c418");
    }

    private void rebuildBuiltInPresets() {
        // Keep My Mix as-is, but ensure vanilla/discs presets reflect current library if they were empty
        // No auto-mutation needed since getTracksForPreset synthesizes.
    }

    // ---- Presets ----

    public Collection<MusicPreset> getAllPresets() { return Collections.unmodifiableCollection(presets.values()); }

    public Optional<MusicPreset> getPreset(String id) { return Optional.ofNullable(presets.get(id)); }

    public void addOrReplacePreset(MusicPreset preset) { presets.put(preset.getId(), preset); }

    public void removePreset(String id) {
        if (presets.containsKey(id) && !presets.get(id).isBuiltIn()) {
            presets.remove(id);
            if (activePresetId.equals(id)) activePresetId = "my_mix";
        }
    }

    public String getActivePresetId() { return activePresetId; }

    public void setActivePresetId(String id) {
        if (presets.containsKey(id)) activePresetId = id;
    }

    public MusicPreset getActivePreset() {
        return presets.getOrDefault(activePresetId, presets.get("my_mix"));
    }

    // ---- Filtering helpers ----

    public List<MusicTrack> filter(List<MusicTrack> input, String search, boolean enabledOnly, boolean ambientOnly, SourceType sourceFilter) {
        var q = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        return input.stream()
                .filter(t -> !enabledOnly || t.isEnabled())
                .filter(t -> !ambientOnly || t.isAmbientEligible())
                .filter(t -> sourceFilter == null || t.getSourceType() == sourceFilter)
                .filter(t -> q.isEmpty() || (t.getTitle() != null && t.getTitle().toLowerCase(Locale.ROOT).contains(q))
                        || (t.getArtist() != null && t.getArtist().toLowerCase(Locale.ROOT).contains(q))
                        || t.getId().toLowerCase(Locale.ROOT).contains(q))
                .collect(Collectors.toList());
    }

    // ---- Persistence bridge ----

    public PersistedLibraryState toPersistedState() {
        var state = new PersistedLibraryState();
        state.activePresetId = activePresetId;
        state.tracks = tracks.values().stream().map(t -> {
            var pt = new PersistedLibraryState.PersistedTrack();
            pt.id = t.getId();
            pt.title = t.getTitle();
            pt.artist = t.getArtist();
            pt.sourceType = t.getSourceType().name();
            pt.sourceId = t.getSourceId();
            pt.localAudioPath = t.getLocalAudioPath();
            pt.coverArtPath = t.getCoverArtPath();
            pt.enabled = t.isEnabled();
            pt.ambientEligible = t.isAmbientEligible();
            pt.favorite = t.isFavorite();
            pt.durationSeconds = t.getDurationSeconds();
            return pt;
        }).collect(Collectors.toList());
        state.presets = presets.values().stream()
                .filter(p -> !p.isBuiltIn() || !p.getTrackIds().isEmpty()) // persist built-ins only if they have overrides
                .map(p -> {
                    var pp = new PersistedLibraryState.PersistedPreset();
                    pp.id = p.getId();
                    pp.name = p.getName();
                    pp.description = p.getDescription();
                    pp.builtIn = p.isBuiltIn();
                    pp.trackIds = new ArrayList<>(p.getTrackIds());
                    return pp;
                }).collect(Collectors.toList());
        return state;
    }

    public void applyPersistedState(PersistedLibraryState state) {
        if (state == null) return;
        // Apply track overrides: only custom/local tracks are fully persisted; vanilla tracks' enabled/ambient/favorite are merged
        for (var pt : state.tracks) {
            var existing = tracks.get(pt.id);
            if (existing != null) {
                existing.setEnabled(pt.enabled);
                existing.setAmbientEligible(pt.ambientEligible);
                existing.setFavorite(pt.favorite);
                if (pt.title != null) existing.setTitle(pt.title);
                if (pt.artist != null) existing.setArtist(pt.artist);
                existing.setLocalAudioPath(pt.localAudioPath);
                existing.setCoverArtPath(pt.coverArtPath);
                existing.setDurationSeconds(pt.durationSeconds);
            } else {
                // New local track persisted
                try {
                    var st = SourceType.valueOf(pt.sourceType);
                    var nt = new MusicTrack(pt.id, pt.title, pt.artist, st);
                    nt.setSourceId(pt.sourceId);
                    nt.setLocalAudioPath(pt.localAudioPath);
                    nt.setCoverArtPath(pt.coverArtPath);
                    nt.setEnabled(pt.enabled);
                    nt.setAmbientEligible(pt.ambientEligible);
                    nt.setFavorite(pt.favorite);
                    nt.setDurationSeconds(pt.durationSeconds);
                    tracks.put(nt.getId(), nt);
                } catch (Exception ignored) {}
            }
        }
        if (state.presets != null) {
            for (var pp : state.presets) {
                var existing = presets.get(pp.id);
                if (existing != null) {
                    existing.getTrackIds().clear();
                    if (pp.trackIds != null) existing.getTrackIds().addAll(pp.trackIds);
                    if (!existing.isBuiltIn()) {
                        existing.setName(pp.name);
                        existing.setDescription(pp.description);
                    }
                } else {
                    var np = new MusicPreset(pp.id, pp.name, pp.builtIn);
                    np.setDescription(pp.description);
                    if (pp.trackIds != null) np.getTrackIds().addAll(pp.trackIds);
                    presets.put(np.getId(), np);
                }
            }
        }
        if (state.activePresetId != null && presets.containsKey(state.activePresetId)) {
            activePresetId = state.activePresetId;
        }
    }

    /** For tests: clear and re-add */
    public void clear() {
        tracks.clear();
        presets.clear();
        initBuiltInPresets();
        activePresetId = "my_mix";
    }
}
