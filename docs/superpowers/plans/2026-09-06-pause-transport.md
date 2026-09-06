# Pause Transport Implementation Plan

**Goal:** User-approved boxed Escape/Pause panel with title/artist, draggable elapsed/duration scrub bar, Previous, Play/Pause, immediate Next, and End song preserving vanilla delay.
**Architecture:** Keep MusicManager and Minecraft streaming OGG backend. Seeking reopens the same chosen sound and discards decoded PCM off-thread to the target; no full-track PCM buffer, external codecs, library, config or editable queue. Per-song pause targets only its native channel. Native context replacement remains authoritative.
**Spec:** User approved the box/transport mockup and draggable scrub bar on 2026-09-06. This explicitly supersedes earlier no-pause/no-scrubber scope, not other reduction requirements.

## Files / responsibilities
- MusicDirector: End song, pause toggle, seek same track, expose transport state; do not consume deterministic sequence/history when pausing/seeking.
- PinnedMusicInstance plus minimal streaming helper: selected sound, starting offset, duration, readiness/position/pause state. Must reject stale asynchronous work after skip/reload/disconnect.
- Narrow audio mixins: supply offset stream only for our pinned music; channel pause/resume/readiness/position where needed, preserve all other sound behavior.
- PauseMusicWidget: bounded dark box with vanilla border, two credit lines, slider and labeled accessible native transport controls; only PauseScreen.
- Tests: decoder skipping/progress invariants; paused clock; seek/no planner mutation; End vs Next delay; malformed OGG metadata, stale completion; UI layout/state.

## Tasks
- [ ] Inspect exact actual bytecode before hooks. Parent verified signatures in /tmp/cmm-stream-api.txt and /tmp/cmm-stream-bytecode.txt; research agent's older-signature guesses are not authoritative.
- [ ] Write red tests for bounded PCM discard (including decoder over-read), duration parser and transport state. Implement minimal stream/metadata helper, off render thread, streams always closed; no entire PCM track buffering.
- [ ] Wire native per-song pause and seek replay. Seek must not roll track, advance planner/history, briefly resurrect replaced sounds, or allow vanilla duplicate start. Preserve paused state through seek. End must invoke vanilla completion delay, Next bypasses it. Add tests.
- [ ] Render box and controls; commit drag on release (keyboard also usable), not on every mouse move. Disable seek while duration unavailable/loading; show honest unknown duration. Preserve full narration/tooltip labels and small-window vanilla-button clearance.
- [ ] Run `./gradlew clean test build --console=plain`; independently review diff and test omissions. Launch client only after warning user; verify pause/play, near-end End delay, immediate Next, forward/backward seeks and title persistence. No GitHub Actions/runners.

## Constraints
OGG only, vanilla resource provider, no bundled downloads/cache/transcoder. Reopening and decoding to target is deliberately O(target duration); document ceiling, upgrade only if measured slow. Position must reflect playback, NOT decoder read-ahead. Duration unavailable must not claim full working scrubber. Preserve vanilla event identifiers, weights and context rules.
