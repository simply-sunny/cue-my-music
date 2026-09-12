# Track Weighting and Pool Settings Design

## Goal

Add a native Minecraft configuration page behind the existing Mod Menu music player. The page discovers loaded music pools and tracks dynamically, lets players adjust per-track selection multipliers, and persists those settings across restarts.

The supplied HTML mockup defines the information architecture and controls, not the rendering technology. The implementation must use native Minecraft screens/widgets and remain graphics-backend agnostic.

## Navigation

Mod Menu's Cue My Music configure action continues to open `MusicPlayerScreen` first.

`MusicPlayerScreen` adds one long native button centered near the bottom of the screen:

- Label: `⚙ Configure Track Pools…`
- Tooltip/narration: `Configure track pools and selection chances`
- Action: open `TrackWeightScreen`, passing the same `MusicPlayerScreen` instance as its parent.

Navigation from the settings screen is transactional:

- **Done** validates and saves the draft, applies it to future selections, and returns to the player.
- **Esc** discards the draft and returns to the player.
- A failed save leaves the settings screen and draft open with a visible error message.

Existing player navigation remains unchanged: player Esc returns to Mod Menu's mod list and player `×` returns to vanilla Options.

## Native Settings Layout

### Wide layout

A wide screen contains:

1. Title, pool selector, and search field at the top.
2. A native scrollable track list on the left.
3. A radial probability wheel plus selected-track controls on the right.
4. Bottom actions for pool-wide operations, Anti-Repeat, Test Roll, JSON, and Done.

The selected-track controls show:

- translated title when available, otherwise the resource filename;
- composer parsed from Minecraft's translated credit when available;
- stable sound resource identifier;
- multiplier and resulting percentage chance;
- native `0–10×` slider in `0.5×` steps;
- `0×`, `0.5×`, `1×`, `2×`, and `5×` quick buttons;
- a Play/Stop Sound preview button.

The searchable list filters case-insensitively by title, composer, and resource identifier. Selecting either a list row or radial wedge updates the same selected-track editor.

### Narrow layout

When the available logical width cannot fit the list, wheel, and editor accessibly, the wheel is hidden. The pool selector, search, list, selected-track controls, and bottom actions stack vertically and remain keyboard/controller navigable.

The wheel is supplemental visualization and pointer selection; every operation remains available through native widgets.

### Pool actions

The named preset selector from the mockup is intentionally omitted.

Three quick actions remain and affect only the current pool draft:

- **All 1×**: set every loaded track in the pool to `1×`.
- **C418 2×**: set tracks whose translated composer is exactly `C418` to `2×`; leave tracks without that credit unchanged.
- **Mute 0×**: set every loaded track in the pool to `0×`.

Other controls:

- **Anti-Repeat** edits the global draft setting.
- **Test Roll** simulates one draft selection, selects/highlights the result, and changes neither playback nor planner history.
- **JSON** opens a native read-only JSON view of the complete current draft with Copy and Back buttons.
- **Done** saves.

## Radial Wheel Portability

The radial wheel must not call OpenGL, Vulkan, Metal, Fabric rendering APIs, custom shaders, custom render pipelines, or backend-detection code.

`RadialWeightWidget` CPU-rasterizes the wheel into a small Minecraft `NativeImage`. Minecraft's ordinary dynamic texture lifecycle uploads it, and the widget displays it through the standard vanilla `GuiGraphicsExtractor` texture-blit path. This is the same backend-independent GUI path expected to work through Minecraft's renderer, including an experimental Metal renderer that supports normal Minecraft GUI textures.

Wheel behavior:

- wedge angular size equals the track's effective draft chance;
- disabled tracks have no positive wedge;
- stable colors derive from the resource identifier, so colors do not jump between refreshes;
- hover resolves radius/angle to a track and shows title plus percentage;
- click selects that track;
- the selected wedge receives a CPU-rasterized highlight;
- the image regenerates only when dimensions, selected track, pool membership, or draft weights change;
- texture resources are closed when replaced or when the screen is removed.

If every track is disabled, the wheel renders an empty muted ring and no wedge is selectable.

## Dynamic Pool and Track Discovery

`WeightedMusicCatalog` reads the currently loaded `SoundManager` graph. It includes loaded sound events whose identifier path is `music` or begins with `music.` and recursively expands each event into playable leaf occurrences.

Each occurrence retains:

- pool event identifier;
- leaf sound resource identifier/path;
- the fully composed `Sound` properties Minecraft would play through nested delegates;
- its effective native probability, calculated from the conditional weight at each graph level.

Cycles are ignored with an identity-based visited path. Intentional silence entries are excluded from editable tracks. If one resource is reachable through multiple graph paths, its occurrences remain separate selection candidates so their composed properties and probabilities remain correct, but the UI displays one track row and applies one multiplier to every occurrence sharing that resource identifier.

Pool and track labels use Minecraft's loaded translations when present and resource identifiers as fallback. This supports vanilla changes and resource packs without a hardcoded 26.2 catalog.

Resource reload rebuilds the live catalog, clears resource-dependent future selections through the existing reload hook, and refreshes newly opened settings screens. Saved entries for tracks no longer loaded remain in the config and are not displayed or deleted.

## Weighting and Deterministic Selection

