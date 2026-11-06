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
| Summary | A music player for Minecraft's soundtrack, with a queue, pitch, speed and an equalizer. |
| License | MIT |
| Environment | Client-side only (`client_only`) |
| Featured categories | `utility` |
| Source | https://github.com/simply-sunny/cue-my-music |
| Issues | https://github.com/simply-sunny/cue-my-music/issues |
| Wiki / docs | https://simply-sunny.github.io/cue-my-music/ |
| Discord | *(none)* |
| Donation links | *(none)* |
| Icon | `src/main/resources/assets/cuemymusic/icon.png` (512×512) |
| Gallery | Player (featured), Settings, Pause menu, Commands. 1920 px wide screenshots from 26.3. |

## Version metadata

Upload one Modrinth version per JAR, version number as in `gradle.properties`, channel `release`, loaders `fabric` and `quilt`, environment `client_only`. Quilt loads Fabric mods and is confirmed to work.

| JAR | Version ID |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `bjLhbMkU` |
| `cue-my-music-26.3-1.1.0.jar` | `SOc6iB4G` |
| `cue-my-music-26.3-1.2.0.jar` | `UuENeOmm` |
| `cue-my-music-26.3-1.3.0.jar` | `NEwmFHer` |
| `cue-my-music-26.3-1.4.0.jar` | `zzJi1vnd` |

| JAR | Modrinth `game_versions` |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `26.3` |
| `cue-my-music-26.3-1.1.0.jar` | `26.3` |
| `cue-my-music-26.3-1.2.0.jar` | `26.3` |
| `cue-my-music-26.3-1.3.0.jar` | `26.3` |
| `cue-my-music-26.3-1.4.0.jar` | `26.3` |

Version name format: `Cue My Music 1.4.0 (26.3)`.

### Dependencies

| Project | ID | Type |
| --- | --- | --- |
| Fabric API | `P7dR8mSH` | Required |
| Mod Menu | `mOgUt4GM` | Required |

---

A music player for Minecraft's own soundtrack.

Browse every song by artist or resource pack, and queue up what's next. Change pitch and speed separately. When a list ends, similar songs carry on, or the game takes over.

- Queue, playlists, shuffle and repeat
- Pitch, speed and a 6-band equalizer
- Radio and listening stats
- A page for each resource pack with music
- Commands and keys that work on any screen
- Beatmatched mixing for add-on songs

## Installation

For Minecraft 26.3 on Fabric or Quilt. Client-side only.

1. Install [Fabric API](https://modrinth.com/mod/fabric-api).
2. Install [Mod Menu](https://modrinth.com/mod/modmenu).
3. Put the jar in `mods`.

Only background music changes. Jukeboxes and sound effects don't.

## More

Keys, commands and settings are on the [wiki](https://simply-sunny.github.io/cue-my-music/).

MIT, see the [LICENSE](https://github.com/simply-sunny/cue-my-music/blob/main/LICENSE). Minecraft is a trademark of Mojang Studios.
