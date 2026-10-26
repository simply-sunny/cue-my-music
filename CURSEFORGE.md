# CurseForge release sheet

CurseForge projects can't be created through the API, so the project is made by hand at [authors.curseforge.com](https://authors.curseforge.com/) with these settings. Files are then uploaded with `scripts/publish-curseforge.py`.

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
| Description | The listing body from `MODRINTH.md`, everything after the horizontal rule. |
| Source | https://github.com/simply-sunny/cue-my-music |
| Issues | https://github.com/simply-sunny/cue-my-music/issues |

## Files

One file per jar, release type `release`. The script sets the version tags and relations:

| JAR | Game versions | Other tags |
| --- | --- | --- |
| `cue-my-music-26.3-1.0.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |
| `cue-my-music-26.3-1.1.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |
| `cue-my-music-26.3-1.2.0.jar` | `26.3` | Fabric, Quilt, Client, Java 25 |

1.0.0 uploaded 2026-10-25 as file `9328884`; 1.1.0 uploaded 2026-10-26 as file `9329902`; 1.2.0 uploaded 2026-10-26 as file `9330290`.

Relations: Fabric API and Mod Menu, both required.

```bash
./gradlew buildAll
python3 scripts/publish-curseforge.py <project-id> --dry-run   # check the tags and metadata
python3 scripts/publish-curseforge.py <project-id>             # uploads; published once CurseForge approves it
```
