<p align="center">
  <img src="src/main/resources/assets/cue_my_music/icon.png" width="128" height="128" alt="Cue My Music logo" />
</p>

# Cue My Music

Deterministic vanilla background music with one responsive player for Pause, Options, and Mod Menu: Play/Pause, scrub, Previous, immediate Next, natural End, queue preview, and Playback Rate.

## Features

- **Vanilla Playback Ownership**: Integrates directly with Minecraft's native `MusicManager` to preserve ticks, fades, delays, streaming backend, replacement rules, and toasts.
- **Responsive Native Player**: A compact, GUI-scale-aware top-right player on Pause and eligible Options screens plus a centered expanded player opened through Mod Menu.
- **Previous, Next & End**: Skip immediately to deterministic situational tracks, simulate natural song ends with normal delay recomputation, or step back through recently played history.
- **Upcoming Queue**: Read-only next-five projection using the same deterministic weighted selector as playback; the expanded drawer moves below the player on narrow screens.
- **Playback Rate**: The expanded player adjusts music from `0.50×` to `2.00×` for the current game session. Like a turntable, it changes playback speed and pitch together; previews and other Minecraft sounds are unaffected.
- **Channel Play/Pause**: Pauses only the background music channel without disrupting world sounds, ambient audio, or global volume resets.
- **Draggable Scrub Bar**: Real-time seeking via off-thread stream skipping and compressed OGG framing duration detection.
- **Deterministic Session Isolation**: Seeded per-session music planner maintaining deterministic context-aware track selection across world joins and disconnects.
- **Zero Bloat**: No external audio codecs, downloads, background services, or custom registries.

## Requirements

- Minecraft 26.2
- Fabric Loader (>=0.19.3)
- Fabric API
- Mod Menu
- Java 25

## Install

1. Install Fabric Loader for Minecraft 26.2.
2. Add Fabric API and a Minecraft 26.2-compatible Mod Menu to `mods/`.
3. Add `cue-my-music-0.1.1.jar` (from [Releases](https://github.com/simply-sunny/cue-my-music/releases)) to `mods/`.
4. Launch the client.

## Controls

- Open Pause Screen: press `Escape`.
- Compact transport: use the top-right player on Pause or eligible Options screens.
- Expanded transport: open **Mod Menu → Cue My Music**.
- Player controls:
  - **Previous**: Step back through recently played tracks in the current context.
  - **Play / Pause**: Toggle music playback without muting environmental sounds.
  - **Next**: Immediately advance to the next context-appropriate track.
  - **End**: Stop the current song and trigger natural vanilla cooldown delay before next track.
  - **Scrub Bar**: Click or drag slider (or use arrow keys when focused) to seek within the song.
  - **Queue (`≡`)**: Show or hide the next five projected tracks.
  - **Playback Rate**: In the expanded player, adjust music speed and pitch together from `0.50×` to `2.00×`.

### Track weighting

Open **Mod Menu → Cue My Music → ⚙ Configure Track Pools…**. Cue My Music discovers loaded music pools and resource-pack tracks dynamically. `1×` preserves native chance, `0×` disables a track, and muting silences the selected pool. `✓` (Done) saves to `config/cue-my-music.json`; Esc discards the draft.

All settings UI lives in a centered workspace occupying 80% of the GUI width and height; the remaining 10% on each edge is empty background. From top to bottom the workspace holds the pool tab bar, the content area, and the icon action toolbar.

The radial chance wheel is CPU-generated and displayed through Minecraft's ordinary GUI texture path; it has no OpenGL, Vulkan, Metal, shader, or Fabric rendering API dependency.

- **Pool Tabs**: One native tab per discovered pool, labeled with clean category names (e.g. `Creative`, `Ender Dragon`, `Main Menu`) in user progression order (Main Menu → Survival → Creative → Overworld alphabetically → Underwater → Nether alphabetically → Ender Dragon → The End → Credits → remaining Minecraft → custom alphabetically). Tabs scroll horizontally with the mouse wheel, have no arrow buttons, and auto-reveal the selected tab. Hovering a tab shows a plain-language contextual tooltip (e.g. where that music plays); unknown custom pools show `Custom music pool: <full-id>` without invented semantics.
- **Track Browser**: Collapsible left browser with track list and search. `×` hides it and gives the full content width to the main panel; `☰` shows it again. On narrow windows the browser starts collapsed and opens as an overlay covering the wheel/editor/player while tabs and toolbar stay visible.
- **Below-Wheel Editor**: The main panel stacks selected-track heading and chance, radial wheel, multiplier slider with `0×`/`0.5×`/`1×`/`2×`/`5×` shortcuts, then the preview control/player. There is no side-column editor; list, wheel, Test Roll, pool switching, and search stay synchronized.
- **Icon Toolbar**: Compact bottom actions — `↺` Reset pool to native weights, `♫` Double C418 tracks, `∅` Mute selected pool, `⟳` Anti-Repeat On/Off (with selected/on highlight), `⚄` Test weighted selection, `{}` View and copy JSON, `✓` Done. Every icon except the self-evident preview `▶` has a hover tooltip and narration. `☰`/`×` toggles the track browser.
- **Anti-Repeat**: Prevents immediate back-to-back song repeats across world sessions when another eligible track exists in the pool.
- **Test Roll**: Samples candidate selection using current draft weights without starting playback, highlighting the chosen track in the list and wheel.
- **Track Preview**: Idle `▶` button (narrated, no tooltip) starts audible preview of the highlighted track. While active, a compact preview player in the lower main-panel region shows the track title, elapsed/total time, scrub slider, `⏸`/`▶` pause toggle, and `■` stop. Preview never mutates config, planner, queue, or Anti-Repeat state; background music is paused only if playing and resumed exactly once. Track/pool changes, Done, Esc, screen close, JSON view, and resource reload stop preview.
- **JSON Copy**: View or copy raw configuration JSON directly to the clipboard.

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
