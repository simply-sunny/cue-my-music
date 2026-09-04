package com.cuemymusic.data;

import com.cuemymusic.persistence.PersistedLibraryState;
import java.util.*;
import java.util.stream.Collectors;

// ponytail: minimal library — only vanilla+disc tracks, no presets/collections
public class MusicLibrary {
    private final Map<String, MusicTrack> tracks = new LinkedHashMap<>();

    public void addOrReplaceTrack(MusicTrack track) { tracks.put(track.getId(), track); }
    public void addTracks(Collection<MusicTrack> list) { for (var t : list) tracks.put(t.getId(), t); }
    public void removeTrack(String id) { tracks.remove(id); }
    public Optional<MusicTrack> getTrack(String id) { return Optional.ofNullable(tracks.get(id)); }
    public List<MusicTrack> getAllTracks() { return List.copyOf(tracks.values()); }

    // ponytail: fuzzy search — contains + subsequence + lev<=2 for typos
    public List<MusicTrack> filter(List<MusicTrack> input, String search) {
        var q = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return new ArrayList<>(input);
        return input.stream().filter(t -> matches(t, q)).collect(Collectors.toCollection(ArrayList::new));
    }
    private static boolean matches(MusicTrack t, String q) {
        String title = t.getTitle() != null ? t.getTitle().toLowerCase(Locale.ROOT) : "";
        String artist = t.getArtist() != null ? t.getArtist().toLowerCase(Locale.ROOT) : "";
        String id = t.getId().toLowerCase(Locale.ROOT);
        if (title.contains(q) || artist.contains(q) || id.contains(q)) return true;
        if (isSubsequence(q, title) || isSubsequence(q, artist)) return true;
        // lev distance for short queries
        if (q.length() <= 12) {
            for (String tok : title.split("\\s+")) if (lev(tok, q) <= 2) return true;
            for (String tok : artist.split("\\s+")) if (lev(tok, q) <= 2) return true;
        }
        return false;
    }
    private static boolean isSubsequence(String q, String s) {
        int qi=0; for (int i=0;i<s.length()&&qi<q.length();i++) if (s.charAt(i)==q.charAt(qi)) qi++; return qi==q.length();
    }
    private static int lev(String a, String b) {
        int[] prev=new int[b.length()+1]; for(int j=0;j<=b.length();j++) prev[j]=j;
        for(int i=1;i<=a.length();i++){int[] cur=new int[b.length()+1]; cur[0]=i; for(int j=1;j<=b.length();j++){int c=a.charAt(i-1)==b.charAt(j-1)?0:1; cur[j]=Math.min(Math.min(cur[j-1]+1, prev[j]+1), prev[j-1]+c);} prev=cur;}
        return prev[b.length()];
    }

    public PersistedLibraryState toPersistedState() {
        var s = new PersistedLibraryState();
        s.tracks = tracks.values().stream().map(t -> {
            var pt = new PersistedLibraryState.PersistedTrack();
            pt.id=t.getId(); pt.title=t.getTitle(); pt.artist=t.getArtist(); pt.sourceType=t.getSourceType().name();
            pt.sourceId=t.getSourceId(); pt.jukeboxSongId=t.getJukeboxSongId(); pt.enabled=t.isEnabled(); pt.ambientEligible=t.isAmbientEligible(); pt.durationSeconds=t.getDurationSeconds();
            return pt;
        }).collect(Collectors.toList());
        return s;
    }
    public void applyPersistedState(PersistedLibraryState state) {
        if (state==null||state.tracks==null) return;
        for (var pt: state.tracks) {
            if (pt==null||pt.id==null) continue;
            var existing=tracks.get(pt.id);
            if (existing!=null) {
                existing.setEnabled(pt.enabled); existing.setAmbientEligible(pt.ambientEligible);
                if (pt.title!=null) existing.setTitle(pt.title); if (pt.artist!=null) existing.setArtist(pt.artist);
                existing.setJukeboxSongId(pt.jukeboxSongId); existing.setDurationSeconds(pt.durationSeconds);
            } else {
                // do not re-create stale vanilla/disc ids that were pruned (valid ones already exist)
                if (pt.id.startsWith("vanilla:")||pt.id.startsWith("disc:")) continue;
                try { var st=SourceType.valueOf(pt.sourceType); var nt=new MusicTrack(pt.id, pt.title, pt.artist, st);
                    nt.setSourceId(pt.sourceId); nt.setJukeboxSongId(pt.jukeboxSongId); nt.setEnabled(pt.enabled); nt.setAmbientEligible(pt.ambientEligible); nt.setDurationSeconds(pt.durationSeconds); tracks.put(nt.getId(), nt);
                } catch (Exception ignored) {}
            }
        }
    }
    public void clear() { tracks.clear(); }
}
