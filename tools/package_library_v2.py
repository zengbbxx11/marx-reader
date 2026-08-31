"""Split the audited combined library into a lazy-loading offline asset pack.

The script preserves every book/chapter id, so existing progress, bookmarks and
notes remain valid.  It also creates stable hierarchical TOC nodes.  Run this
after build_library.py whenever bundled content changes.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
from pathlib import Path

from audit_library_quality import suspicious_section_reason


CAPITAL_STRUCTURE_ZH = {
    # (front matter count, chapter counts per part, appendix count)
    "capital-v1": (1, [3, 1, 5, 4, 3, 4, 3, 2], 0),
    "capital-v2": (2, [6, 11, 4], 0),
    "capital-v3": (1, [7, 5, 3, 5, 16, 11, 5], 2),
}

PART_NAMES_ZH = ["第一篇", "第二篇", "第三篇", "第四篇", "第五篇", "第六篇", "第七篇", "第八篇"]
PART_NAMES_EN = ["Part One", "Part Two", "Part Three", "Part Four", "Part Five", "Part Six", "Part Seven", "Part Eight"]

CAPITAL_PART_TITLES_ZH = {
    "capital-v1": [
        "第一篇　商品和货币",
        "第二篇　货币转化为资本",
        "第三篇　绝对剩余价值的生产",
        "第四篇　相对剩余价值的生产",
        "第五篇　绝对剩余价值和相对剩余价值的生产",
        "第六篇　工资",
        "第七篇　资本的积累过程",
        "第八篇　原始积累",
    ],
    "capital-v2": [
        "第一篇　资本形态变化及其循环",
        "第二篇　资本周转",
        "第三篇　社会总资本的再生产和流通",
    ],
    "capital-v3": [
        "第一篇　剩余价值转化为利润和剩余价值率转化为利润率",
        "第二篇　利润转化为平均利润",
        "第三篇　利润率趋向下降的规律",
        "第四篇　商品资本和货币资本转化为商品经营资本和货币经营资本",
        "第五篇　利润分为利息和企业主收入。生息资本",
        "第六篇　超额利润转化为地租",
        "第七篇　各种收入及其源泉",
    ],
}


def safe_id(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]+", "-", value).strip("-")


def clean_title(value: str) -> str:
    """Remove print-TOC leaders/page numbers without changing stable ids."""
    title = re.split(r"…{4,}|\.{6,}", value, maxsplit=1)[0]
    title = re.sub(r"\s*\d+\s*[―—–-]\s*\d+\s*$", "", title)
    title = re.sub(r"\s+", " ", title).strip(" ·…")
    pieces = title.split(" ")
    if len(pieces) > 1 and all(len(piece) == 1 for piece in pieces):
        title = "".join(pieces)
    title = re.sub(r"^(第[一二三四五六七八九十百0-9]+章)(?!\s)", r"\1 ", title)
    return title or value.strip()


def make_toc(book: dict) -> list[dict]:
    book_id = book["id"]
    language = book["language"].lower()
    series = book.get("seriesId") or book_id
    root_id = f"toc-{book_id}-root"
    root_type = "VOLUME" if series.startswith("capital-v") else "PART"
    root_title = book["titleZh"] if language == "zh" else book["titleEn"]
    nodes = [{
        "id": root_id,
        "parentId": None,
        "title": root_title,
        "type": root_type,
        "order": 0,
        "chapterId": None,
        "paragraphIndex": 0,
        "counterpartKey": "root",
    }]

    structure = CAPITAL_STRUCTURE_ZH.get(series) if language == "zh" else None
    part_ids: list[str] = []
    if structure:
        front_count, part_sizes, _ = structure
        for index, _ in enumerate(part_sizes):
            part_id = f"toc-{book_id}-part-{index + 1}"
            part_ids.append(part_id)
            names = CAPITAL_PART_TITLES_ZH.get(series, PART_NAMES_ZH) if language == "zh" else PART_NAMES_EN
            nodes.append({
                "id": part_id,
                "parentId": root_id,
                "title": names[index],
                "type": "PART",
                "order": front_count + index,
                "chapterId": None,
                "paragraphIndex": 0,
                "counterpartKey": f"part-{index + 1}",
            })

    for chapter_index, chapter in enumerate(book.get("chapters", [])):
        node_type = "CHAPTER"
        parent_id = root_id
        if structure:
            front_count, part_sizes, appendix_count = structure
            content_index = chapter_index - front_count
            if chapter_index < front_count:
                node_type = "PREFACE"
            elif content_index >= sum(part_sizes):
                node_type = "APPENDIX"
            else:
                cumulative = 0
                for part_index, size in enumerate(part_sizes):
                    cumulative += size
                    if content_index < cumulative:
                        parent_id = part_ids[part_index]
                        break
        chapter_node_id = f"toc-{book_id}-chapter-{safe_id(chapter['id'])}"
        nodes.append({
            "id": chapter_node_id,
            "parentId": parent_id,
            "title": chapter["title"],
            "type": node_type,
            "order": chapter_index,
            "chapterId": chapter["id"],
            "paragraphIndex": 0,
            "counterpartKey": f"chapter-{chapter_index + 1}",
        })
        section_parent_stack: dict[int, str] = {}
        for section_index, section in enumerate(chapter.get("sections", [])):
            section_title = clean_title(section["title"])
            section_level = max(2, min(4, int(section.get("level", 2))))
            lower_levels = [level for level in section_parent_stack if level < section_level]
            section_parent_id = (
                section_parent_stack[max(lower_levels)] if lower_levels else chapter_node_id
            )
            section_node_id = f"toc-{book_id}-{safe_id(chapter['id'])}-{safe_id(section['id'])}"
            nodes.append({
                "id": section_node_id,
                "parentId": section_parent_id,
                "title": section_title,
                "type": "SECTION",
                "order": section_index,
                "chapterId": chapter["id"],
                "paragraphIndex": int(section["paragraphIndex"]),
                "counterpartKey": f"chapter-{chapter_index + 1}-section-{section_index + 1}",
                "level": section_level,
            })
            section_parent_stack = {
                level: node_id for level, node_id in section_parent_stack.items()
                if level < section_level
            }
            section_parent_stack[section_level] = section_node_id
    return nodes


def package(source: Path, target: Path) -> None:
    if source.exists():
        combined = json.loads(source.read_text(encoding="utf-8"))
    else:
        existing = [
            json.loads(path.read_text(encoding="utf-8"))["books"][0]
            for path in sorted((target / "books").glob("*.json"))
        ]
        if not existing:
            raise FileNotFoundError(source)
        first = json.loads(next(iter(sorted((target / "books").glob("*.json")))).read_text(encoding="utf-8"))
        combined = {"authors": first["authors"], "books": existing}
    target.mkdir(parents=True, exist_ok=True)
    books_dir = target / "books"
    books_dir.mkdir(parents=True, exist_ok=True)

    catalog_books = []
    chinese_books = [book for book in combined["books"] if book.get("language", "").lower() == "zh"]
    expected_files = {f"{book['id']}.json" for book in chinese_books}
    for stale in books_dir.glob("*.json"):
        if stale.name not in expected_files:
            stale.unlink()
    for original in chinese_books:
        book = copy.deepcopy(original)
        for chapter in book.get("chapters", []):
            chapter["title"] = clean_title(chapter["title"])
            chapter["sections"] = [
                section for section in chapter.get("sections", [])
                if suspicious_section_reason(str(section.get("title", ""))) is None
            ]
            for section in chapter["sections"]:
                section["title"] = clean_title(section["title"])
        book["toc"] = make_toc(book)
        full = {"schemaVersion": 2, "authors": combined["authors"], "books": [book]}
        (books_dir / f"{book['id']}.json").write_text(
            json.dumps(full, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
        )

        metadata = copy.deepcopy(book)
        for chapter in metadata.get("chapters", []):
            paragraph_character_counts = [len(paragraph) for paragraph in chapter.get("content", [])]
            chapter["paragraphCount"] = len(paragraph_character_counts)
            chapter["paragraphCharacterCounts"] = paragraph_character_counts
            chapter["characterCount"] = sum(paragraph_character_counts)
            chapter.pop("content", None)
            chapter.pop("footnotes", None)
        metadata["characterCount"] = sum(
            chapter.get("characterCount", 0) for chapter in metadata.get("chapters", [])
        )
        catalog_books.append(metadata)

    catalog = {"schemaVersion": 2, "authors": combined["authors"], "books": catalog_books}
    (target / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    print(f"Packaged {len(catalog_books)} books into {target}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("app/src/main/assets/library.json"))
    parser.add_argument("--target", type=Path, default=Path("app/src/main/assets/library"))
    args = parser.parse_args()
    package(args.source, args.target)


if __name__ == "__main__":
    main()
