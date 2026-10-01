"""Restore reviewed source-image references without changing paragraph indices.

Dry-run by default. Uses the raw-page evidence collected by audit_source_content;
checks text alignment and all image hashes before writing any library assets.
"""
from __future__ import annotations

import argparse
import bisect
import copy
import collections
import hashlib
import json
import re
from pathlib import Path

from audit_source_content import compact
from build_full_chinese_library import extract_chapter
from text_encoding import decode_html
from repair_library_text import repair_book

IMAGE_MARKER = re.compile(r"〔图式\d+〕")
TITLE_ID = "lenin-work-1d0fc116fd84"
TITLE = "列宁选集（网页版节选）"
NAVIGATION_IDS = {
    "https://www.marxists.org/chinese/maozedong/mia-chinese-mao-19670203.htm": "-> 毛泽东",
    "https://www.marxists.org/chinese/maozedong/mia-chinese-mao-196711.htm": "-> 毛泽东",
    "https://www.marxists.org/chinese/stalin/mia-chinese-stalin-1950.htm": "-> 斯大林",
}


def insert_formulas(chapter: dict, source: dict, marker_pattern=IMAGE_MARKER) -> int:
    original = chapter["content"]
    base = "".join(compact(p) for p in original)
    marked = "".join(compact(p) for p in source["content"])
    if base == marked:
        # On repeat runs, require the restored notes to still be present.
        existing = {n["id"]: n for n in chapter.get("footnotes", [])}
        for note in source["footnotes"]:
            if "imageAsset" in note and marker_pattern.fullmatch(note["marker"]):
                assert note["id"] in existing and existing[note["id"]]["imageAsset"] == note["imageAsset"]
        return 0
    assert marker_pattern.sub("", marked) == base, f"Source text changed: {chapter['id']}"
    assert not marker_pattern.search(base), "Partially repaired chapter needs review"
    offsets, cursor = [], 0
    for paragraph in original:
        cursor += len(compact(paragraph))
        offsets.append(cursor)
    insertions: dict[int, list[tuple[int, str]]] = {}
    removed_length = 0
    for match in marker_pattern.finditer(marked):
        position = match.start() - removed_length
        removed_length += len(match.group())
        pi = min(bisect.bisect_left(offsets, position), len(original) - 1)
        prior = offsets[pi - 1] if pi else 0
        target, seen, raw_position = position - prior, 0, 0
        for raw_position, char in enumerate(original[pi]):
            if seen == target:
                break
            if not char.isspace():
                seen += 1
        else:
            raw_position = len(original[pi])
        assert seen == target
        insertions.setdefault(pi, []).append((raw_position, match.group()))
    note_by_marker = {n["marker"]: n for n in source["footnotes"]
                      if "imageAsset" in n and marker_pattern.fullmatch(n["marker"])}
    for pi, edits in insertions.items():
        for note in chapter.get("footnotes", []):
            for reference in note["references"]:
                if reference["paragraphIndex"] == pi:
                    reference["start"] += sum(len(m) for pos, m in edits if pos <= reference["start"])
                    reference["end"] += sum(len(m) for pos, m in edits if pos < reference["end"])
        pieces, cursor, added = [], 0, 0
        for position, marker in edits:
            pieces += [original[pi][cursor:position], marker]
            note = copy.deepcopy(note_by_marker[marker])
            start = position + added
            note["references"] = [{"paragraphIndex": pi, "start": start, "end": start + len(marker)}]
            chapter.setdefault("footnotes", []).append(note)
            cursor = position
            added += len(marker)
        pieces.append(original[pi][cursor:])
        original[pi] = "".join(pieces)
    assert marker_pattern.sub("", "".join(compact(p) for p in original)) == base
    return len(note_by_marker)


def remove_navigation(chapter: dict, prefix: str) -> bool:
    first = chapter["content"][0]
    if not first.startswith(prefix):
        return False
    chapter["content"][0] = first[len(prefix):]
    for note in chapter.get("footnotes", []):
        for reference in note["references"]:
            if reference["paragraphIndex"] == 0:
                assert reference["start"] >= len(prefix)
                reference["start"] -= len(prefix)
                reference["end"] -= len(prefix)
    return True


