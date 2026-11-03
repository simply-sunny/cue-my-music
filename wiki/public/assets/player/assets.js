// The game's files, pulled from Mojang's servers when the page opens; the wiki keeps no copies.
// Textures and the game's strings come out of the client jar with one range request each (data.json
// says where each file sits); the music streams from Mojang's asset server as short previews.

const textDecoder = new TextDecoder();

/** One file out of the jar: its local header, then its bytes, inflated if they're deflated. */
async function entry(jar, [offset, size, method]) {
  // The local header is 30 bytes plus a name and an extra field; 512 covers both for these paths.
  // Busy, the server sometimes answers with the whole jar (41 MB) instead: stop that download and ask again.
  // Each file gets its own URL (the server ignores the query): the browser lets one request at a time use a URL.
  let res;
  for (let tries = 0; ; tries++) {
    const stop = new AbortController();
    res = await fetch(`${jar}?at=${offset}`, { cache: 'no-store', signal: stop.signal, headers: { Range: `bytes=${offset}-${offset + 30 + 512 + size}` } });
    if (res.status === 206) break;
    stop.abort();
    if (tries >= 3) throw new Error(`range request answered ${res.status}`);
    await new Promise(done => setTimeout(done, 250 * (tries + 1)));
  }
  const buf = new Uint8Array(await res.arrayBuffer()), view = new DataView(buf.buffer);
  const start = 30 + view.getUint16(26, true) + view.getUint16(28, true), data = buf.subarray(start, start + size);
  if (method === 0) return data;
  const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Fetches every texture in data.json into {@code textures} (keyed like the mod's, "block/jukebox_top"), and the game's biome names into {@code strings}. */
export async function pullGameFiles(source, textures, strings) {
  const pull = async ([path, at]) => {
    const bytes = await entry(source.jar, at);
    if (path.endsWith('.json')) {
      for (const [k, v] of Object.entries(JSON.parse(textDecoder.decode(bytes)))) if (k.startsWith('biome.minecraft.')) strings[k] = v;
      return;
    }
    const key = path.replace(/^assets\/minecraft\/textures\/(?:gui\/)?/, '').replace(/\.png$/, '');
    textures.set(key, await createImageBitmap(new Blob([bytes], { type: 'image/png' })));
  };
  // Eight at a time.
  const queue = Object.entries(source.entries), failed = [];
  await Promise.all(Array.from({ length: 8 }, async () => {
    while (queue.length) await pull(queue.shift()).catch(e => failed.push(e));
  }));
  if (failed.length) console.warn(`[cue-player] ${failed.length} game files didn't load`, failed[0]);
}

const PREVIEW = 30;

/** Plays what the player plays, 30 seconds at a time: each song is heard for its first 30 seconds from where it starts or is sought to. */
export class Previews {
  constructor(indexUrl) {
    this.indexUrl = indexUrl;
    this.hashes = null;
    this.audio = new Audio();
    this.audio.preload = 'auto';
    this.track = null;
    this.from = 0;
    this.muted = true;
  }

  async index() {
    if (!this.hashes) {
      this.hashes = fetch(this.indexUrl).then(r => r.json()).then(j => j.objects).catch(e => {
        console.warn('[cue-player] no asset index; the music stays silent', e);
        return {};
      });
    }
    return this.hashes;
  }

  /** The song's file on Mojang's asset server, or null for a song that isn't the game's. */
  async url(track) {
    if (track.packId !== null) return null;
    const [ns, path] = track.id.split(':');
    const h = (await this.index())[`${ns}/sounds/${path}.ogg`]?.hash;
    return h ? `https://resources.download.minecraft.net/${h.slice(0, 2)}/${h}` : null;
  }

  /** Matches the audio to the player once a frame: the song, where it is, paused or playing, speed and volume. */
  async sync(player) {
    const a = this.audio, t = player.current, playing = player.isPlaying() && !this.muted;
    if (t !== this.track) {
      this.track = t;
      this.from = player.position;
      a.pause();
      a.removeAttribute('src');
      const url = t && (await this.url(t));
      if (this.track !== t) return;
      if (url) {
        a.src = url;
        a.currentTime = player.position;
      }
    }
    if (!a.src) return;
    // A seek starts a new preview.
    if (Math.abs(a.currentTime - player.position) > 1.5) {
      a.currentTime = player.position;
      this.from = player.position;
    }
    const left = this.from + PREVIEW - player.position;
    a.playbackRate = player.rate();
    a.volume = Math.max(0, Math.min(1, player.cfg.volume * Math.min(1, left / 3)));
    if (playing && left > 0) {
      if (a.paused) a.play().catch(() => {});
    } else if (!a.paused) {
      a.pause();
    }
  }

  /** Seconds of preview left of the playing song, for the page to say when it goes quiet. */
  left(player) {
    return this.track && this.audio.src ? Math.max(0, this.from + PREVIEW - player.position) : 0;
  }
}
