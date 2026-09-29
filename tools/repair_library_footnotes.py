"""Scan or repair printed footnotes across every bundled book, preserving text."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from library_footnotes import recover_book


def repair(library: Path, apply: bool = False) -> dict:
    report = {"booksScanned": 0, "booksChanged": 0, "addedReferences": 0, "books": []}
    for path in sorted((library / "books").glob("*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        book = payload["books"][0]
        result = recover_book(book)
        report["booksScanned"] += 1
        report["addedReferences"] += result["addedReferences"]
        if result["addedReferences"]:
            report["booksChanged"] += 1
            if apply:
                path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        if result["addedReferences"] or result["unresolved"]:
            report["books"].append({"id": book["id"], "title": book["titleZh"],
                                    "sourceUrl": book.get("sourceUrl", ""), **result})
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--report", type=Path, default=Path(".generated/footnote-repair-report.json"))
    args = parser.parse_args()
    result = repair(args.library, args.apply)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "books"}))
    print(f"Unresolved markers: {sum(len(b['unresolved']) for b in result['books'])}; report: {args.report}")


if __name__ == "__main__":
    main()
