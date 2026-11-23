#!/usr/bin/env python3
"""Resolve stable Windows Crocaun releases that still need an Android preview."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request

API = "https://api.github.com/repos/infsofit"
VERSION_TAG = re.compile(r"^v([0-9]+\.[0-9]+(?:\.[0-9]+)?)$")
APP_VERSION = re.compile(r'const\s+appVersion\s*=\s*"([0-9]+\.[0-9]+(?:\.[0-9]+)?)"')
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")


def get_json(url: str):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "crocaun-android-release-sync"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def paginated(path: str):
    page = 1
    while True:
        values = get_json(f"{API}/{path}?per_page=100&page={page}")
        if not isinstance(values, list):
            raise RuntimeError(f"Unexpected GitHub API response for {path}")
        yield from values
        if len(values) < 100:
            break
        page += 1


def windows_version_for(tag: str) -> str | None:
    url = f"https://raw.githubusercontent.com/infsofit/crocaun/{tag}/gui_windows.go"
    try:
        headers = {"User-Agent": "crocaun-android-release-sync"}
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            source = response.read().decode("utf-8", errors="replace")
    except (urllib.error.HTTPError, urllib.error.URLError):
        return None
    match = APP_VERSION.search(source)
    return match.group(1) if match else None


def main() -> int:
    releases = list(paginated("crocaun/releases"))
    android_releases = list(paginated("crocaun-android/releases"))
    android_tags = {
        ref["ref"].removeprefix("refs/tags/")
        for ref in paginated("crocaun-android/git/matching-refs/tags/v")
        if ref.get("ref", "").startswith("refs/tags/")
    }

    release_by_tag = {release.get("tag_name"): release for release in android_releases}
    valid_windows = []
    mismatches = []
    for release in releases:
        tag = release.get("tag_name", "")
        match = VERSION_TAG.fullmatch(tag)
        if not match or release.get("draft") or release.get("prerelease"):
            continue
        source_version = windows_version_for(tag)
        if source_version != match.group(1):
            mismatches.append(f"{tag}: tag version does not match gui_windows.go ({source_version or 'unreadable'})")
            continue
        valid_windows.append((tag, match.group(1), release))

    if not valid_windows:
        raise RuntimeError("No published stable Windows release with a matching gui_windows.go version was found.")

    # The public Releases endpoint is newest-first; the first validated entry is canonical for build-only runs.
    latest_tag, latest_version, _ = valid_windows[0]
    candidates = []
    for tag, version, _ in reversed(valid_windows):
        android_release = release_by_tag.get(tag)
        expected_asset = f"crocaun-android-{tag}-preview.apk"
        has_asset = android_release is not None and any(
            asset.get("name") == expected_asset for asset in android_release.get("assets", [])
        )
        if has_asset:
            continue
        candidates.append({"tag": tag, "version": version, "tag_exists": tag in android_tags})

    for mismatch in mismatches:
        print(f"Skipping Windows release: {mismatch}", file=sys.stderr)
    print(f"Latest validated Windows release: {latest_tag}", file=sys.stderr)
    print(f"Android releases to publish or repair: {len(candidates)}", file=sys.stderr)

    output = os.environ.get("GITHUB_OUTPUT")
    if not output:
        print(json.dumps({"version_name": latest_version, "candidates": candidates}, separators=(",", ":")))
    else:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"version_name={latest_version}\n")
            stream.write("candidates=" + json.dumps(candidates, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Release discovery failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
