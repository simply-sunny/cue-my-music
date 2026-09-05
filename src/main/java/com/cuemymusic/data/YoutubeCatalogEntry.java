package com.cuemymusic.data;

public record YoutubeCatalogEntry(
    String slug,
    String title,
    String artist,
    String file,
    long size,
    String query
) {}
