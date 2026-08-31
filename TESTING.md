# Testing — Cue My Music

This doc covers programmatic + in-game verification, as requested for Phase 3.

## 1) Programmatic tests (JUnit 5)

### Run

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test
./gradlew test --rerun-tasks --info   # verbose
open build/reports/tests/test/index.html
```

### What’s covered

| Test class | Covers |
|------------|--------|
| `MusicLibraryTest` | add/retrieve, collection predicates (VANILLA / MUSIC_DISCS / LOCAL / ALL), preset membership add/remove, enabled/ambient filtering, search filtering, persisted-state round-trip, missing-file handling |
| `PlaybackDirectorTest` | setup library with vanilla+local presets, disabled tracks not chosen, ambient-ineligible excluded for ambient, fallback when preset empty (any enabled track), missing-file skip (null/blank/absolute-missing filtered), empty library returns empty |
| `PersistenceTest` | serialization of `MusicLibrary` → `PersistedLibraryState` → back, including custom local track fields, preset ids, flags |

Tests are in `src/test/java/com/cuemymusic/` and run without a live Minecraft client (pure library logic; `Files.exists` checks use relative paths to avoid filesystem dependency).

Add more cases by extending the helper `vanilla()/disc()/local()` factories in `PlaybackDirectorTest` — see the `getEligibleCandidates` helper that mirrors `MusicDirector`.

### Fixtures

- Vanilla “C418” detection: `sourceId` ending `.c418` is treated as C418 (see `MusicLibrary.isC418`). Tests use `minecraft:music.game.<id>.c418` for C418 vs `minecraft:music.game.<id>` for Lena Raine-era.
- Discs: `sourceId` `minecraft:music_disc.<name>`, `ambientEligible=false` by default.
- Local: `sourceType` `LOCAL_GENERIC/SPOTIFY/YOUTUBE`, `localAudioPath` may be relative (test) or absolute.

## 2) In-game / player-close verification

### Dev workflow (repeatable)

1. Build & run:

   ```bash
   ./gradlew runClient
   ```

   On first run Loom downloads 26.2 and creates `run/cue-my-music/` (or `cue-my-music/` in the gameDir). For isolated dev runs, `run/` is gitignored.

2. Create/load a test world (any world, e.g. “Cue Test”).

3. Open the UI:

   - Via ModMenu: Mods → Cue My Music → Configure
   - Or command: `/cuemymusic open` or `/cmm open`

4. Verify collections populate (left sidebar shows All/Vanilla/Music Discs/Spotify/YouTube/Local/Favorites; counts in detail strip).

5. Verify presets switch:

   - Click My Mix / C418 Only / New Music Only / Discs Everywhere in left sidebar.
   - `filtered from N` count in footer should change.
   - Run `/cmm preset list` and `/cmm preset set <id>` and see list update.

6. Verify toggles persist:

   - In main list, click a row to select; double-click (or toggle via detail) disables/enables; dot color changes.
   - Restart client; reopen UI — toggle should persist (persisted in `library.json`).
   - Also test `/cmm toggle vanilla:sweden` then `/cmm status`.

7. Verify imported/cached OGG can preview/play:

   - Place a small test OGG in `run/cue-my-music/audio/` or use import:

     ```
     /cmm import /absolute/path/to/test.ogg local
     ```

     Expect “Imported: local:test_...”.
   - Select that track in UI (under Local/Spotify/YouTube collection) → Preview via playback director: run `/cmm next` — listen on MUSIC volume.
   - Check `cue-my-music/audio/test_<timestamp>.ogg` exists and `library.json` contains entry with `localAudioPath`.

8. Verify next/skip:

   - Run `/cmm next` twice; music should change (or at least director logs).
   - In logs (`logs/latest.log`), look for `[Cue My Music]` lines and mixin `SoundManager playing`.

9. Verify active preset drives selection:

   - Set preset to `c418_only` (`/cmm preset set c418_only`), then `/cmm next` should only pick C418 tracks (check `/cmm status` and listen).
   - Set to `empty_custom` (create via UI Save with no tracks) and verify director falls back to any enabled track.

### Debug commands reference

All under `/cuemymusic` with alias `/cmm`:

| Command | Effect |
|---------|--------|
| `open` | Opens Jukebox Library |
| `next` | Skips to next eligible track (director chooses from active preset) |
| `preset list` | Lists presets, counts, built-in flag, marks active |
| `preset set <id>` | Sets active preset (suggests ids, persists) |
| `rescan` | Re-registers vanilla tracks (idempotent) and saves |
| `import <path> [local|spotify|youtube|yt]` | Imports local file to audio cache and library (ffmpeg converts if needed) |
| `status` | Prints total/enabled/ambient counts, active preset size, by-source breakdown |
| `toggle <trackId>` | Toggles `enabled` for a track (persists) |

All commands send feedback via `Component.literal` and persist immediately.

### Manual checklist (copy to track runs)

- [ ] `runClient` launches without crash; ModMenu shows “Cue My Music — Jukebox Library”.
- [ ] Collections show expected counts (Vanilla ~20, Discs ~15, All ~35 before local imports).
- [ ] Search filters live (typing narrows list, scissor + scrollbar correct).
- [ ] Enabled-only / Ambient-only toggles filter correctly and show “No matches” empty states.
- [ ] Sort cycles Title/Artist/Source and reorders visible rows.
- [ ] Scroll with wheel/trackpad is smooth, thumb scales with total/visible, rows virtualized.
- [ ] Selecting a row shows detail strip (title · artist · source · duration · flags).
- [ ] Preset buttons highlight active preset; sidebar not cramped.
- [ ] Edit Preset screen: rename, filter, checkboxes add/remove, Save persists, Cancel discards.
- [ ] Bottom buttons Done closes, Add Music → Edit Preset, Edit Preset opens editor.
- [ ] `enabled` toggle persists after restart (check `library.json`).
- [ ] Import local OGG via command succeeds; track appears in Local/All; file cached.
- [ ] `/cmm next` plays a track (MUSIC volume respected; stop+play).
- [ ] Active preset `c418_only` only plays C418-era tracks; `new_music_only` only Lena Raine-era.
- [ ] Missing file skipped: create local track with bogus absolute path `local:fake_...` → director skips it (verify via `candidates` in status — not counted as eligible).
- [ ] Logs contain `[Cue My Music] ...` and mixin line without spam.

### Optional GameTest / harness

Full GameTest for client UI is awkward (UI is screen-driven). For MVP we rely on:

- JUnit for logic
- Manual + command-driven verification for UI/playback

If you later need scripted in-game checks, add a Fabric `gametest` module or a small `runClient` auto-script that executes `/cmm status` and screenshots the screen — but for personal-use dev, the checklist above is the source of truth.

### Seed test data

- Fixtures are in `src/test/java/com/cuemymusic/` and `VanillaTrackRegistry` (used in production). For in-game, use `/cmm rescan` to repopulate.
- For local playback tests, include a committed tiny OGG? Not included; generate one on demand:

  ```bash
  ffmpeg -f lavfi -i anullsrc=r=48000:cl=stereo -t 2 -c:a libvorbis -q:a 4 run/cue-my-music/audio/test_tone.ogg
  /cmm import run/cue-my-music/audio/test_tone.ogg local
  ```

### Troubleshooting

- **UI doesn’t open**: check `logs/latest.log` for `[Cue My Music] Initializing client` and `Debug commands registered`. Ensure ModMenu is installed (it is a compile dependency; `runClient` includes it).
- **Tracks missing after restart**: verify `cue-my-music/library.json` was written (check file mtime, pretty-printed JSON). Permissions / gitignored `run/` still writes.
- **Import fails “Only OGG supported, ffmpeg not found”**: `brew install ffmpeg` and restart.
- **No sound on `/cmm next`**: check MUSIC volume slider >0; director logs “Skipped to next track” but sound may be silent if event missing — try a vanilla track (`minecraft:music.game.c418` is always valid).

---
