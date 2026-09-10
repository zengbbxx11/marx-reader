#!/usr/bin/env python3
"""Audit bundled or generated library data for structural and editorial risks.

Hard errors are deterministic defects that must not enter a release.  Review
items are deliberately non-fatal: they expose incomplete or ambiguous metadata
without pretending an automated guess is authoritative.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from library_text_rules import (
    HEAD_WINDOW,
    NAV_MARK_RE,
    NAV_RE,
    PUA_UNRESOLVED,
    STRUCT_MAX_LEN,
    STRUCT_RE,
    norm,
)
from text_encoding import REPLACEMENT_CHARACTER


ALLOWED_CATEGORIES = {"著作", "文章", "书信"}
ALLOWED_RIGHTS = {"PUBLIC_DOMAIN", "CC_BY_SA", "PERMISSION_REQUIRED", "UNKNOWN"}
ALLOWED_TOC_TYPES = {"VOLUME", "PART", "CHAPTER", "SECTION", "PREFACE", "APPENDIX"}
RESTRICTED_RIGHTS = {"PERMISSION_REQUIRED", "UNKNOWN"}
YEAR_RE = re.compile(r"(?<!\d)((?:18|19|20)\d{2})(?!\d)")
YEAR_VALUE_RE = re.compile(r"^(?:18|19|20)\d{2}(?:\s*[–—-]\s*(?:18|19|20)\d{2})?$")
ALLOWED_YEAR_TYPES = {"WRITTEN", "PUBLISHED", "TRANSLATION_PUBLICATION", "EDITION", "SOURCE_DATE"}
NUMERIC_SECTION_RE = re.compile(r"^[\d\s.,，、:：;%％+\-−—–/()（）]+$")
HTML_RE = re.compile(r"</?[a-z][^>]*>", re.I)


@dataclass(frozen=True)
class Finding:
    severity: str
    code: str
    location: str
    message: str


@dataclass
class LibraryPayload:
    schema_version: int
    authors: list[dict]
    books: list[dict]
    load_findings: list[Finding]


def suspicious_section_reason(value: str) -> str | None:
    """Return a reason when a heading is clearly numeric/table data."""
    title = re.sub(r"\s+", " ", value).strip()
    if not title:
        return "empty title"
    # Dates, edition headings and parenthesized outline numbers are legitimate
    # navigation labels even though they are dominated by digits.
    if re.fullmatch(r"[（(]\d{1,4}[）)]", title):
        return None
    if re.search(r"(?:^|\d[、．。.]\s*)(?:(?:18|19|20)\d{2}年)?\d{1,2}月\d{1,2}日", title):
        return None
    if re.search(r"(?:^|\d[、．。.]\s*)(?:18|19|20)\d{2}年", title):
        return None
    if re.match(r"^\d{1,3}\s+\D", title):
        return None
    if any(character.isdigit() for character in title) and NUMERIC_SECTION_RE.fullmatch(title):
        return "numeric-only title"
    numeric_groups = re.findall(r"\d+(?:[.,]\d+)?", title)
    digit_ratio = sum(character.isdigit() for character in title) / len(title)
    looks_numbered_heading = bool(re.match(r"^\d{1,2}[、．。)]\s*\D", title))
    if (
        title[0].isdigit()
        and len(numeric_groups) >= 2
        and digit_ratio >= 0.35
        and not looks_numbered_heading
    ):
        return "probable table row"
    return None


def is_credible_inferred_heading(value: str) -> bool:
    """Reject data rows while retaining numbered headings with real text."""
    return suspicious_section_reason(value) is None and bool(re.search(r"[^\W\d_]", value, re.UNICODE))


def infer_local_year(label: str, context: str) -> str:
    """Return one unambiguous year without borrowing a distant group date."""
    label_years = set(YEAR_RE.findall(label))
    if len(label_years) == 1:
        return next(iter(label_years))
    if len(label_years) > 1:
        return ""
    context_years = set(YEAR_RE.findall(context))
    if len(context) <= len(label) + 80 and len(context_years) == 1:
        return next(iter(context_years))
    return ""


def load_library(path: Path) -> LibraryPayload:
    findings: list[Finding] = []
    if not path.is_dir():
        root = json.loads(path.read_text(encoding="utf-8"))
        return LibraryPayload(
            schema_version=int(root.get("schemaVersion", 0)),
            authors=list(root.get("authors", [])),
            books=list(root.get("books", [])),
            load_findings=findings,
        )

    catalog_path = path / "catalog.json"
    books_path = path / "books"
    if not catalog_path.is_file():
        raise FileNotFoundError(f"missing catalog: {catalog_path}")
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog_books = list(catalog.get("books", []))
    catalog_ids = [str(book.get("id", "")) for book in catalog_books]
    files = sorted(books_path.glob("*.json")) if books_path.is_dir() else []
    file_ids = [item.stem for item in files]
    for missing in sorted(set(catalog_ids) - set(file_ids)):
        findings.append(Finding("ERROR", "MISSING_BOOK_ASSET", missing, "catalog entry has no book asset"))
    for extra in sorted(set(file_ids) - set(catalog_ids)):
        findings.append(Finding("ERROR", "ORPHAN_BOOK_ASSET", extra, "book asset is absent from catalog"))
    if len(catalog_ids) != len(files):
        findings.append(Finding(
            "ERROR", "CATALOG_ASSET_COUNT", str(path),
            f"catalog has {len(catalog_ids)} books but books/ has {len(files)} files",
        ))

    books: list[dict] = []
    catalog_by_id = {str(book.get("id", "")): book for book in catalog_books}
    for item in files:
        payload = json.loads(item.read_text(encoding="utf-8"))
        asset_books = payload.get("books", [])
        if len(asset_books) != 1:
            findings.append(Finding(
                "ERROR", "BOOK_ASSET_CARDINALITY", item.name,
                f"expected exactly one book, found {len(asset_books)}",
            ))
            continue
        book = asset_books[0]
        if book.get("id") != item.stem:
            findings.append(Finding(
                "ERROR", "BOOK_FILENAME_MISMATCH", item.name,
                f"contains book id {book.get('id')!r}",
            ))
        expected_metadata = copy.deepcopy(book)
        for chapter in expected_metadata.get("chapters", []):
            paragraph_character_counts = [len(paragraph) for paragraph in chapter.get("content", [])]
            chapter["paragraphCount"] = len(paragraph_character_counts)
            chapter["paragraphCharacterCounts"] = paragraph_character_counts
            chapter["characterCount"] = sum(paragraph_character_counts)
            chapter.pop("content", None)
            chapter.pop("footnotes", None)
        expected_metadata["characterCount"] = sum(
            chapter.get("characterCount", 0) for chapter in expected_metadata.get("chapters", [])
        )
        if catalog_by_id.get(str(book.get("id", ""))) != expected_metadata:
            findings.append(Finding(
                "ERROR", "CATALOG_METADATA_MISMATCH", item.name,
                "catalog metadata differs from the corresponding book asset",
            ))
        books.append(book)
    return LibraryPayload(
        schema_version=int(catalog.get("schemaVersion", 0)),
        authors=list(catalog.get("authors", [])),
        books=books,
        load_findings=findings,
    )


def _duplicates(values: Iterable[str]) -> set[str]:
    counts = Counter(values)
    return {value for value, count in counts.items() if value and count > 1}


def audit(payload: LibraryPayload, include_rights_review: bool = False) -> list[Finding]:
    findings = list(payload.load_findings)

    def add(severity: str, code: str, location: str, message: str) -> None:
        findings.append(Finding(severity, code, location, message))

    if payload.schema_version not in {1, 2}:
        add("ERROR", "SCHEMA_VERSION", "library", f"unsupported schema {payload.schema_version}")

    author_ids = [str(author.get("id", "")) for author in payload.authors]
    for author_id in sorted(_duplicates(author_ids)):
        add("ERROR", "DUPLICATE_AUTHOR_ID", author_id, "author id is not unique")
    known_authors = set(author_ids)

    book_ids = [str(book.get("id", "")) for book in payload.books]
    for book_id in sorted(_duplicates(book_ids)):
        add("ERROR", "DUPLICATE_BOOK_ID", book_id, "book id is not unique")
    urls = [str(book.get("sourceUrl", "")).strip() for book in payload.books]
    for source_url in sorted(_duplicates(urls)):
        add("ERROR", "DUPLICATE_SOURCE_URL", source_url, "source URL is reused by multiple books")

    global_chapter_ids: list[str] = []
    content_owners: dict[str, list[str]] = defaultdict(list)
    for book in payload.books:
        book_id = str(book.get("id", "<missing-book-id>"))
        title = str(book.get("titleZh", "")).strip()
        location = book_id
        if not book_id or book_id == "<missing-book-id>":
            add("ERROR", "MISSING_BOOK_ID", location, "book id is empty")
        if not title:
            add("ERROR", "MISSING_TITLE", location, "Chinese title is empty")
        if REPLACEMENT_CHARACTER in title:
            add(
                "ERROR", "REPLACEMENT_CHARACTER", location,
                f"{title.count(REPLACEMENT_CHARACTER)} undecodable character(s) in title; "
                "re-fetch the source page with the correct charset",
            )
        unknown_authors = set(book.get("authorIds", [])) - known_authors
        if unknown_authors:
            add("ERROR", "UNKNOWN_AUTHOR", location, f"unknown author ids: {sorted(unknown_authors)}")
        if book.get("language") != "zh":
            add("ERROR", "INVALID_LANGUAGE", location, f"expected zh, got {book.get('language')!r}")
        if book.get("category") not in ALLOWED_CATEGORIES:
            add("ERROR", "INVALID_CATEGORY", location, f"invalid category {book.get('category')!r}")

        source_url = str(book.get("sourceUrl", "")).strip()
        if not source_url.startswith("https://"):
            add("ERROR", "INVALID_SOURCE_URL", location, "source URL must use HTTPS")
        if not str(book.get("sourceCredit", "")).strip():
            add("ERROR", "MISSING_SOURCE_CREDIT", location, "source credit is empty")
        rights = book.get("rights")
        if rights not in ALLOWED_RIGHTS:
            add("ERROR", "INVALID_RIGHTS", location, f"invalid rights state {rights!r}")
        if include_rights_review and rights in {"PUBLIC_DOMAIN", "CC_BY_SA"} and not str(book.get("rightsBasis", "")).strip():
            add("REVIEW", "RIGHTS_BASIS_UNRECORDED", location, "rights state has no structured evidence note")

        year = str(book.get("year", "")).strip()
        if not year:
            add("REVIEW", "MISSING_YEAR", location, "publication/composition year needs review")
        elif not YEAR_VALUE_RE.fullmatch(year):
            add("REVIEW", "INVALID_YEAR_FORMAT", location, f"unrecognized year value {year!r}")
        title_years = YEAR_RE.findall(title)
        year_type = str(book.get("yearType", "")).strip()
        year_basis = str(book.get("yearBasis", "")).strip()
        year_evidence_url = str(book.get("yearEvidenceUrl", "")).strip()
        year_note = str(book.get("yearNote", "")).strip()
        if year_type and year_type not in ALLOWED_YEAR_TYPES:
            add("ERROR", "INVALID_YEAR_TYPE", location, f"invalid year type {year_type!r}")
        if (year_type or year_basis or year_evidence_url or year_note) and not year:
            add("ERROR", "YEAR_EVIDENCE_WITHOUT_YEAR", location, "year evidence exists but year is empty")
        if year_basis and not year_evidence_url:
            add("REVIEW", "YEAR_EVIDENCE_URL_MISSING", location, "year basis has no evidence URL")
        if year_evidence_url and not year_evidence_url.startswith("https://"):
            add("ERROR", "INVALID_YEAR_EVIDENCE_URL", location, "year evidence URL must use HTTPS")
        if year and title_years and year not in title_years and not year_note:
            add(
                "REVIEW", "TITLE_YEAR_CONFLICT", location,
                f"metadata year {year!r} differs from title year(s) {title_years}; do not auto-replace",
            )
        translator = str(book.get("translator", "")).strip()
        translator_basis = str(book.get("translatorBasis", "")).strip()
        translator_evidence_url = str(book.get("translatorEvidenceUrl", "")).strip()
        if not translator:
            add("REVIEW", "TRANSLATOR_UNRECORDED", location, "translator is unknown or not recorded")
        if (translator_basis or translator_evidence_url) and not translator:
            add("ERROR", "TRANSLATOR_EVIDENCE_WITHOUT_NAME", location, "translator evidence exists but name is empty")
        if translator_basis and not translator_evidence_url:
            add("REVIEW", "TRANSLATOR_EVIDENCE_URL_MISSING", location, "translator basis has no evidence URL")
        if translator_evidence_url and not translator_evidence_url.startswith("https://"):
            add("ERROR", "INVALID_TRANSLATOR_EVIDENCE_URL", location, "translator evidence URL must use HTTPS")
        if not str(book.get("description", "")).strip():
            add("REVIEW", "DESCRIPTION_MISSING", location, "description is empty")

        chapters = list(book.get("chapters", []))
        if not chapters:
            add("ERROR", "NO_CHAPTERS", location, "book has no chapters")
        chapter_by_id = {str(chapter.get("id", "")): chapter for chapter in chapters}
        chapter_ids = [str(chapter.get("id", "")) for chapter in chapters]
        for chapter_id in sorted(_duplicates(chapter_ids)):
            add("ERROR", "DUPLICATE_CHAPTER_ID_IN_BOOK", f"{book_id}/{chapter_id}", "chapter id repeats in book")
        global_chapter_ids.extend(chapter_ids)

        for chapter in chapters:
            chapter_id = str(chapter.get("id", "<missing-chapter-id>"))
            chapter_location = f"{book_id}/{chapter_id}"
            paragraphs = list(chapter.get("content", []))
            body = "\n".join(str(paragraph) for paragraph in paragraphs).strip()
            if not str(chapter.get("title", "")).strip():
                add("ERROR", "MISSING_CHAPTER_TITLE", chapter_location, "chapter title is empty")
            if len(body) < 100:
                add("ERROR", "CHAPTER_BODY_TOO_SHORT", chapter_location, f"body has {len(body)} characters")
            if any(not str(paragraph).strip() for paragraph in paragraphs):
                add("ERROR", "EMPTY_PARAGRAPH", chapter_location, "chapter contains an empty paragraph")
            if "MIA_FOOTNOTE" in body:
                add("ERROR", "PRIVATE_TOKEN_LEAK", chapter_location, "private footnote token leaked into body")
            if HTML_RE.search(body):
                add("ERROR", "HTML_LEAK", chapter_location, "probable HTML tag leaked into body")
            # A replacement character means the source page was decoded with the
            # wrong charset, and the original bytes are already lost by then.
            broken_characters = sum(
                str(value).count(REPLACEMENT_CHARACTER) for value in paragraphs
            ) + str(chapter.get("title", "")).count(REPLACEMENT_CHARACTER)
            if broken_characters:
                add(
                    "ERROR", "REPLACEMENT_CHARACTER", chapter_location,
                    f"{broken_characters} undecodable character(s) in body or title; "
                    "re-fetch the source page with the correct charset",
                )
            # Source-site navigation lines ("上一篇 回目录 下一篇") and structural
            # heading lines ("第一篇 商品和货币") are not prose.  keep_text() keeps
            # them because they only match as combinations, and older MIA pages
            # emit volume/part/chapter titles as plain text rows.  Both are
            # removable by tools/repair_library_text.py.
            navigation_lines = [
                index for index, paragraph in enumerate(paragraphs)
                if isinstance(paragraph, str) and (
                    NAV_RE.match(paragraph.strip())
                    or (NAV_MARK_RE.search(paragraph) and len(paragraph.strip()) < 50)
                )
            ]
            if navigation_lines:
                add(
                    "ERROR", "NAVIGATION_LINE", chapter_location,
                    f"{len(navigation_lines)} source-site navigation line(s) retained "
                    f"(first at paragraph {navigation_lines[0]}); "
                    "run tools/repair_library_text.py",
                )
            chapter_title_normalized = norm(str(chapter.get("title", "")))
            heading_lines = [
                index for index, paragraph in enumerate(paragraphs)
                if isinstance(paragraph, str)
                and STRUCT_RE.match(paragraph.strip())
                and len(paragraph.strip()) <= STRUCT_MAX_LEN
                and (
                    index < HEAD_WINDOW
                    or norm(paragraph.strip()) == chapter_title_normalized
                )
            ]
            if heading_lines:
                add(
                    "ERROR", "DUPLICATE_STRUCTURE_HEADING", chapter_location,
                    f"{len(heading_lines)} structural heading line(s) duplicated in body "
                    f"(first at paragraph {heading_lines[0]}); "
                    "run tools/repair_library_text.py",
                )
            # GBK user-area bytes decode into the Unicode private use area.  Those
            # characters have no glyph, so they surface as blanks in the reader.
            # Mapped ones are repairable; the rest are irreversibly damaged at the
            # source (a literal 0x3F replaced the original glyph) and only reviewed.
            pua_counts: Counter = Counter()
            for paragraph in [*paragraphs, str(chapter.get("title", ""))]:
                if isinstance(paragraph, str):
                    for character in paragraph:
                        if 0xE000 <= ord(character) <= 0xF8FF:
                            pua_counts[character] += 1
            for character, count in pua_counts.items():
                if character in PUA_UNRESOLVED:
                    add(
                        "REVIEW", "KNOWN_SOURCE_DAMAGE", chapter_location,
                        f"U+{ord(character):04X} x{count}: source bytes irreversibly damaged "
                        "(literal 0x3F), original glyph lost",
                    )
                else:
                    add(
                        "ERROR", "PRIVATE_USE_CHARACTER", chapter_location,
                        f"U+{ord(character):04X} x{count}: GBK user-area byte decoded into "
                        "private use area; run tools/repair_library_text.py",
                    )
            if body:
                digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
                content_owners[digest].append(chapter_location)
            repeated_pairs = sum(
                paragraphs[index] == paragraphs[index - 1]
                for index in range(1, len(paragraphs))
            )
            if repeated_pairs:
                add("REVIEW", "CONSECUTIVE_DUPLICATE_PARAGRAPH", chapter_location, f"{repeated_pairs} adjacent duplicate(s)")

            section_ids = [str(section.get("id", "")) for section in chapter.get("sections", [])]
            for section_id in sorted(_duplicates(section_ids)):
                add("ERROR", "DUPLICATE_SECTION_ID", f"{chapter_location}/{section_id}", "section id repeats in chapter")
            for section in chapter.get("sections", []):
                section_id = str(section.get("id", "<missing-section-id>"))
                section_location = f"{chapter_location}/{section_id}"
                section_title = str(section.get("title", "")).strip()
                reason = suspicious_section_reason(section_title)
                if reason:
                    add("ERROR", "SUSPICIOUS_SECTION_TITLE", section_location, f"{reason}: {section_title!r}")
                if REPLACEMENT_CHARACTER in section_title:
                    add(
                        "ERROR", "REPLACEMENT_CHARACTER", section_location,
                        f"{section_title.count(REPLACEMENT_CHARACTER)} undecodable character(s) in section title; "
                        "re-fetch the source page with the correct charset",
                    )
                paragraph_index = section.get("paragraphIndex")
                if not isinstance(paragraph_index, int) or paragraph_index not in range(len(paragraphs)):
                    add("ERROR", "SECTION_TARGET_OUT_OF_RANGE", section_location, f"paragraph index {paragraph_index!r}")

            footnote_ids = [str(note.get("id", "")) for note in chapter.get("footnotes", [])]
            for footnote_id in sorted(_duplicates(footnote_ids)):
                add("ERROR", "DUPLICATE_FOOTNOTE_ID", f"{chapter_location}/{footnote_id}", "footnote id repeats in chapter")
            for footnote in chapter.get("footnotes", []):
                footnote_id = str(footnote.get("id", "<missing-footnote-id>"))
                footnote_location = f"{chapter_location}/{footnote_id}"
                marker = str(footnote.get("marker", ""))
                note_content = [str(value) for value in footnote.get("content", [])]
                if not footnote_id or footnote_id == "<missing-footnote-id>":
                    add("ERROR", "MISSING_FOOTNOTE_ID", footnote_location, "footnote id is empty")
                if not marker or not "".join(note_content).strip():
                    add("ERROR", "EMPTY_FOOTNOTE", footnote_location, "marker or footnote content is empty")
                if any("MIA_FOOTNOTE" in value for value in note_content):
                    add("ERROR", "PRIVATE_TOKEN_LEAK", footnote_location, "private token leaked into footnote")
                broken_characters = sum(value.count(REPLACEMENT_CHARACTER) for value in note_content)
                if broken_characters:
                    add(
                        "ERROR", "REPLACEMENT_CHARACTER", footnote_location,
                        f"{broken_characters} undecodable character(s) in footnote; "
                        "re-fetch the source page with the correct charset",
                    )
                references = list(footnote.get("references", []))
                if not references:
                    add("ERROR", "FOOTNOTE_WITHOUT_REFERENCE", footnote_location, "footnote has no body reference")
                for reference_index, reference in enumerate(references):
                    reference_location = f"{footnote_location}/reference-{reference_index + 1}"
                    paragraph_index = reference.get("paragraphIndex")
                    start = reference.get("start")
                    end = reference.get("end")
                    if not isinstance(paragraph_index, int) or paragraph_index not in range(len(paragraphs)):
                        add("ERROR", "FOOTNOTE_PARAGRAPH_OUT_OF_RANGE", reference_location, f"paragraph index {paragraph_index!r}")
                        continue
                    paragraph = str(paragraphs[paragraph_index])
                    if (
                        not isinstance(start, int)
                        or not isinstance(end, int)
                        or start < 0
                        or end <= start
                        or end > len(paragraph)
                    ):
                        add("ERROR", "FOOTNOTE_OFFSET_OUT_OF_RANGE", reference_location, f"offsets {start!r}:{end!r}")
                    elif paragraph[start:end] != marker:
                        add(
                            "ERROR", "FOOTNOTE_MARKER_MISMATCH", reference_location,
                            f"expected {marker!r}, found {paragraph[start:end]!r}",
                        )

        toc = list(book.get("toc", []))
        toc_ids = [str(node.get("id", "")) for node in toc]
        for node_id in sorted(_duplicates(toc_ids)):
            add("ERROR", "DUPLICATE_TOC_ID", f"{book_id}/{node_id}", "TOC id repeats in book")
        known_toc_ids = set(toc_ids)
        parent_by_id = {str(node.get("id", "")): node.get("parentId") for node in toc}
        for node in toc:
            node_id = str(node.get("id", "<missing-toc-id>"))
            node_location = f"{book_id}/{node_id}"
            parent_id = node.get("parentId")
            if parent_id is not None and parent_id not in known_toc_ids:
                add("ERROR", "UNKNOWN_TOC_PARENT", node_location, f"unknown parent {parent_id!r}")
            if node.get("type") not in ALLOWED_TOC_TYPES:
                add("ERROR", "INVALID_TOC_TYPE", node_location, f"invalid type {node.get('type')!r}")
            node_title = str(node.get("title", ""))
            if REPLACEMENT_CHARACTER in node_title:
                add(
                    "ERROR", "REPLACEMENT_CHARACTER", node_location,
                    f"{node_title.count(REPLACEMENT_CHARACTER)} undecodable character(s) in TOC title; "
                    "re-fetch the source page with the correct charset",
                )
            chapter_id = node.get("chapterId")
            if chapter_id is not None and chapter_id not in chapter_by_id:
                add("ERROR", "UNKNOWN_TOC_CHAPTER", node_location, f"unknown chapter {chapter_id!r}")
                continue
            if chapter_id is not None:
                paragraph_index = node.get("paragraphIndex", 0)
                paragraphs = chapter_by_id[chapter_id].get("content", [])
                if not isinstance(paragraph_index, int) or paragraph_index not in range(len(paragraphs)):
                    add("ERROR", "TOC_TARGET_OUT_OF_RANGE", node_location, f"paragraph index {paragraph_index!r}")
            visited = {node_id}
            ancestor = parent_id
            while ancestor is not None and ancestor in parent_by_id:
                if ancestor in visited:
                    add("ERROR", "TOC_PARENT_CYCLE", node_location, f"cycle includes {ancestor!r}")
                    break
                visited.add(ancestor)
                ancestor = parent_by_id[ancestor]

        if rights in RESTRICTED_RIGHTS and any(chapter.get("content") for chapter in chapters):
            add("ERROR", "RESTRICTED_RIGHTS_BODY", location, f"{rights} work contains distributable body text")

    for chapter_id in sorted(_duplicates(global_chapter_ids)):
        add("ERROR", "DUPLICATE_CHAPTER_ID_GLOBAL", chapter_id, "chapter id is reused across books")
    for owners in content_owners.values():
        if len(owners) > 1:
            add("REVIEW", "EXACT_DUPLICATE_CHAPTER", owners[0], f"same body appears in: {owners}")
    return sorted(findings, key=lambda item: (item.severity != "ERROR", item.code, item.location))


def summary(payload: LibraryPayload, findings: list[Finding]) -> dict:
    chapters = [chapter for book in payload.books for chapter in book.get("chapters", [])]
    paragraphs = [paragraph for chapter in chapters for paragraph in chapter.get("content", [])]
    return {
        "schemaVersion": payload.schema_version,
        "authors": len(payload.authors),
        "books": len(payload.books),
        "chapters": len(chapters),
        "paragraphs": len(paragraphs),
        "characters": sum(
            len("\n".join(str(paragraph) for paragraph in chapter.get("content", [])))
            for chapter in chapters
        ),
        "errors": sum(item.severity == "ERROR" for item in findings),
        "reviewItems": sum(item.severity == "REVIEW" for item in findings),
        "findingsByCode": dict(sorted(Counter(item.code for item in findings).items())),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--json", dest="json_path", type=Path, help="write a machine-readable full report")
    parser.add_argument("--preview", type=int, default=30, help="maximum findings printed to the terminal")
    parser.add_argument("--fail-on-review", action="store_true", help="also fail when editorial review items remain")
    parser.add_argument(
        "--include-rights-review", action="store_true",
        help="include optional rights-evidence review items in the report",
    )
    args = parser.parse_args()

    payload = load_library(args.source)
    findings = audit(payload, include_rights_review=args.include_rights_review)
    report = {"summary": summary(payload, findings), "findings": [asdict(item) for item in findings]}
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    for item in findings[:max(0, args.preview)]:
        print(f"{item.severity} {item.code} {item.location}: {item.message}")
    if len(findings) > args.preview >= 0:
        print(f"... {len(findings) - args.preview} more finding(s); use --json for the complete report")
    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    errors = report["summary"]["errors"]
    reviews = report["summary"]["reviewItems"]
    return 1 if errors or (args.fail_on_review and reviews) else 0


if __name__ == "__main__":
    raise SystemExit(main())
