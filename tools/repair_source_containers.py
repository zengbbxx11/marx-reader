"""Restore reviewed source containers without moving existing paragraph indices."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from repair_source_short_lines import refresh_catalog, text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_CONTAINER_REVIEW_2026-10-02.json'


def restore(chapter, record):
    pi = record['paragraphIndex']; addition = '\n' + '\n'.join(record['sourceLines'])
    if chapter['content'][pi].endswith(addition):
        assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['alignedTextSha256']
        return False
    assert hashlib.sha256(chapter['content'][pi].encode()).hexdigest() == record['previousParagraphSha256']
    before = copy.deepcopy(chapter)
    chapter['content'][pi] += addition
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['alignedTextSha256']
    assert len(chapter['content']) == len(before['content'])
    assert chapter.get('footnotes') == before.get('footnotes')
    assert chapter.get('sections') == before.get('sections')
    return True


def repair(apply=False):
    library = ROOT / 'app/src/main/assets/library'; changed = {}
    records = json.loads(REVIEW.read_text())['repairs']
    for record in records:
        path = library / 'books' / (record['bookId'] + '.json'); payload = json.loads(path.read_text())
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record): changed[record['bookId']] = (path, payload)
    if apply and changed:
        catalog_path, catalog = refresh_catalog(library, changed)
        mapping_path = ROOT / 'app/src/main/java/org/marxreader/app/data/SourceTextRestorations.kt'
        code = mapping_path.read_text()
        for record in records:
            if record['bookId'] not in changed: continue
            m = record['restorationMapping']; ranges = ', '.join(f'{a}..{b}' for a, b in m['ranges'])
            entry = f'    ("{m["currentHash"]}" to "{m["previousHash"]}") to listOf({ranges}),'
            if entry not in code:
                at = code.rfind('\n)'); assert at >= 0
                code = code[:at] + '\n' + entry + code[at:]
        for path, payload in changed.values(): path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
        mapping_path.write_text(code)
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
