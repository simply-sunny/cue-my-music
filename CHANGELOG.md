# Changelog

All notable changes to Cue My Music are documented here. This project follows [Semantic Versioning](https://semver.org/).

## 1.0.0 — Unreleased

First release.

### Added

- Player screen: library sidebar, track list, now playing and play controls. Opens from a music button on the title screen and in the pause menu, from Mod Menu, or with a key.
- **Liked Songs**: a heart on every track and in now playing.
- Pages for **All game music**, each artist and each resource pack that adds music. Packs show their name, icon and credited author; packs without an icon get their initials on a gradient.
- Play any track, pause, seek, next and previous. Space plays and pauses in the player.
- Play through a list: play a song on any page and the rest of the page follows in the order shown, then the game's music takes over.
- Queue: add songs with **+**, or a whole page with **Add all to queue**. Drag to reorder, × to remove. Queued songs play before the list carries on, and a song queued with nothing playing starts at once.
- Chat commands: `/cmm play [song]`, `/cmm queue <song>`, `/cmm next`, `/cmm prev` and `/cmm pause`, with song suggestions.
- **Pause during travel** setting. Off (default), your song keeps playing through dimension changes; on, it pauses while the new dimension loads and resumes from the same spot.
- The game's own music plays through the player, exactly as in vanilla: same pick, same break, same volume and pitch per file, same toast. The queue shows the game's next song and the break before it.
- Sort the track list by title, album or length.
- Long titles and artists scroll in the now playing panel.
- Pitch shift from −12 to +12 semitones that doesn't change speed.
- Speed from 0.5× to 2.0× that doesn't change pitch.
- 6-band equalizer with presets, for every song.
- Built-in lengths for vanilla tracks. Resource pack tracks are measured from the file.
- Keys for open player, effects on/off, previous, play/pause and next, in their own **Cue My Music** section. They work on any screen. Previous, play/pause and next default to F7, F8 and F9 on macOS.
- Icon made from the game's note block and note textures.
- 34 headless checks: DSP, pack names, initials and credits, and the play order rules.

### Notes

- Mod Menu is required.
- Jukeboxes and sound effects aren't affected.
