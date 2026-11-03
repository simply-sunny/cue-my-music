# Changelog

All notable changes to Cue My Music are documented here. This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Beatmatching** in automix: the next song is sped up or slowed down by up to 8%, without changing its pitch, so its beats land on the first song's, then glides back to its own speed over 8 seconds once the transition is over. On by default; each transition has a switch for it.
- **Radio**: endless music picked from what you like and play through, leaning away from songs you skip, from the game's music, add-on songs, or both. **Go to song radio** on a song's menu starts one from that song.
- **Skip with transition**: Next blends into the next song from the coming bar, with the same automix transition (or crossfade) it would get at the end, beats lined up, instead of cutting. A skip still counts as a skip in your stats. Pressing Next again during it cuts straight on; it can be turned off in Settings.
- **Shuffle**, in the bottom bar and beside each list's play button, as on Spotify: the song picked plays first, then the rest of the list in a random order. With shuffle on, the big play button starts from a random song.
- **Autoplay**: when a list ends with nothing queued, songs like its last one carry on, picked as Radio picks them. On by default; turn it off in Settings to hand back to the game's music.
- The queue has **Clear**, and under it **Next from** the list playing; click a song there to skip to it.
- A heart in the bottom bar likes the song playing.
- **Home**, as on Spotify: a greeting, tiles for the lists you played lately, then your top songs, your top artists and radios made from your top songs. The house beside search opens it, and the player starts there the first time.
- **Modes**, at the top of Settings: **Vanilla** (Cue My Music off), **Vanilla + FX** (the game's music with your effects) and **Add-ons** (your packs and downloads on the game's schedule). The library shows each mode's own music; a sort orders it by Recents, Alphabetical or the default order.
- A **Switch mode** key (set it in Controls): flips between Vanilla + FX and Add-ons, or with **Mode key goes round all three** on in Settings, through Vanilla too.
- **Time between songs** (Vanilla + FX): the game's Frequency, or a set time from none to 20 minutes. At 5 seconds or less, or on the game's Constant, the game's songs mix into each other and Next skips with a transition.
- **Track pools** in the library: each place's music (the title screen, survival, creative, underwater, the End, every biome) as a list you can change. Take songs out so the game doesn't play them there, or put any song in with **Add to track pool** on its menu; **Reset** puts a pool back as the game has it.
- Under a playlist's songs, **Let's find something for your playlist** searches the library, with **Add** on each song found.
- Spotify's player keys: Cmd/Ctrl with left and right for previous and next, up and down for music volume, S for shuffle and R for repeat.
- **Looping** in transitions: **Loop out** repeats the first song's bar at the start of the transition in place of its outro.
- **Low pass filter in** and **Low pass in, low pass out** filters.
- Right-click a list in the library to play it, queue it, start a radio from it, or rename or delete a playlist.
- Cmd/Ctrl+Shift+L opens search from anywhere, as in Spotify.
- The player opens on the page it was left on.
- **All game music** has a pool drop-down: the songs the game plays on the title screen, in survival or creative, underwater, in the End, or in each overworld and nether biome.
- Artists from packs and add-ons, downloads included, are in the library's Artists. A song by several artists is on each one's page.
- `/cmm audit` walks every page in both modes and writes what doesn't make sense (cut-off headings, things said twice, one picture for different things, choices a mode rules out, next up the same as now playing...) to `config/cuemymusic-audit.md`.
- Packs can give each song its own cover: an `"art"` PNG named in `cuemymusic.json`.
- For add-ons: `CueSounds.refresh` picks up songs a pack gained or lost without a full resource reload.

### Changed

