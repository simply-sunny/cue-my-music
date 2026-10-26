# Changelog

All notable changes to Cue My Music are documented here. This project follows [Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- Search: the magnifying glass next to Library, or Cmd/Ctrl+F. Finds songs by title, artist or album, and artists by name.
- Automix for songs on add-on pages: matched loudness, silence trimmed, and a crossfade into the next song.
- A **Mix** view for a list's songs, switched on by the Mix chip above the list once two songs next to each other can blend. The page keeps its header and buttons; the game's own songs show dimmed, since they don't blend. It shows each song's tempo and key, and the transition between each pair. Open a transition to see both songs' waveforms with its volume and bass curves, preview it, pick a preset (Auto, Fade, Rise, Blend, Echo, Cut), or set its volume curve, bass swap, filter and echo one by one, plus when it starts and where the next song comes in. Length is a choice of 2, 4, 8 or 16 bars. Each song's beats show as ticks between the two, and scrolling sideways over either song moves it a beat at a time: the first moves where the transition starts, the next where it comes in.
- A smarter **Auto** transition: it picks Blend, Rise, Fade or Echo from the two songs' tempos and keys, ends where the first song's music fades rather than in its quiet tail, and starts on a bar so both songs' bars line up. Songs are analysed again once for this.
- Keep add-on songs out of recordings: they play from a separate process, so recorders of the game's own audio leave them out.
- A **Drop** transition preset: the next song drops in at full volume as the first loses its bass, which then holds for half the transition and fades out in a straight line, over 16 bars. Its parts are also in the drop-downs: **Drop in, then fade** and **Bass swap at the start**. Length goes up to 40 seconds and shows in bars when the tempo is known.
- Search in a playlist or any other page: the magnifier in its toolbar narrows the songs shown as you type.
- Drag songs in a playlist to change their order, one at a time or a whole selection.
- **Vanilla music** in Settings turns Cue My Music and its add-ons off, so the game plays its own music as if they weren't there. The player screen then only offers to turn them back on.
- Music pauses while Minecraft is in the background, and resumes when you come back. **Pause in background** in Settings turns this off.

### Changed

- Tempo, key, loudness and waveforms of add-on songs are saved in `config/cuemymusic-analysis/` and read back next time instead of worked out again. Once the game has loaded, songs not worked out yet are done in the background, so the mix shows tempos and keys straight away.
- The player does less work each frame: the song list, the game's songs and add-on pages are kept between frames instead of rebuilt.
- The right-click menu looks like Spotify's: icons, dividers, and **Add to playlist** with a submenu you can search, instead of one row per playlist. It also has **Go to artist**.
- Stats ignore songs heard for under 3 seconds, so clicking through songs doesn't fill them with plays and skips. A song that ends by itself is a play only if you heard at least half of it (or 30 seconds), so seeking to the end isn't one. Seeking, pausing, travelling and clicking the playing song again carry on the same play. Nothing is counted while music volume is at 0.

### Fixed

- With **Pause during travel** on, a song could start again for a moment while the new dimension was still loading. It now waits until the loading screen has gone, then fades in.
- Automix crossfades crackled: the two songs' decoded blocks were mixed at different lengths, with silence filling the gap.

## 1.2.0 — 2026-10-26

### Added

- Select several songs with shift-click, shift-drag, Cmd/Ctrl-click, shift+↑/↓ or Cmd/Ctrl+A, then right-click to queue, like, dislike or add them all to a playlist, or press Delete to take them off a playlist, Liked Songs or Disliked Songs.
- **Your Stats** page: time listened, plays, streaks, top songs and artists, a 24-hour listening clock, a calendar of every day you listened, time per dimension, how much you picked versus the game, and the song you skip most.
- Add-ons can give their page a Delete action for selected songs.
- Dragging a selection to the top or bottom of the list scrolls it.

### Changed

- Dislike is a thumbs down.

### Fixed

- Music sounded hollow with spatial audio on, such as Directional Audio with AirPods. Stereo songs now go straight to the left and right speakers.

## 1.1.0 — 2026-10-26

### Added

- Repeat: off, all, or one, from the button next to play or with `/cmm repeat`.
- Playlists. **New playlist** in the library makes one; right-click any song to add it. Click a playlist's name to rename it.
- Right-click menu on songs: add to queue, like, add to a playlist.
- Resource packs can credit each song with `assets/<namespace>/cuemymusic.json`.
- Pitch, speed, and EQ changes show a loading ring until they're heard.
- Dislike: the ⊘ button on a song, right-click, or `/cmm dislike` and the game never picks it for its own music. It still plays when you pick it. Disliked songs get their own page.
- The library is grouped into Artists, Packs, and Your playlists, with one-line rows. Click a heading to fold it. The library scrolls when it's long.
- Add-ons can add their own page to the player's library (`mix.cuemymusic.api`).

### Fixed

- Songs a resource pack adds or removes show up after a reload without reopening the player.
- Queue headings and album names no longer run past their columns in a small window.

## 1.0.0 — 2026-10-25

First release.

### Added

- Player screen with a library, track list, now playing, and controls. Opens from a music button on the title screen and pause menu, from Mod Menu, or with a key.
- Pages for all game music, Liked Songs, each artist, and each resource pack that adds music.
- Play, pause, seek, next, and previous. A song plays the rest of its page after it.
- Queue: add a song or a whole page, drag to reorder, × to remove.
- The game's own music plays exactly as in vanilla, and the queue shows its next song and break.
- Pitch (−12 to +12 semitones) and speed (0.5× to 2.0×), independent of each other.
- 6-band equalizer with presets.
- Sorting by title, album, or length.
- Chat commands: `/cmm play`, `queue`, `next`, `prev`, and `pause`.
- Keys for the player, effects, previous, play/pause, and next. F7–F9 by default on macOS.
- **Pause during travel** setting.
- Icon made from the game's own textures.
- 34 headless regression checks.
