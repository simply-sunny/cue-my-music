#!/usr/bin/env python3
"""Download all YouTube Minecraft music tracks as OGG files.
Uses yt-dlp to download as opus (ogg container), renames to .ogg"""
import subprocess
import os
import time
from pathlib import Path

DOWNLOAD_DIR = Path.home() / "Downloads" / "minecraft-music" / "youtube"
DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)

# (filename_base, search_query)
YT_TRACKS = [
    # C418 Album Tracks
    ("Door", "C418 Door Minecraft Volume Alpha"),
    ("Death", "C418 Death Minecraft Volume Alpha"),
    ("Moog City", "C418 Moog City Minecraft Volume Alpha"),
    ("Excuse", "C418 Excuse Minecraft Volume Alpha"),
    ("Dog", "C418 Dog Minecraft Volume Alpha"),
    ("Ki", "C418 Ki Minecraft Volume Alpha"),
    ("Flake", "C418 Flake Minecraft Volume Alpha"),
    ("Kyoto", "C418 Kyoto Minecraft Volume Alpha"),
    ("Intro", "C418 Intro Minecraft Volume Alpha"),
    ("Equinoxe", "C418 Equinoxe Minecraft Volume Alpha"),
    ("Droopy likes ricochet", "C418 Droopy likes ricochet Minecraft"),
    ("Droopy likes your face", "C418 Droopy likes your face Minecraft"),
    # Garden Awakens
    ("Infinite Spooky Amethyst", "Infinite Spooky Amethyst Minecraft"),
    ("Infinite Spooky Amethyst Slowed Reverb", "Infinite Spooky Amethyst Slowed Reverb"),
    ("Infinite Spooky Amethyst Trumpet", "Infinite Spooky Amethyst Trumpet Version"),
    ("Infinite Spooky Amethyst Sped Up Trumpet", "Infinite Spooky Amethyst Sped Up Trumpet"),
    # Spring to Life
    ("otherside Spring to Life Remix full", "otherside Spring to Life Remix full Minecraft"),
    ("otherside Spring to Life Remix short", "otherside Spring to Life Remix short"),
    ("Spring to Life", "Spring to Life Minecraft Camilo Forero"),
    # Monolism remixes
    ("Echo in the Wind Monolism Remix", "Echo in the Wind Monolism Remix"),
    ("A Familiar Room Monolism Remix", "A Familiar Room Monolism Remix"),
    ("Bromeliad Monolism Remix", "Bromeliad Monolism Remix"),
    ("Crescent Dunes Monolism Remix", "Crescent Dunes Monolism Remix"),
    ("Relic Monolism Remix", "Relic Monolism Remix"),
    # Hyper Potions remixes
    ("Featherfall Hyper Potions Remix", "Featherfall Hyper Potions Remix"),
    ("Precipice Hyper Potions Remix", "Precipice Hyper Potions Remix"),
    ("Tears Hyper Potions Remix", "Tears Hyper Potions Remix"),
    ("Broken Clocks Hyper Potions Remix", "Broken Clocks Hyper Potions Remix"),
    # Synthion remixes
    ("Watcher Synthion Remix", "Watcher Synthion Remix"),
    ("Comforting Memories Synthion Remix", "Comforting Memories Synthion Remix"),
    ("pokopoko Synthion Remix", "pokopoko Synthion Remix"),
    # AIKA remixes
    ("Puzzlebox AIKA Remix", "Puzzlebox AIKA Remix"),
    ("komorebi AIKA Remix", "komorebi AIKA Remix"),
    # Other remixes
    ("Infinite Amethyst Snail's House Remix", "Infinite Amethyst Snail's House Remix"),
    ("Deeper Elliot Hsu Remix", "Deeper Elliot Hsu Remix"),
    ("Eld Unknown Elliot Hsu Remix", "Eld Unknown Elliot Hsu Remix"),
    ("Creator leon chang Remix", "Creator leon chang Remix"),
    ("Bromeliad floopy Remix", "Bromeliad floopy Remix"),
    ("A Familiar Room Turbo Remix", "A Familiar Room Turbo Remix"),
    ("otherside Turbo Remix", "otherside Turbo Remix"),
    ("Labyrinthine meganeko Remix", "Labyrinthine meganeko Remix"),
    ("Relic EX-LYD Remix", "Relic EX-LYD Remix"),
    ("Echo in the Wind floopy Remix", "Echo in the Wind floopy Remix"),
    ("Left to Bloom EX-LYD Remix", "Left to Bloom EX-LYD Remix"),
    ("Aerie meganeko Remix", "Aerie meganeko Remix"),
    # Squeak E. Clean
    ("Comforting Memories Rock Remix", "Comforting Memories Rock Remix"),
    ("Comforting Memories Pop Remix", "Comforting Memories Pop Remix"),
    ("Comforting Memories Orchestral Remix", "Comforting Memories Orchestral Remix"),
    ("Copper Age Trailer", "Minecraft Copper Age Original Trailer Score"),
    ("Mounts of Mayhem Trailer", "Minecraft Mounts of Mayhem Original Trailer Score"),
    ("Chaos Spreads Trailer", "Chaos Spreads Original Trailer Score"),
    ("Chaos Cubed Trailer", "Chaos Cubed Original Trailer Score"),
    # Minecraft Live / Showcase
    ("Minecraft Shape Your World", "Minecraft Shape Your World"),
    ("Happy Ghast Song", "Happy Ghast Song Element Animation"),
    ("Happy Ghast Song Minecraft Live", "Happy Ghast Song Minecraft Live Version"),
    ("20 Million Villager Song", "The 20 Million Villager Song"),
    # James Everingham
    ("Eccentric Balloon", "Eccentric Balloon Minecraft"),
    ("Hazy Lazy", "Hazy Lazy Minecraft"),
    ("Round Clockwork", "Round Clockwork Minecraft"),
    ("Quite Quaint", "Quite Quaint Minecraft"),
    ("What's Up", "What's Up Minecraft"),
    ("Terrific Trees", "Terrific Trees Minecraft"),
    ("Blubbery Shrubbery", "Blubbery Shrubbery Minecraft"),
    ("Unless", "Unless Minecraft"),
    ("Time to Go", "Time to Go Minecraft"),
    ("Vivid Nights", "Vivid Nights Minecraft"),
    ("Great Mountain", "Great Mountain Minecraft"),
    # Minecraft Live 2021/2022
    ("Minecraft Live Opening", "Minecraft Live Opening"),
    ("Legends Segment", "Legends Segment Minecraft Live"),
    ("Interlude", "Interlude Minecraft Live"),
    ("Minecart Ride", "Minecart Ride Minecraft Live"),
    ("Timelapse", "Timelapse Minecraft Live"),
    ("Vanilla Segment", "Vanilla Segment Minecraft Live"),
    ("Closing", "Closing Minecraft Live"),
    # Minecraft Live 2023
    ("Save the Date", "Save the Date Minecraft Live 2023"),
    ("Welcome to Mojang", "Welcome to Mojang Minecraft Live"),
    ("Looking Back and Forward", "Looking Back and Forward Minecraft Live"),
    ("Bundles", "Bundles Minecraft Live 2023"),
    ("Devs One", "Devs One Minecraft Live"),
    ("A Minecraft Music", "A Minecraft Music Minecraft Live"),
    ("Minecraft Experience", "Minecraft Experience Niccolo Pacella"),
    ("Creakstep", "Creakstep Minecraft Live"),
    ("Devs Two", "Devs Two Minecraft Live"),
    ("End of Show", "End of Show Minecraft Live"),
    ("Jens in a Boat", "Jens in a Boat Minecraft Live"),
    # Minecraft Live 2024+
    ("Introduction", "Introduction Niccolo Pacella"),
    ("Welcome", "Welcome Minecraft Live"),
    ("Welcome Back", "Welcome Back Minecraft Live"),
    ("Infinite Interstitial Chicken", "Infinite Interstitial Chicken"),
    ("Spring to Life Again", "Spring to Life Again"),
    ("Spring Diary", "Spring Diary"),
    ("A Segment", "A Segment Niccolo Pacella"),
    ("A Live Event", "A Live Event Niccolo Pacella"),
    ("Infinite Interstitial Enderman", "Infinite Interstitial Enderman"),
    ("Vibrant Visuals", "Vibrant Visuals"),
    ("Vibrant Diary", "Vibrant Diary"),
    ("Infinite Interstitial Allay", "Infinite Interstitial Allay"),
    ("Happy Ghast", "Happy Ghast"),
    ("Ghastly Diary", "Ghastly Diary"),
    ("Ending", "Ending Minecraft Live"),
    ("Copper Golem", "Copper Golem"),
    ("Nautilus", "Nautilus"),
    ("Spears", "Spears"),
    ("Tiny Takeover Segment", "Tiny Takeover Segment"),
    ("Chaos Cubed Segment Pt 1", "Chaos Cubed Segment Pt 1"),
    ("Chaos Cubed Segment Pt 2", "Chaos Cubed Segment Pt 2"),
    # Special
    ("Soothing Synths", "Soothing Synths Monolism"),
    ("Pixel Genesis", "Pixel Genesis Minecraft"),
    ("And Action", "And Action Minecraft 25w14craftmine"),
]


