#!/usr/bin/env python3
"""Download all Minecraft music tracks to ~/Downloads/minecraft-music/

Source priority:
- Java .ogg from Mojang assets (game files, music discs)
- YouTube for everything else (C418 album tracks, remixes, trailer scores, etc.)
"""

import json
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# === CONFIG ===
DOWNLOAD_DIR = Path.home() / "Downloads" / "minecraft-music"
JAVA_OGG_DIR = DOWNLOAD_DIR / "java-ogg"
YOUTUBE_DIR = DOWNLOAD_DIR / "youtube"
ASSET_INDEX_PATH = "/tmp/minecraft_assets_26.2.json"

# Mojang resource server base URL
MOJANG_RESOURCE_URL = "https://resources.download.minecraft.net"

# Load asset index
with open(ASSET_INDEX_PATH) as f:
    ASSET_INDEX = json.load(f)
OBJECTS = ASSET_INDEX["objects"]


def get_asset_hash(filename: str) -> str | None:
    """Get the full hash for an asset filename, or None if not found."""
    if filename in OBJECTS:
        return OBJECTS[filename]["hash"]
    return None


def get_download_url(filename: str) -> str | None:
    """Get the direct download URL for a Minecraft asset."""
    h = get_asset_hash(filename)
    if h:
        return f"{MOJANG_RESOURCE_URL}/{h[:2]}/{h}/{filename}"
    return None


