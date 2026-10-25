# Changelog

All notable changes to Cue My Music are documented here. This project follows [Semantic Versioning](https://semver.org/).

## 1.0.0 — Unreleased

First release.

### Added

- Player screen in Mod Menu: library sidebar, track list, now playing and play controls.
- Pages for **All game music**, each artist and each resource pack that adds music. Packs show their name, icon and credited author; packs without an icon get their initials on a gradient.
- Play any track, pause, seek, next and previous. Space plays and pauses in the player.
- Queue: add songs with **+**, drag to reorder, × to remove.
- The game's own music plays through the player. The queue shows the game's next song and the break before it, picked ahead of time with the game's own rules.
- Sort the track list by title, album or length.
- Long titles and artists scroll in the now playing panel.
- Pitch shift from −12 to +12 semitones that doesn't change speed.
- Speed from 0.5× to 2.0× that doesn't change pitch.
- 6-band equalizer with presets, for every song.
- Built-in lengths for vanilla tracks. Resource pack tracks are measured from the file.
- **Open player** and **Turn effects on/off** keys, plus previous, play/pause and next in Miscellaneous (F7, F8 and F9 by default on macOS).
- 20 headless checks: DSP, plus pack names, initials and credits.

### Notes

- Mod Menu is required.
- Jukeboxes and sound effects aren't affected.
