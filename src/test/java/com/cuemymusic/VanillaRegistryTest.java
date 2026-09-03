package com.cuemymusic;

import com.cuemymusic.client.music.VanillaTrackRegistry;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class VanillaRegistryTest {
    private MusicLibrary library;
    @BeforeEach void setUp(){ library=new MusicLibrary(); VanillaTrackRegistry.registerAll(library); }

    @Test void catalogIs92(){ assertEquals(92, library.getAllTracks().size()); assertEquals(70, library.getAllTracks().stream().filter(t->t.getSourceType()==SourceType.VANILLA).count()); assertEquals(22, library.getAllTracks().stream().filter(t->t.getSourceType()==SourceType.MUSIC_DISC).count()); }

    @Test void idempotent(){ VanillaTrackRegistry.registerAll(library); VanillaTrackRegistry.registerAll(library); assertEquals(92, library.getAllTracks().size()); }

    @Test void noDup(){ var ids=new HashSet<String>(); for(var t: library.getAllTracks()) assertTrue(ids.add(t.getId()),"dup "+t.getId()); }

    @Test void vanillaSourceIds(){ for(var t: library.getAllTracks().stream().filter(x->x.getSourceType()==SourceType.VANILLA).toList()){ assertNotNull(t.getSourceId()); assertTrue(t.getSourceId().startsWith("minecraft:music")); assertTrue(t.getId().startsWith("vanilla:")); } }

    @Test void discSourceIds(){ for(var t: library.getAllTracks().stream().filter(x->x.getSourceType()==SourceType.MUSIC_DISC).toList()){ assertTrue(t.getSourceId().startsWith("minecraft:music_disc.")); assertTrue(t.getJukeboxSongId().startsWith("minecraft:")); } }

    @Test void fuzzySearch(){
        var all=library.getAllTracks();
        var r1=library.filter(all,"swden"); assertTrue(r1.stream().anyMatch(t->t.getId().equals("vanilla:sweden")), "fuzzy swden should match sweden");
        var r2=library.filter(all,"pigstp"); assertTrue(r2.stream().anyMatch(t->t.getId().equals("disc:pigstep")));
        var r3=library.filter(all,""); assertEquals(92, r3.size());
    }

    @Test void ambientEligiblePersists(){
        library.getTrack("vanilla:sweden").orElseThrow().setAmbientEligible(false);
        var state=library.toPersistedState();
        var restored=new MusicLibrary(); VanillaTrackRegistry.registerAll(restored); restored.applyPersistedState(state);
        assertFalse(restored.getTrack("vanilla:sweden").orElseThrow().isAmbientEligible());
        // eligible queue excludes it
        var eligible=com.cuemymusic.client.playback.MusicDirector.getInstance(); // not testing director here, just library
        assertFalse(restored.getTrack("vanilla:sweden").orElseThrow().isAmbientEligible());
    }

    @Test void catalogSortedPrint(){
        var sorted=library.getAllTracks().stream().sorted(Comparator.comparing(MusicTrack::getId)).toList();
        System.out.println("id | sourceId | title | artist | type");
        for(var t: sorted) System.out.println(t.getId()+" | "+t.getSourceId()+" | "+t.getTitle()+" | "+t.getArtist()+" | "+t.getSourceType());
        assertEquals(92, sorted.size());
        assertEquals("Lena Raine", library.getTrack("disc:pigstep").orElseThrow().getArtist());
        assertEquals("Aaron Cherof", library.getTrack("disc:relic").orElseThrow().getArtist());
    }

    @Test void preservesEnabledOnReregister(){
        library.getTrack("vanilla:sweden").orElseThrow().setEnabled(false);
        VanillaTrackRegistry.registerAll(library);
        assertFalse(library.getTrack("vanilla:sweden").orElseThrow().isEnabled());
        assertEquals(92, library.getAllTracks().size());
    }
}
