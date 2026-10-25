<h1 align="center">Cue My Music</h1>

<p align="center">A music player for Minecraft's own soundtrack, with pitch, speed and an equalizer.<br>Fabric &amp; Quilt &nbsp;|&nbsp; 26.3</p>

<p align="center">
<a href="https://github.com/simply-sunny/cue-my-music/releases"><img src="https://img.shields.io/github/v/release/simply-sunny/cue-my-music?label=release&color=2ea44f" alt="Latest release"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license"></a>
</p>

---

Pick any game track and play it, queue up what's next, or let the game keep choosing and see what it picked and when. Drop the music 2 semitones, play it at 1.2× speed, or both. Pitch and speed are separate settings: changing one doesn't change the other. A 6-band equalizer applies to every song.

Only background music is affected. Jukeboxes and sound effects play as normal.

## Install

Client-side only. Servers don't need it.

1. Install [Fabric Loader](https://fabricmc.net/use/) (or Quilt Loader), [Fabric API](https://modrinth.com/mod/fabric-api) and [Mod Menu](https://modrinth.com/mod/modmenu).
2. Put the jar in `mods`.

| Minecraft | Jar | Java | Mod Menu |
| --- | --- | --- | --- |
| 26.3 | `26.3` | 25+ | 21.0.0+ |

Jars are named `cue-my-music-<jar>-<version>.jar`.

## Use

Open **Mod Menu → Cue My Music**, or bind **Open player** under **Options → Controls → Key Binds → Cue My Music**.

The player has four parts:

- **Library** (left): **All game music**, then one page per artist (C418, Lena Raine, Aaron Cherof, Kumi Tanioka, Amos Roddy, fingerspit), then one page per resource pack that adds music. **Settings** is at the bottom. Artists with no tracks loaded are hidden.
- **Track list** (middle): click a track to play it. Hover it and press **+** to add it to the queue. Click **Title**, **Album** or the clock to sort: up, down, then back to the original order.
- **Now playing** (right): album, art, title and artist. Long names scroll. Below that is the queue. Hidden when the window is narrow.
- **Controls** (bottom): previous, play/pause, next and a progress bar. Click or drag the bar to seek.

Space plays and pauses while the player is open.

Album art comes from game textures, one block per album. Resource pack tracks use the pack's icon, or its initials on a colour gradient if it has none.

### The queue

Songs you queue play one after another, as soon as the current song ends. Drag a row to reorder it, click × to remove it, or double-click it to play it now.

After the queue comes the game's own music. The game still decides when music plays: a random break of up to 20 minutes between songs on the default setting (**Options → Music & Sounds → Frequency**), and different music on the title screen, underwater, in the Nether and so on. Cue My Music picks the game's next song and break ahead of time, with the same rules, so the queue can show them: **In 0:42** while waiting, or **After a 12:30 break** while a song plays. Click it to play it now and skip the break. **Next** with an empty queue does the same.

Songs the game starts play through the player too, with the usual "Now playing" toast. Songs you pick yourself aren't cut off when you change dimension or open a menu; the game's songs still are, as in vanilla. While a song is paused, the game's music stays quiet.

### Keys

Bind them under **Options → Controls → Key Binds**.

| Key | Category | Default |
| --- | --- | --- |
| Open player | Cue My Music | None |
| Turn effects on/off | Cue My Music | None |
| Previous song | Miscellaneous | F7 on macOS, none elsewhere |
| Play/pause music | Miscellaneous | F8 on macOS, none elsewhere |
| Next song | Miscellaneous | F9 on macOS, none elsewhere |

F7 to F9 are the media keys on a Mac keyboard. macOS keeps them unless you hold **fn**, or turn on **System Settings → Keyboard → Keyboard Shortcuts → Function Keys → Use F1, F2, etc. keys as standard function keys**.

### Resource packs

Any pack that adds or replaces music shows up as its own page. The album is the pack's name, the art is its icon, and the artist is the "By ..." line of its description if it has one (otherwise **Unknown artist**).

Packs written for older versions sometimes point at file names that no longer exist. Those songs can't play in vanilla either, so they're left out.

### Settings

Changes apply to the song that's playing within a few seconds, which is how much audio the game has already queued.

| Setting | Default | Notes |
| --- | --- | --- |
| Effects | On | Turns pitch, speed and EQ off together. Also bindable as **Turn effects on/off**. |
| Pitch | 0 semitones | −12 to +12. 12 is one octave. |
| Speed | ×1.00 | 0.5 to 2.0, in steps of 0.05. `1.2` is 20% faster. |
| Use equalizer | Off | Moving a band or picking a preset turns it on. |
| EQ bands | 0 dB | 60, 150, 400, 1k, 2.4k and 15k Hz, ±12 dB each. |

Presets: Flat, Bass Booster, Bass Reducer, Treble Booster, Treble Reducer, Vocal Booster, Small Speakers.

Settings are saved to `config/cuemymusic.json`. Delete the file to reset. In the JSON, `speed` takes any value from 0.5 to 2.0 to two decimals, such as `1.08`.

## How it works

Every streamed song in the `music` sound category gets wrapped before it reaches OpenAL. Samples go through three stages:

1. **WSOLA time-stretch** by `speed / pitch`. This cuts the audio into overlapping slices of about 40 to 125 ms and lines each one up with the previous before crossfading. It changes length without changing pitch.
2. **Resampling** by `pitch`. This raises or lowers pitch and changes length by the same amount, so the net result is the requested pitch at the requested speed.
3. **Equalizer:** six biquad filters, a low shelf, four peaks and a high shelf. Output is turned down by half the largest boost, so boosting doesn't clip.

All three are written from scratch, in plain Java with no native code. Processing runs about 500× faster than realtime on an M-series Mac.

The game's music manager still runs its timers. When it would start a song, the player plays the song it planned instead, and the game sees that song as its own, so it waits for it to end before starting its break.

Game streams can't rewind, so pause, seek and resume restart the track at a position and skip ahead to it. Track lengths for vanilla songs are built in. Resource pack songs are measured from their Ogg file the first time they show up.

## Develop

Requires JDK 25.

```bash
./gradlew clean buildAll
```

This runs the headless DSP checks (pitch and length on test tones, EQ response on sine waves) and builds every target. Jars land in `build/libs/`. To build one target:

```bash
./gradlew :26.3:build
```

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `requires modmenu` | Install Mod Menu for your Minecraft version. |
| Change doesn't apply | Wait a few seconds. Already-queued audio plays out first. |
| Seek is slow on a long track | Seeking decodes up to that point. Far into a 6-minute track can take a moment. |
| Jukebox isn't affected | Expected. Only background music is. |
| F7–F9 change brightness or volume instead | Hold **fn**, or turn on standard function keys in macOS settings. |
| A pack's songs are missing | The pack points at files that don't exist in this version. |
| Broken config | Delete `config/cuemymusic.json` and relaunch. |

Logs are in `.minecraft/logs/latest.log`. Include the log and your Minecraft, mod and loader versions when you report an issue.

## Limits

- **Sound effects and jukeboxes:** not affected, only background music.
- **Other music mods:** mods that replace the music stream (custom players, streaming radios) may bypass it.
- **Extreme settings:** WSOLA sounds best within about ±5 semitones and 0.75–1.5× speed. Beyond that you'll hear some smearing or echo.
- **Quilt:** expected to work through Fabric compatibility. Not tested yet.
- **Tested:** Fabric Loader 0.20.1, Fabric API 0.165.0 and Mod Menu 21.0.0 on 26.3, macOS. Play, pause, seek, next and previous were checked in game, plus the playlist, artist, pack and settings pages at the default and a larger window size. The queue, sorting, and the game's break and next song were checked on the title screen. Packs tested: True Ending Dragon Music, C4Music 4.3, Golden Days Music, C418 Background Music Only.

## License

MIT, see [LICENSE](LICENSE). Copyright © 2026 Saunak Karnati (simply-sunny). Minecraft is a trademark of Mojang Studios.
