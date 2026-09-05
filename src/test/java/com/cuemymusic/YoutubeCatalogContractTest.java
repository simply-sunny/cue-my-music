package com.cuemymusic;

import com.cuemymusic.data.SourceType;
import com.cuemymusic.data.YoutubeCatalogEntry;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class YoutubeCatalogContractTest {

    @Test
    void catalogHasExact114Entries() {
        var stream = getClass().getResourceAsStream("/assets/cue_my_music/youtube_catalog.json");
        assertNotNull(stream, "youtube_catalog.json resource must exist");
        List<YoutubeCatalogEntry> entries = new Gson().fromJson(
                new InputStreamReader(stream, StandardCharsets.UTF_8),
                new TypeToken<List<YoutubeCatalogEntry>>(){}.getType()
        );
        assertEquals(114, entries.size(), "Catalog must contain exactly 114 entries");

        var slugs = new HashSet<String>();
        for (var entry : entries) {
            assertNotNull(entry.slug());
            assertFalse(entry.slug().isBlank());
            assertTrue(entry.slug().matches("^[a-z0-9_.-]+$"), "Slug must be valid identifier path: " + entry.slug());
            assertTrue(slugs.add(entry.slug()), "Slug must be unique: " + entry.slug());
            assertNotNull(entry.title(), "Title missing for " + entry.slug());
            assertNotNull(entry.artist(), "Artist missing for " + entry.slug());
            assertNotNull(entry.file(), "File missing for " + entry.slug());
            assertTrue(entry.file().endsWith(".ogg"), "File must end in .ogg: " + entry.file());
            assertNotNull(entry.query(), "Query missing for " + entry.slug());
            assertFalse(entry.query().isBlank(), "Query cannot be blank for " + entry.slug());
            assertTrue(entry.size() > 0, "Size must be positive for " + entry.slug());
        }
    }

    @Test
    void sourceTypeIncludesYoutube() {
        assertNotNull(SourceType.valueOf("YOUTUBE"));
        assertFalse(SourceType.YOUTUBE.isVanilla());
    }
}
