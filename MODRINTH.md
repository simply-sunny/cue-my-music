# Modrinth release sheet

This file is the source of truth for the Modrinth project settings, version metadata, and listing body. The listing body starts after the horizontal rule.

## Project settings

| Field | Value |
| --- | --- |
| Title | **Cue My Music** |
| Slug | `cue-my-music` |
| Project type | Mod |
| Project ID | *(not created yet)* |
| Summary | Change the pitch and speed of in-game music and music discs, independently. Fabric and Quilt 26.3, client-side. |
| License | MIT |
| Environment | Client-side only (`client_only`) |
| Featured categories | `utility`, `decoration` |
| Source | https://github.com/simply-sunny/cue-my-music |
| Issues | https://github.com/simply-sunny/cue-my-music/issues |
| Wiki / docs | https://github.com/simply-sunny/cue-my-music#use |
| Discord | *(none)* |
| Donation links | *(none)* |
| Icon | *(todo)* 512×512 PNG |

## Version metadata

Upload one Modrinth version per JAR, version number `1.0.0`, channel `release`, loaders `fabric` and `quilt`.

| JAR | Modrinth `game_versions` |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `26.3` |

Version name format: `Cue My Music 1.0.0 (26.3)`.

### Dependencies

| Project | ID | Type |
| --- | --- | --- |
| Fabric API | `P7dR8mSH` | Required |
| Cloth Config API | `9s6osm5g` | Required |
| Mod Menu | `mOgUt4GM` | Optional |

---

Change the pitch and speed of in-game music, independently.

Drop the music 2 semitones, play it at 1.2× speed, or add 10 BPM to every song. Pitch and speed are separate settings, so changing one doesn't change the other. Works on background music and music discs.

## Features

- **Pitch:** −12 to +12 semitones, same speed.
- **Speed:** a multiplier (0.5–2.0×) or a BPM offset, same pitch.
- **BPM offset:** each song's tempo is detected when it starts, so `+10` means 10 BPM faster than that song.
- **Live:** changes apply to the song that's already playing.
- **Pick what's affected:** background music, music discs, or both. Sound effects are never touched.
- **Settings screen:** Cloth Config, through Mod Menu or a key.

## Installation

1. Install Fabric Loader, Fabric API and Cloth Config for 26.3. Mod Menu is optional.
2. Put the jar in `mods`.

## Compatibility

- **Minecraft:** 26.3 on Fabric and Quilt. Java 25.
- **Client-side only.** Servers don't need it.
- **Ambient tracks:** many vanilla tracks have no clear beat. In BPM offset mode they're treated as 120 BPM.
- **Other music mods** that replace the music stream may bypass it.

Full settings and troubleshooting notes are on [GitHub](https://github.com/simply-sunny/cue-my-music#readme).

## License

MIT, see the [LICENSE](https://github.com/simply-sunny/cue-my-music/blob/main/LICENSE). Minecraft is a trademark of Mojang Studios.