- Shorter, plainer text across the player: setting descriptions, empty pages, mode descriptions, mix presets and the mod list description.
- The Downloads pack and its add-on page are one place: opening the pack (from Home or a list) opens the Downloads page, with its icon and name.
- **Playing from** and **Next from** name every list: an add-on's page (and "filtered by" while you filter it), and Your top songs from Home, instead of "the list" or the pack's file name.
- Long names scroll to their end and back, pausing at each end, instead of looping round with the end run into the start.
- Track pools are left out of Add-ons: no **Add to track pool** on songs or selections there.
- Menus have their own icons for Play, Radio, track pools and Reset, instead of the queue's and the plus.
- Your Stats has its own picture (a cartography table), so it no longer looks like Radio.
- A mix's summary counts its straight cuts too ("257 songs · 254 transitions · 2 straight cuts"), so the numbers add up.
- On a narrow bottom bar, pitch and speed show short ("-2 st · ×1.00") instead of not at all, and still open Settings.
- Songs under **Next from** can be dragged to change the order they play in, as the queue can; a click still plays one. The list itself isn't changed.
- Add-ons can draw tabs (`PanelCanvas.tabs`), styled as the Mode switch.
- A hard line between the modes: Vanilla + FX plays only the game's music and Add-ons only add-on songs. Switching mode stops a song from the other one and takes its songs out of the queue and the list playing; playlists, Liked Songs, search and Home show only the mode's own songs, and nothing from the other mode can be played or queued.
- **Time between songs** is a Vanilla + FX setting only. Add-ons has no breaks: each song mixes into the next, and Next up says "Right after this one".
- The music volume slider always has its place in the bottom bar. On a narrow bar (a big GUI scale) the pitch and speed readout is left out first, and the slider narrows before it would go.
- **Skip with transition** jumps to where the song's transition starts and plays it into the next song, as at the song's natural end, instead of starting it early.
- Track pools are playlists: they're listed in Your Playlists (in Vanilla + FX), sorted with yours, and their pages read as playlists.
- Radio is only in Add-ons. In Vanilla + FX the game's music already comes on its own, so Radio, its Home row and "Go to song radio" aren't there.

- A resource pack reload no longer stops or changes the song: the sound engine keeps running through it, with every other sound stopped and reloaded as before.
- A playlist's Rename and Delete are in a ⋯ menu beside its search, so the toolbar never runs into itself; when it's narrow, Add all to queue shrinks to its +.

- Songs play through loading screens and server switches, the game's picks too: a loading screen no longer swaps the song for menu music, and a new dimension or server no longer cuts it. When it ends, the game picks the next from where you are. Only the dragon fight and the credits still take over.

- Radio plays the mode's music (the game's in Vanilla + FX, add-on songs in Add-ons) instead of asking, and repeat is greyed out while it plays, since it never ends.
- With repeat one on, the queue says **On repeat** instead of showing the song playing as Next up.
- The top of the now-playing panel says where the song is playing from ("Playing from Radio", "Played by the game").
- Pitch and speed in the bottom bar are labelled ("Pitch -3 · Speed ×0.80").
- Each track pool has the block of its place as its picture, and pools only an add-on plays (such as Downloads) aren't listed.
- Shorter library subtitles that fit; an empty Your Playlists stays open with New playlist showing.

