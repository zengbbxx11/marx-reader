"""Add reviewed TOC locations where original heading text is already intact."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from package_library_v2 import make_toc
from repair_source_short_lines import refresh_catalog, text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_HEADING_LOCATIONS_REVIEW_2026-10-02.json'


def restore(chapter, record):
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
    changed = False
    before = copy.deepcopy(chapter)
    for reviewed in record['sections']:
        section = {k: reviewed[k] for k in ['id', 'title', 'level', 'paragraphIndex']}
        assert section['title'] in chapter['content'][section['paragraphIndex']]
        existing = next((s for s in chapter['sections'] if s['id'] == section['id']), None)
        if existing:
            assert existing == section
        else:
            assert not any(s['title'] == section['title'] for s in chapter['sections'])
            chapter['sections'].append(section); changed = True
    chapter['sections'].sort(key=lambda s: s['paragraphIndex'])
    assert chapter['content'] == before['content']
    assert chapter['footnotes'] == before['footnotes']
    return changed


def repair(apply=False):
    library = ROOT / 'app/src/main/assets/library'; changed = {}
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = library / 'books' / (record['bookId'] + '.json'); payload = json.loads(path.read_text()); book = payload['books'][0]
        chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record):
            ids = {n['id'] for n in book['toc']}
            book['toc'].extend(n for n in make_toc(book) if n['id'] not in ids)
            assert len({n['id'] for n in book['toc']}) == len(book['toc'])
            changed[record['bookId']] = (path, payload)
    if apply and changed:
        catalog_path, catalog = refresh_catalog(library, changed)
        for path, payload in changed.values(): path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
