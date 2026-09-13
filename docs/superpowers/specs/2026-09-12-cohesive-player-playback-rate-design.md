# Cohesive Player and Playback Rate Design

**Date:** 2026-09-12  
**Status:** Approved in conversation; pending written-spec review

## Goal

Replace the separately arranged player controls with one cohesive shared player composition that serves two layouts:

- a compact player on eligible Pause and Options screens;
- an expanded player on the dedicated Mod Menu screen.

The player presents current-track status, progress, transport, secondary actions, and optional effects in that order. The expanded layout adds an intentional queue drawer and one technically honest Playback Rate control. Existing weighted selection, deterministic queue projection, anti-repeat, preview ownership, and navigation behavior remain unchanged.

## Selective Integration

The track-weighting branch remains the implementation base. Playback-effects commits are reference material only and are not merged or cherry-picked wholesale.

Port only the useful playback-rate behavior and any small transport-clock or native-channel hooks it requires. Do not port:

- `WsolaAudioStream` or any claim of independent tempo and pitch;
- pitch/semitone controls;
- stock/resource-pack/custom source switching;
- advanced/debug toggles;
- crossfade;
- queue editing or reordering.

This avoids conflicts with the weighted selector and preserves the corrected preview stream ownership model: only `StreamOwner.TRANSPORT` participates in normal transport channel hooks, while preview streams remain independent.

## Shared Player Composition

A single player component owns widget creation, layout, refresh, accessibility text, and actions. Host screens select a presentation mode rather than constructing separate players.

The visual hierarchy is:

1. **Current track:** title and artist when known.
2. **Progress:** elapsed/total time and scrub control.
3. **Primary transport:** previous, play/pause, and next.
4. **Secondary actions:** End/skip cooldown, queue, minimize or close as appropriate.
5. **Effects:** Playback Rate, expanded mode only.

The shared component may retain a small immutable layout record for pure geometry tests. It must not introduce a general component framework, pluggable effect system, or duplicate transport state outside `MusicDirector`.

### Compact mode

Compact mode is used on eligible Pause and Options screens. It keeps the existing top-right placement, fit-based forced minimization, and `+` restore/open-player behavior. It shows current track, progress, core transport, End/cooldown, queue toggle, and minimize. It never shows playback effects.

The compact queue remains a read-only list of the next five deterministic projected tracks and opens beneath the compact card when space permits. If the projection is empty, it shows an explicit empty state instead of a blank card.

### Expanded mode

Expanded mode is used only by `MusicPlayerScreen`. It is centered and uses the available screen area as a deliberate full player rather than a scaled-up compact card.

The main card contains the hierarchy above. The queue toggle opens a read-only drawer beside the card when horizontal space permits and below it otherwise. Drawer opening must not compress controls below their usable minimum widths or place content outside the screen.

The dedicated screen retains:

- `Esc` returning to Mod Menu;
- `×` returning to vanilla Options;
- the Configure Track Pools action opening `TrackWeightScreen`.

## Explicit Playback States

Rendering and control availability derive from existing director/transport facts. The UI must distinguish:

- **No track:** “No music playing”; scrub and play/pause unavailable.
- **Loading:** selected/starting track known but no audible channel yet; progress unavailable and transport cannot falsely report playing.
- **Playing:** progress advances; pause is available.
- **Paused:** progress is frozen; resume is available.
- **Unknown duration:** elapsed time may display, total is `--:--`, and scrubbing is disabled.
- **Cooldown:** no active track; remaining delay is visible and can be skipped.
- **Empty queue:** the open drawer says “No upcoming tracks.”

Previous and next remain enabled only when their corresponding director operations are valid. “End” ends an active track into its natural delay; while cooling down, the same secondary control skips the delay with state-specific label, tooltip, and narration.

## Playback Rate

Expanded mode exposes one slider labeled **Playback Rate**:

