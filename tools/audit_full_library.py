"""Audit a generated monolithic library before packaging Android assets."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

from bs4 import BeautifulSoup


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", type=Path, default=Path(".generated/library-full.json"))
    parser.add_argument("--cache", type=Path, default=Path(".content-cache"))
    args = parser.parse_args()
    root = json.loads(args.source.read_text("utf-8"))
    books = root["books"]

    ids = [book["id"] for book in books]
    urls = [book["sourceUrl"] for book in books]
    assert len(ids) == len(set(ids)), "duplicate book ids"
    assert len(urls) == len(set(urls)), "duplicate source URLs"
    assert all(book["language"] == "zh" for book in books), "non-Chinese book found"
    assert all(book.get("category") in {"著作", "文章", "书信"} for book in books), "invalid category"

    author_counts: Counter[str] = Counter()
    category_counts: Counter[str] = Counter()
    chapter_count = paragraph_count = character_count = section_count = 0
    content_owners: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for book in books:
        assert book["chapters"], f"book has no chapters: {book['id']}"
        category_counts[book["category"]] += 1
        for author_id in book["authorIds"]:
            author_counts[author_id] += 1
        for chapter in book["chapters"]:
            assert chapter["content"], f"chapter has no body: {book['id']}/{chapter['id']}"
            body = "\n".join(chapter["content"]).strip()
            assert len(body) >= 100, f"chapter body too short: {book['id']}/{chapter['id']}"
            chapter_count += 1
            paragraph_count += len(chapter["content"])
            character_count += len(body)
            section_count += len(chapter.get("sections", []))
            for section in chapter.get("sections", []):
                assert 0 <= section["paragraphIndex"] < len(chapter["content"]), (
                    f"section target outside body: {book['id']}/{chapter['id']}/{section['id']}"
                )
            content_owners[hashlib.sha256(body.encode()).hexdigest()].append((book["id"], chapter["id"]))

        cached = args.cache / f"{hashlib.sha256(book['sourceUrl'].encode()).hexdigest()}.html"
        if cached.exists():
            source_text = "".join(BeautifulSoup(cached.read_text("utf-8"), "lxml").get_text(" ", strip=True).split())
            extracted = sum(len("".join(paragraph.split())) for chapter in book["chapters"] for paragraph in chapter["content"])
            assert extracted >= len(source_text) * .5, (
                f"likely truncated body: {book['titleZh']} ({extracted}/{len(source_text)})"
            )

    duplicates = [owners for owners in content_owners.values() if len(owners) > 1]
    regression_minimums = {
        "共产党宣言": 50_000,
        "改造我们的学习": 4_500,
        "论持久战": 45_000,
        "新民主主义论": 28_000,
        "中国革命战争的战略问题": 40_000,
        "马克思主义与语言学问题": 20_000,
    }
    by_title = {book["titleZh"]: book for book in books}
    for title, minimum in regression_minimums.items():
        book = by_title[title]
        actual = sum(len(paragraph) for chapter in book["chapters"] for paragraph in chapter["content"])
        assert actual >= minimum, f"regression body too short: {title} ({actual} < {minimum})"
    print(f"authors={dict(sorted(author_counts.items()))}")
    print(f"categories={dict(sorted(category_counts.items()))}")
    print(
        f"works={len(books)} chapters={chapter_count} sections={section_count} "
        f"paragraphs={paragraph_count} characters={character_count}"
    )
    print(f"exact_duplicate_chapter_groups={len(duplicates)}")
    for owners in duplicates[:20]:
        print("duplicate:", owners)


if __name__ == "__main__":
    main()
