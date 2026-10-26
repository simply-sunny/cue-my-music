#!/usr/bin/env python3
"""Draft the Modrinth project and version from MODRINTH.md. Nothing is submitted for review.

Creates the project as a draft if the slug isn't taken yet (with the icon and the listing
body from MODRINTH.md), then uploads the built jar as a draft version if that version number
isn't there yet. Submitting the project for review is done by hand on modrinth.com.

Usage: MODRINTH_API_KEY=... python3 scripts/publish-modrinth.py [--dry-run]
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API = "https://api.modrinth.com/v2"
USER_AGENT = "simply-sunny/cue-my-music (github.com/simply-sunny/cue-my-music)"
ROOT = Path(__file__).resolve().parent.parent
VERSION = "1.0.0"
SLUG = "cue-my-music"

# jar suffix -> (label, Minecraft versions)
JARS = {
    "26.3": ("26.3", ["26.3"]),
}
LOADERS = ["fabric", "quilt"]
DEPENDENCIES = [
    {"project_id": "P7dR8mSH", "dependency_type": "required"},  # Fabric API
    {"project_id": "mOgUt4GM", "dependency_type": "required"},  # Mod Menu
]


def request(method, path, data=None, content_type=None, api=API):
    req = urllib.request.Request(api + path, data=data, method=method)
    req.add_header("Authorization", os.environ["MODRINTH_API_KEY"])
    req.add_header("User-Agent", USER_AGENT)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        sys.exit(f"{method} {path} -> {e.code}: {e.read().decode()}")


def multipart(data, files):
    boundary = uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
             f"Content-Type: application/json\r\n\r\n{json.dumps(data)}\r\n".encode()]
    for name, (path, mime) in files.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{path.name}"\r\n'
                     f"Content-Type: {mime}\r\n\r\n".encode())
        parts.append(path.read_bytes())
        parts.append(b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def sheet():
    """Project settings table and listing body from MODRINTH.md."""
    text = (ROOT / "MODRINTH.md").read_text()
    head, body = text.split("\n---\n", 1)
    fields = dict(re.findall(r"^\| ([^|]+?) \| (.+?) \|$", head, re.M))
    return fields, body.strip()


def changelog():
    text = (ROOT / "CHANGELOG.md").read_text()
    match = re.search(rf"^## {re.escape(VERSION)}\b.*?\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return match.group(1).strip()


def main():
    dry_run = "--dry-run" in sys.argv
    fields, body = sheet()
    summary = fields["Summary"]
    categories = re.findall(r"`([^`]+)`", fields["Featured categories"])
    icon = ROOT / "src/main/resources/assets/cuemymusic/icon.png"

    project = request("GET", f"/project/{SLUG}")
    if project is None:
        data = {
            "slug": SLUG,
            "title": "Cue My Music",
            "description": summary,
            "body": body,
            "categories": categories,
            "client_side": "required",
            "server_side": "unsupported",
            "project_type": "mod",
            "license_id": "MIT",
            "source_url": "https://github.com/simply-sunny/cue-my-music",
            "issues_url": "https://github.com/simply-sunny/cue-my-music/issues",
            "wiki_url": "https://github.com/simply-sunny/cue-my-music#use",
            "is_draft": True,
            "initial_versions": [],
        }
        if dry_run:
            print("would create draft project", json.dumps({**data, "body": f"<{len(body)} chars>"}))
            return
        payload, content_type = multipart(data, {"icon": (icon, "image/png")})
        project = request("POST", "/project", payload, content_type)
        print(f"created draft project {project['id']} ({project['slug']})")
    else:
        print(f"project {project['id']} exists, status {project['status']}")

    # Draft versions aren't listed by the API, so the ones already uploaded are the IDs recorded in MODRINTH.md.
    existing = {v["name"] for v in request("GET", f"/project/{project['id']}/version") or []}
    recorded = dict(re.findall(r"^\| `([^`]+\.jar)` \| `([A-Za-z0-9]{8})` \|$", (ROOT / "MODRINTH.md").read_text(), re.M))
    notes = changelog()
    for suffix, (label, game_versions) in JARS.items():
        name = f"Cue My Music {VERSION} ({label})"
        jar = ROOT / "build" / "libs" / f"cue-my-music-{suffix}-{VERSION}.jar"
        draft = recorded.get(jar.name)
        if name in existing or (draft and request("GET", f"/version/{draft}")):
            print(f"{name} already uploaded")
            continue
        if not jar.is_file():
            sys.exit(f"missing {jar}, run ./gradlew buildAll")
        data = {
            "project_id": project["id"],
            "name": name,
            "version_number": VERSION,
            "changelog": notes,
            "dependencies": DEPENDENCIES,
            "game_versions": game_versions,
            "version_type": "release",
            "loaders": LOADERS,
            "featured": True,
            "status": "draft",
            "file_parts": ["file"],
            "primary_file": "file",
        }
        if dry_run:
            print("would upload", jar.name, json.dumps({**data, "changelog": f"<{len(notes)} chars>"}))
            continue
        payload, content_type = multipart(data, {"file": (jar, "application/java-archive")})
        version = request("POST", "/version", payload, content_type)
        # Client or server side is set per version, and only through v3.
        request("PATCH", f"/version/{version['id']}", json.dumps({"environment": "client_only"}).encode(),
                "application/json", api=API.replace("/v2", "/v3"))
        print(f"{jar.name} -> draft version {version['id']}; add it to the version table in MODRINTH.md")


if __name__ == "__main__":
    main()
