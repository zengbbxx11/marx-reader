"""Trim source-reviewed duplicate a-supplements without changing old anchors."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from repair_source_short_lines import text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_PRINTED_NOTE_TAIL_REVIEW_2026-10-02.json'


def restore(chapter, record):
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
    notes = {n['id']: n for n in chapter['footnotes']}
    note = notes[record['noteId']]
    assert note['marker'] == record['marker'] and note['references'] == record['references']
    for independent in record['independentPrintedNotes']:
        assert notes[independent['id']] == independent
    if note['content'] == record['sourceNoteContent']:
        return False
    assert note['content'] == record['previousNoteContent']
    size = len(record['sourceNoteContent'])
    assert note['content'][:size] == record['sourceNoteContent']
    compact = lambda t: re.sub(r'\s+', '', t)
    assert [compact(p) for p in note['content'][size:]] == [
        compact(n['marker'] + ''.join(n['content'])) for n in record['independentPrintedNotes']
    ]
    note['content'] = record['sourceNoteContent'][:]
    return True


def repair(apply=False):
    changed = {}
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')
        payload = changed.get(record['bookId'], (path, None))[1] or json.loads(path.read_text())
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record):
            changed[record['bookId']] = (path, payload)
    if apply:
        for path, payload in changed.values():
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
