#!/usr/bin/env python3
"""Apply the KR Climax Scramble layer on top of a pinned Eden checkout.

Usage: apply_overlay.py <eden_dir> [--version-name X]

Every text edit asserts that its anchor exists exactly once, so an Eden
upgrade that moves things around fails loudly here instead of producing a
silently broken APK.
"""
import argparse
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OVERLAY = ROOT / "overlay" / "main"

APP_NAME = "KR Climax Scramble"
APP_ID = "com.krclimax.scramble"


def replace_once(text: str, old: str, new: str, where: str) -> str:
    count = text.count(old)
    if count != 1:
        sys.exit(f"[apply_overlay] anchor found {count}x in {where}: {old[:80]!r}")
    return text.replace(old, new)


def copy_overlay(main_dir: pathlib.Path) -> None:
    for src in OVERLAY.rglob("*"):
        if src.is_dir():
            continue
        dst = main_dir / src.relative_to(OVERLAY)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print(f"  + {dst.relative_to(main_dir)}")


def patch_gradle(app_dir: pathlib.Path) -> None:
    path = app_dir / "build.gradle.kts"
    t = path.read_text()
    t = replace_once(t, 'applicationId = "dev.eden.eden_emulator"',
                     f'applicationId = "{APP_ID}"', path.name)
    t = replace_once(t, 'manifestPlaceholders += mapOf("appNameBase" to "Eden")\n',
                     f'manifestPlaceholders += mapOf("appNameBase" to "{APP_NAME}")\n',
                     path.name)
    t = replace_once(t, 'resValue("string", "app_name_suffixed", "Eden")\n',
                     f'resValue("string", "app_name_suffixed", "{APP_NAME}")\n', path.name)
    t = replace_once(t, 'val currentName = manifestPlaceholders["appNameBase"] as? String ?: "Eden"',
                     f'val currentName = manifestPlaceholders["appNameBase"] as? String ?: "{APP_NAME}"',
                     path.name)
    t = replace_once(t, 'resValue("string", "app_name", "Eden$suffix")',
                     f'resValue("string", "app_name", "{APP_NAME}$suffix")', path.name)
    # Target hardware is a 64-bit Snapdragon 870 tablet on Android 13+; Vulkan 1.1
    # and scoped-storage era APIs are assumed, so drop the very old floor.
    t = replace_once(t, "minSdk = 24", "minSdk = 29", path.name)
    path.write_text(t)
    print(f"  ~ {path.name}")


MAIN_ACTIVITY_FILTER = """            <intent-filter>
                <action android:name="com.miui.gamecenter.GAME_BOOSTER_LAUNCH"/>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.GAME" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
"""

KR_ACTIVITY = """        <activity
            android:name="org.yuzu.yuzu_emu.krclimax.KrLauncherActivity"
            android:exported="true"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"
            android:theme="@style/Theme.KrClimax">
            <intent-filter>
                <action android:name="com.miui.gamecenter.GAME_BOOSTER_LAUNCH"/>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.GAME" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>

"""


def patch_manifest(main_dir: pathlib.Path) -> None:
    path = main_dir / "AndroidManifest.xml"
    t = path.read_text()
    # Eden's own home screen stays available ("Mode Lanjutan") but is no longer
    # the launcher entry point.
    t = replace_once(t, MAIN_ACTIVITY_FILTER, "", path.name)
    t = replace_once(t, '        <activity\n            android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"',
                     KR_ACTIVITY + '        <activity\n            android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"',
                     path.name)
    t = replace_once(t, 'android:icon="@mipmap/ic_launcher"',
                     'android:icon="@mipmap/ic_kr_launcher"', path.name)
    t = replace_once(t, 'android:roundIcon="@mipmap/ic_launcher_round"',
                     'android:roundIcon="@mipmap/ic_kr_launcher"', path.name)
    path.write_text(t)
    print(f"  ~ {path.name}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("eden_dir", type=pathlib.Path)
    args = ap.parse_args()

    app_dir = args.eden_dir / "src" / "android" / "app"
    main_dir = app_dir / "src" / "main"
    if not main_dir.is_dir():
        sys.exit(f"[apply_overlay] not an Eden checkout: {args.eden_dir}")

    print("[apply_overlay] copying overlay files")
    copy_overlay(main_dir)
    print("[apply_overlay] patching build files")
    patch_gradle(app_dir)
    patch_manifest(main_dir)
    print("[apply_overlay] done")


if __name__ == "__main__":
    main()
