"""Restore reviewed paragraph 88 without changing existing paragraph or note positions."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from repair_source_short_lines import refresh_catalog, text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_LONG_WAR_REVIEW_2026-10-02.json'


def restore(chapter, review):
    text = review['sourceParagraph']
    if text in '\n'.join(chapter['content']):
        note = next(n for n in chapter['footnotes'] if n['id'] == review['note']['id'])
        assert note['content'] == review['note']['content']
        for ref in note['references']:
            assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
        return False
    indices = [i for i, p in enumerate(chapter['content']) if p.startswith('（八七）')]
    assert len(indices) == 1
    pi = indices[0]
    assert chapter['content'][pi + 1].startswith('（八九）')
    assert not any(n['id'] == review['note']['id'] for n in chapter['footnotes'])
    start = len(chapter['content'][pi]) + 1
    chapter['content'][pi] += '\n' + text
    note = copy.deepcopy(review['note'])
    at = start + text.index(note['marker'])
    note['references'] = [{'paragraphIndex': pi, 'start': at, 'end': at + len(note['marker'])}]
    chapter['footnotes'].append(note)
    return True


def repair(apply=False):
    review = json.loads(REVIEW.read_text())
    library = ROOT / 'app/src/main/assets/library'
    path = library / 'books' / (review['bookId'] + '.json')
    payload = json.loads(path.read_text()); book = payload['books'][0]
    chapter = next(c for c in book['chapters'] if c['id'] == review['chapterId'])
    before = copy.deepcopy(chapter)
    changed = restore(chapter, review)
    assert len(chapter['content']) == len(before['content'])
    assert chapter['sections'] == before['sections']
    for note in before['footnotes']:
        assert note in chapter['footnotes']
    for note in chapter['footnotes']:
        for ref in note['references']:
            assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == review['alignedTextSha256']
    if changed and apply:
        catalog_path, catalog = refresh_catalog(library, {book['id']: (path, payload)})
        path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
        # Keep all previously verified mappings; append this independently reviewed insertion.
        mapping = ROOT / 'app/src/main/java/org/marxreader/app/data/SourceTextRestorations.kt'
        pi = review['paragraphIndex']; old = before['content'][pi]; new = chapter['content'][pi]
        entry = f'    ("{hashlib.sha256(new.encode()).hexdigest()}" to "{hashlib.sha256(old.encode()).hexdigest()}") to listOf({len(old)}..{len(new)-1}),\n'
        code = mapping.read_text()
        if entry not in code:
            at = code.rfind('\n)'); assert at >= 0
            mapping.write_text(code[:at] + '\n' + entry.rstrip('\n') + code[at:])
    return {'changed': changed, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
