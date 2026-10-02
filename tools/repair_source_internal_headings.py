"""Restore reviewed internal headings without changing existing paragraph indices."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from package_library_v2 import make_toc
from repair_source_short_lines import refresh_catalog, text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_INTERNAL_HEADINGS_REVIEW_2026-10-02.json'


def restore_added_notes(chapter, record):
    added = False
    for note in record.get('addedFootnotes', []):
        existing = next((n for n in chapter['footnotes'] if n['id'] == note['id']), None)
        if existing:
            assert existing == note
        else:
            for ref in note['references']:
                assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
            chapter['footnotes'].append(copy.deepcopy(note)); added = True
    return added


def restore(chapter, record):
    digest = hashlib.sha256(text_map(chapter)[0].encode()).hexdigest()
    if digest == record['localBodySha256']:
        for change in record['changes']:
            assert chapter['content'][change['paragraphIndex']] == change['currentParagraph']
            assert all(any(s['title'] == heading['title'] and s['paragraphIndex'] == change['paragraphIndex'] for s in chapter['sections']) for heading in change['sections'])
        return restore_added_notes(chapter, record)
    assert digest == record['previousBodySha256']
    before = copy.deepcopy(chapter)
    for change in record['changes']:
        pi = change['paragraphIndex']; prefix = change['prefix']
        at = change.get('insertionOffset', 0)
        assert chapter['content'][pi] == change['previousParagraph']
        assert change['currentParagraph'] == change['previousParagraph'][:at] + prefix + change['previousParagraph'][at:]
        chapter['content'][pi] = change['currentParagraph']
        for spans in ([r for n in chapter.get('footnotes', []) for r in n['references']], chapter.get('sourceTextRepairs', [])):
            for span in spans:
                if span['paragraphIndex'] == pi:
                    if at <= span['start']: span['start'] += len(prefix)
                    if at < span['end']: span['end'] += len(prefix)
        for heading in change['sections']:
            chapter['sections'].append({'id': 'source-internal-' + hashlib.sha1((record['sourceUrl'] + heading['title'] + (str(pi) if record.get('sectionIdsIncludeParagraphIndex') else '')).encode()).hexdigest()[:12], **heading, 'paragraphIndex': pi})
    chapter['sections'].sort(key=lambda s: s['paragraphIndex'])
    assert len(chapter['content']) == len(before['content']) == record['paragraphCount']
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
    for old, new in zip(before.get('footnotes', []), chapter.get('footnotes', [])):
        assert (old['id'], old['marker'], old['content']) == (new['id'], new['marker'], new['content'])
        for a, b in zip(old['references'], new['references']):
            assert a['paragraphIndex'] == b['paragraphIndex']
            assert before['content'][a['paragraphIndex']][a['start']:a['end']] == chapter['content'][b['paragraphIndex']][b['start']:b['end']]
    restore_added_notes(chapter, record)
    return True


def repair(apply=False):
    library = ROOT / 'app/src/main/assets/library'; changed = {}
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = library / 'books' / (record['bookId'] + '.json')
        payload = changed.get(record['bookId'], (path, None))[1] or json.loads(path.read_text())
        book = payload['books'][0]; chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record):
            ids = {n['id'] for n in book['toc']}
            book['toc'].extend(n for n in make_toc(book) if n['id'] not in ids)
            changed[record['bookId']] = (path, payload)
    if apply and changed:
        catalog_path, catalog = refresh_catalog(library, changed)
        for path, payload in changed.values(): path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