def repair(library: Path, evidence: Path, apply: bool = False) -> dict:
    report = json.loads((evidence / "report.json").read_text())
    capital_sources = next(b for b in report["results"] if b["id"] == "capital-v2-zh")
    paths = sorted((library / "books").glob("*.json"))
    payloads = {p: json.loads(p.read_text()) for p in paths}
    changed, formula_count, nav_count = {}, 0, 0
    for path, payload in payloads.items():
        book = payload["books"][0]
        before = copy.deepcopy(book)
        if book["id"] == "capital-v2-zh":
            sources = {c["id"]: c for c in capital_sources["chapters"]}
            for chapter in book["chapters"]:
                entry = sources[chapter["id"]]
                if not entry.get("sourceImages"):
                    continue
                meta = entry["evidence"]
                raw = (evidence / "snapshots" / (hashlib.sha256(entry["url"].encode()).hexdigest() + ".bin")).read_bytes()
                assert hashlib.sha256(raw).hexdigest() == meta["rawSha256"]
                source = extract_chapter(entry["url"], chapter["title"], decode_html(raw, meta.get("charset")), 1)
                source["id"] = chapter["id"]
                source_book = copy.deepcopy(book)
                source_book["chapters"] = [source]
                repair_book(source_book, collections.Counter(), {})
                formula_count += insert_formulas(chapter, source)
        if book["id"] == TITLE_ID:
            assert book["titleZh"] in {"网页版", TITLE}
            book["titleZh"] = book["titleEn"] = TITLE
            book["description"] = "收录源站网页版已公开链接的部分篇目，包括凡例、编者的话和第一卷相关文献及注释；不代表完整四卷。"
            book["titleBasis"] = "源页题名为列宁选集；按实际收录范围注明网页版节选。"
            book["titleEvidenceUrl"] = book["sourceUrl"]
            for node in book["toc"]:
                if node["parentId"] is None:
                    node["title"] = TITLE
        if book["sourceUrl"] in NAVIGATION_IDS:
            nav_count += remove_navigation(book["chapters"][0], NAVIGATION_IDS[book["sourceUrl"]])
        if book != before:
            assert [(c["id"], len(c["content"])) for c in book["chapters"]] == [(c["id"], len(c["content"])) for c in before["chapters"]]
            changed[book["id"]] = (path, payload)
    # Verify every reference, including old notes shifted by inline insertions.
    for payload in payloads.values():
        for chapter in payload["books"][0]["chapters"]:
            for note in chapter.get("footnotes", []):
                if note.get("imageAsset"):
                    image = library.parent / note["imageAsset"]
                    assert hashlib.sha256(image.read_bytes()).hexdigest() == note["imageSha256"]
                for ref in note["references"]:
                    assert chapter["content"][ref["paragraphIndex"]][ref["start"]:ref["end"]] == note["marker"]
    catalog_path = library / "catalog.json"
    catalog = json.loads(catalog_path.read_text())
    for i, metadata in enumerate(catalog["books"]):
        if metadata["id"] not in changed:
            continue
        full = copy.deepcopy(changed[metadata["id"]][1]["books"][0])
        for chapter in full["chapters"]:
            counts = [len(p) for p in chapter.pop("content")]
            chapter.update(paragraphCount=len(counts), paragraphCharacterCounts=counts, characterCount=sum(counts))
            chapter.pop("footnotes", None)
            chapter.pop("sourceTextRepairs", None)
        full["characterCount"] = sum(c["characterCount"] for c in full["chapters"])
        catalog["books"][i] = full
    result = {"changedBooks": list(changed), "formulaReferencesAdded": formula_count,
              "navigationPrefixesRemoved": nav_count, "applied": apply}
    if apply:
        # Keep an exact local backup for review; only write after all checks pass.
        backup = evidence / "before-repair"
        backup.mkdir(exist_ok=True)
        for path, payload in changed.values():
            (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
        if changed:
            (backup / "catalog.json").write_bytes(catalog_path.read_bytes())
            catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--evidence", type=Path, default=Path(".generated/source-audit-20261001"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.evidence, args.apply), ensure_ascii=False, indent=2))
