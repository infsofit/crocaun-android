#!/usr/bin/env python3
"""Validate an Android-only prerelease version before GitHub Actions publishes it."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

from android_release_version import parse_android_release_version
from discover_releases import windows_version_for

API = "https://api.github.com/repos/infsofit"
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")


def get_json(path: str):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "crocaun-android-release-validator"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    request = urllib.request.Request(f"{API}/{path}", headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def exists(path: str) -> bool:
    try:
        get_json(path)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise
    return True


def main() -> int:
    if len(sys.argv) != 2:
        raise ValueError("Usage: validate_android_suffix_release.py VERSION")

    parsed = parse_android_release_version(sys.argv[1])
    windows_tag = f"v{parsed.base_windows_version}"
    android_tag = f"v{parsed.version}"

    try:
        windows_release = get_json(f"crocaun/releases/tags/{urllib.parse.quote(windows_tag, safe='')}")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise ValueError(f"Windows release {windows_tag} does not exist.") from error
        raise

    if windows_release.get("draft") or windows_release.get("prerelease"):
        raise ValueError(f"Windows release {windows_tag} must be published and stable.")
    if windows_version_for(windows_tag) != parsed.base_windows_version:
        raise ValueError(
            f"Windows tag {windows_tag} does not match its source appVersion; refusing to publish."
        )

    android_release_path = f"crocaun-android/releases/tags/{urllib.parse.quote(android_tag, safe='')}"
    android_ref_path = f"crocaun-android/git/ref/tags/{urllib.parse.quote(android_tag, safe='')}"
    if exists(android_release_path) or exists(android_ref_path):
        raise ValueError(f"Android tag {android_tag} already exists; releases are never overwritten.")

    values = {
        "android_version": parsed.version,
        "android_tag": android_tag,
        "base_windows_version": parsed.base_windows_version,
    }
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as output:
            for key, value in values.items():
                output.write(f"{key}={value}\n")
    else:
        for key, value in values.items():
            print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Android suffix release validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
