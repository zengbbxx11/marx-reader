"""Apply evidenced note supplements and retain unresolved version conflicts."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

MANIFEST = Path(__file__).with_name('library_source_gap_reviews.json')


def repair(library, apply=False):
    reviews = json.loads(MANIFEST.read_text())['notes']
    supplement_path = Path(__file__).with_name('library_footnote_supplements.json')
    supplements = json.loads(supplement_path.read_text()); before_supplements = copy.deepcopy(supplements)
    changes = {}
    for record in reviews:
        path = library / 'books' / (record['bookId'] + '.json')
        payload = changes.get(path) or json.loads(path.read_text()); before = copy.deepcopy(payload)
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        note = next(n for n in chapter['footnotes'] if n['id'] == record['noteId'])
        ref = note['references'][0]; paragraph = chapter['content'][ref['paragraphIndex']]
        assert note['marker'] == record['marker'] == paragraph[ref['start']:ref['end']]
        assert hashlib.sha256(paragraph.encode()).hexdigest() == record['paragraphSha256']
        expected_status = 'SOURCE_MISSING' if record['status'] in ('version_conflict_pending', 'verified_source_gap') else None
        assert note.get('status') in ('SOURCE_MISSING', expected_status)
        if expected_status: note['status'] = expected_status
        else: note.pop('status', None)
        note.update(content=record['displayContent'], sourceUrl=record['evidence']['url'],
                    sourceRawSha256=record['evidence']['archiveSha256'], sourceEvidence=record['evidence'])
        supplement = next(r for r in supplements['records'] if r['bookId'] == record['bookId']
                          and r['chapterId'] == record['chapterId'] and r['paragraphIndex'] == ref['paragraphIndex']
                          and r['start'] == ref['start'] and r['marker'] == record['marker'])
        supplement.update(content=record['displayContent'], source=record['evidence'])
        if expected_status: supplement['status'] = expected_status
        else: supplement.pop('status', None)
        assert chapter['content'] == next(c for c in before['books'][0]['chapters'] if c['id'] == record['chapterId'])['content']
        assert payload['books'][0]['toc'] == before['books'][0]['toc']
        assert [(n['id'], n['references']) for n in chapter['footnotes']] == [(n['id'], n['references']) for n in next(c for c in before['books'][0]['chapters'] if c['id'] == record['chapterId'])['footnotes']]
        if payload != before: changes[path] = payload
    if apply:
        backup = Path('.generated/source-final-gaps-20261001/before-gap-notes'); backup.mkdir(exist_ok=True)
        for path, payload in changes.items():
            if not (backup / path.name).exists(): (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        if supplements != before_supplements:
            if not (backup / supplement_path.name).exists(): (backup / supplement_path.name).write_bytes(supplement_path.read_bytes())
            supplement_path.write_text(json.dumps(supplements, ensure_ascii=False, indent=2) + '\n')
    return {'changedBooks': [p.stem for p in changes], 'supplementsChanged': supplements != before_supplements,
            'verifiedNotes': sum(r['status'] == 'fixed_verified' for r in reviews),
            'verifiedSourceGaps': sum(r['status'] == 'verified_source_gap' for r in reviews),
            'versionConflictsPending': sum(r['status'] == 'version_conflict_pending' for r in reviews), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.apply), ensure_ascii=False, indent=2))
