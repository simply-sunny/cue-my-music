# Responsive Track-Weighting Layout and Preview Player Design

**Date:** 2026-09-12  
**Status:** Approved in conversation; pending written-spec review

## Goal

Refine the track-weighting screen into a centered, responsive workspace with readable scrollable pool tabs, a collapsible track browser, controls below the probability wheel, icon-only actions, and a working compact preview player.

This revision preserves dynamic resource-pack discovery, transactional configuration, weighted selection semantics, accessibility, and renderer-neutral radial rendering from the original track-weighting design.

## Workspace

All settings UI is constrained to a centered inner workspace occupying 80% of the current GUI width and 80% of its height. The remaining 10% on each edge is empty background.

The workspace contains, from top to bottom:

1. the pool tab viewport;
2. the content area;
3. the icon action toolbar.

Geometry is recalculated from screen dimensions during initialization and resize. Controls must remain inside the workspace and must not overlap at the minimum supported GUI dimensions.

## Pool Tabs

Each dynamically discovered pool is represented by one native Minecraft `MenuTabBar.MenuTabButton`, preserving the World Creation tab appearance, selected state, focus underline, narration, and keyboard selection behavior.

Tab titles use clean human-readable category names, such as `Creative`, `Ender Dragon`, `Main Menu`, and `Cherry Grove`. Known vanilla categories receive accurate contextual names; dynamically discovered custom pools receive a title-c-cased path with their namespace retained when useful. The complete stable pool ID remains available to narration/debug context but is not the visible label.

Hovering a tab shows a plain-language tooltip explaining where that category's music can play, such as “Plays during the end credits” or “Plays in the Cherry Grove Overworld biome.” Unknown resource-pack pools use “Custom music pool: `<full-id>`” rather than inventing gameplay semantics.

Tabs follow a user-oriented progression rather than raw identifier order: Main Menu, Survival, Creative, Overworld categories alphabetically, Underwater, Nether categories alphabetically, Ender Dragon, The End, Credits, remaining Minecraft pools, then custom pools alphabetically. This order affects only the UI; catalog traversal and deterministic selection order remain unchanged.

The tab viewport:

- occupies the full width of the inner workspace;
- is one row high;
- clips overflowing tabs to its bounds;
- scrolls horizontally through mouse wheel or trackpad input;
- has no left/right scroll buttons;
- sizes tabs from the clean visible category names;
- automatically reveals a tab selected by mouse or keyboard;
- clamps its offset after resize;
- preserves the selected pool through widget rebuilds;
- retains native `Ctrl+Tab`, `Ctrl+Shift+Tab`, and supported numbered-tab behavior.

No tab wrapping, paging, abbreviated labels, or cycling pool button is present.

## Track Browser

The track list and search field form a collapsible left browser.

### Wide mode

When the workspace can fit both the browser and a useful main panel, the browser starts open. Its toggle is an icon-only native button:

- `×` closes the browser;
- `☰` reopens it.

Closing the browser gives the full content width to the main panel. User choice remains stable while the screen stays at a compatible size.

### Narrow mode

When the workspace cannot fit the browser and the minimum main-panel width side by side, the browser starts automatically collapsed. This decision is fit-based: browser width + gap + minimum main width must fit, rather than relying on an unrelated absolute screen breakpoint.

If the user opens the browser in narrow mode:

- the browser fills the content area of the inner workspace;
- the radial wheel, editor, and preview player are hidden;
- pool tabs and the bottom action toolbar remain visible;
- `×` returns to the main panel.

Resizing from wide to narrow automatically hides the browser if required. Resizing back to wide restores the normal side-by-side layout without mutating the draft or selected track.

## Main Panel

The main panel is vertically organized and centered in its available content bounds:

1. selected-track heading and chance;
2. radial probability wheel;
3. multiplier slider;
4. icon/compact multiplier shortcuts;
5. preview control or active preview player.

The multiplier editor is never positioned as a separate right-hand column. Its state remains synchronized with list selection, wheel selection, Test Roll, pool switching, search filtering, and draft changes.

The radial wheel remains CPU-rasterized into `NativeImage` and displayed through ordinary vanilla texture blitting. This revision adds no renderer backend detection, custom shaders, custom pipelines, Fabric rendering API, or direct graphics API calls.