def download_track(filename, query):
    """Download a track using yt-dlp as opus, rename to .ogg"""
    dest = DOWNLOAD_DIR / f"{filename}.ogg"
    if dest.exists() and dest.stat().st_size > 1000:
        return (filename, 'skip')
    
    # Use opus format -> .opus extension, then rename to .ogg
    opus_path = DOWNLOAD_DIR / f"{filename}.opus"
    cmd = [
        "yt-dlp",
        "-x", "--audio-format", "opus",
        "-o", str(opus_path),
        "--no-playlist",
        "--default-search", "ytsearch1",
        query
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if opus_path.exists() and opus_path.stat().st_size > 1000:
            opus_path.rename(dest)
            return (filename, 'ok')
        return (filename, 'fail')
    except Exception as e:
        return (filename, f'error: {e}')


def main():
    print(f"YouTube download batch: {len(YT_TRACKS)} tracks")
    print(f"Output dir: {DOWNLOAD_DIR}")
    existing = len(list(DOWNLOAD_DIR.glob('*.ogg')))
    print(f"Already have {existing} files")
    print()
    
    ok = 0
    fail = 0
    skipped = 0
    
    for i, (filename, query) in enumerate(YT_TRACKS):
        status = download_track(filename, query)
        if status[1] == 'ok':
            ok += 1
            print(f"  [{i+1}/{len(YT_TRACKS)}] OK: {filename}")
        elif status[1] == 'skip':
            skipped += 1
        else:
            fail += 1
            print(f"  [{i+1}/{len(YT_TRACKS)}] FAIL: {filename} ({status[1]})")
        time.sleep(0.5)
    
    total = len(list(DOWNLOAD_DIR.glob('*.ogg')))
    print(f"\n=== Done: {ok} ok, {fail} failed, {skipped} skipped ===")
    print(f"Total files: {total}")


if __name__ == "__main__":
    main()
