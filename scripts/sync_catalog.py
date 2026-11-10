#!/usr/bin/env python3
"""Kavir Store catalog tool.

Reads catalog/sources.json (the registry edited by the admin) and produces catalog/apps.json
(what every store client reads). For each enabled source it:

  * finds the newest release that carries an APK,
  * downloads the APK once (skipped when the release asset is unchanged),
  * reads package name, versionCode, versionName, minSdk and label with aapt2,
  * records the APK's sha256 and its signing-certificate fingerprint,
  * extracts the launcher icon when it is a PNG,
  * fills description / icon / screenshots from the repository when the registry has no override.

Usage:
  sync_catalog.py                 full sync, writes catalog/apps.json
  sync_catalog.py --validate      schema + remote checks only, never writes (used by CI on PRs)
  sync_catalog.py --only o/n      sync a single repository
Environment: GITHUB_TOKEN (optional but recommended), GITHUB_REPOSITORY, ANDROID_HOME.
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES_PATH = ROOT / "catalog" / "sources.json"
APPS_PATH = ROOT / "catalog" / "apps.json"
ICONS_DIR = ROOT / "catalog" / "icons"

API = "https://api.github.com"
UA = "KavirStore-Sync/1.0"
CATEGORIES = {"tools", "games", "social", "media", "productivity", "education", "communication", "other"}
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
MAX_APK_BYTES = 400 * 1024 * 1024
SCHEMA = 1


class SyncError(Exception):
    pass


class RateLimited(SyncError):
    pass


# --------------------------------------------------------------------------------------------------
# GitHub access
# --------------------------------------------------------------------------------------------------

def _token() -> str | None:
    return os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")


def api_get(path: str):
    url = path if path.startswith("http") else API + path
    headers = {"Accept": "application/vnd.github+json", "User-Agent": UA, "X-GitHub-Api-Version": "2022-11-28"}
    tok = _token()
    if tok:
        headers["Authorization"] = f"Bearer {tok}"
    last: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (403, 429) and e.headers.get("X-RateLimit-Remaining") == "0":
                raise RateLimited(f"GitHub API rate limit reached while calling {url}")
            if e.code >= 500 and attempt < 2:
                last = e
                time.sleep(2 * (attempt + 1))
                continue
            raise SyncError(f"HTTP {e.code} for {url}")
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last = e
            time.sleep(2 * (attempt + 1))
    raise SyncError(f"network error for {url}: {last}")


def download(url: str, dest: Path) -> tuple[str, int]:
    """Streams url to dest. Returns (sha256 hex, size). The auth token is never sent to download hosts."""
    if not url.startswith("https://github.com/"):
        raise SyncError(f"refusing to download from {url}")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    h = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
            while True:
                chunk = r.read(1024 * 256)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_APK_BYTES:
                    raise SyncError("APK is larger than the allowed maximum")
                h.update(chunk)
                f.write(chunk)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        raise SyncError(f"download failed: {e}")
    return h.hexdigest(), size


# --------------------------------------------------------------------------------------------------
# Validation of sources.json
# --------------------------------------------------------------------------------------------------

def validate_sources(data) -> list[str]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["sources.json must be a JSON object"]
    if data.get("schema") != SCHEMA:
        errors.append(f"schema must be {SCHEMA}")
    apps = data.get("apps")
    if not isinstance(apps, list):
        return errors + ["'apps' must be a list"]
    seen: set[str] = set()
    for i, a in enumerate(apps):
        where = f"apps[{i}]"
        if not isinstance(a, dict):
            errors.append(f"{where} must be an object")
            continue
        repo = a.get("repo")
        if not isinstance(repo, str) or not REPO_RE.match(repo):
            errors.append(f"{where}.repo must look like 'owner/name'")
            continue
        where = f"{where} ({repo})"
        if repo.lower() in seen:
            errors.append(f"{where}: duplicate repo")
        seen.add(repo.lower())
        if not isinstance(a.get("enabled", True), bool):
            errors.append(f"{where}.enabled must be true/false")
        if a.get("category", "other") not in CATEGORIES:
            errors.append(f"{where}.category must be one of {sorted(CATEGORIES)}")
        for key in ("name", "name_fa", "description", "description_fa", "icon", "asset_pattern"):
            v = a.get(key)
            if v is not None and not isinstance(v, str):
                errors.append(f"{where}.{key} must be a string or null")
        icon = a.get("icon")
        if isinstance(icon, str) and icon and not icon.startswith("https://"):
            errors.append(f"{where}.icon must be an https:// URL")
        shots = a.get("screenshots", [])
        if not isinstance(shots, list) or any(not isinstance(s, str) or not s.startswith("https://") for s in shots):
            errors.append(f"{where}.screenshots must be a list of https:// URLs")
        pat = a.get("asset_pattern")
        if isinstance(pat, str) and pat:
            try:
                re.compile(pat)
            except re.error as e:
                errors.append(f"{where}.asset_pattern is not a valid regex: {e}")
    return errors


# --------------------------------------------------------------------------------------------------
# Release / asset selection
# --------------------------------------------------------------------------------------------------

def pick_asset(assets: list[dict], pattern: str | None = None) -> dict | None:
    apks = [a for a in assets if str(a.get("name", "")).lower().endswith(".apk") and a.get("state", "uploaded") == "uploaded"]
    if pattern:
        rx = re.compile(pattern)
        apks = [a for a in apks if rx.search(a["name"])]
    if not apks:
        return None

    def score(a: dict):
        n = a["name"].lower()
        s = 0
        if "universal" in n:
            s -= 30
        if "debug" in n:
            s += 50
        if "unsigned" in n:
            s += 40
        if any(x in n for x in ("x86", "armeabi", "arm64", "x64")) and "universal" not in n:
            s += 10
        if "release" in n:
            s -= 5
        return (s, n)

    return sorted(apks, key=score)[0]


def pick_release(releases: list[dict], allow_pre: bool = False, pattern: str | None = None):
    for rel in releases:
        if rel.get("draft"):
            continue
        if rel.get("prerelease") and not allow_pre:
            continue
        asset = pick_asset(rel.get("assets", []), pattern)
        if asset:
            return rel, asset
    return None


# --------------------------------------------------------------------------------------------------
# APK inspection (aapt2 / apksigner from the Android SDK build-tools)
# --------------------------------------------------------------------------------------------------

def find_tool(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/usr/local/lib/android/sdk"]
    for root in roots:
        if not root:
            continue
        cands = sorted(glob.glob(os.path.join(root, "build-tools", "*", name)), key=_version_key, reverse=True)
        if cands:
            return cands[0]
    return None


def _version_key(path: str):
    ver = Path(path).parent.name
    return [int(p) if p.isdigit() else 0 for p in re.split(r"[.\-]", ver)]


def parse_badging(text: str) -> dict:
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)'(?: versionName='([^']*)')?", text)
    if not m:
        raise SyncError("aapt2 output has no package line")
    out = {
        "package": m.group(1),
        "version_code": int(m.group(2)),
        "version_name": m.group(3) or m.group(2),
    }
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    out["min_sdk"] = int(sdk.group(1)) if sdk else 1
    label = re.search(r"^application-label:'([^']*)'", text, re.M)
    out["label"] = label.group(1) if label else None
    label_fa = re.search(r"^application-label-fa:'([^']*)'", text, re.M)
    out["label_fa"] = label_fa.group(1) if label_fa else None
    icons = [(int(d), p) for d, p in re.findall(r"^application-icon-(\d+):'([^']+)'", text, re.M)]
    pngs = sorted([i for i in icons if i[1].lower().endswith(".png")], reverse=True)
    out["icon_path"] = pngs[0][1] if pngs else None
    return out


def apk_info(apk: Path) -> dict:
    aapt2 = find_tool("aapt2")
    if not aapt2:
        raise SyncError("aapt2 not found (set ANDROID_HOME to an SDK with build-tools)")
    res = subprocess.run([aapt2, "dump", "badging", str(apk)], capture_output=True, text=True)
    if res.returncode != 0:
        raise SyncError(f"aapt2 failed: {res.stderr.strip()[:300]}")
    return parse_badging(res.stdout)


def signer_sha256(apk: Path) -> str | None:
    tool = find_tool("apksigner")
    if not tool:
        print("  warning: apksigner not found, signer fingerprint skipped")
        return None
    res = subprocess.run([tool, "verify", "--print-certs", str(apk)], capture_output=True, text=True)
    if res.returncode != 0:
        raise SyncError("APK signature is invalid or the APK is unsigned")
    m = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", res.stdout)
    return m.group(1).lower() if m else None


def extract_icon(apk: Path, icon_path: str | None, dest: Path) -> bool:
    if not icon_path:
        return False
    try:
        with zipfile.ZipFile(apk) as z:
            data = z.read(icon_path)
    except (KeyError, zipfile.BadZipFile):
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return True


# --------------------------------------------------------------------------------------------------
# Repository metadata (icon / screenshots guessed from the repo tree)
# --------------------------------------------------------------------------------------------------

def repo_assets(repo: str, branch: str) -> tuple[str | None, list[str]]:
    try:
        tree = api_get(f"/repos/{repo}/git/trees/{branch}?recursive=1").get("tree", [])
    except SyncError:
        return None, []
    paths = [t["path"] for t in tree if t.get("type") == "blob"]
    raw = f"https://raw.githubusercontent.com/{repo}/{branch}/"

    icon = None
    for p in paths:
        if re.search(r"fastlane/metadata/android/[^/]+/images/icon\.(png|jpg|webp)$", p):
            icon = raw + p
            break
    if not icon:
        for p in paths:
            if re.search(r"(^|/)(icon|logo|app_icon)\.png$", p, re.I) and p.count("/") <= 2:
                icon = raw + p
                break

    shots = sorted(
        p for p in paths
        if re.search(r"fastlane/metadata/android/(en-US|en)/images/phoneScreenshots/[^/]+\.(png|jpg|webp)$", p)
    )
    if not shots:
        shots = sorted(p for p in paths if re.search(r"^(screenshots|docs/screenshots)/[^/]+\.(png|jpg|webp)$", p, re.I))
    return icon, [raw + p for p in shots[:8]]


# --------------------------------------------------------------------------------------------------
# Building catalog entries
# --------------------------------------------------------------------------------------------------

def self_repo() -> str:
    return os.environ.get("GITHUB_REPOSITORY") or "kavirkobra19/KavirStore"


def icon_url(package: str, version_code: int) -> str:
    return f"https://raw.githubusercontent.com/{self_repo()}/main/catalog/icons/{package}.png?v={version_code}"


def trim_changelog(body: str | None) -> str:
    return (body or "").replace("\r\n", "\n").strip()[:4000]


def build_release(rel: dict, asset: dict, meta: dict, sha: str, size: int, signer: str | None) -> dict:
    return {
        "tag": rel["tag_name"],
        "version_name": meta["version_name"],
        "version_code": meta["version_code"],
        "apk_url": asset["browser_download_url"],
        "apk_name": asset["name"],
        "size": size,
        "sha256": sha,
        "min_sdk": meta["min_sdk"],
        "changelog": trim_changelog(rel.get("body")),
        "published_at": rel.get("published_at") or rel.get("created_at") or "",
        "signer": signer,
        "asset_id": asset["id"],
        "asset_updated_at": asset.get("updated_at"),
    }


def process_source(src: dict, existing: dict | None, seen_packages: dict[str, str]) -> dict:
    repo = src["repo"]
    info = api_get(f"/repos/{repo}")
    releases = api_get(f"/repos/{repo}/releases?per_page=20")
    found = pick_release(releases, bool(src.get("prerelease")), src.get("asset_pattern") or None)
    if not found:
        raise SyncError("no (non-draft) release with an APK asset")
    rel, asset = found

    cached = (existing or {}).get("release")
    auto = (existing or {}).get("auto")
    if (
        cached and auto
        and cached.get("asset_id") == asset["id"]
        and cached.get("asset_updated_at") == asset.get("updated_at")
        and cached.get("tag") == rel["tag_name"]
    ):
        release = dict(cached)
        release["changelog"] = trim_changelog(rel.get("body"))
        package = existing["package_name"]
        auto = dict(auto)
        print(f"  unchanged ({rel['tag_name']})")
    else:
        print(f"  downloading {asset['name']} ({rel['tag_name']})")
        with tempfile.TemporaryDirectory() as td:
            apk = Path(td) / "app.apk"
            sha, size = download(asset["browser_download_url"], apk)
            meta = apk_info(apk)
            signer = signer_sha256(apk)
            package = meta["package"]
            has_icon = extract_icon(apk, meta["icon_path"], ICONS_DIR / f"{package}.png")
            release = build_release(rel, asset, meta, sha, size, signer)
        auto = {"name": meta["label"] or info.get("name") or repo.split("/")[1], "name_fa": meta["label_fa"],
                "icon": icon_url(package, meta["version_code"]) if has_icon else None}

    if package in seen_packages and seen_packages[package] != repo:
        raise SyncError(f"package {package} is already registered by {seen_packages[package]}")
    seen_packages[package] = repo

    default_branch = info.get("default_branch") or "main"
    repo_icon, repo_shots = repo_assets(repo, default_branch)
    owner_avatar = (info.get("owner") or {}).get("avatar_url")

    name = src.get("name") or auto.get("name") or repo.split("/")[1]
    entry = {
        "id": package,
        "name": name,
        "name_fa": src.get("name_fa") or auto.get("name_fa"),
        "package_name": package,
        "description": src.get("description") or info.get("description") or "",
        "description_fa": src.get("description_fa"),
        "category": src.get("category", "other"),
        "icon": src.get("icon") or auto.get("icon") or repo_icon or owner_avatar,
        "screenshots": src.get("screenshots") or repo_shots,
        "repo": repo,
        "repo_url": info.get("html_url") or f"https://github.com/{repo}",
        "featured": bool(src.get("featured", False)),
        "enabled": True,
        "release": release,
        "auto": auto,
    }
    return entry


# --------------------------------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------------------------------

def load_json(path: Path, default):
    if not path.exists():
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def validate_remote(sources: list[dict]) -> list[str]:
    problems: list[str] = []
    for src in sources:
        if not src.get("enabled", True):
            continue
        repo = src["repo"]
        try:
            api_get(f"/repos/{repo}")
            rels = api_get(f"/repos/{repo}/releases?per_page=20")
            if not pick_release(rels, bool(src.get("prerelease")), src.get("asset_pattern") or None):
                print(f"warning: {repo}: no release with an APK yet")
            else:
                print(f"ok: {repo}")
        except RateLimited as e:
            print(f"warning: {e}; skipping remote checks")
            break
        except SyncError as e:
            problems.append(f"{repo}: {e}")
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", action="store_true", help="validate only, never write")
    ap.add_argument("--only", help="sync only this owner/name")
    args = ap.parse_args(argv)

    sources = load_json(SOURCES_PATH, None)
    errors = validate_sources(sources)
    if errors:
        print("sources.json is invalid:")
        for e in errors:
            print(f"  - {e}")
        return 1
    apps_in = sources["apps"]

    if args.validate:
        problems = validate_remote(apps_in)
        try:
            cat = load_json(APPS_PATH, {})
            assert isinstance(cat.get("apps", []), list)
        except Exception as e:  # noqa: BLE001
            problems.append(f"apps.json is not valid: {e}")
        for p in problems:
            print(f"error: {p}")
        print("validation finished" + (" with errors" if problems else " successfully"))
        return 1 if problems else 0

    old = load_json(APPS_PATH, {"apps": []})
    existing = {a["repo"]: a for a in old.get("apps", []) if "repo" in a}
    result: list[dict] = []
    seen_packages: dict[str, str] = {}
    failures: list[str] = []

    for src in apps_in:
        repo = src["repo"]
        if not src.get("enabled", True):
            print(f"- {repo}: disabled, hidden from the catalog")
            continue
        if args.only and args.only.lower() != repo.lower():
            if repo in existing:
                result.append(existing[repo])
                seen_packages[existing[repo]["package_name"]] = repo
            continue
        print(f"- {repo}")
        try:
            result.append(process_source(src, existing.get(repo), seen_packages))
        except RateLimited as e:
            print(f"  rate limited: {e}")
            failures.append(repo)
            if repo in existing:
                result.append(existing[repo])
        except SyncError as e:
            print(f"  skipped: {e}")
            failures.append(repo)
            if repo in existing:
                # Keep serving the last good data, but apply admin edits that need no network.
                kept = dict(existing[repo])
                kept["featured"] = bool(src.get("featured", False))
                kept["category"] = src.get("category", kept.get("category", "other"))
                result.append(kept)

    result.sort(key=lambda a: a["name"].lower())
    new_catalog = {"schema": SCHEMA, "generated_at": old.get("generated_at", ""), "apps": result}
    if new_catalog["apps"] != old.get("apps") or old.get("schema") != SCHEMA:
        new_catalog["generated_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        write_json(APPS_PATH, new_catalog)
        print(f"catalog updated: {len(result)} app(s)")
    else:
        print("catalog unchanged")

    if failures:
        print("some sources could not be synced: " + ", ".join(failures))
    return 0


if __name__ == "__main__":
    sys.exit(main())
