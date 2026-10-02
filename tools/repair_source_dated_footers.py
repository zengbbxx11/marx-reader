"""Append verified source dates without moving existing paragraph or note anchors."""
import argparse
import json
from repair_source_document_tails import ROOT, restore
from repair_source_short_lines import refresh_catalog

REVIEW = ROOT / 'docs/SOURCE_DATED_FOOTERS_REVIEW_2026-10-02.json'


def repair(apply=False):
    library = ROOT / 'app/src/main/assets/library'
    changed = {}
    for record in json.loads(REVIEW.read_text())['repairs']:
        path = library / 'books' / (record['bookId'] + '.json')
        payload = changed.get(record['bookId'], (path, None))[1] or json.loads(path.read_text())
        chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == record['chapterId'])
        if restore(chapter, record):
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