A configured value is a multiplier over native probability, not an absolute replacement weight:

- `1×` preserves native probability distribution;
- `0×` excludes the resource;
- values above `1×` proportionally increase its chance.

For every occurrence:

```text
adjusted weight = effective native probability × configured resource multiplier
```

Selection normalizes positive adjusted weights and samples them with the existing deterministic session seed, pool identifier, and forward index. Actual playback, Next, queue preview, and Test Roll use one shared pure selection implementation; Test Roll uses the screen draft and a non-mutating test seed/index.

At `1×` everywhere, the distribution remains native even though flattening may change the exact historical seeded sequence from earlier mod versions. Queue preview and actual future playback remain internally identical.

### Anti-Repeat

When enabled, the selector gives the immediately previous played resource an adjusted weight of zero if any other enabled candidate exists. The rule applies across pool changes. If it is the only enabled candidate, it remains selectable.

Previous navigation remains history-based and is not blocked by Anti-Repeat.

### Fully muted pool

When every loaded track in the current pool has multiplier `0×`, that context intentionally produces no music. It must not fall back to vanilla selection. The queue shows no upcoming tracks until the context or saved weights change.

Saving settings affects future selections and immediately refreshes queue projection. It does not stop, replace, or reweight the currently playing track.

## Audio Preview

Play Sound previews the selected real composed sound through Minecraft's normal sound system; no alternate audio engine or decoder is added.

- At most one settings preview may exist.
- Starting a preview pauses active tracked background music.
- Pressing Stop Sound, selecting another preview, leaving/removing the settings screen, or preview completion stops the preview and resumes music only if this screen paused it.
- Preview playback does not change planner sequence, queue, history, or saved settings.
- If no playable occurrence exists for the selected resource, the button is disabled.

## Persistence

`TrackWeightConfig` loads from Fabric Loader's supported config directory:

```text
config/cue-my-music.json
```

Schema version 1:

```json
{
  "version": 1,
  "antiRepeat": true,
  "weights": {
    "minecraft:music.game": {
      "minecraft:music/game/sweden": 2.0
    }
  }
}
```

Rules:

- missing config, pools, or tracks default to `1×` and Anti-Repeat defaults to enabled;
- non-finite or malformed numeric values are ignored and default to `1×`;
- finite values are clamped to `[0, 10]`;
- unknown pool and track entries are retained across saves;
- loaded UI edits merge into the retained map;
- Gson already supplied by Minecraft/Fabric performs JSON encoding/decoding;
- saving writes a sibling temporary file and then replaces the target atomically when supported, with a normal replace fallback when the filesystem does not support atomic movement.

A malformed top-level file is logged and treated as defaults without overwriting it until the user explicitly saves. Save failures are reported in-screen and never clear the draft.

The JSON view serializes the complete current draft, including retained unloaded entries.

## Components and Integration

Minimal production additions:

- `TrackWeightConfig`: immutable/copyable state, validation, Gson load/save, and draft merge.
- `WeightedMusicCatalog`: graph discovery, effective native probabilities, display metadata, and pure deterministic selection.
- `TrackWeightScreen`: transactional native settings screen and preview lifecycle.
- `RadialWeightWidget`: CPU wheel rasterization plus hover/click selection.

Focused existing changes:

- `MusicPlayerScreen`: add the long configure button and parent transition.
- `MusicDirector`: hold the active saved config/catalog, route actual selection and queue projection through the shared selector, expose preview-safe operations, and rebuild catalog on resource reload.
- `MusicGraph`: expose only the additional traversal information needed to preserve nested probabilities and composed sounds.
- `CueMyMusicClient`: load config during client initialization and integrate catalog refresh with the existing resource reload listener.

No new dependency, web view, renderer-specific API, hardcoded soundtrack catalog, standalone playback engine, or preset subsystem is introduced.

## Error Handling and Boundaries

- Invalid config values never crash selection or UI.
- Graph cycles, missing nested events, and intentional silence are skipped.
- One malformed pool cannot disable unrelated pools.
- A save error retains the draft and current saved runtime config.
- A resource reload invalidates stale catalog candidates before future playback.
- Selection uses doubles and rejects non-positive/non-finite adjusted weights.
- UI dimensions and wheel texture dimensions are clamped before allocation.
- Input validation and accessible narration remain present even where the visual wheel is hidden.

## Verification

Automated tests will cover:

- config defaults, round-trip, clamping, malformed input, unknown-entry retention, and failed/atomic save behavior;
- recursive native probability calculation, nested composition, duplicate resource paths, cycles, silence, and resource-pack additions;
- deterministic selection, multiplier normalization, Anti-Repeat fallback, fully muted pools, and queue/actual parity;
- Test Roll non-mutation and current-track stability after save;
- draft Done/save versus Esc/discard navigation;
- search, pool actions, slider bounds, and JSON draft projection;
- wide/narrow responsive geometry;
- radial pixel generation, stable colors, angle/radius hit-testing, selection, muted ring, and texture cleanup;
- existing Pause/Options player geometry and navigation regressions.

Final verification requires a clean local test/build, artifact inspection, runtime Minecraft 26.2 screenshots captured from only the client window (including a wide radial view and narrow fallback), and an independent Gemini review. No release or Modrinth publication occurs without explicit user confirmation.
