#!/usr/bin/env python3
import json
import sys
from pathlib import Path

library_dir = Path("app/src/main/assets/library")
books = [
    json.loads(path.read_text("utf-8"))["books"][0]
    for path in sorted((library_dir / "books").glob("*.json"))
]
for book in books:
    chapters = book["chapters"]
    paragraphs = sum(len(chapter["content"]) for chapter in chapters)
    characters = sum(len(text) for chapter in chapters for text in chapter["content"])
    print(
        f"{book['id']}: chapters={len(chapters)}, toc={len(book.get('toc', []))}, "
        f"paragraphs={paragraphs}, chars={characters}"
    )
    if "--titles" in sys.argv:
        for index, chapter in enumerate(chapters, 1):
            print(f"  {index:02d}. {chapter['title']}")
