<p align="center">
  <img src="src/main/resources/assets/cue_my_music/icon.png" width="128" height="128" alt="Cue My Music logo" />
</p>

# Cue My Music

Browse, preview, download, and control Minecraft music through a native client-side library.

## Features

- **Jukebox Library screen**: Vanilla `Screen` UI with Mod Menu integration (`Configure` opens library).
- **Track catalog**: Full collection of 206 tracks (92 built-in native tracks + 114 extended YouTube tracks).
- **Selective & Batch Downloads**: Download missing YouTube tracks on click (`↓`) or batch download all missing tracks via `yt-dlp`.
- **Search & Sort**: Fuzzy search by title/artist/id, sort by Artist/Title/Source.
- **Preview & Ambient Queue**: Per-row preview (`>` / `||`) and ambient-rotation checkbox.
- **Scrub & Seek**: Playback scrub bar with drag-to-seek support while playing.
- **Persistent State**: Automatically saves enabled/ambient preferences across game restarts.

## Requirements

- Minecraft 26.2
- Fabric Loader
- Fabric API
- Mod Menu (optional)
- `yt-dlp` installed and on PATH (for downloading optional YouTube tracks)

## Install

1. Install Fabric Loader for Minecraft 26.2.
2. Add Fabric API and (optionally) Mod Menu to `mods/`.
3. Add `cue-my-music-0.1.0.jar` (from [Releases](https://github.com/simply-sunny/cue-my-music/releases)) to `mods/`.
4. Launch the client.

## Controls

- Library: search field filters by title/artist, sort button cycles artist/title/source.
- Row checkbox: include or exclude track from ambient selection.
- Row action button: `↓` to download missing track, `…` while downloading, `↻` to retry failed download, `>` / `||` to preview.
- Footer Download all button: batch downloads all missing YouTube tracks.
- Top scrub bar: drag to seek when a seekable track is playing; displays current time and duration.
- Done: saves state and returns to the previous screen.

## Build

```bash
./gradlew clean test build
```

Output jar: `build/libs/cue-my-music-0.1.0.jar` (requires Java 25).

## Limitations

- Client-side only; no server sync or multiplayer state.
- YouTube downloads require `yt-dlp` installed on the system.
- Ambient replacement respects vanilla music timing and volume settings.

## License

MIT — see [LICENSE](LICENSE).
