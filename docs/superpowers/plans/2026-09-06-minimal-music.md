# Minimal Music Implementation Plan

> Execute task-by-task with tests before implementation and independent review after implementation.

**Goal:** Delete the library product; deterministic vanilla music plus an Escape/Pause-screen title/artist and Previous/Next widget only.
**Architecture:** Keep MusicManager's tick, fades, replacement logic, current sound and streaming backend. Substitute deterministic sound selection at startPlaying, using vanilla situational music and loaded weighted sound events. Director owns bounded planner/history, not a second playback engine.
**Tech:** Minecraft 26.2, Java 25, existing Fabric API, JUnit. No new dependencies or GitHub Actions.
**Spec:** User-approved design in this file below.

## Approved design
- One session seed; context event and monotonic sequence index determine choices. Separate deterministic delay stream. One materialized next entry at most.
- Every start resolves current vanilla eligibility; resource reload invalidates plans/history references. Previous scans up to eight historical entries by actual file membership, not context equality; it does not rewind forward progress.
- Preserve vanilla handling of ongoing songs across context changes and null music. Manual Next bypasses delay but not loading/context/volume rules.
- Installed resources supply OGG, localized titles and artist credits. No copied registry, downloads, cache, arbitrary codecs or discs added to background eligibility.
- Fabric screen events attach only to PauseScreen: six-pixel top-right margin, title/artist, two independently focusable vanilla buttons; no toast or other screen.
- Target 6–8 production files, minimizing mixins. Exact graph membership must account for nested weighted event delegates without probabilistic sampling.

## Tasks
- [ ] Core: add deterministic planner/history tests (same seed, 30 Next, context changes, Nether/boss pools, history skip, return context). Run red, implement minimal state, run green.
- [ ] Vanilla integration: inspect actual mapped bytecode; substitute sound creation in MusicManager.startPlaying without cancelling tick/start lifecycle. Reuse vanilla selection weights and native streaming. Maintain event identifier on pinned sound. Use minimal graph access for exact eligibility. Add tests for real loaded sounds and native selection/path behavior.
- [ ] UI and deletion: replace entrypoint, attach pause-only screen-event controls wired to director. Delete old production classes/tests supporting removed features; delete downloads and catalog, Mod Menu dependency/entrypoint, config and persistence, buffering/seek code, manifest rewriting. Keep tests for surviving behavior, add UI/architecture contracts. Update README and mixin rationale.
- [ ] Verification: run targeted tests, full clean test/build, inspect jar and diff; launch local client with assets and verify mixin application and resources. Report manual gameplay checks actually performed versus still pending. Independently review integration risks and fix confirmed defects.

## Commands and acceptance
`./gradlew test --tests '*MusicPlanTest' --console=plain` for core. `./gradlew clean test build --console=plain` final. `./gradlew runClient --console=plain` runtime, no CI/runners. Tests must check behavior rather than only source strings. Never claim audible gameplay or UI interaction without doing it.
