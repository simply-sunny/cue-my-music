// Mounts the web player on each <div class="cmm-player"> on the page:
//
//   <div class="cmm-player" data-page="SETTINGS" data-parts="center"></div>
//   <div class="cmm-player" data-demo="select" data-parts="center"></div>
//
// data-page opens a page as CueScreen names them (HOME, SETTINGS, STATS, SEARCH, RADIO, LIKED, PLAYLIST,
// LIST:<id>, ARTIST:<name>, PACK:<id>, POOL:<id>); data-mode is fx, addons or vanilla; data-parts shows some of the screen
// (library, center, now, bar; all of it if left out); data-height is the GUI height in pixels (16:9, or at least 270 on a
// narrow page, unless set). data-demo plays a script from demos.js instead of taking input. Each player is its own copy of
// the mod, drawn at a whole-number GUI scale like the game's, with the game's textures and music previews pulled from Mojang.

import { Font } from './gfx.js';
import { Library, Player, Stats, defaultConfig, STRINGS, tr, ALL } from './model.js';
import { pullGameFiles, Previews } from './assets.js';
import { CueScreen, BOTTOM } from './screen.js';
import { Demo, SCRIPTS } from './demos.js';
import './pages.js';
import './mix.js';
import './stats.js';
import './side.js';

const BASE = new URL('.', import.meta.url);
const GUI_WIDTH = 480;

let shared;
/** The data, the font and the game's files, loaded once for every player on the page. */
function load() {
  shared ??= (async () => {
    const font = new Font();
    const [data] = await Promise.all([fetch(new URL('data.json', BASE)).then(r => r.json()), font.load(new URL('fonts/Monocraft.otf', BASE))]);
    const textures = new Map();
    // The library first, so strings exist; the game's biome names join them as they arrive.
    const lib = new Library(data);
    const files = pullGameFiles(data.source, textures, STRINGS);
    return { data, font, textures, lib, files };
  })();
  return shared;
}

const players = new Set();

