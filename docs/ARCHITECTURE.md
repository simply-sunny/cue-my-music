# Cue My Music — Architecture (MVP Ultra Minimal)

> Minecraft 26.2 Fabric mod. 92 exact vanilla tracks (70 Music + 22 Discs), fuzzy search, queue checkbox, preview > / || + scrub-while-playing, vanilla FILE streamed via MUSIC. No Cloth Config, no external tools, no presets/collections.

## Package map

```
src/main/java/com/cuemymusic/
  CueMyMusic.java                 // ModInitializer — init library + persistence
  config/CueMyMusicConfig.java    // 2 fields: enableAmbientReplacement, nextTrackDelaySeconds
  data/
    MusicLibrary.java             // Map<String,Track> + filter fuzzy + dedup + prune
    MusicTrack.java               // id,title,artist,sourceType(VANILLA|MUSIC_DISC),sourceId,jukeboxSongId,enabled,ambientEligible,durationSeconds
    SourceType.java               // VANILLA, MUSIC_DISC
  persistence/
    PersistenceManager.java        // gameDir/cue-my-music/{config.json,library.json}
    PersistedLibraryState.java    // List<PersistedTrack>

src/client/java/com/cuemymusic/
  client/CueMyMusicClient.java    // ClientModInitializer — registerAll + ClientTickEvents.tick → MusicDirector.tick
  client/music/VanillaTrackRegistry.java // 70 VANILLA_KEYS + 22 DISC_KEYS, VANILLA_FILE/TITLES/ARTISTS, registerFallback, pruneStale, isValidId
  client/playback/
    NativeMinecraftPlayback.java  // singleton lock + VanillaFileSoundInstance(FILE streamed MUSIC) vs forMusic discs, play/stop/togglePause/isPlaying/isPaused/getElapsed
    MusicDirector.java            // eligible = enabled && ambientEligible, chooseNextTrack random, playTrack → nativePlayback, tick auto-next, scheduleNextDelay respects Frequency
    PlaybackState.java            // STOPPED, PLAYING, PAUSED
  client/ui/JukeboxLibraryScreen.java // centered vanilla Screen: search EditBox + Sort Artist/Title/Source + top scrub bar next to search/sort (visible when isPlaying, effDur 180 fallback, fill pos/effDur, knob, times, drag seek) + header ✓ TYPE TITLE ARTIST + rows (checkbox, type, title, artist, preview > / ||) + scroll + dedup + bottom X selected / Y tracks
  client/integration/ModMenuIntegration.java // ConfigScreenFactory → new JukeboxLibraryScreen(parent)
  mixin/
    MusicManagerMixin.java        // suppress vanilla startPlaying when eligible not empty & replacement enabled
    SoundManagerAccessor, SoundEngineAccessor, ChannelAccessor, ChannelHandleAccessor // for AL_SEC_OFFSET seek
```

## Data flow

```
CueMyMusic.onInitialize → new MusicLibrary + load library.json → applyPersistedState
CueMyMusicClient.onInitializeClient → VanillaTrackRegistry.registerAll(library) → fallback 70+22 (if missing) → pruneStale 92 → applyPersistedState(load again, skips stale vanilla:hal1) → ClientTickEvents.tick → director.tick()
JukeboxLibraryScreen.rebuild → dedup putIfAbsent → library.filter(search) fuzzy (contains/subseq/lev≤2) → sort Comparator (Artist default) → displayed
  click checkbox → track.setAmbientEligible(!) → saveAll()
  click > → stop vanilla MusicManager+SoundManager(MUSIC) → director.stopCurrent → director.playTrack → nativePlayback.play (stopLocked → FILE or forMusic)
  top scrub bar next to search/sort (visible when anyPlaying, effDur 180 fallback): topScrubX/Y/W/H at y=30 next to search/sort (or fallback y=52), barW=topScrubW-60, fill pos/effDur, knob, times, drag → director.seek via (mx-topBarX)/topBarW
  scroll: first=scroll/ROW_H, yOff=-(scroll%ROW_H), rowY=tableTop+yOff+(idx-first)*ROW_H, clamp
Persistence: library.toPersistedState → tracks → saveLibraryIndex → library.json (human JSON)
```

## Key invariants

- **92 exact**: VANILLA_KEYS 70 + DISC_KEYS 22, validated against `minecraft/sounds.json` + `JUKEBOX_SONG`. `registerFallback` if missing, `pruneStale` removes legacy `vanilla:game`, `vanilla:hal1` etc. `isValidId` guards.
- **Fuzzy**: `filter(List,String)` lowercases/trim, empty→new ArrayList(input), else `title.contains||artist.contains||id.contains` → `isSubsequence` → if q.length≤12 token lev≤2.
- **No overlap playback**: `play()` synchronized + `stopLocked` before `play`, `previewTrack` also stops vanilla via `MUSIC` category, same-track togglePause.
- **Scrub**: top bar next to search/sort — visible only while `isPlaying` (`cur != null && isPlaying`, `effDur=180` fallback). At `y=30` next to search(200)/sort(110) (`topScrubX=sortX+sortW+8` or fallback `panelL+6,y=52`), `topBarW=topScrubW-60`, fill `pos/effDur`, knob, times, drag seek via `(mx-topBarX)/topBarW`. Hidden when paused/stopped, no per-row bar, table `tableBottom=doneY-8` always (no resize).
- **No Cloth Config**: vanilla `Screen` only (`EditBox`/`Button`/`GuiGraphicsExtractor`). No `cloth-config` dependency. ModMenu factory returns `JukeboxLibraryScreen` directly.
- **Mixins**: only `MusicManagerMixin` (suppress startPlaying) + 4 accessors for OpenAL position. No `SoundOptionsScreenMixin` (scrapped).

## Build & run

```bash
./gradlew build # 58k jar, no refmap, no cloth
cp build/libs/cue-my-music-1.0.0.jar ~/Library/Application\ Support/PrismLauncher/instances/26.2/minecraft/mods/
./gradlew test # 6 suites: VanillaRegistryTest 8, MusicLibraryFilterTest 7, MusicTrackTest 4, PersistenceRoundTripTest 3, PlaybackStateTest 4, JukeboxLogicTest 5
npx serve site -l 3000 # throwaway site: Hero, Install, 92 Catalog filterable, UI, Playback, Verify
```

## Verification

- Cross-check 108 mp3 vs 92: 0 missing, 16 extra album extras (Flake, Ki, Door, Beginning, Moog City, Equinoxe, etc) correctly excluded.
- Ponytail audit: `src` clean, debt ledger suggests removing `TrackDetailWidget` (dead 178 LOC), `durationSeconds` (always null), `addTracks/clear`, `getConfigDir` — ~300 LOC removable without breaking screens.
- Workflow with phases tested: Discover → Audit → Report via `SubagentWorkflow` (see `wf_fa495b...`).

## Throwaway site

`site/index.html` (Tailwind CDN, dark, 92 rows, fuzzy search live JS, Source/Artist filters). Served at `http://localhost:3000`, also `file://`.

## Known stale handling

`library.json` with 106 (84 vanilla) from old aggregates is healed on next save: `applyPersistedState` skips re-creating `vanilla:`/`disc:` not already present, `registerAll` prune removes `vanilla:hal1` etc, dedup in `rebuild` guards UI.
