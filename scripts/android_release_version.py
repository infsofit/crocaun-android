"""Parsing for Android-only prerelease versions tied to a Windows release."""

from __future__ import annotations

from dataclasses import dataclass
import re

_COMPONENT = r"(?:0|[1-9][0-9]*)"
_ANDROID_RELEASE_VERSION = re.compile(
    rf"^(?P<base>[1-9][0-9]*\.{_COMPONENT}(?:\.{_COMPONENT})?)-android\.(?P<serial>[1-9][0-9]?)$"
)


@dataclass(frozen=True)
class AndroidReleaseVersion:
    version: str
    base_windows_version: str
    serial: int


def parse_android_release_version(value: str) -> AndroidReleaseVersion:
    """Parse `1.11-android.1` or `1.11.1-android.2` (serial 1..99)."""
    match = _ANDROID_RELEASE_VERSION.fullmatch(value)
    if match is None:
        raise ValueError(
            "Android release version must look like `1.11-android.1` or "
            "`1.11.1-android.2`; the Android serial must be 1..99."
        )
    return AndroidReleaseVersion(
        version=value,
        base_windows_version=match.group("base"),
        serial=int(match.group("serial")),
    )
