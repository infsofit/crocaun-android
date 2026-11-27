#!/usr/bin/env python3
"""Choose the next unique Android test-preview version for a Windows base."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.parse
import urllib.request

from android_release_version import parse_android_release_version

API = "https://api.github.com/repos/infsofit"
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")


def next_android_preview_version(base_version: str, tags: list[str]) -> str:
    """Return the next unused `BASE-android.N` version for this Windows base."""
    parse_android_release_version(f"{base_version}-android.1")
    pattern = re.compile(rf"^v{re.escape(base_version)}-android\.([1-9][0-9]?)$")
    highest = 0
    for tag in tags:
        match = pattern.fullmatch(tag)
        if match:
            highest = max(highest, int(match.group(1)))
    if highest >= 99:
        raise ValueError(f"No Android preview suffix remains for Windows version {base_version} (maximum is 99).")
    return f"{base_version}-android.{highest + 1}"


def get_android_tags(base_version: str) -> list[str]:
    """Read existing Android tag refs with the exact Windows-version prefix."""
    prefix = f"v{base_version}-android."
    path = "crocaun-android/git/matching-refs/tags/" + urllib.parse.quote(prefix, safe="")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "crocaun-android-preview-version"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    request = urllib.request.Request(f"{API}/{path}", headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        refs = json.load(response)
    if not isinstance(refs, list):
        raise RuntimeError("GitHub returned an unexpected tag-reference response.")
    return [ref.get("ref", "").removeprefix("refs/tags/") for ref in refs]


def main() -> int:
    if len(sys.argv) != 2:
        raise ValueError("Usage: next_android_preview.py WINDOWS_BASE_VERSION")
    base_version = sys.argv[1]
    version = next_android_preview_version(base_version, get_android_tags(base_version))
    tag = f"v{version}"
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"android_version={version}\n")
            stream.write(f"android_tag={tag}\n")
    else:
        print(f"android_version={version}")
        print(f"android_tag={tag}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Could not choose Android preview version: {exc}", file=sys.stderr)
        raise SystemExit(1)