def download_file(url: str, dest: Path, retries: int = 3) -> bool:
    """Download a file using curl. Returns True on success."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(retries):
        try:
            result = subprocess.run(
                ["curl", "-L", "-o", str(dest), "-s", "-S", "-f", url],
                capture_output=True, text=True, timeout=120
            )
            if result.returncode == 0 and dest.exists() and dest.stat().st_size > 0:
                return True
            print(f"  FAILED: {url} (attempt {attempt+1}/{retries}): {result.stderr[:100]}")
        except Exception as e:
            print(f"  ERROR: {url}: {e}")
        time.sleep(1)
    return False


def download_ytdlp(url: str, dest: Path, search: str | None = None) -> bool:
    """Download using yt-dlp. Returns True on success."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    # Use search query if URL is just a search term
    if search:
        cmd = [
            "yt-dlp", "-x", "--audio-format", "ogg",
            "-o", str(dest),
            "ytsearch1:" + search
        ]
    else:
        cmd = [
            "yt-dlp", "-x", "--audio-format", "ogg",
            "-o", str(dest),
            url
        ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if result.returncode == 0 and dest.exists():
            return True
        print(f"  YTDLP FAILED: {url}: {result.stderr[:200]}")
        return False
    except Exception as e:
        print(f"  YTDLP ERROR: {url}: {e}")
        return False


# === SONG MANIFEST ===
# Format: (name, artist, asset_path_in_index, source_type, source_name, yt_search)
# asset_path_in_index is relative path like "minecraft/sounds/music/game/minecraft.ogg"
# source_type: "java-ogg", "youtube", or "youtube-search"

SONGS = [
    # ====== JAVA OGG - Game music (26.2) ======
    ("Minecraft", "C418", "minecraft/sounds/music/game/minecraft.ogg", "java-ogg", "Minecraft 26.2"),
    ("Sweden", "C418", "minecraft/sounds/music/game/sweden.ogg", "java-ogg", "Minecraft 26.2"),
    ("Aria Math", "C418", "minecraft/sounds/music/game/creative/aria_math.ogg", "java-ogg", "Minecraft 26.2"),
    ("Biome Fest", "C418", "minecraft/sounds/music/game/creative/biome_fest.ogg", "java-ogg", "Minecraft 26.2"),
    ("Blind Spots", "C418", "minecraft/sounds/music/game/creative/blind_spots.ogg", "java-ogg", "Minecraft 26.2"),
    ("Dreiton", "C418", "minecraft/sounds/music/game/creative/dreiton.ogg", "java-ogg", "Minecraft 26.2"),
    ("Haunt Muskie", "C418", "minecraft/sounds/music/game/creative/haunt_muskie.ogg", "java-ogg", "Minecraft 26.2"),
    ("Taswell", "C418", "minecraft/sounds/music/game/creative/taswell.ogg", "java-ogg", "Minecraft 26.2"),
    ("Crescent Dunes", "Monolism", "minecraft/sounds/music/game/crescent_dunes.ogg", "java-ogg", "Minecraft 26.2"),
    ("Bromeliad", "C418", "minecraft/sounds/music/game/bromeliad.ogg", "java-ogg", "Minecraft 26.2"),
    ("Comforting Memories", "C418", "minecraft/sounds/music/game/comforting_memories.ogg", "java-ogg", "Minecraft 26.2"),
    ("Echo in the Wind", "Monolism", "minecraft/sounds/music/game/echo_in_the_wind.ogg", "java-ogg", "Minecraft 26.2"),
    ("Featherfall", "Hyper Potions", "minecraft/sounds/music/game/featherfall.ogg", "java-ogg", "Minecraft 26.2"),
    ("Left to Bloom", "EX-LYD", "minecraft/sounds/music/game/left_to_bloom.ogg", "java-ogg", "Minecraft 26.2"),
    ("Endless", "Anamanaguchi", "minecraft/sounds/music/game/endless.ogg", "java-ogg", "Minecraft 26.2"),
    ("Eld Unknown", "Elliot Hsu", "minecraft/sounds/music/game/eld_unknown.ogg", "java-ogg", "Minecraft 26.2"),
    ("Deeper", "Elliot Hsu", "minecraft/sounds/music/game/deeper.ogg", "java-ogg", "Minecraft 26.2"),
    ("Infinite Amethyst", "Jukio Kallio", "minecraft/sounds/music/game/infinite_amethyst.ogg", "java-ogg", "Minecraft 26.2"),
    ("Komorebi", "AIKA", "minecraft/sounds/music/game/komorebi.ogg", "java-ogg", "Minecraft 26.2"),
    ("Yakusoku", "leon chang", "minecraft/sounds/music/game/yakusoku.ogg", "java-ogg", "Minecraft 26.2"),
    ("Stand Tall", "C418", "minecraft/sounds/music/game/stand_tall.ogg", "java-ogg", "Minecraft 26.2"),
    ("Nightly", "C418", "minecraft/sounds/music/game/nightly.ogg", "java-ogg", "Minecraft 26.2"),
    ("One More Day", "C418", "minecraft/sounds/music/game/one_more_day.ogg", "java-ogg", "Minecraft 26.2"),
    ("Aerie", "meganeko", "minecraft/sounds/music/game/swamp/aerie.ogg", "java-ogg", "Minecraft 26.2"),
    ("Labyrinthine", "meganeko", "minecraft/sounds/music/game/swamp/labyrinthine.ogg", "java-ogg", "Minecraft 26.2"),
    ("Fireflies", "C418", "minecraft/sounds/music/game/fireflies.ogg", "java-ogg", "Minecraft 26.2"),
    ("Floating Dream", "C418", "minecraft/sounds/music/game/floating_dream.ogg", "java-ogg", "Minecraft 26.2"),
    ("Living Mice", "C418", "minecraft/sounds/music/game/living_mice.ogg", "java-ogg", "Minecraft 26.2"),
    ("Memories", "C418", "minecraft/sounds/music/game/memories.ogg", "java-ogg", "Minecraft 26.2"),
    ("Mice on Venus", "C418", "minecraft/sounds/music/game/mice_on_venus.ogg", "java-ogg", "Minecraft 26.2"),
    ("OS Piano", "C418", "minecraft/sounds/music/game/os_piano.ogg", "java-ogg", "Minecraft 26.2"),
    ("Subwoofer Lullaby", "C418", "minecraft/sounds/music/game/subwoofer_lullaby.ogg", "java-ogg", "Minecraft 26.2"),
    ("Dry Hands", "C418", "minecraft/sounds/music/game/dry_hands.ogg", "java-ogg", "Minecraft 26.2"),
    ("Wet Hands", "C418", "minecraft/sounds/music/game/wet_hands.ogg", "java-ogg", "Minecraft 26.2"),
    ("Key", "C418", "minecraft/sounds/music/game/key.ogg", "java-ogg", "Minecraft 26.2"),
    ("Oxygene", "C418", "minecraft/sounds/music/game/oxygene.ogg", "java-ogg", "Minecraft 26.2"),
    ("Haggstrom", "C418", "minecraft/sounds/music/game/haggstrom.ogg", "java-ogg", "Minecraft 26.2"),
    ("Home", "C418", "minecraft/sounds/music/game/home.ogg", "java-ogg", "Minecraft 26.2"),
    ("Ebb", "C418", "minecraft/sounds/music/game/ebb.ogg", "java-ogg", "Minecraft 26.2"),
    ("Danny", "C418", "minecraft/sounds/music/game/danny.ogg", "java-ogg", "Minecraft 26.2"),
    ("Ancestry", "C418", "minecraft/sounds/music/game/ancestry.ogg", "java-ogg", "Minecraft 26.2"),
    ("An Ordinary Day", "C418", "minecraft/sounds/music/game/an_ordinary_day.ogg", "java-ogg", "Minecraft 26.2"),
    ("Below and Above", "C418", "minecraft/sounds/music/game/below_and_above.ogg", "java-ogg", "Minecraft 26.2"),
    ("Clark", "C418", "minecraft/sounds/music/game/clark.ogg", "java-ogg", "Minecraft 26.2"),
    ("Broken Clocks", "C418", "minecraft/sounds/music/game/broken_clocks.ogg", "java-ogg", "Minecraft 26.2"),
    ("Pigstep", "Lena Raine", "minecraft/sounds/records/pigstep.ogg", "java-ogg", "Minecraft 26.2"),
    ("Precipice", "Hyper Potions", "minecraft/sounds/records/precipice.ogg", "java-ogg", "Minecraft 26.2"),
    ("Relic", "C418", "minecraft/sounds/records/relic.ogg", "java-ogg", "Minecraft 26.2"),
    ("Tears", "Hyper Potions", "minecraft/sounds/records/tears.ogg", "java-ogg", "Minecraft 26.2"),
    ("Creator", "C418", "minecraft/sounds/records/creator.ogg", "java-ogg", "Minecraft 26.2"),
    ("Creator Music Box", "C418", "minecraft/sounds/records/creator_music_box.ogg", "java-ogg", "Minecraft 26.2"),
    ("Otherside", "C418", "minecraft/sounds/records/otherside.ogg", "java-ogg", "Minecraft 26.2"),
    ("Minecraft (Game)", "C418", "minecraft/sounds/music/menu/moog_city_2.ogg", "java-ogg", "Minecraft 26.2"),
    ("Beginning", "C418", "minecraft/sounds/music/menu/beginning_2.ogg", "java-ogg", "Minecraft 26.2"),
    ("Mutation", "C418", "minecraft/sounds/music/menu/mutation.ogg", "java-ogg", "Minecraft 26.2"),
    ("Floating Trees", "C418", "minecraft/sounds/music/menu/floating_trees.ogg", "java-ogg", "Minecraft 26.2"),
    ("The End", "C418", "minecraft/sounds/music/game/end/the_end.ogg", "java-ogg", "Minecraft 26.2"),
    ("Alpha", "C418", "minecraft/sounds/music/game/end/alpha.ogg", "java-ogg", "Minecraft 26.2"),
    ("Boss", "C418", "minecraft/sounds/music/game/end/boss.ogg", "java-ogg", "Minecraft 26.2"),
    ("Rubedo", "C418", "minecraft/sounds/music/game/nether/nether_wastes/rubedo.ogg", "java-ogg", "Minecraft 26.2"),
    ("Warmth", "C418", "minecraft/sounds/music/game/nether/warmth.ogg", "java-ogg", "Minecraft 26.2"),
    ("Dead Voxel", "C418", "minecraft/sounds/music/game/nether/dead_voxel.ogg", "java-ogg", "Minecraft 26.2"),
    ("Ballad of the Cats", "C418", "minecraft/sounds/music/game/nether/ballad_of_the_cats.ogg", "java-ogg", "Minecraft 26.2"),
    ("Concrete Halls", "C418", "minecraft/sounds/music/game/nether/concrete_halls.ogg", "java-ogg", "Minecraft 26.2"),
    ("So Below", "C418", "minecraft/sounds/music/game/nether/soulsand_valley/so_below.ogg", "java-ogg", "Minecraft 26.2"),
    ("Chrysopoeia", "C418", "minecraft/sounds/music/game/nether/crimson_forest/chrysopoeia.ogg", "java-ogg", "Minecraft 26.2"),
    ("Axolotl", "C418", "minecraft/sounds/music/game/water/axolotl.ogg", "java-ogg", "Minecraft 26.2"),
    ("Dragon Fish", "C418", "minecraft/sounds/music/game/water/dragon_fish.ogg", "java-ogg", "Minecraft 26.2"),
    ("Shuniji", "C418", "minecraft/sounds/music/game/water/shuniji.ogg", "java-ogg", "Minecraft 26.2"),
    ("Wending", "C418", "minecraft/sounds/music/game/wending.ogg", "java-ogg", "Minecraft 26.2"),
    ("Shores", "C418", "minecraft/sounds/music/game/shores.ogg", "java-ogg", "Minecraft 26.2"),
    ("Lily Pad", "C418", "minecraft/sounds/music/game/lilypad.ogg", "java-ogg", "Minecraft 26.2"),
    ("Waggy Tongue", "C418", "minecraft/sounds/music/game/wet_hands.ogg", "java-ogg", "Minecraft 26.2"),
    ("Firebugs", "C418", "minecraft/sounds/music/game/swamp/firebugs.ogg", "java-ogg", "Minecraft 26.2"),
    ("Warding", "C418", "minecraft/sounds/music/game/key.ogg", "java-ogg", "Minecraft 26.2"),
    # Music disc .ogg files
    ("11", "C418", "minecraft/sounds/records/11.ogg", "java-ogg", "Minecraft 26.2"),
    ("13", "C418", "minecraft/sounds/records/13.ogg", "java-ogg", "Minecraft 26.2"),
    ("5", "C418", "minecraft/sounds/records/5.ogg", "java-ogg", "Minecraft 26.2"),
    ("Blocks", "C418", "minecraft/sounds/records/blocks.ogg", "java-ogg", "Minecraft 26.2"),
    ("Bounce", "C418", "minecraft/sounds/records/bounce.ogg", "java-ogg", "Minecraft 26.2"),
    ("Cat", "C418", "minecraft/sounds/records/cat.ogg", "java-ogg", "Minecraft 26.2"),
    ("Chirp", "C418", "minecraft/sounds/records/chirp.ogg", "java-ogg", "Minecraft 26.2"),
    ("Far", "C418", "minecraft/sounds/records/far.ogg", "java-ogg", "Minecraft 26.2"),
    ("Mall", "C418", "minecraft/sounds/records/mall.ogg", "java-ogg", "Minecraft 26.2"),
    ("Mellohi", "C418", "minecraft/sounds/records/mellohi.ogg", "java-ogg", "Minecraft 26.2"),
    ("Stal", "C418", "minecraft/sounds/records/stal.ogg", "java-ogg", "Minecraft 26.2"),
    ("Strad", "C418", "minecraft/sounds/records/strad.ogg", "java-ogg", "Minecraft 26.2"),
    ("Wait", "C418", "minecraft/sounds/records/wait.ogg", "java-ogg", "Minecraft 26.2"),
    ("Ward", "C418", "minecraft/sounds/records/ward.ogg", "java-ogg", "Minecraft 26.2"),
    ("Lava Chicken", "C418", "minecraft/sounds/records/lava_chicken.ogg", "java-ogg", "Minecraft 26.2"),
    # ====== YOUTUBE ONLY - C418 Album Tracks ======
    ("Door", "C418", None, "youtube", "C418 official", "C418 Door Minecraft Volume Alpha"),
    ("Death", "C418", None, "youtube", "C418 official", "C418 Death Minecraft Volume Alpha"),
    ("Moog City", "C418", None, "youtube", "C418 official", "C418 Moog City Minecraft Volume Alpha"),
    ("Excuse", "C418", None, "youtube", "C418 official", "C418 Excuse Minecraft Volume Alpha"),
    ("Dog", "C418", None, "youtube", "C418 official", "C418 Dog Minecraft Volume Alpha"),
    ("Ki", "C418", None, "youtube", "C418 official", "C418 Ki Minecraft Volume Alpha"),
    ("Flake", "C418", None, "youtube", "C418 official", "C418 Flake Minecraft Volume Alpha"),
    ("Kyoto", "C418", None, "youtube", "C418 official", "C418 Kyoto Minecraft Volume Alpha"),
    ("Intro", "C418", None, "youtube", "C418 official", "C418 Intro Minecraft Volume Alpha"),
    ("Équinoxe", "C418", None, "youtube", "C418 official", "C418 Équinoxe Minecraft Volume Alpha"),
    ("Droopy likes ricochet", "C418", None, "youtube", "C418 official", "C418 Droopy likes ricochet Minecraft Volume Alpha"),
    ("Droopy likes your face", "C418", None, "youtube", "C418 official", "C418 Droopy likes your face Minecraft Volume Alpha"),
    # ====== YOUTUBE ONLY - Garden Awakens ======
    ("Infinite Spooky Amethyst", "Jukio Kallio", None, "youtube", "Minecraft Topic", "Infinite Spooky Amethyst Minecraft"),
    ("Infinite Spooky Amethyst (Slowed + Reverb)", "Jukio Kallio", None, "youtube", "Minecraft Topic", "Infinite Spooky Amethyst Slowed Reverb"),
    ("Infinite Spooky Amethyst (Trumpet Version)", "Jukio Kallio", None, "youtube", "Minecraft Topic", "Infinite Spooky Amethyst Trumpet"),
    ("Infinite Spooky Amethyst (Sped Up Trumpet Version)", "Jukio Kallio", None, "youtube", "Minecraft Topic", "Infinite Spooky Amethyst Sped Up Trumpet"),
    # ====== YOUTUBE ONLY - Spring to Life ======
    ("otherside (Spring to Life Remix) [full]", "Camilo Forero", None, "youtube", "Minecraft Topic", "otherside Spring to Life Remix full"),
    ("otherside (Spring to Life Remix) [short]", "Camilo Forero", None, "youtube", "Minecraft Topic", "otherside Spring to Life Remix short"),
    ("Spring to Life", "Camilo Forero / David Murillo R.", None, "youtube", "Minecraft Topic", "Spring to Life Minecraft"),
    # ====== YOUTUBE ONLY - Remixes (Pixel Drift, etc.) ======
    ("Echo in the Wind (Monolism Remix)", "Monolism", None, "youtube", "Minecraft official", "Echo in the Wind Monolism Remix"),
    ("A Familiar Room (Monolism Remix)", "Monolism", None, "youtube", "Minecraft official", "A Familiar Room Monolism Remix"),
    ("Bromeliad (Monolism Remix)", "Monolism", None, "youtube", "Minecraft official", "Bromeliad Monolism Remix"),
    ("Crescent Dunes (Monolism Remix)", "Monolism", None, "youtube", "Minecraft official", "Crescent Dunes Monolism Remix"),
    ("Relic (Monolism Remix)", "Monolism", None, "youtube", "Minecraft official", "Relic Monolism Remix"),
    ("Featherfall (Hyper Potions Remix)", "Hyper Potions", None, "youtube", "Minecraft official", "Featherfall Hyper Potions Remix"),
    ("Watcher (Synthion Remix)", "Synthion", None, "youtube", "Minecraft official", "Watcher Synthion Remix"),
    ("Puzzlebox (AIKA Remix)", "AIKA", None, "youtube", "Minecraft official", "Puzzlebox AIKA Remix"),
    ("komorebi (AIKA Remix)", "AIKA", None, "youtube", "Minecraft official", "komorebi AIKA Remix"),
    ("pokopoko (Synthion Remix)", "Synthion", None, "youtube", "Minecraft official", "pokopoko Synthion Remix"),
    ("yakusoku (leon chang Remix)", "leon chang", None, "youtube", "Minecraft official", "yakusoku leon chang Remix"),
    ("Infinite Amethyst (Snail's House Remix)", "Snail's House", None, "youtube", "Minecraft official", "Infinite Amethyst Snail's House Remix"),
    ("Deeper (Elliot Hsu Remix)", "Elliot Hsu", None, "youtube", "Minecraft official", "Deeper Elliot Hsu Remix"),
    ("Eld Unknown (Elliot Hsu Remix)", "Elliot Hsu", None, "youtube", "Minecraft official", "Eld Unknown Elliot Hsu Remix"),
    ("Creator (leon chang Remix)", "leon chang", None, "youtube", "Minecraft official", "Creator leon chang Remix"),
    ("Precipice (Hyper Potions Remix)", "Hyper Potions", None, "youtube", "Minecraft official", "Precipice Hyper Potions Remix"),
    ("Bromeliad (floopy Remix)", "floopy", None, "youtube", "Minecraft Topic", "Bromeliad floopy Remix"),
    ("Comforting Memories (Synthion Remix)", "Synthion", None, "youtube", "Minecraft Topic", "Comforting Memories Synthion Remix"),
    ("Broken Clocks (Hyper Potions Remix)", "Hyper Potions", None, "youtube", "Minecraft Topic", "Broken Clocks Hyper Potions Remix"),
    ("A Familiar Room (Turbo Remix)", "Turbo", None, "youtube", "Minecraft Topic", "A Familiar Room Turbo Remix"),
    ("Labyrinthine (meganeko Remix)", "meganeko", None, "youtube", "Minecraft Topic", "Labyrinthine meganeko Remix"),
    ("Relic (EX-LYD Remix)", "EX-LYD", None, "youtube", "Minecraft Topic", "Relic EX-LYD Remix"),
    ("Echo in the Wind (floopy Remix)", "floopy", None, "youtube", "Minecraft Topic", "Echo in the Wind floopy Remix"),
    ("Tears (Hyper Potions Remix)", "Hyper Potions", None, "youtube", "Minecraft Topic", "Tears Hyper Potions Remix"),
    ("otherside (Turbo Remix)", "Turbo", None, "youtube", "Minecraft Topic", "otherside Turbo Remix"),
    ("Left to Bloom (EX-LYD Remix)", "EX-LYD", None, "youtube", "Minecraft Topic", "Left to Bloom EX-LYD Remix"),
    ("Aerie (meganeko Remix)", "meganeko", None, "youtube", "Minecraft Topic", "Aerie meganeko Remix"),
    # ====== YOUTUBE ONLY - Trailer Scores ======
    ("Comforting Memories (Rock Remix)", "Squeak E. Clean", None, "youtube", "Minecraft Topic", "Comforting Memories Rock Remix"),
    ("Comforting Memories (Pop Remix)", "Squeak E. Clean", None, "youtube", "Minecraft Topic", "Comforting Memories Pop Remix"),
    ("Comforting Memories (Orchestral Remix)", "Squeak E. Clean", None, "youtube", "Minecraft Topic", "Comforting Memories Orchestral Remix"),
    ("Minecraft: The Copper Age (Original Trailer Score)", "Squeak E. Clean", None, "youtube", "Minecraft Topic", "Minecraft Copper Age Trailer Score"),
    ("Minecraft: Mounts of Mayhem (Original Trailer Score)", "Squeak E. Clean", None, "youtube", "Minecraft Topic", "Minecraft Mounts of Mayhem Trailer Score"),
    ("Chaos Spreads (Original Trailer Score)", "Adam Schiff / Kyle Ogren / Bleeding Fingers", None, "youtube", "Minecraft Topic", "Chaos Spreads Minecraft Trailer Score"),
    ("Chaos Cubed (Original Trailer Score)", "Adam Schiff / Kyle Ogren / Bleeding Fingers", None, "youtube", "Minecraft Topic", "Chaos Cubed Minecraft Trailer Score"),
    # ====== YOUTUBE ONLY - Minecraft Live / Showcase ======
    ("Minecraft: Shape Your World", "Douglas Haines", None, "youtube", "Minecraft Topic", "Minecraft Shape Your World"),
    ("Happy Ghast Song", "Element Animation", None, "youtube", "Minecraft Topic", "Happy Ghast Song Element Animation"),
    ("Happy Ghast Song (Minecraft Live Version)", "Element Animation", None, "youtube", "Minecraft Topic", "Happy Ghast Song Minecraft Live"),
    ("The 20 Million Villager Song", "Element Animation", None, "youtube", "Minecraft Topic", "20 Million Villager Song"),
    # ====== YOUTUBE ONLY - James Everingham tracks ======
    ("Eccentric Balloon", "James Everingham", None, "youtube", "Minecraft Topic", "Eccentric Balloon James Everingham"),
    ("Hazy & Lazy", "James Everingham", None, "youtube", "Minecraft Topic", "Hazy Lazy James Everingham"),
    ("Round Clockwork", "James Everingham", None, "youtube", "Minecraft Topic", "Round Clockwork James Everingham"),
    ("Quite Quaint", "James Everingham", None, "youtube", "Minecraft Topic", "Quite Quaint James Everingham"),
    ("What's Up?", "James Everingham", None, "youtube", "Minecraft Topic", "What's Up James Everingham"),
    ("Terrific Trees", "James Everingham", None, "youtube", "Minecraft Topic", "Terrific Trees James Everingham"),
    ("Blubbery Shrubbery", "James Everingham", None, "youtube", "Minecraft Topic", "Blubbery Shrubbery James Everingham"),
    ("Unless", "James Everingham", None, "youtube", "Minecraft Topic", "Unless James Everingham"),
    ("Time to Go!", "James Everingham", None, "youtube", "Minecraft Topic", "Time to Go James Everingham"),
    ("Vivid Nights", "James Everingham", None, "youtube", "Minecraft Topic", "Vivid Nights James Everingham"),
    ("Great Mountain", "James Everingham", None, "youtube", "Minecraft Topic", "Great Mountain James Everingham"),
    # ====== YOUTUBE ONLY - Minecraft Live 2021/2022 soundtracks ======
    ("Opening", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Minecraft Live Opening"),
    ("Legends Segment", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Legends Segment Minecraft Live"),
    ("Interlude", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Interlude Minecraft Live"),
    ("Minecart Ride", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Minecart Ride Minecraft Live"),
    ("Timelapse", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Timelapse Minecraft Live"),
    ("Vanilla Segment", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Vanilla Segment Minecraft Live"),
    ("Closing", "Camilo Forero / Joseph S. Djafar", None, "youtube", "Minecraft Topic", "Closing Minecraft Live"),
    # ====== YOUTUBE ONLY - Minecraft Live 2023 ======
    ("Save the Date", "Camilo Forero", None, "youtube", "Minecraft Topic", "Save the Date Minecraft Live 2023"),
    ("Welcome to Mojang", "Camilo Forero", None, "youtube", "Minecraft Topic", "Welcome to Mojang Minecraft Live 2023"),
    ("Looking Back and Forward", "Camilo Forero", None, "youtube", "Minecraft Topic", "Looking Back and Forward Minecraft Live 2023"),
    ("Bundles", "Camilo Forero", None, "youtube", "Minecraft Topic", "Bundles Minecraft Live 2023"),
    ("Devs One", "Adam Lukas", None, "youtube", "Minecraft Topic", "Devs One Minecraft Live 2023"),
    ("A Minecraft Music", "Camilo Forero / David Murillo R.", None, "youtube", "Minecraft Topic", "A Minecraft Music Minecraft Live 2023"),
    ("Minecraft Experience", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Minecraft Experience Minecraft Live 2023"),
    ("Creakstep", "Camilo Forero", None, "youtube", "Minecraft Topic", "Creakstep Minecraft Live 2023"),
    ("Devs Two", "Camilo Forero", None, "youtube", "Minecraft Topic", "Devs Two Minecraft Live 2023"),
    ("End of Show", "Camilo Forero", None, "youtube", "Minecraft Topic", "End of Show Minecraft Live 2023"),
    ("Jens in a Boat", "Camilo Forero", None, "youtube", "Minecraft Topic", "Jens in a Boat Minecraft Live 2023"),
    # ====== YOUTUBE ONLY - Minecraft Live 2024/2025/2026 ======
    ("Introduction", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Introduction Minecraft Live"),
    ("Welcome", "Camilo Forero", None, "youtube", "Minecraft Topic", "Welcome Minecraft Live"),
    ("Welcome Back", "Camilo Forero", None, "youtube", "Minecraft Topic", "Welcome Back Minecraft Live"),
    ("Infinite Interstitial - Chicken", "Camilo Forero", None, "youtube", "Minecraft Topic", "Infinite Interstitial Chicken"),
    ("Spring to Life Again", "Camilo Forero", None, "youtube", "Minecraft Topic", "Spring to Life Again"),
    ("Spring Diary", "Camilo Forero", None, "youtube", "Minecraft Topic", "Spring Diary"),
    ("A Segment", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "A Segment Minecraft Live"),
    ("A Live Event", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "A Live Event Minecraft Live"),
    ("Infinite Interstitial - Enderman", "Camilo Forero", None, "youtube", "Minecraft Topic", "Infinite Interstitial Enderman"),
    ("Vibrant Visuals", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Vibrant Visuals Minecraft Live"),
    ("Vibrant Diary", "Camilo Forero", None, "youtube", "Minecraft Topic", "Vibrant Diary"),
    ("Infinite Interstitial - Allay", "Camilo Forero", None, "youtube", "Minecraft Topic", "Infinite Interstitial Allay"),
    ("Happy Ghast", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Happy Ghast Minecraft Live"),
    ("Ghastly Diary", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Ghastly Diary"),
    ("Ending", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Ending Minecraft Live"),
    ("Copper Golem", "Camilo Forero", None, "youtube", "Minecraft Topic", "Copper Golem"),
    ("Nautilus", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Nautilus Minecraft Live"),
    ("Spears", "Camilo Forero", None, "youtube", "Minecraft Topic", "Spears Minecraft Live"),
    ("Tiny Takeover Segment", "Niccolo Pacella", None, "youtube", "Minecraft Topic", "Tiny Takeover Segment"),
    ("Chaos Cubed Segment, Pt. 1", "Camilo Forero", None, "youtube", "Minecraft Topic", "Chaos Cubed Segment Pt 1"),
    ("Chaos Cubed Segment, Pt. 2", "Camilo Forero", None, "youtube", "Minecraft Topic", "Chaos Cubed Segment Pt 2"),
    ("Soothing Synths", "Monolism", None, "youtube", "Minecraft official", "Soothing Synths Minecraft"),
    ("Pixel Genesis", "C418", None, "youtube", "Minecraft official", "Pixel Genesis Minecraft"),
    # ====== C418 Album - Volume Alpha/Beta individual tracks ======
    ("And Action!", "Tone Deaf Rebellion", None, "youtube", "Minecraft Wiki", "And Action Minecraft 25w14craftmine"),
]


def download_java_ogg(song):
    """Download a Java .ogg file from Mojang."""
    name, artist, asset_path, source_type, source_name = song[:5]
    filename = os.path.basename(asset_path)
    dest = JAVA_OGG_DIR / filename
    
    if dest.exists() and dest.stat().st_size > 1000:
        print(f"  SKIP (exists): {name} by {artist}")
        return True
    
    url = get_download_url(asset_path)
    if not url:
        print(f"  NOT IN ASSETS: {name} by {artist} ({asset_path})")
        return False
    
    print(f"  Downloading: {name} by {artist} -> {filename}")
    success = download_file(url, dest)
    if success:
        print(f"  OK: {name}")
    return success


def download_youtube(song):
    """Download a YouTube track."""
    name, artist, _, source_type, source_name, yt_search = song[5:]
    safe_name = name.replace(" ", "_").replace("/", "_").replace("(", "").replace(")", "")
    dest = YOUTUBE_DIR / f"{safe_name}.ogg"
    
    if dest.exists() and dest.stat().st_size > 1000:
        print(f"  SKIP (exists): {name} by {artist}")
        return True
    
    print(f"  Downloading: {name} by {artist}")
    success = download_ytdlp(None, dest, yt_search)
    if success:
        print(f"  OK: {name}")
    return success


def main():
    print(f"=== Minecraft Music Downloader ===")
    print(f"Java .ogg dir: {JAVA_OGG_DIR}")
    print(f"YouTube dir: {YOUTUBE_DIR}")
    print(f"Total songs: {len(SONGS)}")
    
    java_songs = [s for s in SONGS if s[3] == "java-ogg"]
    youtube_songs = [s for s in SONGS if s[3] == "youtube"]
    print(f"Java .ogg: {len(java_songs)}, YouTube: {len(youtube_songs)}")
    print()
    
    # Download Java .ogg files (parallel)
    print("=" * 60)
    print("PHASE 1: Downloading Java .ogg files from Mojang...")
    print("=" * 60)
    success_java = 0
    fail_java = 0
    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = {executor.submit(download_java_ogg, s): s for s in java_songs}
        for future in as_completed(futures):
            if future.result():
                success_java += 1
            else:
                fail_java += 1
    
    print(f"\nJava .ogg: {success_java} succeeded, {fail_java} failed")
    
    # Download YouTube tracks (sequential due to rate limits)
    print("\n" + "=" * 60)
    print("PHASE 2: Downloading YouTube tracks...")
    print("=" * 60)
    success_yt = 0
    fail_yt = 0
    for song in youtube_songs:
        if download_youtube(song):
            success_yt += 1
        else:
            fail_yt += 1
        time.sleep(1)  # Be polite to YouTube
    
    print(f"\nYouTube: {success_yt} succeeded, {fail_yt} failed")
    print(f"\n=== Done! Files in {DOWNLOAD_DIR} ===")


if __name__ == "__main__":
    main()
