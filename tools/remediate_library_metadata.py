#!/usr/bin/env python3
"""Apply conservative, evidence-backed metadata corrections to split assets.

Automatic year changes require two local signals to agree. Ambiguous records
remain untouched and visible to the quality audit. Exceptional cases live in a
small JSON override file so editorial decisions stay reviewable.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

from bs4 import BeautifulSoup


YEAR_RE = re.compile(r"(?<!\d)((?:18|19|20)\d{2})(?!\d)")
DATED_YEAR_RE = re.compile(r"(?<!\d)((?:18|19|20)\d{2})\s*年")
EVIDENCE_FIELDS = {
    "year", "yearType", "yearBasis", "yearEvidenceUrl", "yearNote",
    "translator", "translatorBasis", "translatorEvidenceUrl", "translationYear",
    "editionNote",
}


def years(value: str) -> set[str]:
    return set(YEAR_RE.findall(value or ""))


def cached_header(cache: Path, source_url: str) -> str:
    path = cache / f"{hashlib.sha256(source_url.encode()).hexdigest()}.html"
    if not path.is_file():
        return ""
    html = path.read_text(encoding="utf-8", errors="replace")
    soup = BeautifulSoup(html, "lxml")
    for node in soup.select("script, style, noscript, nav, footer, form"):
        node.decompose()
    return " ".join(soup.get_text(" ", strip=True).split())[:2400]


def automatic_year_patch(book: dict, header: str) -> tuple[dict, str | None]:
    current = str(book.get("year", "")).strip()
    title_years = years(str(book.get("titleZh", "")))
    url_years = years(str(book.get("sourceUrl", "")))
    header_years = years(header)
    dated_header_years = set(DATED_YEAR_RE.findall(header))

    if not current:
        candidates = url_years & dated_header_years
        if len(candidates) == 1:
            year = next(iter(candidates))
            return {
                "year": year,
                "yearType": "SOURCE_DATE",
                "yearBasis": "来源页头部日期与来源 URL 年份一致",
                "yearEvidenceUrl": book["sourceUrl"],
            }, "missing-year"
        return {}, None

    if title_years and current not in title_years:
        # A year printed as a date in the source-page header is stronger than
        # a distant index grouping. Requiring it to match the work title avoids
        # inheriting author life dates and unrelated navigation metadata.
        candidates = title_years & dated_header_years
        if len(candidates) == 1:
            year = next(iter(candidates))
            return {
                "year": year,
                "yearType": "SOURCE_DATE",
                "yearBasis": "作品题名年份与来源页头部日期一致",
                "yearEvidenceUrl": book["sourceUrl"],
                "yearNote": f"原目录年份为{current}；依据题名与页面日期的一致信号校正",
            }, "title-page-date-conflict"
    return {}, None


def catalog_metadata(book: dict) -> dict:
    metadata = copy.deepcopy(book)
    for chapter in metadata.get("chapters", []):
        chapter["paragraphCount"] = len(chapter.get("content", []))
        chapter.pop("content", None)
        chapter.pop("footnotes", None)
    return metadata


def remediate(library: Path, cache: Path, overrides_path: Path, write: bool) -> dict:
    catalog_path = library / "catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    overrides = json.loads(overrides_path.read_text(encoding="utf-8")).get("books", {})
    known_ids: set[str] = set()
    changed_books = 0
    counts: Counter[str] = Counter()
    updated_books: list[dict] = []

    for asset_path in sorted((library / "books").glob("*.json")):
        root = json.loads(asset_path.read_text(encoding="utf-8"))
        if len(root.get("books", [])) != 1:
            raise ValueError(f"{asset_path}: expected one book")
        original = root["books"][0]
        book = copy.deepcopy(original)
        book_id = str(book["id"])
        known_ids.add(book_id)

        patch, reason = automatic_year_patch(
            book, cached_header(cache, str(book.get("sourceUrl", "")))
        )
        if patch:
            book.update(patch)
            counts[reason or "automatic"] += 1

        manual = overrides.get(book_id, {})
        unknown_fields = set(manual) - EVIDENCE_FIELDS
        if unknown_fields:
            raise ValueError(f"{book_id}: unsupported override fields {sorted(unknown_fields)}")
        if manual:
            book.update(manual)
            if "year" in manual:
                book.setdefault("yearEvidenceUrl", book["sourceUrl"])
                counts["manual-year"] += 1
            if "translator" in manual:
                book.setdefault("translatorEvidenceUrl", book["sourceUrl"])
                counts["manual-translator"] += 1

        updated_books.append(book)
        if book != original:
            changed_books += 1
            if write:
                root["books"][0] = book
                asset_path.write_text(
                    json.dumps(root, ensure_ascii=False, separators=(",", ":")),
                    encoding="utf-8",
                )

    missing_override_ids = set(overrides) - known_ids
    if missing_override_ids:
        raise ValueError(f"override ids absent from library: {sorted(missing_override_ids)}")

    if write:
        catalog["books"] = [catalog_metadata(book) for book in updated_books]
        catalog_path.write_text(
            json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )

    return {
        "books": len(updated_books),
        "changedBooks": changed_books,
        "write": write,
        "changesByReason": dict(sorted(counts.items())),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--cache", type=Path, default=Path(".content-cache"))
    parser.add_argument(
        "--overrides", type=Path, default=Path("tools/library_metadata_overrides.json")
    )
    parser.add_argument("--write", action="store_true", help="persist changes; default is dry-run")
    parser.add_argument(
        "--inspect", action="append", default=[], metavar="BOOK_ID",
        help="print current metadata and cached source-page header for one book",
    )
    args = parser.parse_args()
    if args.inspect:
        for book_id in args.inspect:
            asset = args.library / "books" / f"{book_id}.json"
            root = json.loads(asset.read_text(encoding="utf-8"))
            book = root["books"][0]
            print(json.dumps({
                "id": book_id,
                "title": book.get("titleZh", ""),
                "year": book.get("year", ""),
                "sourceUrl": book.get("sourceUrl", ""),
                "cachedHeader": cached_header(args.cache, book.get("sourceUrl", "")),
            }, ensure_ascii=False, indent=2))
        return 0
    result = remediate(args.library, args.cache, args.overrides, args.write)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
