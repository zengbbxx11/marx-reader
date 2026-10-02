"""Remove reviewed outside-container page credits from clickable note payloads."""
import argparse
import copy
import json
from pathlib import Path
from repair_source_short_lines import text_map
import hashlib

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_NOTE_TAIL_REVIEW_2026-10-02.json'


def restore(chapter, record):
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
    note = next(n for n in chapter['footnotes'] if n['id'] == record['noteId'])
    assert note['marker'] == record['marker'] and note['references'] == record['references']
    if note['content'] == record['sourceNoteContent']: return False
    assert note['content'] == record['previousNoteContent']
    assert note['content'][:-1] == record['sourceNoteContent']
    note['content'] = record['sourceNoteContent'][:]
    return True


def repair(apply=False):
    changed = {}
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')
        payload = changed.get(record['bookId'], (path, None))[1] or json.loads(path.read_text())
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        before = copy.deepcopy(chapter)
        if restore(chapter, record):
            assert chapter['content'] == before['content']
            assert chapter['sections'] == before['sections']
            assert [(n['id'], n['references']) for n in chapter['footnotes']] == [(n['id'], n['references']) for n in before['footnotes']]
            changed[record['bookId']] = (path, payload)
    if apply:
        for path, payload in changed.values(): path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
