<h1 align="center">Cue My Music</h1>

<p align="center">Change the pitch and speed of in-game music, independently.<br>Fabric &amp; Quilt &nbsp;|&nbsp; 26.3</p>

<p align="center">
<a href="https://github.com/simply-sunny/cue-my-music/releases"><img src="https://img.shields.io/github/v/release/simply-sunny/cue-my-music?label=release&color=2ea44f" alt="Latest release"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license"></a>
</p>

---

Drop the background music 2 semitones, or play it at 1.2× speed, or both. Pitch and speed are separate settings: changing one doesn't change the other. This differs from turning a record's pitch knob, which changes both together. It works on background music and on music discs in jukeboxes.

Speed can be a multiplier (`1.2×`) or a BPM offset (`+10 BPM`). In BPM mode, each song's tempo is detected when it starts, so `+10` means 10 BPM faster than that song, whatever its tempo is.

## Install

Client-side only. Servers don't need it.

1. Install [Fabric Loader](https://fabricmc.net/use/) (or Quilt Loader), [Fabric API](https://modrinth.com/mod/fabric-api) and [Cloth Config](https://modrinth.com/mod/cloth-config). [Mod Menu](https://modrinth.com/mod/modmenu) is optional; it adds a settings button.
2. Put the jar in `mods`.

| Minecraft | Jar | Java | Cloth Config |
| --- | --- | --- | --- |
| 26.3 | `26.3` | 25+ | 26.3.159+ |

Jars are named `cue-my-music-<jar>-<version>.jar`.

## Use

Open **Mod Menu → Cue My Music**, or bind **Open settings** under **Options → Controls → Key Binds → Cue My Music**. Changes apply to the song that's playing within a few seconds, which is how much audio the game has already queued.

| Setting | Default | Notes |
| --- | --- | --- |
| Enabled | On | Also bindable as **Turn on/off**. |
| Pitch | 0 semitones | −12 to +12. 12 is one octave. |
| Speed mode | Multiplier | **Multiplier** or **BPM offset**. |
| Speed multiplier | 1.0 | 0.5 to 2.0. `1.2` is 20% faster. |
| BPM offset | 0 | −60 to +60, added to each song's detected BPM. |
| Background music | On | Menu, overworld, Nether, End, and so on. |
| Music discs | On | Jukeboxes. |

The settings screen shows the last song that played and its detected BPM.

Settings are saved to `config/cuemymusic.json`. Delete the file to reset. One key exists only in the JSON:

| Key | Default | Notes |
| --- | --- | --- |
| `fallbackBpm` | `120` | BPM assumed for songs with no clear beat in BPM offset mode. |

### BPM detection

BPM offset mode decodes the first 30 seconds of a song before it starts, finds the beat, and sets the speed to `(bpm + offset) / bpm`. Many vanilla tracks are ambient and have no clear beat. Those use `fallbackBpm`, so `+10` acts like ×1.083 on them.

Checked against the 26.3 tracks: Pigstep reads 169 (its tempo is 170). Cat, Blocks, Chirp, Otherside, Lava Chicken and Clark get a BPM. Sweden, Minecraft, Moog City 2, Taswell, Relic, Precipice and Creator read as no clear beat. Like any beat tracker, it can land on half or double the tempo of a song.

## How it works

Every streamed song in the `music` or `record` sound category gets wrapped before it reaches OpenAL. Samples go through two stages:

1. **WSOLA time-stretch** by `speed / pitch`. This cuts the audio into overlapping slices of about 40 to 125 ms and lines each one up with the previous before crossfading. It changes length without changing pitch.
2. **Resampling** by `pitch`. This raises or lowers pitch and changes length by the same amount, so the net result is the requested pitch at the requested speed.

Both stages are written from scratch, in plain Java with no native code. Processing runs about 500× faster than realtime on an M-series Mac, so a 3-minute song takes about 0.3 s of CPU. Sound effects and note blocks aren't touched.

## Develop

Requires JDK 25.

```bash
./gradlew clean test buildAll
```

This runs the headless DSP checks (pitch and length on test tones, BPM on click tracks) and builds every target. Jars land in `build/libs/`. To build one target:

```bash
./gradlew :26.3:build
```

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `requires cloth-config2` | Install Cloth Config for your Minecraft version. |
| No settings button | Install Mod Menu, or bind **Open settings** in Controls. |
| Change doesn't apply | Wait a few seconds. Already-queued audio plays out first. |
| BPM offset does nothing on a song | The song has no clear beat. It uses `fallbackBpm`, or switch to Multiplier. |
| Broken config | Delete `config/cuemymusic.json` and relaunch. |

Logs are in `.minecraft/logs/latest.log`. In BPM offset mode, each song's detected BPM is logged there. Include the log and your Minecraft, mod and loader versions when you report an issue.

## Limits

- **Sound effects:** not affected, only music and discs.
- **Other music mods:** mods that replace the music stream (custom players, streaming radios) may bypass it.
- **Extreme settings:** WSOLA sounds best within about ±5 semitones and 0.75–1.5× speed. Beyond that you'll hear some smearing or echo.
- **Quilt:** expected to work through Fabric compatibility. Not tested yet.
- **Tested:** Fabric Loader 0.20.1, Fabric API 0.165.0, Cloth Config 26.3.159 and Mod Menu 21.0.0 on 26.3, macOS.

## License

MIT, see [LICENSE](LICENSE). Copyright © 2026 Saunak Karnati (simply-sunny). Minecraft is a trademark of Mojang Studios.
