#!/usr/bin/env python3
import json
import sys
from pathlib import Path

from audit_library_quality import audit, load_library, summary


def main(path: str) -> int:
    source = Path(path)
    if source.is_dir():
        catalog = json.loads((source / "catalog.json").read_text("utf-8"))
        asset_paths = sorted((source / "books").glob("*.json"))
        roots = [json.loads(item.read_text("utf-8")) for item in asset_paths]
        root = {
            "schemaVersion": catalog["schemaVersion"],
            "authors": catalog["authors"],
            "books": [book for item in roots for book in item["books"]],
        }
        assert len(roots) == len(catalog["books"])
        assert {path.stem for path in asset_paths} == {book["id"] for book in catalog["books"]}
        catalog_by_id = {book["id"]: book for book in catalog["books"]}
        for item in roots:
            for full_book in item["books"]:
                summary_book = catalog_by_id[full_book["id"]]
                full_chapters = {chapter["id"]: chapter for chapter in full_book["chapters"]}
                assert summary_book["characterCount"] == sum(
                    len(paragraph)
                    for chapter in full_book["chapters"]
                    for paragraph in chapter["content"]
                )
                for summary_chapter in summary_book["chapters"]:
                    full_chapter = full_chapters[summary_chapter["id"]]
                    expected_counts = [len(paragraph) for paragraph in full_chapter["content"]]
                    assert summary_chapter["paragraphCharacterCounts"] == expected_counts
                    assert summary_chapter["characterCount"] == sum(expected_counts)
    else:
        root = json.loads(source.read_text("utf-8"))
    assert root["schemaVersion"] in {1, 2}
    author_ids = {item["id"] for item in root["authors"]}
    book_ids = set()
    chapter_ids = set()
    for book in root["books"]:
        assert book["id"] not in book_ids
        book_ids.add(book["id"])
        assert set(book["authorIds"]) <= author_ids
        assert book["language"] == "zh"
        assert book.get("category") in {"著作", "文章", "书信"}
        assert book["sourceUrl"].startswith("https://www.marxists.org/")
        assert book["rights"] in {"PUBLIC_DOMAIN", "CC_BY_SA", "PERMISSION_REQUIRED", "UNKNOWN"}
        toc_ids = {node["id"] for node in book.get("toc", [])}
        assert len(toc_ids) == len(book.get("toc", []))
        for node in book.get("toc", []):
            assert node.get("parentId") is None or node["parentId"] in toc_ids
            assert node["type"] in {"VOLUME", "PART", "CHAPTER", "SECTION", "PREFACE", "APPENDIX"}
            if node["type"] == "SECTION":
                target = next(chapter for chapter in book["chapters"] if chapter["id"] == node["chapterId"])
                assert 0 <= node["paragraphIndex"] < len(target["content"])
        if book["id"].startswith("capital-"):
            assert all("……" not in chapter["title"] for chapter in book["chapters"])
        if book["rights"] in {"PERMISSION_REQUIRED", "UNKNOWN"}:
            assert all(not chapter["content"] for chapter in book["chapters"])
        for chapter in book["chapters"]:
            assert chapter["id"] not in chapter_ids
            chapter_ids.add(chapter["id"])
            assert chapter["title"].strip()
            assert all(paragraph.strip() for paragraph in chapter["content"])
            assert len("".join(chapter["content"]).strip()) >= 100
    quality_payload = load_library(source)
    quality_findings = audit(quality_payload)
    quality_summary = summary(quality_payload, quality_findings)
    errors = [item for item in quality_findings if item.severity == "ERROR"]
    if errors:
        preview = "\n".join(
            f"{item.code} {item.location}: {item.message}" for item in errors[:20]
        )
        raise AssertionError(f"library quality audit found {len(errors)} error(s):\n{preview}")
    print(
        f"OK: {len(author_ids)} authors, {len(book_ids)} versions, {len(chapter_ids)} chapters; "
        f"quality errors=0, review items={quality_summary['reviewItems']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/library"))
