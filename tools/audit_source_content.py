"""Read-only live MIA comparison; retain raw evidence and report review candidates.

This checks the shipped JSON, not a rebuilt replacement. Source differences are
review candidates, never automatic corrections or proof that either text is right.
"""
from __future__ import annotations

import argparse
import collections
import copy
import datetime as dt
import difflib
import hashlib
import json
import re
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from bs4 import BeautifulSoup
from build_full_chinese_library import extract_chapter, normalize_url
from repair_library_text import repair_book
from repair_source_shift import CORRECTIONS
from text_encoding import decode_html


def compact(value: str) -> str:
    # Ignore layout whitespace only; retain punctuation, numbers and characters.
    return re.sub(r"\s+", "", value)


class Snapshots:
    def __init__(self, root: Path):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.next_request = 0.0

    def fetch(self, url: str) -> tuple[str, dict]:
        if urllib.parse.urlparse(url).netloc != "www.marxists.org":
            raise ValueError(f"Unexpected source host: {url}")
        key = hashlib.sha256(url.encode()).hexdigest()
        raw_path, meta_path = self.root / (key + ".bin"), self.root / (key + ".json")
        if raw_path.exists() and meta_path.exists():
            raw, meta = raw_path.read_bytes(), json.loads(meta_path.read_text())
        else:
            with self.lock:
                delay = max(0, self.next_request - time.monotonic())
                self.next_request = max(self.next_request, time.monotonic()) + .35
            time.sleep(delay)
            request = urllib.request.Request(url, headers={"User-Agent": "MarxReader-SourceAudit/1.0"})
            with urllib.request.urlopen(request, timeout=35) as response:
                raw = response.read()
                meta = {"url": url, "finalUrl": response.url, "status": response.status,
                        "charset": response.headers.get_content_charset(),
                        "retrievedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                        "rawSha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
            raw_path.write_bytes(raw)
            meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2))
        if hashlib.sha256(raw).hexdigest() != meta["rawSha256"]:
            raise ValueError(f"Snapshot checksum mismatch: {url}")
        return decode_html(raw, meta.get("charset")), meta