- range: `0.50×` through `2.00×`;
- default: `1.00×`;
- step: `0.05×`;
- applying the slider updates the active transport channel immediately when one exists;
- a newly opened transport channel receives the current rate before audible playback;
- the selected rate remains in memory across tracks for the current game session;
- restarting the game restores `1.00×`;
- preview playback is unaffected.

Playback Rate is varispeed. It intentionally changes both playback speed and pitch. Labels, tooltips, narration, README text, and tests must not describe it as tempo, pitch preservation, time stretching, or WSOLA.

The native OpenAL channel pitch/rate operation is the audio mechanism. No PCM resampler, DSP wrapper, decoder replacement, direct graphics API, Fabric rendering API, or new dependency is added.

## Transport Timing

`TransportClock` tracks source-media position, not unscaled wall-clock time. While running at rate `r`, source position advances by `elapsedWallSeconds × r`.

Changing rate while playing first snapshots the source position at the old rate, then anchors the new rate at the same instant. This prevents progress jumps. Changing rate while paused preserves the frozen position. Seek, pause/resume, track replacement, and reset retain their existing semantics; reset clears per-track timing while retaining the session rate.

Duration remains the source duration. Progress reaches the end sooner above `1.00×` and later below `1.00×`. Unknown duration behavior remains explicit and non-seekable.

## Queue Semantics

The queue drawer is display-only. It uses the same configured selector and deterministic projection as Next, including weights, mute, and Anti-Repeat. Opening, closing, or rendering the drawer does not consume random state, mutate history, or alter future selection.

The drawer displays at most five upcoming tracks in playback order. It does not expose drag handles, removal, insertion, source selection, or persistence.

## Error Handling and Ownership

Missing or released native channel handles are safe no-ops; the current rate is also applied by the audible-start hook so asynchronous channel creation cannot lose it.

Rate inputs are clamped to the supported finite range. Non-finite values fall back to `1.00×`.

All native channel operations remain scoped to the owned transport `SoundInstance`. They must not alter UI sounds, ambient sounds, other mods’ sounds, or preview playback. Preview start/pause/resume/stop and background-pause ownership continue to be governed solely by `TrackPreviewController` and `StreamOwner`.

## Accessibility

Every icon-only control has a tooltip and explicit narration. Dynamic controls narrate their current action and state, including Play versus Pause, End versus Skip cooldown, queue open versus closed, and the current Playback Rate.

Keyboard focus follows visual order. Hidden compact/expanded widgets are both invisible and inactive; the shared component must not leave stale focus on hidden controls after resizing, minimizing, or changing drawer placement.

## Testing

Implementation follows TDD. Add the smallest focused tests for:

- shared compact and expanded geometry at representative narrow and wide sizes;
- visual/control hierarchy and expanded-only effect visibility;
- queue drawer side/below placement and explicit empty state;
- no-track, loading, playing, paused, unknown-duration, and cooldown presentation;
- Playback Rate slider mapping, clamping, formatting, and `0.05×` stepping;
- transport-clock continuity during running and paused rate changes;
- source-position progression at `0.50×`, `1.00×`, and `2.00×`;
- rate application to existing and asynchronously created transport channels;
- rate retention across tracks but reset across game sessions;
- preview and non-transport channels remaining unaffected;
- deterministic read-only queue behavior and weighted-selector preservation;
- navigation and forced-minimization regressions.

After focused tests, run the complete clean test/build suite, inspect JUnit XML, and inspect the built jar for dependency and metadata boundaries. Then launch Minecraft for user-supplied screenshot review at representative compact, expanded, narrow drawer, empty, playing, paused, cooldown, and unknown-duration states.

## Delivery

After user-approved runtime screenshots:

1. perform broad nonvisual review and correct confirmed issues test-first;
2. integrate verified commits into `main` only with approval;
3. push only with approval;
4. do not tag, release, or publish to Modrinth without explicit instruction.
