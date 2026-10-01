"""Verify the built APK contains the complete offline pack and no network permission."""

from __future__ import annotations

import argparse
import json
import hashlib
import subprocess
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", nargs="?", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--aapt", type=Path, default=Path(".toolchains/android-sdk/build-tools/35.0.0/aapt.exe"))
    args = parser.parse_args()

    catalog = json.loads(Path("app/src/main/assets/library/catalog.json").read_text("utf-8"))
    expected = {f"assets/library/books/{book['id']}.json" for book in catalog["books"]}
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        image_references = 0
        for name in sorted(expected):
            book = json.loads(archive.read(name))["books"][0]
            for chapter in book["chapters"]:
                for note in chapter.get("footnotes", []):
                    if note.get("imageAsset"):
                        image_name = "assets/" + note["imageAsset"]
                        assert image_name in names, f"missing offline source illustration: {image_name}"
                        assert hashlib.sha256(archive.read(image_name)).hexdigest() == note["imageSha256"], image_name
                        image_references += 1
    actual = {name for name in names if name.startswith("assets/library/books/") and name.endswith(".json")}
    assert actual == expected, f"offline asset mismatch: missing={expected - actual}, extra={actual - expected}"

    permission_dump = subprocess.check_output(
        [str(args.aapt), "dump", "permissions", str(args.apk)], text=True, encoding="utf-8", errors="replace"
    )
    assert "android.permission.INTERNET" not in permission_dump, "APK unexpectedly requests INTERNET"
    print(f"OK: apk_bytes={args.apk.stat().st_size} offline_books={len(actual)} image_references={image_references} INTERNET=false")


if __name__ == "__main__":
    main()
