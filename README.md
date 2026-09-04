# Cue My Music

Browse, preview, and control Minecraft music through a native client-side library.

## Features

- Jukebox Library screen built with vanilla `Screen` widgets (no Cloth Config)
- Unified track model for vanilla background music and music discs
- Fuzzy search by title/artist/id and sorting by artist/title/source
- Per-row preview and ambient-eligibility toggle
- Global playback scrub with seek support via buffered decoding
- Persistent enabled/ambient state in `cue-my-music/` under the game directory
- Mod Menu integration — Configure opens the library

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3
- Fabric API 0.158.0+26.2
- Java 25
- Mod Menu 20.0.1 (optional)

## Install

1. Install Fabric Loader for Minecraft 26.2.
2. Add Fabric API and (optionally) Mod Menu to `mods/`.
3. Add `cue-my-music-1.0.0-pre-queue.jar` to `mods/`.
4. Launch the client.

## Controls

- Library: search field filters by title/artist, sort button cycles artist/title/source.
- Row checkbox: include or exclude track from ambient selection.
- Row preview button (`>` / `||`): preview the track.
- Top scrub bar: drag to seek when a seekable track is playing; displays current time and duration.
- Done: saves state and returns to the previous screen.

## Build

```bash
./gradlew clean test build
```

Output jar: `build/libs/cue-my-music-1.0.0-pre-queue.jar` (requires Java 25).

## Limitations

- Client-side only; no server sync or multiplayer state.
- Library covers vanilla tracks and music discs only.
- No external imports, streaming, or queue editing in this pre-queue build.
- Ambient replacement respects vanilla music timing and volume settings.

## License

MIT — see [LICENSE](LICENSE).
