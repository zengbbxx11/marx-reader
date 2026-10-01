"""Restore reviewed attachments from checked source snapshots; preview by default."""
import argparse
import collections
import copy
import hashlib
import json
import re
from pathlib import Path

from build_full_chinese_library import extract_chapter
from library_source_images import MANIFEST
from repair_library_text import repair_book
from repair_source_content import insert_formulas
from text_encoding import decode_html

ATTACHMENTS = re.compile(r'〔附件\d+〕')


def repair(library, evidence, apply=False):
    records = [r for r in json.loads(MANIFEST.read_text())['images']
               if r.get('kind') == 'source_attachment']
    pages = {p['url']: p for p in json.loads((evidence / 'pages.json').read_text())}
    targets = {(r['bookId'], r['chapterId']): r['sourceUrl']
               for image in records for r in image['references']}
    for image in records:
        assert hashlib.sha256((library.parent / image['assetPath']).read_bytes()).hexdigest() == image['sha256']
    changes, reviewed, unresolved = {}, [], []
    for bid in sorted({bid for bid, _ in targets}):
        path = library / 'books' / (bid + '.json')
        payload = json.loads(path.read_text()); book = payload['books'][0]
        before = copy.deepcopy(book)
        for chapter in book['chapters']:
            url = targets.get((bid, chapter['id']))
            if url is None: continue
            meta = pages[url]['evidence']
            raw = (evidence / 'snapshots' / (hashlib.sha256(url.encode()).hexdigest() + '.bin')).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == meta['rawSha256']
            source = extract_chapter(url, chapter['title'], decode_html(raw, meta.get('charset')), 1)
            if source is None:
                unresolved.append({'bookId': bid, 'chapterId': chapter['id'], 'reason': 'source extraction below minimum length'})
                continue
            temporary = copy.deepcopy(book); temporary['chapters'] = [source]
            repair_book(temporary, collections.Counter(), {})
            trial = copy.deepcopy(chapter)
            try:
                added = insert_formulas(trial, source, ATTACHMENTS)
            except AssertionError as exc:
                unresolved.append({'bookId': bid, 'chapterId': chapter['id'], 'reason': str(exc)})
                continue
            expected = [n for n in source['footnotes'] if ATTACHMENTS.fullmatch(n['marker'])]
            assert len(expected) == sum(r['sourceUrl'] == url for image in records for r in image['references'])
            chapter.update(trial)
            for note in chapter.get('footnotes', []):
                for ref in note['references']:
                    assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
            reviewed.append({'bookId': bid, 'chapterId': chapter['id'], 'chapterTitle': chapter['title'],
                             'sourceUrl': url, 'sourceRawSha256': meta['rawSha256'],
                             'referencesAdded': added, 'referencesVerified': len(expected)})
        if book != before:
            assert [(c['id'], len(c['content'])) for c in book['chapters']] == [(c['id'], len(c['content'])) for c in before['chapters']]
            assert book['toc'] == before['toc']
            changes[bid] = (path, payload)
    catalog_path = library / 'catalog.json'; catalog = json.loads(catalog_path.read_text())
    for i, old in enumerate(catalog['books']):
        if old['id'] not in changes: continue
        full = copy.deepcopy(changes[old['id']][1]['books'][0])
        for chapter in full['chapters']:
            counts = [len(p) for p in chapter.pop('content')]
            chapter.update(paragraphCount=len(counts), paragraphCharacterCounts=counts, characterCount=sum(counts))
            chapter.pop('footnotes', None); chapter.pop('sourceTextRepairs', None)
        full['characterCount'] = sum(c['characterCount'] for c in full['chapters'])
        catalog['books'][i] = full
    if apply and changes:
        backup = evidence / 'before-attachment-repair'; backup.mkdir(exist_ok=True)
        for path, payload in changes.values():
            if not (backup / path.name).exists(): (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        if not (backup / 'catalog.json').exists(): (backup / 'catalog.json').write_bytes(catalog_path.read_bytes())
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changes), 'reviewed': reviewed, 'unresolved': unresolved, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--evidence', type=Path, default=Path('.generated/source-attachments-20261001'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.evidence, args.apply), ensure_ascii=False, indent=2))
