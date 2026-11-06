# CurseForge release sheet

CurseForge projects can't be created through the API, so the project is made by hand at [authors.curseforge.com](https://authors.curseforge.com/) with these settings. Files are then uploaded with `scripts/publish-curseforge.py`. The listing body starts after the horizontal rule. It matches `MODRINTH.md` except that mod links point at CurseForge.

## Project settings

| Field | Value |
| --- | --- |
| Name | **Cue My Music** |
| Project ID | `1766472` (slug `cue-my-music`) |
| Class | Mods |
| Summary | A music player for Minecraft's soundtrack, with a queue, pitch, speed and an equalizer. |
| Main category | Cosmetic |
| Other categories | Utility & QoL |
| License | MIT |
| Avatar | `src/main/resources/assets/cuemymusic/icon.png` |
| Source | https://github.com/simply-sunny/cue-my-music |
| Issues | https://github.com/simply-sunny/cue-my-music/issues |
| Wiki | https://simply-sunny.github.io/cue-my-music/ |

## Files

One file per jar, release type `release`. The script sets the version tags and relations:

| JAR | Game versions | Other tags |
| --- | --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |
| `cue-my-music-26.3-1.1.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |
| `cue-my-music-26.3-1.2.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |
| `cue-my-music-26.3-1.3.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |
| `cue-my-music-26.3-1.4.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |

1.0.0 uploaded 2026-10-25 as file `9328884`; 1.1.0 uploaded 2026-10-26 as file `9329902`; 1.2.0 uploaded 2026-10-26 as file `9330290`; 1.3.0 uploaded 2026-10-27 as file `9347989`; 1.4.0 uploaded 2026-11-03 as file `9423438`.

Relations: Fabric API and Mod Menu, both required.

```bash
./gradlew buildAll
python3 scripts/publish-curseforge.py <project-id> --dry-run   # check the tags and metadata
python3 scripts/publish-curseforge.py <project-id>             # uploads; published once CurseForge approves it
```

---

A music player for Minecraft's own soundtrack.

Browse every song in the game by artist or resource pack. Play one and the rest of its page follows, queue up what's next, or like it for later. When a list ends, similar songs carry on, or turn Autoplay off and the game takes over, with the queue showing which song it picked and when it plays.

Pitch, speed, and a 6-band equalizer apply to every song. Pitch and speed are separate: drop the music 2 semitones without slowing it down, or play it at 1.2× without raising it.

## Features

- **Player:** opens from a music button on the title screen or pause menu, from Mod Menu, or with a key.
- **Modes:** Vanilla (the mod off), Vanilla + FX (the game's music with your effects), and Add-ons (your packs and downloads), with a key to switch.
- **Home:** lists you played lately, your top songs and artists, and radios made from your top songs.
- **Pages:** all game music, Liked Songs, each artist, and each resource pack that adds music, in a library with folding sections.
- **Track pools:** each place's music (the title screen, survival, underwater, every biome) as a list you can change.
- **Playlists:** make your own; right-click any song to add it.
- **Radio:** in Add-ons, endless music from what you like and play through, leaning away from what you skip.
- **Play-through:** a song plays the rest of its page after it, and Autoplay carries on with similar songs when the list ends.
- **Queue:** add a song or a whole page, drag to reorder. It shows the game's next song and the break before it.
- **Shuffle and repeat:** shuffle a list, and repeat off, all, or one.
- **Mixing:** add-on songs blend into each other with beatmatched transitions you can edit, and Next can skip with a transition.
- **Dislike:** thumbs down a song and the game never picks it for its own music. You can still play it.
- **Select several:** shift-click, shift-drag, or Cmd/Ctrl-click songs, then queue, like, dislike, or add them to a playlist at once.
- **Your Stats:** top songs and artists, a 24-hour listening clock, a calendar of every day you listened, streaks, and where you listen.
- **Vanilla timing:** in Vanilla + FX the game picks its songs, and you choose the game's Frequency or a set time between them.
- **Pitch:** −12 to +12 semitones, same speed.
- **Speed:** 0.5× to 2.0×, same pitch.
- **Equalizer:** 6 bands with presets.
- **Commands:** `/cmm play`, `queue`, `next`, `prev`, `pause`, `repeat`, and `dislike`.
- **Keys:** play/pause, next, and previous, on any screen. F7–F9 by default on macOS. Spotify's player keys work inside the player.
- **Travel:** your song keeps playing through loading screens, dimension changes, and server switches, or pauses until the new one loads.
- **Resource packs:** each gets its own page, with its name, icon, and author. A pack can name its songs with a `cuemymusic.json`.

## Installation

For Minecraft 26.3.

1. Install Fabric Loader (or Quilt Loader).
2. Install [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api).
3. Install [Mod Menu](https://www.curseforge.com/minecraft/mc-mods/modmenu).
4. Put the jar in `mods`.

## Compatibility

- **Minecraft:** 26.3 on Fabric and Quilt. Java 25.
- **Client-side only.** Servers don't need it.
- **Background music only.** Jukeboxes and sound effects aren't touched.
- **Other music mods** that replace the music stream may bypass it.

Full settings and troubleshooting are on [GitHub](https://github.com/simply-sunny/cue-my-music#readme).

## License

MIT, see the [LICENSE](https://github.com/simply-sunny/cue-my-music/blob/main/LICENSE). Minecraft is a trademark of Mojang Studios.
