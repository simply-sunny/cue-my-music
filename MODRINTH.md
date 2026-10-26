# Modrinth release sheet

This file is the source of truth for the Modrinth project settings, version metadata, and listing body. The listing body starts after the horizontal rule.

## Project settings

| Field | Value |
| --- | --- |
| Title | **Cue My Music** |
| Slug | `cue-my-music` |
| Project type | Mod |
| Project ID | `dCISwMOO` (draft, not submitted for review) |
| Summary | A music player for Minecraft's soundtrack, with pitch, speed and an equalizer. Fabric and Quilt 26.3, client-side. |
| License | MIT |
| Environment | Client-side only (`client_only`) |
| Featured categories | `utility`, `decoration` |
| Source | https://github.com/simply-sunny/cue-my-music |
| Issues | https://github.com/simply-sunny/cue-my-music/issues |
| Wiki / docs | https://github.com/simply-sunny/cue-my-music#use |
| Discord | *(none)* |
| Donation links | *(none)* |
| Icon | `src/main/resources/assets/cuemymusic/icon.png` (512×512, built by `scripts/MakeIcon.java`) |

## Version metadata

Upload one Modrinth version per JAR, version number `1.0.0`, channel `release`, loaders `fabric` and `quilt`, environment `client_only`. `scripts/publish-modrinth.py` creates the draft project and draft versions from this file; submitting for review is done by hand.

| JAR | Draft version ID |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `kpVlrJns` |

| JAR | Modrinth `game_versions` |
| --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `26.3` |

Version name format: `Cue My Music 1.0.0 (26.3)`.

### Dependencies

| Project | ID | Type |
| --- | --- | --- |
| Fabric API | `P7dR8mSH` | Required |
| Mod Menu | `mOgUt4GM` | Required |

---

A music player for Minecraft's own soundtrack, with pitch, speed and an equalizer.

Pick any game track and play it, queue up what's next, or let the game keep choosing and see what it picked and when. Drop the music 2 semitones, play it at 1.2× speed, or both. Pitch and speed are separate settings, so changing one doesn't change the other.

## Features

- **Player:** opens from a music button on the title screen or pause menu, Mod Menu or a key. Library on the left, track list in the middle, now playing on the right, controls at the bottom.
- **Liked Songs:** heart any track to keep it on its own page.
- **Artist pages:** C418, Lena Raine, Aaron Cherof, Kumi Tanioka, Amos Roddy and fingerspit, with only their in-game tracks.
- **Controls:** play any track, pause, seek, next and previous. Space pauses. Media keys F7–F9 on macOS.
- **Queue:** add songs, drag to reorder. After your queue it shows the game's next song and how long until it plays.
- **Vanilla timing:** the game still picks its songs and breaks exactly as usual; the player just shows them and plays them.
- **Sorting:** by title, album or length.
- **Pitch:** −12 to +12 semitones, same speed.
- **Speed:** 0.5× to 2.0×, same pitch.
- **Equalizer:** 6 bands with presets, for every song.
- **Live:** changes apply to the song that's already playing.
- **Resource packs:** each pack that adds music gets its own page, with its name, icon and author.

## Installation

1. Install Fabric Loader, Fabric API and Mod Menu for 26.3.
2. Put the jar in `mods`.

## Compatibility

- **Minecraft:** 26.3 on Fabric and Quilt. Java 25.
- **Client-side only.** Servers don't need it.
- **Background music only.** Jukeboxes and sound effects aren't touched.
- **Other music mods** that replace the music stream may bypass it.

Full settings and troubleshooting notes are on [GitHub](https://github.com/simply-sunny/cue-my-music#readme).

## License

MIT, see the [LICENSE](https://github.com/simply-sunny/cue-my-music/blob/main/LICENSE). Minecraft is a trademark of Mojang Studios.
