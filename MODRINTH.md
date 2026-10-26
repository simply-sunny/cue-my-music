# Modrinth release sheet

This file is the source of truth for the Modrinth project settings, version metadata, and listing body. The listing body starts after the horizontal rule.

## Project settings

| Field | Value |
| --- | --- |
| Title | **Cue My Music** |
| Slug | `cue-my-music` |
| Project type | Mod |
| Project ID | `dCISwMOO` (submitted for review 2026-10-25) |
| CurseForge project ID | `1766472` (slug `cue-my-music`), uploaded with `scripts/publish-curseforge.py` |
| Summary | A music player for Minecraft's own soundtrack, with a queue, pitch, speed, and an equalizer. Fabric and Quilt 26.3, client-side. |
| License | MIT |
| Environment | Client-side only (`client_only`) |
| Featured categories | `utility`, `decoration` |
| Source | https://github.com/simply-sunny/cue-my-music |
| Issues | https://github.com/simply-sunny/cue-my-music/issues |
| Wiki / docs | https://github.com/simply-sunny/cue-my-music#use |
| Discord | *(none)* |
| Donation links | *(none)* |
| Icon | `src/main/resources/assets/cuemymusic/icon.png` (512×512, built by `scripts/MakeIcon.java`) |
| Gallery | Player (featured), Settings, Pause menu, Commands. 1920 px wide screenshots from 26.3. |

## Version metadata

Upload one Modrinth version per JAR, version number as in `gradle.properties`, channel `release`, loaders `fabric` and `quilt`, environment `client_only`. Quilt loads Fabric mods and is confirmed to work.

| JAR | Version ID |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `bjLhbMkU` |
| `cue-my-music-26.3-1.1.0.jar` | *(pending)* |

| JAR | Modrinth `game_versions` |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `26.3` |
| `cue-my-music-26.3-1.1.0.jar` | `26.3` |

Version name format: `Cue My Music 1.1.0 (26.3)`.

### Dependencies

| Project | ID | Type |
| --- | --- | --- |
| Fabric API | `P7dR8mSH` | Required |
| Mod Menu | `mOgUt4GM` | Required |

---

A music player for Minecraft's own soundtrack.

Browse every song in the game by artist or resource pack. Play one and the rest of its page follows, queue up what's next, or like it for later. When you're done, the game takes over exactly as it would without the mod, and the queue shows which song it picked and when it plays.

Pitch, speed, and a 6-band equalizer apply to every song. Pitch and speed are separate: drop the music 2 semitones without slowing it down, or play it at 1.2× without raising it.

## Features

- **Player:** opens from a music button on the title screen or pause menu, from Mod Menu, or with a key.
- **Pages:** all game music, Liked Songs, each artist, and each resource pack that adds music, in a library with folding sections.
- **Playlists:** make your own; right-click any song to add it.
- **Play-through:** a song plays the rest of its page after it.
- **Queue:** add a song or a whole page, drag to reorder. It shows the game's next song and the break before it.
- **Repeat:** off, all, or one.
- **Dislike:** the game never picks a disliked song for its own music. You can still play it.
- **Vanilla timing:** the game still picks its songs and breaks exactly as usual.
- **Pitch:** −12 to +12 semitones, same speed.
- **Speed:** 0.5× to 2.0×, same pitch.
- **Equalizer:** 6 bands with presets.
- **Commands:** `/cmm play`, `queue`, `next`, `prev`, `pause`, `repeat`, and `dislike`.
- **Keys:** play/pause, next, and previous, on any screen. F7–F9 by default on macOS.
- **Travel:** your song keeps playing through dimension changes, or pauses until the new one loads.
- **Resource packs:** each gets its own page, with its name, icon, and author. A pack can name its songs with a `cuemymusic.json`.

## Installation

1. Install Fabric Loader (or Quilt Loader), Fabric API, and Mod Menu for 26.3.
2. Put the jar in `mods`.

## Compatibility

- **Minecraft:** 26.3 on Fabric and Quilt. Java 25.
- **Client-side only.** Servers don't need it.
- **Background music only.** Jukeboxes and sound effects aren't touched.
- **Other music mods** that replace the music stream may bypass it.

Full settings and troubleshooting are on [GitHub](https://github.com/simply-sunny/cue-my-music#readme).

## License

MIT, see the [LICENSE](https://github.com/simply-sunny/cue-my-music/blob/main/LICENSE). Minecraft is a trademark of Mojang Studios.
