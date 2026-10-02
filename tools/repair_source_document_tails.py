"""Append verified source tails truncated by embedded document-ending tags."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from package_library_v2 import make_toc
from repair_source_short_lines import refresh_catalog, text_map

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_DOCUMENT_TAIL_REVIEW_2026-10-02.json'


def restore(chapter, record):
    before = copy.deepcopy(chapter)
    assert chapter['content'][:record['previousParagraphCount']] == record['previousParagraphs']
    if len(chapter['content']) == record['restoredParagraphCount']:
        assert chapter['content'][record['previousParagraphCount']:] == record['appendedParagraphs']
        assert all(s in chapter['sections'] for s in record['addedSections'])
        assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
        return False
    assert len(chapter['content']) == record['previousParagraphCount']
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['previousBodySha256']
    chapter['content'].extend(record['appendedParagraphs'])
    chapter['sections'].extend(copy.deepcopy(record['addedSections']))
    assert len({s['id'] for s in chapter['sections']}) == len(chapter['sections'])
    assert chapter['content'][:len(before['content'])] == before['content']
    assert chapter['footnotes'] == before['footnotes']
    assert chapter['sections'][:len(before['sections'])] == before['sections']
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['localBodySha256']
    return True


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