## Preview Player

The existing preview button defect must be root-caused and reproduced before correction. The fix must ensure the selected catalog occurrence produces audible playback through Minecraft's sound engine.

The idle preview control is the icon-only `▶` button. Because its meaning is self-evident, it does not require a visual hover tooltip, but it retains accessible narration.

When preview starts, the `▶` control is replaced by a compact preview player occupying the available lower main-panel region beneath the radial wheel and multiplier controls. It shows:

- the full selected track title;
- elapsed and total time when duration is known;
- a progress/scrub slider;
- `⏸` while playing and `▶` while paused;
- `■` to stop and return to the idle preview control.

The preview player is settings-specific rather than a refactor of `PauseMusicWidget`. It may reuse existing duration, clock, stream-offset, and sound-instance utilities, but must not route preview selection through the normal planner or queue.

Preview lifecycle rules remain:

- only one preview exists at a time;
- choosing another track stops the previous preview;
- pool/track selection changes, Done, Esc, screen removal, JSON transition, and resource reload stop preview;
- current background music is paused only if active and only by preview ownership;
- background music resumes exactly once only if preview paused it;
- preview never starts normal background music;
- preview and scrubbing do not mutate config, planner sequence/history, queue, or Anti-Repeat state;
- natural completion returns to the idle `▶` control and resumes owned background music;
- unavailable duration disables scrubbing rather than inventing a duration.

## Icon-Only Actions

Bottom actions use compact native buttons with character icons. Every icon except the self-explanatory preview control has a hover tooltip and explicit narration.

| Icon | Action | Tooltip / narration |
|---|---|---|
| `↺` | Set selected pool to All 1× | Reset pool to native weights |
| `♫` | Set C418 tracks to 2× | Double C418 tracks |
| `∅` | Set selected pool to Mute 0× | Mute selected pool |
| `⟳` | Toggle Anti-Repeat | Anti-Repeat: On/Off |
| `⚄` | Test Roll | Test weighted selection |
| `{}` | Open read-only JSON view | View and copy JSON |
| `✓` | Save and return | Done |
| `☰` | Open track browser | Show track list |
| `×` | Close track browser | Hide track list |

Button state must remain distinguishable without relying on tooltip text. Anti-Repeat therefore has a selected/on visual state and narration that includes its current value.

Icons must be verified against Minecraft's bundled font. If a listed glyph is unavailable, use the nearest bundled single-character equivalent without introducing an icon asset or font dependency.

## State and Navigation

The existing immutable draft remains the single configuration state. Layout state consists only of browser visibility and tab scroll offset.

- `Done` saves atomically, applies the saved config, and returns to the player.
- `Esc` discards draft changes and returns to the player.
- Opening/closing the browser, scrolling tabs, resizing, previewing, and Test Roll never save or mutate the planner.
- List selection remains the primary keyboard-accessible representation of wheel probabilities.
- Focus order follows tabs, browser toggle/search/list when visible, main editor/player when visible, then bottom toolbar.

## Testing

Implementation follows TDD and adds the smallest behavioral checks for:

- centered 80% workspace geometry across representative sizes;
- fit-based wide/narrow browser behavior;
- browser overlay mode hiding wheel/editor/player;
- full-ID scrollable tabs with no arrow buttons;
- mouse-wheel scrolling, clamping, resize preservation, and selected-tab auto-reveal;
- below-wheel editor geometry and non-overlap;
- icon labels, tooltips, narration, and Anti-Repeat state;
- reproduced preview playback defect;
- actual preview start, pause/resume, stop, replacement, natural completion, scrub, unknown duration, and ownership cleanup;
- no config/planner/queue mutation from preview or layout actions;
- renderer-neutral radial contract.

After automated verification, a Gemini worker launches Minecraft and leaves it open. The user performs visual review and sends screenshots. Automated agents do not judge final visual appearance in place of that review.

## Delivery

After user-approved runtime screenshots:

1. run clean tests/build and inspect JUnit XML;
2. inspect the built jar and dependency boundaries;
3. request independent correctness review for nonvisual behavior;
4. correct confirmed Critical/Important findings test-first;
5. integrate and push verified commits to `main`;
6. do not tag, create a GitHub release, or publish to Modrinth without explicit instruction.
