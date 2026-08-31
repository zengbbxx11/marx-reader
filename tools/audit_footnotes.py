#!/usr/bin/env python3
"""Validate offline footnote payloads and their paragraph character offsets."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def audit(source: Path) -> None:
    if source.is_dir():
        payload = {
            "books": [
                json.loads(path.read_text(encoding="utf-8"))["books"][0]
                for path in sorted((source / "books").glob("*.json"))
            ]
        }
    else:
        payload = json.loads(source.read_text(encoding="utf-8"))
    errors: list[str] = []
    book_count = 0
    chapter_count = 0
    footnote_count = 0
    reference_count = 0
    books_with_footnotes = 0

    for book in payload.get("books", []):
        book_count += 1
        book_has_footnotes = False
        for chapter in book.get("chapters", []):
            chapter_count += 1
            paragraphs = chapter.get("content", [])
            if any("MIA_FOOTNOTE" in paragraph for paragraph in paragraphs):
                errors.append(f"private extraction token leaked into {book.get('titleZh')} / {chapter.get('title')}")
            if any("MIA_FOOTNOTE" in section.get("title", "") for section in chapter.get("sections", [])):
                errors.append(f"private extraction token leaked into a section title in {book.get('titleZh')}")
            seen_ids: set[str] = set()
            for footnote in chapter.get("footnotes", []):
                book_has_footnotes = True
                footnote_count += 1
                footnote_id = footnote.get("id", "")
                marker = footnote.get("marker", "")
                label = f"{book.get('titleZh')} / {chapter.get('title')} / {footnote_id}"
                if not footnote_id or footnote_id in seen_ids:
                    errors.append(f"duplicate or empty id: {label}")
                seen_ids.add(footnote_id)
                if not marker or not footnote.get("content") or not "".join(footnote["content"]).strip():
                    errors.append(f"empty marker or content: {label}")
                references = footnote.get("references", [])
                if not references:
                    errors.append(f"no references: {label}")
                for reference in references:
                    reference_count += 1
                    paragraph_index = reference.get("paragraphIndex", -1)
                    start = reference.get("start", -1)
                    end = reference.get("end", -1)
                    if paragraph_index not in range(len(paragraphs)):
                        errors.append(f"paragraph out of range: {label}")
                        continue
                    paragraph = paragraphs[paragraph_index]
                    if start < 0 or end <= start or end > len(paragraph):
                        errors.append(f"offset out of range: {label} ({start}, {end}, {len(paragraph)})")
                    elif paragraph[start:end] != marker:
                        errors.append(
                            f"marker mismatch: {label}: expected {marker!r}, got {paragraph[start:end]!r}"
                        )
        books_with_footnotes += int(book_has_footnotes)

    if errors:
        preview = "\n".join(errors[:40])
        raise SystemExit(f"Footnote audit failed with {len(errors)} error(s):\n{preview}")
    if footnote_count == 0:
        raise SystemExit("Footnote audit failed: no footnotes were extracted")
    print(
        f"Footnote audit passed: {book_count} books, {chapter_count} chapters, "
        f"{books_with_footnotes} books with notes, {footnote_count} footnotes, "
        f"{reference_count} clickable references"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", type=Path, default=Path(".generated/library-full-v2.json"))
    audit(parser.parse_args().source)


if __name__ == "__main__":
    main()
