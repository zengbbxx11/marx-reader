"""Remove reviewed duplicate editor definitions from the final author note."""
import argparse
import hashlib
import json
from pathlib import Path
from repair_source_short_lines import text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_SECOND_INTERNATIONAL_NOTE_REVIEW_2026-10-02.json'


def restore(chapter, record):
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
    notes = {n['id']: n for n in chapter['footnotes']}
    note = notes[record['noteId']]
    assert note['marker'] == record['marker'] and note['references'] == record['references']
    for editor in record['independentEditorNotes']:
        assert notes[editor['id']] == editor
    if note['content'] == record['sourceNoteContent']:
        return False
    assert note['content'] == record['previousNoteContent']
    size = len(record['sourceNoteContent'])
    assert note['content'][:size] == record['sourceNoteContent']
    assert note['content'][size:] == [n['marker'] + ''.join(n['content']) for n in record['independentEditorNotes']]
    note['content'] = record['sourceNoteContent'][:]
    return True


def repair(apply=False):
    changed = []
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')
        payload = json.loads(path.read_text())
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record):
            changed.append(record['bookId'])
            if apply:
                path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': changed, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