def audit_book(book: dict, snapshots: Snapshots) -> dict:
    result = {"id": book["id"], "title": book["titleZh"], "sourceUrl": book["sourceUrl"], "chapters": []}
    try:
        html, index_meta = snapshots.fetch(book["sourceUrl"])
        result["indexEvidence"] = index_meta
        urls = {book["sourceUrl"]}
        for anchor in BeautifulSoup(html, "lxml").select("a[href]"):
            url = normalize_url(book["sourceUrl"], anchor["href"])
            if urllib.parse.urlparse(url).netloc == "www.marxists.org":
                urls.add(url)
        by_hash = {hashlib.sha1(url.encode()).hexdigest()[:10]: url for url in urls}
        for chapter in book["chapters"]:
            record = {"id": chapter["id"], "title": chapter["title"]}
            result["chapters"].append(record)
            url = by_hash.get(chapter["id"].rsplit("-", 1)[-1])
            if url is None:
                record.update(status="SOURCE_URL_UNRESOLVED")
                continue
            record["url"] = url
            try:
                source_html, meta = (html, index_meta) if url == book["sourceUrl"] else snapshots.fetch(url)
                record["evidence"] = meta
                source = extract_chapter(url, chapter["title"], source_html, 1)
                if source is None:
                    dom_text = compact(BeautifulSoup(source_html, "lxml").get_text("", strip=True))
                    cursor, found_all = 0, True
                    for paragraph in chapter["content"]:
                        text = compact(paragraph)
                        position = dom_text.find(text, cursor)
                        if position < 0:
                            found_all = False
                            break
                        cursor = position + len(text)
                    record["status"] = "SHORT_TEXT_SOURCE_CONTAINS_LOCAL" if found_all else "SOURCE_EXTRACTION_EMPTY"
                    record["note"] = "Extractor minimum-length threshold; independent DOM containment only, not a completeness assertion."
                    continue
                source["id"] = chapter["id"]
                corrected = copy.deepcopy(book)
                corrected["chapters"] = [source]
                corrections = []
                # Apply only pre-existing, explicitly evidenced source repairs.
                for bid, cid, _, damaged, replacement, evidence in CORRECTIONS:
                    if bid == book["id"] and cid == chapter["id"]:
                        for i, text in enumerate(source["content"]):
                            if damaged in text:
                                source["content"][i] = text.replace(damaged, replacement)
                                corrections.append(evidence)
                repair_book(corrected, collections.Counter(), {})
                record["knownSourceCorrections"] = corrections
                local_lines = [compact(p) for p in chapter["content"]]
                source_lines = [compact(p) for p in source["content"]]
                local_text, source_text = "".join(local_lines), "".join(source_lines)
                record.update(localCharacters=len(local_text), sourceCharacters=len(source_text))
                record["status"] = "TEXT_MATCH" if local_text == source_text else "TEXT_DIFFERENCE_REVIEW"
                if local_text != source_text:
                    changes = []
                    for op, i, j, k, l in difflib.SequenceMatcher(None, local_lines, source_lines, autojunk=False).get_opcodes():
                        if op != "equal":
                            changes.append({"operation": op, "localParagraphRange": [i, j],
                                            "sourceParagraphRange": [k, l],
                                            "local": "\n".join(chapter["content"][i:j])[:1200],
                                            "source": "\n".join(source["content"][k:l])[:1200]})
                    record["differenceCount"] = len(changes)
                    record["differences"] = changes[:30]
                # Independent DOM check detects possible omissions shared by the extractor.
                soup = BeautifulSoup(source_html, "lxml")
                for node in soup.select("script,style,nav,header,footer"):
                    node.decompose()
                images = []
                for node in soup.find_all("img", src=True):
                    image_url = urllib.parse.urljoin(url, node["src"])
                    images.append({"url": image_url, "alt": node.get("alt", ""),
                                   "html": str(node)})
                record["sourceImages"] = images
                record["sourceTableCount"] = len(soup.find_all("table"))
                local_with_notes = local_text + "".join(compact(p) for n in chapter.get("footnotes", []) for p in n["content"])
                local_with_notes = re.sub(r"〔图式\d+〕", "", local_with_notes)
                omitted = []
                for node in soup.find_all(["p", "blockquote", "li"]):
                    if node.find_parent(["p", "blockquote", "li"]):
                        continue
                    text = compact(node.get_text("", strip=True))
                    if len(text) >= 80 and text not in local_with_notes:
                        omitted.append(text[:500])
                record["unmatchedSourceBlocks"] = len(omitted)
                record["unmatchedSourceBlockSamples"] = omitted[:8]
            except Exception as exc:
                record.update(status="FETCH_OR_COMPARE_FAILED", error=f"{type(exc).__name__}: {exc}")
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--output", type=Path, default=Path(".generated/source-audit-20261001"))
    parser.add_argument("--only", nargs="*", help="Book IDs; omit for all shipped books")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    snapshots = Snapshots(args.output / "snapshots")
    books = [json.loads(p.read_text())["books"][0] for p in sorted((args.library / "books").glob("*.json"))]
    if args.only:
        books = [b for b in books if b["id"] in args.only]
    if not books:
        raise SystemExit("No books selected")
    results = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(audit_book, b, snapshots): b for b in books}
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            (args.output / (result["id"] + ".json")).write_text(json.dumps(result, ensure_ascii=False, indent=2))
            if len(results) % 25 == 0 or len(results) == len(books):
                print(f"Audited {len(results)}/{len(books)} books", flush=True)
    counts = collections.Counter(c["status"] for b in results for c in b["chapters"])
    summary = {"books": len(results), "bookFailures": sum("error" in b for b in results),
               "chapterStatuses": dict(counts),
               "chaptersWithUnmatchedSourceBlocks": sum(c.get("unmatchedSourceBlocks", 0) > 0 for b in results for c in b["chapters"]),
               "chaptersWithSourceImages": sum(bool(c.get("sourceImages")) for b in results for c in b["chapters"]),
               "sourceImageOccurrences": sum(len(c.get("sourceImages", [])) for b in results for c in b["chapters"]),
               "note": "Whitespace-normalized text-only machine comparison; TEXT_MATCH does not establish image, formula or table completeness. REVIEW is not a confirmed defect; DOM/image candidates require manual inspection.",
               "results": sorted(results, key=lambda b: b["id"])}
    (args.output / "report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps({k: v for k, v in summary.items() if k != "results"}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
