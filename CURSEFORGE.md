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
| `cue-my-music-26.3-1.5.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |

1.0.0 uploaded 2026-10-25 as file `9328884`; 1.1.0 uploaded 2026-10-26 as file `9329902`; 1.2.0 uploaded 2026-10-26 as file `9330290`; 1.3.0 uploaded 2026-10-27 as file `9347989`; 1.4.0 uploaded 2026-11-03 as file `9423438`; 1.5.0 uploaded 2026-11-06 as file `9486586`.

Relations: Fabric API and Mod Menu, both required.

```bash
./gradlew buildAll
python3 scripts/publish-curseforge.py <project-id> --dry-run   # check the tags and metadata
python3 scripts/publish-curseforge.py <project-id>             # uploads; published once CurseForge approves it
```

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

1. Install [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api).
2. Install [Mod Menu](https://www.curseforge.com/minecraft/mc-mods/modmenu).
3. Put the jar in `mods`.

Only background music changes. Jukeboxes and sound effects don't.

## More

Keys, commands and settings are on the [wiki](https://simply-sunny.github.io/cue-my-music/).

MIT, see the [LICENSE](https://github.com/simply-sunny/cue-my-music/blob/main/LICENSE). Minecraft is a trademark of Mojang Studios.
