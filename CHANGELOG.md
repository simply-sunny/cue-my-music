# Changelog

All notable changes to Cue My Music are documented here. This project follows [Semantic Versioning](https://semver.org/).

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
