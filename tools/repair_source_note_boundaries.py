"""Limit reviewed note payloads to their source boundary, retaining all body text."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_FINAL_CHAPTER_REVIEW_2026-10-02.json'


def restore(chapter, record):
    note = next(n for n in chapter['footnotes'] if n['id'] == record['noteId'])
    assert note['references'] == record['references']
    assert record['indexSection'] in chapter['sections']
    if note['content'] == record['sourceNoteContent']: return False
    digest = hashlib.sha256(json.dumps(note['content'], ensure_ascii=False).encode()).hexdigest()
    assert digest == record['previousNoteContentSha256']
    assert note['content'][:1] == record['sourceNoteContent']
    assert note['content'][1] == '人名索引'
    note['content'] = record['sourceNoteContent'][:]
    return True


def repair(apply=False):
    changed = []
    for record in json.loads(REVIEW.read_text())['repairs']:
        if record.get('kind') == 'importer_navigation_regression': continue
        path = ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')
        payload = json.loads(path.read_text())
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        body_before = json.dumps(chapter['content'], ensure_ascii=False)
        if restore(chapter, record):
            assert json.dumps(chapter['content'], ensure_ascii=False) == body_before
            changed.append(record['bookId'])
            if apply: path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': changed, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
