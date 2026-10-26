<p align="center"><img src="src/main/resources/assets/cuemymusic/icon.png" width="128" alt="Cue My Music icon"></p>

<h1 align="center">Cue My Music</h1>

<p align="center">A music player for Minecraft's own soundtrack.<br>Fabric &amp; Quilt &nbsp;|&nbsp; 26.3</p>

<p align="center">
<a href="https://modrinth.com/mod/cue-my-music"><img src="https://img.shields.io/modrinth/dt/cue-my-music?logo=modrinth&label=Modrinth&color=1bd96a" alt="Modrinth downloads"></a>
<a href="https://www.curseforge.com/minecraft/mc-mods/cue-my-music"><img src="https://img.shields.io/curseforge/dt/1766472?logo=curseforge&label=CurseForge&color=f16436" alt="CurseForge downloads"></a>
<a href="https://github.com/simply-sunny/cue-my-music/releases"><img src="https://img.shields.io/github/v/release/simply-sunny/cue-my-music?label=release&color=2ea44f" alt="Latest release"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license"></a>
</p>

---

Browse every song in the game by artist or resource pack. Play one and the rest of its page follows, queue up what's next, or like it for later. When you're done, the game takes over exactly as it would without the mod, and the queue shows which song it picked and when it plays. Pitch, speed, and a 6-band equalizer apply to every song. Pitch and speed are separate: drop the music 2 semitones without slowing it down, or play it at 1.2× without raising it.

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
- The library on the left has **All game music**, **Liked Songs**, **Disliked Songs** once you have any, and pages added by add-ons. Below them are three sections: **Artists**, **Packs** (one page per resource pack that adds music), and **Your playlists**. Click a section's heading to fold it away; it shows its count while folded. Scroll the library when it's longer than the window. **Settings** stays at the bottom.
- **Search**: click the magnifying glass next to **Library**, or press Cmd+F (Ctrl+F on Windows and Linux) anywhere in the player. Type part of a song's title, artist or album, in any order and without accents. Matching artists show as chips that open their page; matching songs list below, titles that start with what you typed first. Enter plays the results from the top.
- Click a song to play it. The rest of the page plays after it, in the order shown. The big green button plays the page from the top, or pauses it while it's playing.
- Hover a song and click the heart to like it, or **+** to add it to the queue. **Add all to queue** adds the whole page.
- Right-click a song to queue it, like or dislike it, or add it to a playlist.
- **Dislike** (the thumbs down left of the heart on a hovered row, next to the heart under the song that's playing, or right-click) keeps the game from ever picking a song for its own music. Songs an add-on keeps on its own page never play by themselves, so they don't have it. If the game is playing it, it skips to another. You can still play it yourself, and it still plays through a page you started. Disliked songs are greyed with a red thumbs down, and listed on **Disliked Songs**. Click it again to bring one back. Liking a song takes its dislike away, and the other way round.
- The repeat button next to play cycles through off, repeat all (the page starts over at its end), and repeat one (the song plays again). Next and previous still move on.
- Click **Title**, **Album**, or the clock to sort. Each click goes up, down, then back to the original order.
- Music pauses while Minecraft is in the background and resumes when you return, following Minecraft's **Pause on lost focus** (F3+P turns it on and off).
- Space plays and pauses while the player is open. Click or drag the progress bar to seek.
- Select several songs: shift-click or shift-drag for a run of songs, Cmd-click (Ctrl-click on Windows and Linux) to add or take out one, shift+↑/↓ to grow or shrink the selection, and Cmd/Ctrl+A for the whole page. Right-click a selected song for one menu for all of them: queue, like, dislike, or add to a playlist. Delete or Backspace takes them off the page you're on: out of a playlist, off Liked Songs, or off Disliked Songs. Esc clears the selection.

### Commands

Client-side, so they work on any server. Songs are named by title, and chat suggests them as you type.

| Command | Does |
| --- | --- |
| `/cmm play <song>` | Plays the song now, then the queue, then the game's music. |
| `/cmm play` | Resumes. |
| `/cmm queue <song>` | Adds the song to the queue. |
| `/cmm next` | Next song. |
| `/cmm prev` | Previous song, or the start of this one. |
| `/cmm pause` | Pauses. |
| `/cmm repeat [off\|all\|one]` | Sets repeat, or cycles it with no argument. |
| `/cmm dislike [song]` | Dislikes the song, or the one playing. Again to take the dislike away. |

A partial title works if only one song matches, such as `/cmm play subwoofer`.

### Playlists

Click **New playlist** in the library, then right-click songs anywhere and pick **Add to** the playlist, or **Add to new playlist** to make one with that song. On a playlist's page, click its name or **Rename** to rename it, and click **Delete** twice to delete it. Right-click a song there to remove it. Playlists are saved with your settings.

### Your stats

**Your Stats**, above Settings in the library, shows what you've listened to:

- Time listened, plays, songs heard, and your streak of days in a row.
- Top songs (click one to play from there) and top artists.
- A listening clock: a petal for each hour of the day, longer the more you listen then.
- A calendar of the last few months, greener on days you listened more. Hover a day for its time.
- Where you listen, as a bar of Overworld, Nether and End blocks, and how much you picked versus the game.
- The song you skip most, with a button to dislike it.

A song is only counted once you've heard 3 seconds of it, so clicking through songs leaves no trace. It's a play after 30 seconds, or when it ends by itself after you heard at least half of it. Moving on before that is a skip. Seeking or pausing doesn't start a new play, and nothing is counted while music volume is at 0. Stats are kept in `config/cuemymusic-stats.json`, only on your computer. **Reset stats** at the bottom of the page (click twice) clears them.

### Mixing

With an add-on that adds songs, a **Mix** chip appears above lists with at least two of its songs. It opens the list as a mix: each song with its tempo, key (in Camelot notation, coloured so keys that mix well look alike) and length, and between two songs that blend, a chip with their transition.

Click a transition to edit it:

- Both songs' waveforms, bass in blue, mids in orange and highs in white, lined up where they overlap. The light blue line is each song's volume, and the yellow line is its bass during a bass swap. Drag along a song to move where the transition starts.
- The button between them plays from a few seconds before the transition.
- **Presets**: Auto (Blend when the tempos are close, otherwise Fade), Fade, Rise, Blend, Echo and Cut.
- **Volume**, **EQ**, **Filter** and **Effects** set the parts one by one: the crossfade curve, where the bass swaps, a filter sweep in or out, and an echo out. Changing one makes the transition Custom.
- **Length**, **Starts** (seconds before the first song's end) and **Next song comes in at**.

Songs keep their own speed, so there's no beatmatching. Tempo and key are worked out from the audio in the background and can be off, such as half or double the tempo.

### The queue

Queued songs play next, before the rest of the page. The page then picks up after the song the queue interrupted. A song queued while nothing is playing starts at once. Drag to reorder, click × to remove, or double-click to play now. A long queue scrolls.

**Next** plays the queue, then the page, then the game's next song. Where the game has no music, it carries on through **All game music**, skipping disliked songs. **Previous** goes back through the page, or restarts the song after its first 3 seconds.

After that, the game's own music returns. The game still decides when music plays: a random break of up to 20 minutes between songs on the default **Music Frequency**, and different music on the title screen, underwater, in the Nether, and so on. The queue shows the game's next song with **In 0:42** while waiting, or **After a 12:30 break** while a song plays. Click it to play it now.

Songs the game starts play exactly as in vanilla, apart from disliked songs: the same weighted pick from `sounds.json` (a disliked song is drawn again, so the rest keep their odds; if every song for the situation is disliked, the game stays quiet until its next break), the same break, each file's own volume and pitch, and the same "Now playing" toast. They still stop when you change dimension or when music that replaces them starts, such as the dragon fight. Songs you pick or queue yourself aren't cut off: they carry on through dimension changes and menus, and while a song is paused the game's music stays quiet.

### Resource packs

A pack that adds or replaces music gets its own page. The album is the pack's name, the art is its icon (or its initials on a gradient if it has none), and the artist is the "By ..." line of its description, or **Unknown artist**.

A pack can name its songs itself with `assets/<namespace>/cuemymusic.json`. Keys are sound files as in `sounds.json`, and any field left out falls back to the above:

```json
{
  "tracks": {
    "mypack:music/rain": { "title": "Rain", "artist": "Someone", "album": "Weather" }
  }
}
```

The player lists every sound event whose name starts with `music.`. Put songs in one the game never plays, such as `mypack:music.extra`, and they play from the player only.

## Configure

Open the player and click **Settings**. Changes apply to the song that's playing within a few seconds, which is how much audio the game has already queued. Until then a small ring shows next to the value.

| Setting | Default | Notes |
| --- | --- | --- |
| Effects | On | Turns pitch, speed, and EQ off together. |
| Pitch | 0 | −12 to +12 semitones. 12 is one octave. |
| Speed | ×1.00 | 0.5 to 2.0, in steps of 0.05. |
| Pause during travel | Off | On: your song pauses while a new dimension loads, then resumes from the same spot. Off: it keeps playing, with a brief gap. |
| Use equalizer | Off | Moving a band or picking a preset turns it on. |
| EQ bands | 0 dB | 60, 150, 400, 1k, 2.4k, and 15k Hz, ±12 dB each. |
| Automix add-on songs | On | Shown with an add-on page. Songs on add-on pages play at the same loudness, without silence at their ends, and blend into the next song when one follows another in a page or the queue (see [Mixing](#mixing)). Songs need the same sample rate to blend; otherwise they play one after the other. |
| Keep add-on songs out of recordings | On | Shown with an add-on page. Songs on add-on pages play from a small separate Java process, and the game plays silence in their place. Recorders that take only the game's own audio, such as rec-util or OBS's application audio capture, leave them out; recorders of all system audio still pick them up. Applies from the next song. Game music and music discs are unaffected. |

Presets: Flat, Bass Booster, Bass Reducer, Treble Booster, Treble Reducer, Vocal Booster, and Small Speakers.

Settings, liked and disliked songs, and playlists are saved to `config/cuemymusic.json` in your Minecraft folder:

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

This runs the headless checks (pitch and length on test tones, EQ response on sine waves, pack names, initials, and credits, and the play order rules) and builds the jar into `build/libs/`.

Pitch and speed use a WSOLA time-stretch followed by resampling, and the equalizer is six biquad filters, all in plain Java. The icon and the pause menu button are built from the game's textures by `scripts/MakeIcon.java`:

```bash
java scripts/MakeIcon.java <26.3 client jar> src/main/resources/assets/cuemymusic/icon.png src/main/resources/assets/cuemymusic/textures/gui/sprites/music.png
```

### Add-ons

An add-on can give itself a page in the player's library. Implement `mix.cuemymusic.api.CuePanel` and call `CuePanels.register(panel)` from your client initializer. The page draws through a `PanelCanvas`, which has the player's header, headings, text, text fields, buttons, progress bar, and song list, so it matches the rest of the player. A panel can also take over a resource pack's entry (`packId`; that pack's songs then stay out of **All game music** too), add items to the right-click menu of its songs (`songActions`), and decide what Delete does to the songs selected on its page (`deleteAction`). The API is new in 1.1.0 and may still change.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `requires modmenu` | Install Mod Menu for your Minecraft version. |
| Open player key does nothing | Bind **Open player** under Controls. |
| F7–F9 change brightness or volume | Hold **fn**, or turn on standard function keys in macOS settings. |
| A change doesn't apply | Wait for the ring next to it to go. Already-queued audio plays out first. |
| Seek is slow on a long song | Seeking decodes up to that point. Far into a 6-minute song can take a moment. |
| A pack's songs are missing | The pack points at files that don't exist in this version. Vanilla can't play them either. |
| Music sounds hollow with AirPods or other spatial audio | Fixed: stereo songs go straight to the speakers. If music still sounds off, turn off **Directional Audio** in **Options → Music & Sounds**. |
| Add-on songs are silent, or come out of a different device | With **Keep add-on songs out of recordings** on they play through the system's default output, not the game's. See `logs/cuemymusic-outside.log`, or turn the setting off. |
| Jukebox isn't affected | Expected. Only background music is. |
| Broken config | Delete `config/cuemymusic.json` and relaunch. |

Logs are in `.minecraft/logs/latest.log`. Include the log and your Minecraft, mod, and loader versions when you report an issue.

## Limits

- **Sound effects and jukeboxes:** not affected, only background music.
- **Other music mods:** mods that replace the music stream, such as custom players or radios, may bypass it.
- **Extreme settings:** pitch and speed sound best within about ±5 semitones and 0.75–1.5×. Beyond that you'll hear some smearing or echo.

## License

MIT, see [LICENSE](LICENSE). Copyright © 2026 Saunak Karnati (simply-sunny).

The icon and the pause menu button are made from Minecraft's note block, note particle, and "Now playing" textures. Minecraft is a trademark of Mojang Studios.
