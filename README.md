# Cue My Music

A personal-use Fabric mod for Minecraft **26.2** — manage vanilla music, music discs, and locally cached tracks from Spotify/YouTube sources through a native-feeling **Jukebox Library** UI.

Mod ID: `cue_my_music` · Display name: **Cue My Music** · Package: `com.cuemymusic`

## Features (MVP v1)

- **Unified library** — vanilla, discs, Spotify/Youtube/local OGG files share the same `MusicTrack` model with separate `enabled` / `ambientEligible` / preset-membership states.
- **Collections** (fixed filters): All Tracks, Vanilla, Music Discs, Spotify, YouTube, Local, Favorites.
- **Presets** (playlists): C418 Only, New Music Only, Discs Everywhere, My Mix, Vanilla, Music Discs (+ edit custom presets).
- **Vertical scroll / infinite scroll** track list — no pagination, virtualized row rendering.
- **Search, filters** (enabled-only, ambient-only), sort (Title/Artist/Source).
- **Persistence** — `cue-my-music/` folder in game directory with `config.json` + `library.json` (human-inspectable JSON).
- **Local OGG import** — copy to `cue-my-music/audio/`; ffmpeg auto-converts non-OGG if available.
- **External tools isolated** — `spotDL` / `yt-dlp` / `ffmpeg` detected via `ExternalToolService`; mod runs without them.
- **Playback director** — selects from active preset's ambient-eligible tracks, skips missing files, plays local OGGs via `LocalOggSoundInstance` on the MUSIC sound channel (respects Music volume), vanilla tracks via registry sound events.
- **Mod Menu** integration — Configure opens the Jukebox Library.
- **Jukebox Library UI** — left sidebar (Collections / Presets, roomy), main scrollable list, detail strip, bottom actions (Add Music, Edit Preset, Done). Feels vanilla.
- **Debug commands**: `/cuemymusic` / `/cmm` (`open`, `next`, `preset list|set`, `rescan`, `import <path>`, `status`, `toggle`).

## Versions pinned

| Component | Version |
|-----------|---------|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric Loom | 1.17-SNAPSHOT (1.17.20) |
| Fabric API | 0.158.0+26.2 |
| Cloth Config | 26.2.155 (artifact `me.shedaniel.cloth:cloth-config`) |
| Mod Menu | 20.0.1 (`com.terraformersmc:modmenu`) |
| Java | 25 (release 25) |
| Gradle | 8.13 |

These are the current latest compatible for 26.2 at time of scaffolding (Aug 2026). Loom uses official Mojang mappings for 26.2.

## Project layout

```
cue-my-music/
├── src/main/java/com/cuemymusic/
│   ├── CueMyMusic.java
│   ├── config/CueMyMusicConfig.java
│   ├── data/{MusicTrack, MusicPreset, MusicLibrary, MusicCollection, SourceType}
│   └── persistence/{PersistenceManager, PersistedLibraryState}
├── src/client/java/com/cuemymusic/
│   ├── client/CueMyMusicClient.java
│   ├── client/music/VanillaTrackRegistry.java
│   ├── client/playback/{MusicDirector, LocalOggSoundInstance, PlaybackState}
│   ├── client/sources/{LocalImportService, ExternalToolService}
│   ├── client/ui/{JukeboxLibraryScreen, EditPresetScreen, TrackDetailWidget}
│   ├── client/integration/ModMenuIntegration.java
│   ├── client/debug/CueMyMusicCommands.java
│   └── mixin/MusicManagerMixin.java
├── src/main/resources/{fabric.mod.json, cue_my_music.mixins.json}
├── src/test/java/com/cuemymusic/{MusicLibraryTest, PlaybackDirectorTest, PersistenceTest}
├── gradle.properties / settings.gradle / build.gradle
└── cue-my-music/         (created at runtime in gameDir)
    ├── config.json
    ├── library.json
    ├── audio/            (cached OGGs)
    └── covers/           (optional art)
```

Architecture keeps `library` / `persistence` / `playback` / `sources` / `ui` decoupled behind small interfaces.

## Setup

### Requirements

- Java 25 (Temurin 25.0.4.1 recommended). Gradle will use `JAVA_HOME`.
- Minecraft 26.2 will be downloaded by Loom on first build.

### Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew build          # compiles, runs tests, produces jar
./gradlew test           # unit tests only
```

Jar output: `build/libs/cue-my-music-1.0.0.jar`

### Run client (dev)

```bash
./gradlew runClient
```

Loom will download 26.2 client, deobfuscate with official mappings, and launch with the mod on the classpath. First launch creates `cue-my-music/` in the run directory.

### Import a local OGG (in-game)

Either use the UI (Add Music) or command:

```
/cmm import /absolute/path/to/song.ogg local
/cmm import "/path/with spaces/track.ogg" spotify
```

Or place an `.ogg` directly into `cue-my-music/audio/` and run `/cmm rescan` or restart.

### External tools (optional)

- `ffmpeg` — for non-OGG imports. Install via `brew install ffmpeg`. Check: `ffmpeg -version`
- `yt-dlp` — `brew install yt-dlp` / `pipx install yt-dlp`
- `spotDL` — `pipx install spotDL`

The mod probes `PATH`; missing tools show as “missing” in logs and import gracefully degrades (only OGG without ffmpeg).

## Configuration

`cue-my-music/config.json`:

```json
{
  "activePresetId": "my_mix",
  "musicVolume": 1.0,
  "enableAmbientReplacement": true,
  "nextTrackDelaySeconds": 300,
  "enableDebugCommands": true
}
```

Edit via file or in-game preset screen (persists on save).

## Testing

See [TESTING.md](TESTING.md) for programmatic + in-game verification, debug commands, and manual checklist.

```bash
./gradlew test
```

## Git & GitHub

```bash
git log --oneline
gh repo view --web   # private repo cue-my-music
```

If `gh` was unauthenticated, create manually:

```bash
gh repo create cue-my-music --private --source=. --push
# or
git remote add origin https://github.com/<you>/cue-my-music.git
git push -u origin main
```

## Next steps (MVP v2 ideas)

- Real streaming of local OGGs via `OggAudioStream` (current stub wraps vanilla event; replace with proper channel).
- Album art download/cache and cover rendering in list rows.
- Background sync playlist watcher.
- OAuth for Spotify (deferred).
- Weighting / shuffle history, “Discover Weekly”-style director.
- Fancy animations, toast previews.
- Multiplayer preset sync (if ever needed).

---
MIT · personal use
