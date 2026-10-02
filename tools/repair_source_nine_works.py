"""Apply the signed nine-work review, retaining paragraph and reader anchor IDs."""
import argparse
import copy
import json
from pathlib import Path
from package_library_v2 import make_toc
from repair_source_short_lines import refresh_catalog

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_NINE_WORKS_REVIEW_2026-10-02.json'


def restore(chapter, record):
    if chapter == record['currentChapter']:
        return False
    assert chapter == record['previousChapter'], 'Reviewed chapter changed; recheck its source'
    assert chapter['id'] == record['currentChapter']['id']
    assert len(chapter['content']) == len(record['currentChapter']['content'])
    chapter.clear()
    chapter.update(copy.deepcopy(record['currentChapter']))
    return True


def repair(apply=False):
    library = ROOT / 'app/src/main/assets/library'
    changed = {}
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = library / 'books' / (record['bookId'] + '.json')
        payload = json.loads(path.read_text())
        book = payload['books'][0]
        chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record):
            old_ids = {n['id'] for n in book['toc']}
            book['toc'].extend(n for n in make_toc(book) if n['id'] not in old_ids)
            changed[record['bookId']] = (path, payload)
    if apply and changed:
        catalog_path, catalog = refresh_catalog(library, changed)
        for path, payload in changed.values():
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