- A click on a song selects it, and a double click (or Enter) plays it, as on Spotify. Before, a single click played it.
- The transition editor is laid out like Spotify's: close, Beta, more and **Save** along the top; both songs' waveforms with the transition and its curves over them, a preview button between them and the length under them; the presets in a row; and the parts as rows with their curve's colour. Changes play while the editor is open and are kept only on **Save**. Drag a song sideways to move it a beat at a time.
- The preview button plays just the transition, from a few seconds before it to a few after, then pauses.
- Smoother automatic transitions: Blend is picked only when the songs' beats stay together, near-silent intros are skipped, fades last at least 6 seconds, and two songs overlapping at full volume are turned down a little so the overlap isn't louder. Automatic lengths are whole bars.
- Cmd/Ctrl+F searches the page you're on; search everywhere is Cmd/Ctrl+Shift+L.
- Liked Songs shows in the library once something is liked.
- The mix view no longer has an Automix switch (it's in Settings), and its songs can be right-clicked.
- Artists without art of their own show their initials over a block.
- Buttons and chips are square with snipped corners instead of round.
- Filters are named as in Spotify: high pass filter in, low pass filter out.
- Songs from an add-on page keep that add-on's actions, such as delete, in search and on artist pages.

### Fixed

- Pausing a song and then switching mode no longer leaves the music stopped until you press play.
- One meaning of add-on song: every song a resource pack or add-on adds. Add-ons mode plays them all, and Vanilla + FX leaves them out, even ones a pack puts in the game's own music. Before, Add-ons could pick a music pack's song it wouldn't play and go quiet for up to 20 minutes.
- With no add-on songs, Add-ons stays quiet instead of stalling, checks again every 10 seconds, and Now playing says why.
- `/cmm play`, `/cmm queue` and `/cmm dislike` only find and suggest songs from the mode you're in, so they no longer say "Playing" for a song that won't play.
- Dislike works in Add-ons too: a disliked add-on song isn't picked, and Disliked Songs is in the sidebar in both modes.
- A playlist, Liked Songs or Disliked Songs whose songs are all in the other mode says so, instead of looking empty.
- The effects key says "Effects on" or "Effects off", not "Cue My Music: off", which Vanilla means.
- The mode key goes from Vanilla back to the mode you left, not always to Add-ons.
- A song whose file is gone, such as a deleted download, leaves the queue instead of being tried in turn.
- Changing the audio device no longer skips to another song: the song carries on from the same place on the new device.
- Deep Dark and Underwater's pictures show one frame instead of every frame squashed together, and Sulfur Caves has a picture.
- The mix view, and pages shown as a mix such as Downloads, lagged while scrolling: each transition on screen re-read both songs' whole loudness every frame. It's worked out once per song now.
- Music no longer stops for a moment when the level changes: changing dimension, or joining or leaving a world or server. Your song keeps playing on the same sound instead of starting again.
- A song the game picked fades out when you change dimension, join or leave a world, and the game picks the next from where you are now. Menu music no longer carries on into the world, or overworld music into the nether.
- **Pause during travel** fades the song out as a nether portal's swirl builds, silent as it takes you (half a second without a portal), and fades it back in over 2 seconds once the new dimension is on screen. Joining or leaving a world doesn't count as travel.

## [1.3.0] - 2026-10-27

### Added

- Search: the magnifying glass next to Library, or Cmd/Ctrl+F. Finds songs by title, artist or album, and artists by name.
- Automix for songs on add-on pages: matched loudness, silence trimmed, and a crossfade into the next song.
- A **Mix** view for a list's songs, switched on by the Mix chip above the list once two songs next to each other can blend. Each page remembers whether it's shown as a mix. The page keeps its header and buttons; the game's own songs show dimmed, since they don't blend. It shows each song's tempo and key, and the transition between each pair. Open a transition to see both songs' waveforms with its volume and bass curves, preview it, pick a preset (Auto, Fade, Rise, Blend, Echo, Cut), or set its volume curve, bass swap, filter and echo one by one, plus when it starts and where the next song comes in. Length is a choice of 2, 4, 8 or 16 bars. Each song's beats show as ticks between the two, and scrolling sideways over either song moves it a beat at a time: the first moves where the transition starts, the next where it comes in.
- A smarter **Auto** transition: it picks Blend, Rise, Fade or Echo from the two songs' tempos and keys, ends where the first song's music fades rather than in its quiet tail, and starts on a bar so both songs' bars line up. Songs are analysed again once for this.
- Keep add-on songs out of recordings: they play from a separate process, so recorders of the game's own audio leave them out.
- A **Drop** transition preset: the next song drops in at full volume as the first loses its bass, which then holds for half the transition and fades out in a straight line, over 16 bars. Its parts are also in the drop-downs: **Drop in, then fade** and **Bass swap at the start**. Length goes up to 40 seconds and shows in bars when the tempo is known.
- Search in a playlist or any other page: the magnifier in its toolbar narrows the songs shown as you type.
- Drag songs in a playlist to change their order, one at a time or a whole selection.
- **Vanilla music** in Settings turns Cue My Music and its add-ons off, so the game plays its own music as if they weren't there. The player screen then only offers to turn them back on.
- A short **Crossfade** between songs played through the player, 4 seconds by default: each song fades into the next one in a page or the queue, game songs too. Songs automix blends keep their own transition. **Crossfade** in Settings sets it from 1 to 12 seconds, or Off.
- A volume slider in the bottom bar, next to pitch and speed. It's the game's Music volume; click the speaker to mute and unmute.
- Music pauses while Minecraft is in the background, and resumes when you come back. **Pause in background** in Settings turns this off.

### Changed

- Tempo, key, loudness and waveforms of add-on songs are saved in `config/cuemymusic-analysis/` and read back next time instead of worked out again. Once the game has loaded, songs not worked out yet are done in the background, so the mix shows tempos and keys straight away.
- The player does less work each frame: the song list, the game's songs and add-on pages are kept between frames instead of rebuilt.
- The right-click menu looks like Spotify's: icons, dividers, and **Add to playlist** with a submenu you can search, instead of one row per playlist. It also has **Go to artist**.
- Stats ignore songs heard for under 3 seconds, so clicking through songs doesn't fill them with plays and skips. A song that ends by itself is a play only if you heard at least half of it (or 30 seconds), so seeking to the end isn't one. Seeking, pausing, travelling and clicking the playing song again carry on the same play. Nothing is counted while music volume is at 0.

### Fixed

- With **Pause during travel** on, a song could start again for a moment while the new dimension was still loading. It now waits until the loading screen has gone, then fades in.
- Automix crossfades crackled: the two songs' decoded blocks were mixed at different lengths, with silence filling the gap.

## [1.2.0] - 2026-10-26

### Added

- Select several songs with shift-click, shift-drag, Cmd/Ctrl-click, shift+↑/↓ or Cmd/Ctrl+A, then right-click to queue, like, dislike or add them all to a playlist, or press Delete to take them off a playlist, Liked Songs or Disliked Songs.
- **Your Stats** page: time listened, plays, streaks, top songs and artists, a 24-hour listening clock, a calendar of every day you listened, time per dimension, how much you picked versus the game, and the song you skip most.
- Add-ons can give their page a Delete action for selected songs.
- Dragging a selection to the top or bottom of the list scrolls it.

### Changed

- Dislike is a thumbs down.

### Fixed

- Music sounded hollow with spatial audio on, such as Directional Audio with AirPods. Stereo songs now go straight to the left and right speakers.

## [1.1.0] - 2026-10-26

### Added

- Repeat: off, all, or one, from the button next to play or with `/cmm repeat`.
- Playlists. **New playlist** in the library makes one; right-click any song to add it. Click a playlist's name to rename it.
- Right-click menu on songs: add to queue, like, add to a playlist.
- Resource packs can credit each song with `assets/<namespace>/cuemymusic.json`.
- Pitch, speed, and EQ changes show a loading ring until they're heard.
- Dislike: the ⊘ button on a song, right-click, or `/cmm dislike` and the game never picks it for its own music. It still plays when you pick it. Disliked songs get their own page.
- The library is grouped into Artists, Packs, and Your playlists, with one-line rows. Click a heading to fold it. The library scrolls when it's long.
- Add-ons can add their own page to the player's library (`mix.cuemymusic.api`).

### Fixed

- Songs a resource pack adds or removes show up after a reload without reopening the player.
- Queue headings and album names no longer run past their columns in a small window.

## [1.0.0] - 2026-10-25

First release.

### Added

- Player screen with a library, track list, now playing, and controls. Opens from a music button on the title screen and pause menu, from Mod Menu, or with a key.
- Pages for all game music, Liked Songs, each artist, and each resource pack that adds music.
- Play, pause, seek, next, and previous. A song plays the rest of its page after it.
- Queue: add a song or a whole page, drag to reorder, × to remove.
- The game's own music plays exactly as in vanilla, and the queue shows its next song and break.
- Pitch (−12 to +12 semitones) and speed (0.5× to 2.0×), independent of each other.
- 6-band equalizer with presets.
- Sorting by title, album, or length.
- Chat commands: `/cmm play`, `queue`, `next`, `prev`, and `pause`.
- Keys for the player, effects, previous, play/pause, and next. F7–F9 by default on macOS.
- **Pause during travel** setting.
- Icon made from the game's own textures.
- 34 headless regression checks.
