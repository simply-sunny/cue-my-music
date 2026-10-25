# Changelog

All notable changes to Cue My Music are documented here. This project follows [Semantic Versioning](https://semver.org/).

## 1.0.0 — Unreleased

First release.

### Added

- Pitch shift from −12 to +12 semitones that doesn't change speed.
- Speed change as a multiplier (0.5–2.0×) or a BPM offset (−60 to +60) that doesn't change pitch.
- BPM detection from each song's first 30 seconds, with a `fallbackBpm` for songs with no clear beat.
- Separate toggles for background music and music discs.
- Cloth Config settings screen, through Mod Menu or an **Open settings** key, showing the last song and its BPM.
- **Turn on/off** key.
- 16 headless DSP checks.
