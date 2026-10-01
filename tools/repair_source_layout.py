"""Restore reviewed inline images and numbered headings; dry-run unless --apply.

Uses source-audit snapshots with SHA-256 verification. Preserves chapter IDs,
paragraph indices, existing section/TOC IDs, prose, and old footnote references.
"""
import argparse
import collections
import copy
import hashlib
import json
from pathlib import Path

from audit_source_content import compact
from build_full_chinese_library import extract_chapter
from package_library_v2 import make_toc
from repair_library_text import repair_book
from repair_source_content import insert_formulas
from text_encoding import decode_html


def repair(library: Path, evidence: Path, apply=False):
    review = json.loads(Path('docs/SOURCE_LAYOUT_REVIEW_2026-10-01.json').read_text())
    audit = json.loads((evidence / 'report.json').read_text())
    source_entries = {(b['id'], c['id']): c for b in audit['results'] for c in b['chapters']}
    images = [r for r in review['images'] if r['status'] in ('BODY_TABLE_IMAGE_MISSING', 'BODY_DIAGRAM_MISSING')]
    targets = {(r['bookId'], r['chapterId']) for x in images for r in x['references']}
    targets |= {(r['bookId'], r['chapterId']) for r in review['missingHeadings']}
    changes = {}
    count = headings = 0
    for bid in sorted({b for b, c in targets}):
        path = library / 'books' / f'{bid}.json'
        payload = json.loads(path.read_text()); book = payload['books'][0]
        before = copy.deepcopy(book)
        for chapter in book['chapters']:
            if (bid, chapter['id']) not in targets:
                continue
            entry = source_entries[bid, chapter['id']]
            raw = (evidence / 'snapshots' / (hashlib.sha256(entry['url'].encode()).hexdigest() + '.bin')).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == entry['evidence']['rawSha256']
            source = extract_chapter(entry['url'], chapter['title'], decode_html(raw, entry['evidence'].get('charset')), 1)
            temporary = copy.deepcopy(book); temporary['chapters'] = [source]
            repair_book(temporary, collections.Counter(), {})
            missing = [r for r in review['missingHeadings'] if r['bookId'] == bid and r['chapterId'] == chapter['id']]
            source_heading_indices = {s['paragraphIndex'] for s in source.get('sections', [])
                                      if s['title'] in {r['heading'] for r in missing}}
            expected_prose = ''.join(compact(p) for i, p in enumerate(source['content'])
                                     if i not in source_heading_indices)
            existing_prose = list(chapter['content'])
            for s in chapter.get('sections', []):
                if s['title'] in {r['heading'] for r in missing}:
                    prefix = s['title'] + '\n'
                    assert existing_prose[s['paragraphIndex']].startswith(prefix)
                    existing_prose[s['paragraphIndex']] = existing_prose[s['paragraphIndex']][len(prefix):]
            if missing:
                assert ''.join(compact(p) for p in existing_prose) == expected_prose, (bid, 'Source prose changed')
            # Insert only the reviewed heading at its unique following-prose anchor.
            for record in missing:
                title = record['heading']
                section = next(s for s in source['sections'] if s['title'] == title)
                pi = section['paragraphIndex']
                assert source['content'][pi] == title
                following = compact(source['content'][pi + 1])[:50]
                existing = next((s for s in chapter.get('sections', []) if s['title'] == title), None)
                if existing is None:
                    positions = [i for i, text in enumerate(chapter['content']) if compact(text).startswith(following)]
                    assert len(positions) == 1, (bid, title, following, positions)
                    target = positions[0]
                    chapter['content'][target] = title + '\n' + chapter['content'][target]
                    for note in chapter.get('footnotes', []):
                        for ref in note['references']:
                            if ref['paragraphIndex'] == target:
                                ref['start'] += len(title) + 1; ref['end'] += len(title) + 1
                    new_section = {'id': 'source-heading-' + hashlib.sha1((entry['url'] + title).encode()).hexdigest()[:10],
                                   'title': title, 'level': section['level'], 'paragraphIndex': target}
                    chapter.setdefault('sections', []).append(new_section)
                    headings += 1
                # Source prose alignment here only concerns images; headings are handled separately.
            if missing:
                chapter['sections'].sort(key=lambda s: s['paragraphIndex'])
            else:
                count += insert_formulas(chapter, source)
        if book == before:
            continue
        assert [(c['id'], len(c['content'])) for c in book['chapters']] == [(c['id'], len(c['content'])) for c in before['chapters']]
        # Keep existing TOC nodes exactly; append only genuinely new section nodes.
        previous_ids = {n['id'] for n in book['toc']}
        book['toc'].extend(n for n in make_toc(book) if n['id'] not in previous_ids)
        for chapter in book['chapters']:
            for note in chapter.get('footnotes', []):
                if note.get('imageAsset'):
                    assert hashlib.sha256((library.parent / note['imageAsset']).read_bytes()).hexdigest() == note['imageSha256']
                for ref in note['references']:
                    assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
        changes[bid] = (path, payload)
    catalog_path = library / 'catalog.json'; catalog = json.loads(catalog_path.read_text())
    for i, old in enumerate(catalog['books']):
        if old['id'] not in changes:
            continue
        full = copy.deepcopy(changes[old['id']][1]['books'][0])
        for chapter in full['chapters']:
            counts = [len(p) for p in chapter.pop('content')]
            chapter.update(paragraphCount=len(counts), paragraphCharacterCounts=counts, characterCount=sum(counts))
            chapter.pop('footnotes', None)
            chapter.pop('sourceTextRepairs', None)
        full['characterCount'] = sum(c['characterCount'] for c in full['chapters'])
        catalog['books'][i] = full
    if apply and changes:
        backup = evidence / 'before-layout-repair'; backup.mkdir(exist_ok=True)
        for path, payload in changes.values():
            if not (backup / path.name).exists(): (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        if not (backup / 'catalog.json').exists(): (backup / 'catalog.json').write_bytes(catalog_path.read_bytes())
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changes), 'imageReferencesAdded': count, 'headingsAdded': headings, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--evidence', type=Path, default=Path('.generated/source-audit-20261001'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.evidence, args.apply), ensure_ascii=False, indent=2))
