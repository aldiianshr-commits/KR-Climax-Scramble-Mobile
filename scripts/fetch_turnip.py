#!/usr/bin/env python3
"""Download a Mesa Turnip (Adreno 6xx/7xx Vulkan) driver package for adrenotools.

Usage: fetch_turnip.py <output_dir>

Writes <output_dir>/turnip.zip and <output_dir>/SOURCE.txt. The package must be
an adrenotools zip (meta.json + libvulkan_freedreno.so). Turnip is Mesa (MIT),
so bundling it in the APK is allowed.

Set TURNIP_URL to force a specific zip. Otherwise the newest suitable asset from
the release feeds below is used. Exits 0 without a driver if nothing could be
fetched: the app then falls back to the system Qualcomm driver and the user can
drop a zip into /KRiderClimax/drivers/.
"""
import io
import json
import os
import pathlib
import re
import sys
import urllib.request
import zipfile

FEEDS = [
    "K11MCH1/AdrenoToolsDrivers",
    "The412Banner/Banners-Turnip",
]
SKIP = re.compile(r"a8xx|handle|a7xx_only|debug", re.I)


def get(url: str, accept: str = "application/vnd.github+json") -> bytes:
    req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "kr-climax-ci"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and "api.github.com" in url:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def valid_package(blob: bytes) -> dict | None:
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            names = z.namelist()
            meta_name = next((n for n in names if n.lower().endswith("meta.json")), None)
            if not meta_name:
                return None
            meta = json.loads(z.read(meta_name))
            lib = meta.get("libraryName")
            if not lib or lib not in [pathlib.PurePosixPath(n).name for n in names]:
                return None
            return meta
    except Exception:
        return None


def score(name: str) -> int:
    # Prefer the plain/general build over forced Gmem/Sysmem/experimental variants.
    s = 0
    low = name.lower()
    for bad in ("gmem", "sysmem", "autotuner", "exp", "test", "hack", "perf_fix"):
        if bad in low:
            s += 1
    return s


def candidates():
    forced = os.environ.get("TURNIP_URL")
    if forced:
        yield forced, forced
        return
    for repo in FEEDS:
        try:
            releases = json.loads(get(f"https://api.github.com/repos/{repo}/releases?per_page=15"))
        except Exception as e:  # noqa: BLE001
            print(f"[turnip] cannot list {repo}: {e}")
            continue
        for rel in releases:
            assets = [a for a in rel.get("assets", [])
                      if a["name"].lower().endswith(".zip")
                      and "turnip" in a["name"].lower()
                      and not SKIP.search(a["name"])]
            for a in sorted(assets, key=lambda a: score(a["name"])):
                yield a["browser_download_url"], f"{repo} @ {rel.get('tag_name')} / {a['name']}"


def main() -> None:
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    tried = 0
    for url, label in candidates():
        tried += 1
        if tried > 8:
            break
        try:
            blob = get(url, accept="application/octet-stream")
        except Exception as e:  # noqa: BLE001
            print(f"[turnip] download failed {label}: {e}")
            continue
        meta = valid_package(blob)
        if not meta:
            print(f"[turnip] not an adrenotools package: {label}")
            continue
        (out / "turnip.zip").write_bytes(blob)
        (out / "SOURCE.txt").write_text(
            f"source: {label}\nurl: {url}\nname: {meta.get('name')}\n"
            f"driverVersion: {meta.get('driverVersion')}\nlibraryName: {meta.get('libraryName')}\n"
        )
        print(f"[turnip] bundled {label} ({meta.get('name')}, {len(blob)} bytes)")
        return
    print("[turnip] WARNING: no Turnip package bundled; app will use the system driver")


if __name__ == "__main__":
    main()
