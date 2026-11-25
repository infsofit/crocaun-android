from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_release_version import parse_android_release_version


class AndroidReleaseVersionTest(unittest.TestCase):
    def test_parses_android_release_based_on_two_component_windows_version(self):
        parsed = parse_android_release_version("1.11-android.1")
        self.assertEqual(parsed.base_windows_version, "1.11")
        self.assertEqual(parsed.serial, 1)

    def test_parses_android_release_based_on_three_component_windows_version(self):
        parsed = parse_android_release_version("1.11.2-android.12")
        self.assertEqual(parsed.base_windows_version, "1.11.2")
        self.assertEqual(parsed.serial, 12)

    def test_rejects_plain_windows_version(self):
        with self.assertRaises(ValueError):
            parse_android_release_version("1.11")

    def test_rejects_zero_or_out_of_range_serial(self):
        for value in ("1.11-android.0", "1.11-android.100"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_android_release_version(value)

    def test_rejects_leading_zero_and_v_prefix(self):
        for value in ("01.11-android.1", "v1.11-android.1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_android_release_version(value)


if __name__ == "__main__":
    unittest.main()
