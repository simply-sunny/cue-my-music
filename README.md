<p align="center">
  <img src="src/main/resources/assets/cue_my_music/icon.png" width="128" height="128" alt="Cue My Music logo" />
</p>

# Cue My Music

Deterministic vanilla background music with a boxed Pause-screen transport: Play/Pause, draggable scrub bar, Previous, immediate Next, and End song with the natural delay.

## Features

- **Vanilla Playback Ownership**: Integrates directly with Minecraft's native `MusicManager` to preserve ticks, fades, delays, streaming backend, replacement rules, and toasts.
- **Pause Screen Transport**: Compact, GUI-scale-aware top-right transport panel on the Escape menu with track metadata, live audible clock, and full playback controls.
- **Previous, Next & End**: Skip immediately to deterministic situational tracks, simulate natural song ends with normal delay recomputation, or step back through recently played history.
- **Channel Play/Pause**: Pauses only the background music channel without disrupting world sounds, ambient audio, or global volume resets.
- **Draggable Scrub Bar**: Real-time seeking via off-thread stream skipping and compressed OGG framing duration detection.
- **Deterministic Session Isolation**: Seeded per-session music planner maintaining deterministic context-aware track selection across world joins and disconnects.
- **Zero Bloat**: No external audio codecs, downloads, background services, custom registries, or extraneous configuration screens.

## Requirements

- Minecraft 26.2
- Fabric Loader (>=0.19.3)
- Fabric API
- Mod Menu 20.0.1 or another Minecraft 26.2-compatible version
- Java 25

## Install

1. Install Fabric Loader for Minecraft 26.2.
2. Add Fabric API and a Minecraft 26.2-compatible Mod Menu to `mods/`.
3. Add `cue-my-music-0.1.1.jar` (from [Releases](https://github.com/simply-sunny/cue-my-music/releases)) to `mods/`.
4. Launch the client.

## Controls

- Open Pause Screen: press `Escape`.
- Transport Controls (top-right panel):
  - **Previous**: Step back through recently played tracks in the current context.
  - **Play / Pause**: Toggle music playback without muting environmental sounds.
  - **Next**: Immediately advance to the next context-appropriate track.
  - **End**: Stop the current song and trigger natural vanilla cooldown delay before next track.
  - **Scrub Bar**: Click or drag slider (or use arrow keys when focused) to seek within the song.

## Build

```bash
./gradlew clean test build
```

Output jar: `build/libs/cue-my-music-0.1.1.jar` (requires Java 25).

## Limitations

- Client-side only; hooks into native Minecraft client audio engine.
- Seeking is stream-discard based (`O(target duration)`) capped at 256 MiB dropped PCM to avoid memory overhead.
- Chained OGG stream containers report duration based on initial bitstream framing.
- Built specifically for Mojang-mapped Minecraft 26.2 runtime.

## License

MIT — see [LICENSE](LICENSE).
