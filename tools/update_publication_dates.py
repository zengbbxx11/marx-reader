"""Apply explicit source-credit dates to the split library and its catalog."""
import argparse
import json
from pathlib import Path
from library_publication_dates import apply_publication, FIELDS


def update(library: Path, write: bool = False) -> dict:
    catalog_path = library / "catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    metadata = {b["id"]: b for b in catalog["books"]}
    report = {"books": 0, "dated": 0, "changed": 0, "evidence": []}
    for path in sorted((library / "books").glob("*.json")):
        original = path.read_text(encoding="utf-8")
        payload = json.loads(original)
        book = payload["books"][0]
        apply_publication(book)
        report["books"] += 1
        if book.get("publicationDate"):
            report["dated"] += 1
            report["evidence"].append({"id": book["id"], "title": book["titleZh"], **{k: book.get(k, "") for k in FIELDS}})
            for field in FIELDS:
                metadata[book["id"]][field] = book.get(field, "")
        output = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        if output != original:
            report["changed"] += 1
            if write:
                path.write_text(output, encoding="utf-8")
    if write:
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--report", type=Path, default=Path(".generated/publication-date-report.json"))
    args = parser.parse_args()
    result = update(args.library, args.write)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print({k: v for k, v in result.items() if k != "evidence"})
