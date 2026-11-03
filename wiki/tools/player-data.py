#!/usr/bin/env python3
"""Builds the web player's data from the mod and the game.

Reads the track catalog, the mod's English strings and the game's music events
(sounds.json), and writes public/assets/player/data.json. No game files are
copied: data.json points at Mojang's client jar and asset index, and the
browser pulls the textures, the biome names and the music previews from
Mojang's servers. Run from the wiki folder after the catalog, the strings or
the game version change:

    python3 tools/player-data.py
"""
import json
import re
import sys
import zipfile
from pathlib import Path

WIKI = Path(__file__).resolve().parents[1]
REPO = WIKI.parent
OUT = WIKI / 'public/assets/player'
VERSION = '26.3'
LOOM = Path.home() / '.gradle/caches/fabric-loom'
INFO = LOOM / VERSION / 'mojang_minecraft_info.json'
CLIENT = LOOM / VERSION / 'minecraft-client.jar'
INDEX = LOOM / 'assets/indexes' / f'{VERSION}-34.json'

CATALOG = REPO / 'src/common/java/mix/cuemymusic/library/Catalog.java'
LANG = REPO / 'src/main/resources/assets/cuemymusic/lang/en_us.json'

# Textures the player pulls from the jar, beyond each album's: its own pages, the track pools' places, Your Stats' dimensions
# and add-on artists.
BLOCKS = {
    'jukebox_top', 'jukebox_side', 'crafting_table_top', 'cartography_table_top', 'note_block',
    'bookshelf', 'grass_block_side', 'diamond_block', 'prismarine', 'end_stone', 'dragon_egg', 'obsidian',
    'sculk', 'sulfur', 'dripstone_block', 'powder_snow', 'packed_ice', 'moss_block', 'mud', 'oak_log', 'podzol_top',
    'hay_block_side', 'cherry_leaves', 'ice', 'snow', 'calcite', 'flowering_azalea_leaves', 'sand', 'red_sand',
    'jungle_log', 'jungle_planks', 'bamboo_block', 'netherrack', 'warped_nylium', 'soul_sand', 'crimson_nylium',
    'basalt_side', 'oak_planks', 'stone',
    # Behind an add-on artist's initials (CueScreen.ARTIST_BLOCKS).
    'spruce_planks', 'cherry_planks', 'bamboo_planks', 'crimson_planks', 'warped_planks', 'bricks', 'mud_bricks',
    'stone_bricks', 'deepslate_tiles', 'amethyst_block', 'terracotta', 'blue_ice', 'copper_block', 'lapis_block',
    'purpur_block', 'quartz_bricks', 'red_sandstone', 'end_stone_bricks', 'honeycomb_block', 'glowstone', 'packed_mud',
}
SPRITES = {'hud/heart/full'}


def catalog():
    text = CATALOG.read_text()
    albums = {m[0]: {'name': m[1], 'texture': m[2]} for m in
              re.findall(r'Album (\w+) = new Album\("([^"]+)", "([^"]+)"\)', text)}
    artists = dict(re.findall(r'String (\w+) = "([^"]+)";', text))
    order = re.search(r'ARTISTS = List\.of\(([^)]*)\)', text).group(1)
    art = dict(re.findall(r'(\w+), (\w+)(?:,|\))', re.search(r'ARTIST_ART = Map\.of\(([^;]*)\);', text).group(1)))
    tracks = []
    for path, title, artist, album, seconds in re.findall(
            r'add\("([^"]+)", "([^"]+)", (\w+), (\w+), ([\d.]+)\);', text):
        tracks.append({'id': 'minecraft:' + path, 'title': title, 'artist': artists[artist],
                       'album': albums[album]['name'], 'art': albums[album]['texture'], 'seconds': float(seconds)})
    return {
        'tracks': tracks,
        'artists': [artists[a.strip()] for a in order.split(',')],
        'artistArt': {artists[k]: albums[v]['texture'] for k, v in art.items()},
        'unknownAlbum': albums['UNKNOWN_ALBUM'],
    }


def pools():
    index = json.loads(INDEX.read_text())['objects']
    digest = index['minecraft/sounds.json']['hash']
    sounds = json.loads((LOOM / 'assets/objects' / digest[:2] / digest).read_text())
    direct, refs = {}, {}
    for event, entry in sounds.items():
        if not event.startswith('music.'):
            continue
        for s in entry['sounds']:
            if isinstance(s, dict) and s.get('type') == 'event':
                refs.setdefault(event, []).append(s['name'])
            else:
                name = s['name'] if isinstance(s, dict) else s
                direct.setdefault(event, []).append('minecraft:' + name)

    def files(event, seen):
        out = list(direct.get(event, []))
        if event in seen:
            return out
        seen.add(event)
        for ref in refs.get(event, []):
            out += [f for f in files(ref, seen) if f not in out]
        return out

    result = {}
    for event in sorted(set(direct) | set(refs)):
        songs = files(event, set())
        if songs:
            result['minecraft:' + event] = songs
    return result


def main():
    if not CLIENT.exists() or not INDEX.exists():
        sys.exit(f'Needs Minecraft {VERSION} in {LOOM}: build the mod once with ./gradlew build.')
    data = catalog()
    data['pools'] = pools()
    data['strings'] = json.loads(LANG.read_text())
    info = json.loads(INFO.read_text())
    # Where each file sits in the jar, so the browser reads it with one range request.
    # The jar's URL names its hash, so the offsets never go stale.
    files = ['assets/minecraft/lang/en_us.json']
    wanted = BLOCKS | {t['art'].split('/', 1)[1] for t in data['tracks']} | {data['unknownAlbum']['texture'].split('/', 1)[1]}
    files += [f'assets/minecraft/textures/block/{b}.png' for b in sorted(wanted)]
    files += [f'assets/minecraft/textures/gui/sprites/{s}.png' for s in sorted(SPRITES)]
    with zipfile.ZipFile(CLIENT) as jar:
        entries = {}
        for name in files:
            z = jar.getinfo(name)
            entries[name] = [z.header_offset, z.compress_size, z.compress_type]
    data['source'] = {'jar': info['downloads']['client']['url'], 'index': info['assetIndex']['url'], 'entries': entries}
    (OUT / 'data.json').write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')))
    print(f"{len(data['tracks'])} songs, {len(data['pools'])} pools, {len(wanted)} blocks")


if __name__ == '__main__':
    main()
