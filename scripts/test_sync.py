#!/usr/bin/env python3
"""Offline unit tests for sync_catalog.py (no network, no Android SDK needed)."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sync_catalog as sc  # noqa: E402

BADGING = """package: name='org.example.app' versionCode='42' versionName='1.4.2' platformBuildVersionCode='35' compileSdkVersion='35'
sdkVersion:'26'
targetSdkVersion:'35'
application-label:'Example'
application-label-fa:'نمونه'
application-icon-160:'res/mipmap-mdpi-v4/ic_launcher.png'
application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
application-icon-65534:'res/mipmap-anydpi-v26/ic_launcher.xml'
"""


def asset(name, aid=1, state="uploaded"):
    return {"name": name, "id": aid, "state": state, "browser_download_url": f"https://github.com/o/n/releases/download/v1/{name}"}


class PickAsset(unittest.TestCase):
    def test_prefers_universal_over_abi_and_debug(self):
        a = sc.pick_asset([asset("app-arm64-v8a-release.apk"), asset("app-universal-release.apk"), asset("app-debug.apk")])
        self.assertEqual(a["name"], "app-universal-release.apk")

    def test_ignores_non_apk_and_unfinished(self):
        self.assertIsNone(sc.pick_asset([asset("notes.txt"), asset("x.apk", state="starter")]))

    def test_pattern(self):
        a = sc.pick_asset([asset("a-release.apk"), asset("b-release.apk")], r"^b-")
        self.assertEqual(a["name"], "b-release.apk")

    def test_unsigned_is_last_resort(self):
        a = sc.pick_asset([asset("app-release-unsigned.apk"), asset("app-release.apk")])
        self.assertEqual(a["name"], "app-release.apk")


class PickRelease(unittest.TestCase):
    def rel(self, tag, assets, draft=False, pre=False):
        return {"tag_name": tag, "draft": draft, "prerelease": pre, "assets": assets}

    def test_skips_drafts_prereleases_and_apkless(self):
        rels = [
            self.rel("v4", [asset("a.apk")], draft=True),
            self.rel("v3", [asset("a.apk")], pre=True),
            self.rel("v2", [asset("notes.txt")]),
            self.rel("v1", [asset("a.apk")]),
        ]
        rel, a = sc.pick_release(rels)
        self.assertEqual(rel["tag_name"], "v1")

    def test_allow_prerelease(self):
        rels = [self.rel("v3", [asset("a.apk")], pre=True), self.rel("v1", [asset("a.apk")])]
        self.assertEqual(sc.pick_release(rels, True)[0]["tag_name"], "v3")

    def test_none(self):
        self.assertIsNone(sc.pick_release([self.rel("v1", [])]))


class Badging(unittest.TestCase):
    def test_parse(self):
        m = sc.parse_badging(BADGING)
        self.assertEqual(m["package"], "org.example.app")
        self.assertEqual(m["version_code"], 42)
        self.assertEqual(m["version_name"], "1.4.2")
        self.assertEqual(m["min_sdk"], 26)
        self.assertEqual(m["label"], "Example")
        self.assertEqual(m["label_fa"], "نمونه")
        self.assertEqual(m["icon_path"], "res/mipmap-xxxhdpi-v4/ic_launcher.png")

    def test_aapt2_minsdkversion_and_webp_icon(self):
        text = BADGING.replace("sdkVersion:'26'", "minSdkVersion:'23'") + "application-icon-720:'res/x/ic.webp'\n"
        m = sc.parse_badging(text)
        self.assertEqual(m["min_sdk"], 23)  # must not pick targetSdkVersion (35)
        self.assertEqual(m["icon_path"], "res/x/ic.webp")

    def test_missing_package(self):
        with self.assertRaises(sc.SyncError):
            sc.parse_badging("nothing here")


class ValidateSources(unittest.TestCase):
    def good(self):
        return {"schema": 1, "apps": [{"repo": "o/n", "enabled": True, "category": "tools", "screenshots": ["https://x/y.png"]}]}

    def test_good(self):
        self.assertEqual(sc.validate_sources(self.good()), [])

    def test_bad_repo(self):
        d = self.good()
        d["apps"][0]["repo"] = "not a repo"
        self.assertTrue(sc.validate_sources(d))

    def test_duplicate(self):
        d = self.good()
        d["apps"].append({"repo": "O/N"})
        self.assertTrue(any("duplicate" in e for e in sc.validate_sources(d)))

    def test_bad_category_and_regex_and_urls(self):
        d = self.good()
        d["apps"][0].update({"category": "nope", "asset_pattern": "(", "icon": "http://insecure", "screenshots": ["ftp://x"]})
        self.assertEqual(len(sc.validate_sources(d)), 4)

    def test_wrong_schema(self):
        self.assertTrue(sc.validate_sources({"schema": 2, "apps": []}))

    def test_repository_sources_file_is_valid(self):
        with open(sc.SOURCES_PATH, encoding="utf-8") as f:
            self.assertEqual(sc.validate_sources(json.load(f)), [])


class Misc(unittest.TestCase):
    def test_download_refuses_foreign_hosts(self):
        with self.assertRaises(sc.SyncError):
            sc.download("https://evil.example/app.apk", Path(tempfile.gettempdir()) / "x.apk")

    def test_changelog_trim(self):
        self.assertEqual(sc.trim_changelog(None), "")
        self.assertEqual(len(sc.trim_changelog("x" * 9999)), 4000)

    def test_build_release_shape(self):
        meta = sc.parse_badging(BADGING)
        rel = {"tag_name": "v1", "body": "hi", "published_at": "2026-01-01T00:00:00Z"}
        r = sc.build_release(rel, asset("a.apk", 7), meta, "ab" * 32, 123, "cd" * 32)
        for key in ("tag", "version_name", "version_code", "apk_url", "size", "sha256", "min_sdk", "changelog", "published_at", "signer"):
            self.assertIn(key, r)
        self.assertEqual(r["version_code"], 42)

    def test_extract_icon(self):
        import zipfile
        with tempfile.TemporaryDirectory() as td:
            apk = Path(td) / "a.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("res/icon.png", b"PNGDATA")
            out = Path(td) / "icons"
            self.assertEqual(sc.extract_icon(apk, "res/icon.png", out, "org.p"), "org.p.png")
            self.assertEqual((out / "org.p.png").read_bytes(), b"PNGDATA")
            self.assertIsNone(sc.extract_icon(apk, "res/missing.png", out, "org.p"))
            self.assertIsNone(sc.extract_icon(apk, None, out, "org.p"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
