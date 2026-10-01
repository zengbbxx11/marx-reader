#!/usr/bin/env python3
"""Check the portable repair ledger against shipped assets (no network or snapshots)."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / 'docs/SOURCE_REPAIR_PROGRESS.json'


def verify():
    ledger = json.loads(LEDGER.read_text())
    for item in ledger['verifiedAssets']:
        path = ROOT / item['path']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise SystemExit(f"Recorded asset changed or missing: {item['path']}. Review this file's repair entries before skipping them.")
    complete = [r for r in ledger['repairs'] if r['status'] == 'fixed_verified']
    print(f"Repair ledger verified: {len(complete)} completed entries, {len(ledger['verifiedAssets'])} assets. Pending issues remain separately listed.")


if __name__ == '__main__':
    verify()
