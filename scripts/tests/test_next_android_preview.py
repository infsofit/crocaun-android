from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from next_android_preview import next_android_preview_version


class NextAndroidPreviewTest(unittest.TestCase):
    def test_starts_at_one_when_no_android_suffix_exists(self):
        self.assertEqual(next_android_preview_version("1.11", ["v1.11"]), "1.11-android.1")

    def test_increments_after_highest_matching_suffix(self):
        tags = ["v1.11-android.1", "v1.11-android.4", "v1.10-android.9", "v1.11"]
        self.assertEqual(next_android_preview_version("1.11", tags), "1.11-android.5")

    def test_rejects_invalid_windows_base_version(self):
        with self.assertRaises(ValueError):
            next_android_preview_version("v1.11", [])

    def test_stops_after_supported_suffix_range(self):
        with self.assertRaises(ValueError):
            next_android_preview_version("1.11", ["v1.11-android.99"])


if __name__ == "__main__":
    unittest.main()
