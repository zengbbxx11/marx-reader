#!/usr/bin/env python3
"""Check the portable repair ledger against shipped assets (no network or snapshots)."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / 'docs/SOURCE_REPAIR_PROGRESS.json'


def verify():
    ledger = json.loads(LEDGER.read_text())
    for item in ledger['verifiedAssets']:
        path = ROOT / item['path']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise SystemExit(f"Recorded asset changed or missing: {item['path']}. Review this file's repair entries before skipping them.")
    # Recent chapter reviews record body hashes as well as evidence-file hashes.
    # Validate them against shipped text so an unchanged report cannot hide drift.
    for review in ledger.get('reviews', []):
        expected = review.get('localBodySha256')
        if not expected:
            continue
        path = ROOT / 'app/src/main/assets/library/books' / (review['bookId'] + '.json')
        book = json.loads(path.read_text())['books'][0]
        chapter = next((c for c in book['chapters'] if c['id'] == review['chapterId']), None)
        if chapter is None:
            raise SystemExit(f"Reviewed chapter missing: {review['bookId']}/{review['chapterId']}")
        text = re.sub(r'\s+|〔(?:原表|图式|附件)\d+〕', '', ''.join(chapter['content']))
        if hashlib.sha256(text.encode()).hexdigest() != expected:
            raise SystemExit(f"Reviewed body changed: {review['bookId']}/{review['chapterId']}. Recheck this chapter's source alignment.")
    complete = [r for r in ledger['repairs'] if r['status'] == 'fixed_verified']
    print(f"Repair ledger verified: {len(complete)} completed entries, {len(ledger['verifiedAssets'])} assets. Pending issues remain separately listed.")


if __name__ == '__main__':
    verify()