async function mount(root) {
  root.classList.add('is-loading');
  const script = root.dataset.demo ? SCRIPTS[root.dataset.demo] : null;
  if (root.dataset.demo && !script) throw new Error('no demo named ' + root.dataset.demo);
  const parts = root.dataset.parts ? new Set(root.dataset.parts.split(',').map(p => p.trim())) : null;
  const canvas = document.createElement('canvas');
  canvas.tabIndex = 0;
  const bar = document.createElement('div');
  bar.className = 'cmm-player__bar';
  const note = document.createElement('span');
  note.className = 'cmm-player__note';
  // A demo has play/pause and restart, and its caption; a player has sound and full screen.
  const sound = button('cmm-player__sound', 'Sound off');
  const full = button('cmm-player__full', 'Full screen');
  const pause = button('cmm-player__pause', 'Pause');
  const restart = button('cmm-player__restart', 'Restart');
  if (script) {
    root.classList.add('is-demo');
    canvas.setAttribute('role', 'img');
    canvas.removeAttribute('tabindex');
    bar.append(pause, note, restart);
  } else {
    canvas.setAttribute('role', 'application');
    bar.append(sound, note);
    if (!parts) bar.append(full);
  }
  root.replaceChildren(canvas, bar);

  const { data, font, textures, lib } = await load();
  const previews = new Previews(data.source.index);
  let cfg, player, screen;
  /** A fresh copy of the mod, on the element's page. */
  const build = () => {
    const keep = screen && { width: screen.width, height: screen.height };
    cfg = defaultConfig(lib);
    if (root.dataset.mode === 'vanilla') cfg.vanilla = true;
    else if (root.dataset.mode) cfg.mode = root.dataset.mode;
    const stats = new Stats(lib);
    player = new Player(lib, cfg, stats);
    screen = new CueScreen({ lib, cfg, player, stats, font, textures, previews });
    screen.parts = parts;
    if (keep) Object.assign(screen, keep);
    // Something on, paused, so the player isn't empty: the game's song, or the example pack in Add-ons.
    if (player.mode() === 'addons') player.playAll('pack:' + lib.packs[0].id, player.only(lib.addOnMusic()));
    else if (player.mode() === 'fx') player.playUpcoming();
    if (player.current) {
      player.position = Math.min(42, player.current.seconds / 3);
      player.paused = true;
    }
    screen.start(root.dataset.page || 'HOME');
    self.screen = screen;
    self.player = player;
  };
  const self = { root, previews, sound };
  build();
  players.add(self);
  root.classList.remove('is-loading');

  // A demo runs while on screen, and starts over a moment after it ends. With reduced motion it waits to be played.
  const demo = script && new Demo(script);
  let demoPaused = !!demo && matchMedia('(prefers-reduced-motion: reduce)').matches, restartIn = -1;
  if (demo) {
    demo.start(screen);
    canvas.setAttribute('aria-label', 'An animation of the player. The caption under it says what it shows.');
  }
  const showPaused = () => {
    pause.textContent = demoPaused ? 'Play' : 'Pause';
    pause.setAttribute('aria-pressed', String(demoPaused));
  };
  showPaused();
  pause.addEventListener('click', () => {
    demoPaused = !demoPaused;
    showPaused();
  });
  restart.addEventListener('click', () => {
    build();
    demo.start(screen);
    restartIn = -1;
    demoPaused = false;
    showPaused();
  });

  // Size: a whole-number scale that makes the GUI about 480 pixels wide, as the game picks its GUI scale.
  let scale = 1;
  const resize = () => {
    const dpr = window.devicePixelRatio || 1, cssW = root.clientWidth, fullscreen = document.fullscreenElement === root;
    const devW = Math.round(cssW * dpr);
    // Never under a CSS pixel per GUI pixel, so a phone shows a narrower GUI rather than tiny text.
    scale = Math.max(1, Math.round(devW / GUI_WIDTH), Math.round(dpr));
    screen.width = Math.floor(devW / scale);
    screen.height = fullscreen ? Math.floor((root.clientHeight - bar.offsetHeight) * dpr / scale)
      : Number(root.dataset.height) || (parts && parts.size === 1 && parts.has('bar') ? BOTTOM + 30
        : Math.round(Math.max(screen.width * 9 / 16, Math.min(screen.width, 270))));
    // On the page's 8px grid, so what follows the player stays on it (docs/LAYOUT.md in the templates).
    if (!fullscreen) {
      for (let h = screen.height; h > screen.height - 32; h--) {
        const css = h * scale / dpr;
        if (Math.abs(css / 8 - Math.round(css / 8)) < 1e-6) {
          screen.height = h;
          break;
        }
      }
    }
    canvas.width = screen.width * scale;
    canvas.height = screen.height * scale;
    canvas.style.width = canvas.width / dpr + 'px';
    canvas.style.height = canvas.height / dpr + 'px';
  };
  new ResizeObserver(resize).observe(root);
  resize();

  // Input, in GUI pixels.
  const at = e => {
    const r = canvas.getBoundingClientRect();
    return { x: Math.floor((e.clientX - r.left) / r.width * screen.width), y: Math.floor((e.clientY - r.top) / r.height * screen.height) };
  };
  const mods = e => ({ shift: e.shiftKey, ctrl: e.metaKey || e.ctrlKey, alt: e.altKey });
  canvas.addEventListener('pointermove', e => {
    if (demo) return;
    const p = at(e);
    screen.mx = p.x;
    screen.my = p.y;
    if (e.buttons & 1) screen.mouseDragged({ ...p, ...mods(e) });
    canvas.style.cursor = screen.clickable(p.x, p.y) ? 'pointer' : 'default';
  });
  canvas.addEventListener('pointerleave', () => {
    if (demo) return;
    screen.mx = screen.my = -1;
  });
  canvas.addEventListener('pointerdown', e => {
    if (demo) return;
    canvas.focus({ preventScroll: true });
    canvas.setPointerCapture(e.pointerId);
    activate(self);
    const p = at(e);
    screen.mx = p.x;
    screen.my = p.y;
    screen.mouseClicked({ ...p, ...mods(e), button: e.button, double: e.detail >= 2 });
    e.preventDefault();
  });
  canvas.addEventListener('pointerup', e => {
    if (demo) return;
    if (e.button === 0) screen.mouseReleased({ ...at(e), ...mods(e) });
  });
  canvas.addEventListener('contextmenu', e => e.preventDefault());
  canvas.addEventListener('wheel', e => {
    if (demo) return;
    const p = at(e), unit = e.deltaMode === 1 ? 1 : 100;
    if (screen.mouseScrolled(p.x, p.y, -e.deltaX / unit, -e.deltaY / unit, e.shiftKey)) e.preventDefault();
  }, { passive: false });
  canvas.addEventListener('keydown', e => {
    if (demo) return;
    const ev = { key: e.key.length === 1 && (e.metaKey || e.ctrlKey) ? e.key.toLowerCase() : e.key, ...mods(e) };
    let used = screen.keyPressed(ev);
    if (!used && e.key.length === 1 && !e.metaKey && !e.ctrlKey) used = screen.charTyped(e.key);
    if (used) e.preventDefault();
  });

  sound.addEventListener('click', () => {
    if (previews.muted) activate(self);
    else previews.muted = true;
  });
  full.addEventListener('click', () => {
    if (document.fullscreenElement === root) document.exitFullscreen();
    else root.requestFullscreen?.();
  });
  document.addEventListener('fullscreenchange', () => {
    full.textContent = document.fullscreenElement === root ? 'Exit full screen' : 'Full screen';
    resize();
  });

  // Drawn only while on screen.
  let visible = false, last = performance.now();
  new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting;
    if (visible) requestAnimationFrame(frame);
  }).observe(root);
  const ctx = canvas.getContext('2d');
  function frame(now) {
    const dt = Math.min(0.25, (now - last) / 1000);
    last = now;
    if (demo && !demoPaused) {
      if (demo.done) {
        if (restartIn < 0) restartIn = 2500;
        restartIn -= dt * 1000;
        if (restartIn <= 0) {
          restartIn = -1;
          build();
          demo.start(screen);
        }
      } else {
        demo.tick(dt * 1000);
      }
    }
    if (!demo || !demoPaused) player.tick(dt);
    if (!demo) previews.sync(player);
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    ctx.imageSmoothingEnabled = false;
    screen.render(ctx);
    if (demo) {
      demo.draw(screen.g);
      note.textContent = demoPaused && !demo.caption ? 'Paused' : demo.caption;
    } else {
      sound.textContent = previews.muted ? 'Sound off' : 'Sound on';
      sound.setAttribute('aria-pressed', String(!previews.muted));
      const left = previews.left(player);
      note.textContent = previews.muted ? '' : !player.current ? '' : player.current.packId !== null ? 'Example songs are silent'
        : left > 0 ? `Preview: ${Math.ceil(left)} s left` : 'Preview over';
    }
    if (visible || document.fullscreenElement === root) requestAnimationFrame(frame);
  }
}

/** One player is heard at a time: the last one clicked. */
function activate(self) {
  for (const p of players) p.previews.muted = p !== self;
}

function button(className, label) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = className;
  b.textContent = label;
  return b;
}

for (const root of document.querySelectorAll('.cmm-player')) {
  mount(root).catch(e => {
    root.classList.remove('is-loading');
    root.classList.add('is-failed');
    root.textContent = "The player didn't load.";
    console.error('[cue-player]', e);
  });
}

export { tr, ALL };
