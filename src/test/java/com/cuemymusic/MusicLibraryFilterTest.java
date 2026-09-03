package com.cuemymusic;

import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import com.cuemymusic.persistence.PersistedLibraryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class MusicLibraryFilterTest {

    private MusicLibrary library;

    @BeforeEach void setUp() { library = new MusicLibrary(); }

    private MusicTrack mk(String id, String title, String artist, SourceType type) {
        MusicTrack t = new MusicTrack(id, title, artist, type);
        t.setSourceId("minecraft:" + id.replace(':','/'));
        return t;
    }

    @Test void filterEmptyReturnsAll() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        library.addOrReplaceTrack(mk("disc:pigstep","Pigstep","Lena Raine", SourceType.MUSIC_DISC));
        var all = library.getAllTracks();
        assertEquals(2, library.filter(all, "").size());
        assertEquals(2, library.filter(all, "   ").size());
        assertEquals(2, library.filter(all, null).size());
    }

    @Test void filterContainsTitleCaseInsensitive() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        library.addOrReplaceTrack(mk("disc:pigstep","Pigstep","Lena Raine", SourceType.MUSIC_DISC));
        var all = library.getAllTracks();
        assertEquals(1, library.filter(all, "sweden").size());
        assertEquals(1, library.filter(all, "SWEDEN").size());
        assertEquals(1, library.filter(all, "lena").size());
        assertEquals(1, library.filter(all, "vanilla:sweden").size());
    }

    @Test void filterSubsequenceMatches() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        var all = library.getAllTracks();
        assertEquals(1, library.filter(all, "swden").size());
        assertEquals(1, library.filter(all, "sdn").size());
    }

    @Test void filterLevMatchesTypo() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        library.addOrReplaceTrack(mk("disc:pigstep","Pigstep","Lena Raine", SourceType.MUSIC_DISC));
        var all = library.getAllTracks();
        assertEquals(1, library.filter(all, "pigstp").size());
        assertTrue(library.filter(all, "swedn").size() >= 1);
    }

    @Test void filterLevNotAppliedForLongQuery() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        var all = library.getAllTracks();
        String longTypo = "sweden_typo_x";
        assertEquals(0, library.filter(all, longTypo).size());
    }

    @Test void dedupPreservesFirst() {
        MusicTrack a = mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA);
        MusicTrack b = mk("vanilla:sweden","SWEDEN_DUP","Other", SourceType.VANILLA);
        Map<String,MusicTrack> dedup = new LinkedHashMap<>();
        dedup.putIfAbsent(a.getId(), a);
        dedup.putIfAbsent(b.getId(), b);
        assertEquals(1, dedup.size());
        assertEquals("Sweden", dedup.get("vanilla:sweden").getTitle());
    }

    @Test void sortByTitle() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        library.addOrReplaceTrack(mk("vanilla:ancestry","Ancestry","Lena Raine", SourceType.VANILLA));
        var list = new ArrayList<>(library.getAllTracks());
        Comparator<MusicTrack> cmp = Comparator.comparing(t -> t.getTitle()!=null ? t.getTitle().toLowerCase(Locale.ROOT) : t.getId().toLowerCase(Locale.ROOT));
        list.sort(cmp);
        assertEquals("vanilla:ancestry", list.get(0).getId());
        assertEquals("vanilla:sweden", list.get(1).getId());
    }

    @Test void applyPersistedStateStaleNotRecreated() {
        var state = new PersistedLibraryState();
        var pt = new PersistedLibraryState.PersistedTrack();
        pt.id = "vanilla:stale_track"; pt.title="Stale"; pt.artist="X"; pt.sourceType="VANILLA";
        pt.sourceId="minecraft:music/game/stale"; pt.enabled=true; pt.ambientEligible=true;
        state.tracks = List.of(pt);
        library.applyPersistedState(state);
        assertTrue(library.getTrack("vanilla:stale_track").isEmpty());
    }

    @Test void clearRemovesAll() {
        library.addOrReplaceTrack(mk("vanilla:sweden","Sweden","C418", SourceType.VANILLA));
        library.clear();
        assertEquals(0, library.getAllTracks().size());
    }
}
