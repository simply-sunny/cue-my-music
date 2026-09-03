# Playback, Scrub, and Library UI Redesign

## Goal

Make preview playback single-owner and non-overlapping, make the top scrub bar seek actual audio reliably, and replace the current stretched custom table with a compact native Minecraft library screen.

## Scope

The existing 92-track catalog, fuzzy search, Artist/Title/Source sorting, queue persistence, Mod Menu entry, and vanilla-only dependency policy remain. This change modifies playback ownership, active-track sound loading, seeking, and the library screen layout.

Out of scope: thumbnails, playlists/presets, imports, external services, Music & Sounds screen integration, a custom decoder, themes, animation, and new dependencies.

## Evidence and hypotheses

The current code starts playback asynchronously but immediately asks `SoundManager.isActive(currentSound)`. During the startup gap this can return false, allowing `MusicDirector.tick()` to start another track. The UI also performs several separate stop/start operations rather than one atomic replacement.

The current exact-file sound is streamed. Seeking calls OpenAL `AL_SEC_OFFSET`, which is not reliable for queued streaming sources. The UI then updates wall-clock state even when audible playback may not have moved. Hard-coded 180-second durations further desynchronize the scrub display from the recording.

These are evidence-backed hypotheses, not accepted facts until an in-game diagnostic run records channel attachment, active sound identity, and OpenAL errors.

## Playback architecture

`NativeMinecraftPlayback` is the sole owner of the active mod sound. Its lifecycle is `STOPPED`, `STARTING`, `PLAYING`, and `PAUSED`.

Starting or replacing a track is one atomic operation. It stops the prior owned sound, prevents the director tick from starting another sound, and queues exactly one replacement. `MusicDirector.tick()` considers `STARTING`, `PLAYING`, and `PAUSED` occupied. A two-second startup grace period distinguishes asynchronous attachment from a sound that genuinely failed to start. Channel attachment moves the state to `PLAYING`; failure to attach within two seconds stops and clears ownership.

The UI requests preview replacement through `MusicDirector`; it does not coordinate multiple playback stop/start calls itself. Vanilla background music is stopped before replacement, while the existing mixin continues suppressing new vanilla background starts when replacement is active.

Natural completion clears ownership and permits the normal director delay/next-track behavior. Pause retains ownership and must not cause auto-advance.

## Buffered active track and seeking

Only the active OGG is loaded as a non-streamed OpenAL buffer. Background tracks already carry exact file identifiers. Disc sound events are resolved to their selected underlying file resource before creating the buffered sound.

Duration is read from the attached OpenAL buffer using buffer size, channels, sample bits, and sample rate. The fixed 180-second registration duration is removed from playback decisions and scrub rendering.

Seek work runs on the sound-engine/channel execution path. The requested position is clamped to `[0, duration]`, applied with `AL_SEC_OFFSET`, and accepted only when OpenAL reports success. The displayed position is queried from the actual source. The UI never advances its own synthetic position after a failed seek.

While the source is loading or if duration/seek capability is unavailable, the scrub is visibly disabled. Playback continues safely; no fake successful seek is shown. A custom decoder is permitted only if diagnostics prove non-streamed OpenAL playback cannot support the catalog.

## Native compact library screen

The screen uses a centered content width of `min(620, width - 40)` and vanilla background/rendering conventions. It keeps one visual region rather than adding sidebars or secondary panels.

The top row contains:

- fuzzy-search `EditBox`;
- one cycling sort `Button` for Artist, Title, and Source;
- one vanilla-styled scrub control beside them.

Space for the scrub row is always reserved, so starting or pausing audio never moves the table. The scrub is visible and interactive only while the current preview is in `PLAYING` with a known duration and seek-capable channel. It is hidden while paused, stopped, starting, or unavailable. On narrow screens it occupies a reserved second row rather than overlapping search or sort.

The track list uses native selection-list behavior where compatible with the 26.2 API, with one small inner row-entry class rather than a new UI framework. Rows are 20 pixels high and provide:

- a keyboard-focusable 20-pixel queue checkbox control;
- source text;
- ellipsized title;
- ellipsized artist;
- a keyboard-focusable 20-pixel preview button showing `>` or `||`.

Rows use restrained grayscale vanilla states with clear hover/focus feedback. Color is not the only status signal. The scrollbar supports native wheel, drag, and keyboard behavior.

The footer places the selected count under the checkbox column, the total count at the right edge, and a standard centered Done button. Default sorting remains Artist A–Z. Double-clicking a row does nothing.

## Accessibility

Search, sort, queue, preview, and scrub controls must be keyboard reachable and narrated. Hit targets are at least 20 pixels high. Track controls expose the track title and action in narration. Truncation does not remove the full title from narration.

## Diagnostics and failure handling

Before behavior changes, temporary diagnostics record:

- current sound identity and playback lifecycle;
- channel-map attachment timing;
- `SoundManager.isActive` during startup;
- OpenAL errors after position reads and seeks;
- count/identity of mod-owned sounds started during rapid replacement.

Diagnostics are removed after their hypotheses are resolved. Persistent logging is limited to actionable load/seek failures and must not run every tick.

If a resource cannot be resolved or buffered, playback remains stopped and one concise error is logged. If seek is unavailable, playback remains active with a disabled scrub. Resource buffers/channels are released on replacement, client shutdown, and natural completion. Closing the library screen does not stop the active track, matching current preview behavior.

## Test strategy

Production changes follow red-green-refactor.

Automated tests cover:

- startup state prevents director auto-start of a second track;
- paused state retains ownership and prevents auto-advance;
- rapid A-to-B replacement leaves B as the sole owner;
- natural completion clears ownership;
- seek clamps below zero and above real duration;
- failed seek does not report or display a moved position;
- scrub visibility/interactivity for starting, playing, paused, stopped, and unavailable states;
- centered and narrow layout calculations do not overlap;
- row hit targets and selected-count alignment;
- existing 92-track, fuzzy-search, sorting, persistence, and stale-ID tests remain green.

Integration verification runs `./gradlew clean test build`, installs the built jar into the Prism Launcher 26.2 instance, and confirms matching checksums.

Manual in-game verification is mandatory because unit tests cannot prove OpenAL output or visual quality:

1. Start a preview over active vanilla music and confirm only one recording is audible.
2. Seek near 25%, 50%, and 90% and confirm audible position changes and the displayed time remains synchronized.
3. Rapidly switch A to B and confirm A stops fully.
4. Pause and resume; confirm no auto-start or second layer.
5. Inspect the screen at normal and narrow GUI scales for overlap, clipping, focus, and native visual consistency.

## Muse subagent execution

Work proceeds sequentially to avoid overlapping file ownership:

1. A Muse diagnostic worker confirms or rejects the playback hypotheses.
2. A Muse playback worker implements tests and the minimal confirmed root-cause fix.
3. A Muse reviewer gates playback correctness and resource/thread safety.
4. A Muse UI worker implements layout tests and the approved native compact screen.
5. A Muse reviewer gates UI behavior, accessibility, and responsive layout.
6. A Muse integration worker builds, installs, and records verification evidence.
7. A final Muse reviewer audits the complete change.

No worker may spawn its own subagents. Review findings return to the responsible worker before the next task starts.
