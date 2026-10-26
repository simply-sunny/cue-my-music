<p align="center"><img src="src/main/resources/assets/cuemymusic/icon.png" width="128" alt="Cue My Music icon"></p>

<h1 align="center">Cue My Music</h1>

<p align="center">A music player for Minecraft's own soundtrack.<br>Fabric &amp; Quilt &nbsp;|&nbsp; 26.3</p>

<p align="center">
<a href="https://github.com/simply-sunny/cue-my-music/releases"><img src="https://img.shields.io/github/v/release/simply-sunny/cue-my-music?label=release&color=2ea44f" alt="Latest release"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license"></a>
</p>

---

Browse every song in the game by artist, album, or resource pack, play any of them, like them, and queue up what's next. When the queue runs out, the game takes over exactly as it would without the mod, and the queue shows which song it picked and how long until it plays. Pitch, speed, and a 6-band equalizer apply to every song. Pitch and speed are separate: drop the music 2 semitones without slowing it down, or play it at 1.2× without raising it.

Only background music is affected. Jukeboxes and sound effects play as normal.

## Install

Client-side only. Servers don't need it.

1. Install [Fabric Loader](https://fabricmc.net/use/) (or Quilt Loader), [Fabric API](https://modrinth.com/mod/fabric-api), and [Mod Menu](https://modrinth.com/mod/modmenu) for your Minecraft version. Mod Menu is required.
2. Put the jar in `mods`.

| Minecraft | Jar | Java | Mod Menu |
| --- | --- | --- | --- |
| 26.3 | `26.3` | 25+ | 21.0.0+ |

Jars are named `cue-my-music-<jar>-<version>.jar`.

## Use

| Action | Default key |
| --- | --- |
| Open player | Unbound. Bind it in Controls. |
| Play/pause music | `F8` on macOS, unbound elsewhere |
| Next song | `F9` on macOS, unbound elsewhere |
| Previous song | `F7` on macOS, unbound elsewhere |
| Turn effects on/off | Unbound |

All keys are rebindable under **Options → Controls → Key Binds → Cue My Music**. They work in game and on any screen without a text box in focus. On a Mac, F7 to F9 are media keys: hold **fn**, or turn on **System Settings → Keyboard → Keyboard Shortcuts → Function Keys → Use F1, F2, etc. keys as standard function keys**.

- Open the player from the music button on the title screen or pause menu, from **Mod Menu → Cue My Music**, or with **Open player**. The same key closes it.
- The library on the left has **All game music**, **Liked Songs**, one page per artist, one page per resource pack that adds music, and **Settings**.
- Click a song to play it. The rest of the page plays after it, in the order shown. The big green button plays the page from the top, or pauses it while it's playing.
- Hover a song and click the heart to like it, or **+** to add it to the queue. **Add all to queue** adds the whole page.
- Click **Title**, **Album**, or the clock to sort. Each click goes up, down, then back to the original order.
- Space plays and pauses while the player is open. Click or drag the progress bar to seek.

### The queue

Queued songs play one after another, as soon as the current song ends, and before the page you're playing carries on. The page then picks up after the song the queue interrupted. A song queued while nothing is playing starts at once. Drag a song to reorder it, click × to remove it, or double-click it to play it now. A long queue scrolls.

**Next** plays the queue's next song, then the page's, then the game's. **Previous** goes back through the page only, and restarts the song after its first 3 seconds.

After the queue and the page comes the game's own music. The game still decides when music plays: a random break of up to 20 minutes between songs on the default **Music Frequency**, and different music on the title screen, underwater, in the Nether, and so on. The queue shows the game's next song with **In 0:42** while waiting, or **After a 12:30 break** while a song plays. Click it to play it now.

Songs the game starts play exactly as in vanilla: the same weighted pick from `sounds.json`, the same break, each file's own volume and pitch, and the same "Now playing" toast. They still stop when you change dimension or when music that replaces them starts, such as the dragon fight. Songs you pick or queue yourself aren't cut off: they carry on through dimension changes and menus, and while a song is paused the game's music stays quiet.

### Resource packs

A pack that adds or replaces music gets its own page. The album is the pack's name, the art is its icon (or its initials on a gradient if it has none), and the artist is the "By ..." line of its description, or **Unknown artist**.

## Configure

Open the player and click **Settings**. Changes apply to the song that's playing within a few seconds, which is how much audio the game has already queued.

| Setting | Default | Notes |
| --- | --- | --- |
| Effects | On | Turns pitch, speed, and EQ off together. |
| Pitch | 0 | −12 to +12 semitones. 12 is one octave. |
| Speed | ×1.00 | 0.5 to 2.0, in steps of 0.05. |
| Pause during travel | Off | On: your song pauses while a new dimension loads, then resumes from the same spot. Off: it keeps playing, with a brief gap. |
| Use equalizer | Off | Moving a band or picking a preset turns it on. |
| EQ bands | 0 dB | 60, 150, 400, 1k, 2.4k, and 15k Hz, ±12 dB each. |

Presets: Flat, Bass Booster, Bass Reducer, Treble Booster, Treble Reducer, Vocal Booster, and Small Speakers.

Settings and liked songs are saved to `config/cuemymusic.json` in your Minecraft folder:

- **Windows:** `%appdata%\.minecraft\config\cuemymusic.json`
- **macOS:** `~/Library/Application Support/minecraft/config/cuemymusic.json`
- **Linux:** `~/.minecraft/config/cuemymusic.json`

Delete the file to reset to defaults. One value goes further in the JSON than in the screen. Edit it, then restart:

| Key | Default | Notes |
| --- | --- | --- |
| `speed` | `1.0` | Any value from 0.5 to 2.0, to two decimals, such as `1.08`. |

## Develop

Requires JDK 25.

```bash
./gradlew clean buildAll
```

This runs the headless checks (pitch and length on test tones, EQ response on sine waves, pack names, initials, and credits, and the queue and play order rules) and builds every target. Jars land in `build/libs/`. To build one target:

```bash
./gradlew :26.3:build
```

Pitch and speed use a WSOLA time-stretch followed by resampling, and the equalizer is six biquad filters, all in plain Java. The icon and the pause menu button are built from the game's textures by `scripts/MakeIcon.java`:

```bash
java scripts/MakeIcon.java <26.3 client jar> src/main/resources/assets/cuemymusic/icon.png src/main/resources/assets/cuemymusic/textures/gui/sprites/music.png
```

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `requires modmenu` | Install Mod Menu for your Minecraft version. |
| Open player key does nothing | Bind **Open player** under Controls. |
| F7–F9 change brightness or volume | Hold **fn**, or turn on standard function keys in macOS settings. |
| A change doesn't apply | Wait a few seconds. Already-queued audio plays out first. |
| Seek is slow on a long song | Seeking decodes up to that point. Far into a 6-minute song can take a moment. |
| A pack's songs are missing | The pack points at files that don't exist in this version. Vanilla can't play them either. |
| Jukebox isn't affected | Expected. Only background music is. |
| Broken config | Delete `config/cuemymusic.json` and relaunch. |

Logs are in `.minecraft/logs/latest.log`. Include the log and your Minecraft, mod, and loader versions when you report an issue.

## Limits

- **Sound effects and jukeboxes:** not affected, only background music.
- **Other music mods:** mods that replace the music stream, such as custom players or radios, may bypass it.
- **Extreme settings:** pitch and speed sound best within about ±5 semitones and 0.75–1.5×. Beyond that you'll hear some smearing or echo.
- **Quilt:** supported. Quilt Loader runs the Fabric jar, with Fabric API installed as on Fabric.

## License

MIT, see [LICENSE](LICENSE). Copyright © 2026 Saunak Karnati (simply-sunny).

The icon and the pause menu button are made from Minecraft's note block, note particle, and "Now playing" textures. Minecraft is a trademark of Mojang Studios.
