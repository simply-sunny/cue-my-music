# YouTube-track downloads via yt-dlp + generated resource pack

Date: 2026-09-05 | Status: approved design (awaiting spec review) | Approach: 1 (generated resource pack)

## Goal

Every track in `~/Downloads/minecraft-music` (206 files: 92 java-ogg + 114
youtube) is present in the mod's library list. The 92 java-ogg tracks are
already covered by `VanillaTrackRegistry` (70 vanilla + 22 discs) and play
natively — no downloading. The 114 YouTube-only tracks become downloadable
rows: per-track download on click plus a Download All option, assuming the
user has `yt-dlp` installed. Downloaded tracks play through the existing
vanilla engine.

## Non-goals

- No self-hosting of audio, no auth tokens, no network code beyond spawning
  `yt-dlp` (prior options B-hosting / private-repo auth explicitly dropped).
- No custom audio player; no second playback path.
- No cancel button for in-flight downloads; closing the screen never kills them.
- No new persisted download state; no changes to vanilla/disc behavior.

## 1. Catalog

- Generated `src/main/resources/assets/cue_my_music/youtube_catalog.json`,
  114 entries, built once by a script joining `manifest.json` (title/artist/
  size, matched on filename) with `download_youtube.py` `YT_TRACKS`
  (yt-search query, matched on filename base). Script is throwaway; the JSON
  is committed.
- Entry: `{slug, title, artist, file, size, query}`. `slug` = filename base
  lowercased, spaces → underscores, stripped of characters illegal in sound
  event paths.
- IDs: `youtube:<slug>`. New `SourceType.YOUTUBE`.
- `YoutubeTrackRegistry.registerAll(MusicLibrary)` mirrors
  `VanillaTrackRegistry`: adds all 114 (title/artist from catalog,
  `sourceId = "cue_my_music:youtube.<slug>"`, enabled=true,
  ambientEligible=false). Called from `CueMyMusicClient` right after vanilla
  registration; persistence applied afterwards as today.
- Contract test: catalog slugs == manifest youtube-file set exactly (fails on
  drift either way). Coverage test: all 92 manifest java-ogg names resolve to
  an existing registry track (fuzzy on title); gaps become native entries.

## 2. Storage layout

`<gamedir>/cue-my-music/pack/` shaped as a resource pack:

- `pack.mcmeta` (static, committed as template, copied at runtime).
- `assets/cue_my_music/sounds/youtube/<file>.ogg` — yt-dlp output.
- `assets/cue_my_music/sounds.json` — generated at startup declaring all 114
  events (`youtube.<slug>` → `sounds/youtube/<file>`). Pre-declared, never
  rewritten per download.
- READY = file exists and size > 1KB. Derived at row-render/registry time;
  nothing persisted.

## 3. Downloader (`YoutubeDownloader`, client-only)

- Presence check once: `yt-dlp --version` (timeout ~10s). Absent → download
  buttons degrade to "needs yt-dlp" hint; clicks show install message.
- Single-thread background executor. Per track:
  `yt-dlp -x --audio-format ogg -o <dest> ytsearch1:<query>`
  (`-o` points directly at the pack sounds dir; skip if already READY).
- States per track: MISSING → DOWNLOADING → READY / FAILED. FAILED retries
  on click. Download All enqueues every MISSING track sequentially.
- Thread-safety: state map is `ConcurrentHashMap`; completion callback runs
  on client thread (resource reload + screen refresh must be client-thread).

## 4. UI (`JukeboxLibraryScreen`)

- Row preview button becomes state-driven for `YOUTUBE` tracks only:
  `↓` missing (starts download) · `…` downloading (disabled) ·
  `↻` failed (retry) · `>` ready (preview, existing path).
- Source column: "YT" for youtube tracks ("Disc"/"Music" unchanged).
- Footer: "Download all (n)" button, visible only when n > 0, beside the
  existing selected-count. Disabled while a batch runs? No — clicks while
  running are no-ops for already-queued tracks (states dedupe).
- Layout: extend `computeLayout` + logic tests (no overlap at 320px width).

## 5. Playback wiring

- Pack registered programmatically as always-enabled at client init
  (exact hook — `PackRepository` add + `reloadResourcePacks` or Fabric
  builtin-pack equivalent on 26.2 — resolved during planning).
- After each download completes: client resource reload, then row flips to
  `>`. Preview uses the untouched `NativeMinecraftPlayback.play` path with
  sound event `cue_my_music:youtube.<slug>`.
- New tracks default `ambientEligible=false` (opt-in via existing checkbox);
  114 live segments must not hijack ambient rotation.

## 6. Testing (unit only, no game launch)

- Catalog-vs-manifest completeness; java-ogg coverage.
- `sounds.json` generation shape (114 events, paths match catalog files).
- Downloader state machine + exact yt-dlp argv (mocked process spawning).
- Layout test for footer button; row-button state mapping test.
- Existing suite must stay green (`./gradlew test`).

## Open point for planning

Exact 26.2 resource-pack registration/reload hook. Candidates: programmatic
`PackRepository` injection + `Minecraft.reloadResourcePacks`, or Fabric
`registerBuiltinResourcePack` with a static shell. Planner verifies against
the 26.2 mappings before touching playback code.
