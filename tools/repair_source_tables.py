#!/usr/bin/env python3
"""Repair reviewed tables and grouping panels by insertion only, preserving paragraph indices.

Dry-run by default. Requires verified source snapshots and rendered offline
source tables. Writes only after validating every source cell and footnote.
"""
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path
from bs4 import BeautifulSoup
from text_encoding import decode_html
from library_source_tables import BOUNDARY, MANIFEST

TABLE_MARKER = re.compile(r'〔原表\d+〕')


def cell_map(chapter):
    chars, positions = [], []
    for pi, paragraph in enumerate(chapter['content']):
        skipped = {i for m in TABLE_MARKER.finditer(paragraph) for i in range(m.start(), m.end())}
        for i, char in enumerate(paragraph):
            if not char.isspace() and i not in skipped:
                chars.append(char); positions.append((pi, i))
    return ''.join(chars), positions


def table_boundaries(chapter, record):
    compact = lambda text: re.sub(r'\s+', '', text)
    rows = [[compact(c) for c in row] for row in record['rows']]
    flat = ''.join(c for row in rows for c in row)
    text, positions = cell_map(chapter)
    start = text.find(flat)
    if start < 0 or text.find(flat, start + 1) >= 0:
        raise ValueError(f"Table text not uniquely aligned: {record['chapterId']} / {record['tableIndex']}")
    boundaries = {}
    cursor = start
    for ri, row in enumerate(rows):
        nonempty = [c for c in row if c]
        for ci, cell in enumerate(nonempty):
            cursor += len(cell)
            if cursor == start + len(flat):
                continue
            left, right = positions[cursor - 1], positions[cursor]
            if left[0] == right[0]:
                # Existing paragraph boundaries already separate rows/cells.
                boundaries[left] = BOUNDARY + ('\n' if ci == len(nonempty) - 1 else '')
    return positions[start], boundaries


def apply_edits(chapter, edits, new_notes):
    original = list(chapter['content'])
    for pi, changes in edits.items():
        ordered = sorted(changes.items())
        for note in chapter.get('footnotes', []):
            for ref in note['references']:
                if ref['paragraphIndex'] == pi:
                    ref['start'] += sum(len(s) for pos, s in ordered if pos <= ref['start'])
                    ref['end'] += sum(len(s) for pos, s in ordered if pos < ref['end'])
        for repair in chapter.get('sourceTextRepairs', []):
            if repair['paragraphIndex'] == pi:
                repair['start'] += sum(len(s) for pos,s in ordered if pos <= repair['start'])
                repair['end'] += sum(len(s) for pos,s in ordered if pos < repair['end'])
        result, cursor = [], 0
        for pos, insertion in ordered:
            result.extend([original[pi][cursor:pos], insertion]); cursor = pos
        result.append(original[pi][cursor:])
        chapter['content'][pi] = ''.join(result)
    for note, pi, pos in new_notes:
        marker_pos = pos + sum(len(s) for p, s in edits[pi].items() if p < pos)
        # A table marker is the first insertion at its own position.
        actual = chapter['content'][pi].find(note['marker'], marker_pos)
        assert actual == marker_pos
        note['references'] = [{'paragraphIndex': pi, 'start': actual, 'end': actual + len(note['marker'])}]
        chapter.setdefault('footnotes', []).append(note)
    for before, after in zip(original, chapter['content']):
        stripped = TABLE_MARKER.sub('', after).replace(BOUNDARY + '\n', '').replace(BOUNDARY, '')
        # Initial source content has no inserted markers or boundaries.
        baseline = TABLE_MARKER.sub('', before).replace(BOUNDARY + '\n', '').replace(BOUNDARY, '')
        assert stripped == baseline, 'A non-formatting source character changed'


def verify_boundaries(chapter, record):
    _, boundaries = table_boundaries(chapter, record)
    for (pi, left), _ in boundaries.items():
        # Every reviewed adjacent source-cell pair now has a visible separator.
        assert chapter['content'][pi][left + 1].isspace(), (record['chapterId'], record['tableIndex'], pi, left)


def repair(library, evidence, apply=False):
    records = json.loads(MANIFEST.read_text())['tables']
    changes = {}; added = 0; originals = {}; updated_books = {}
    for bid in sorted({r['bookId'] for r in records}):
        path = library / 'books' / f'{bid}.json'
        payload = json.loads(path.read_text()); book = payload['books'][0]; before = copy.deepcopy(book)
        originals[bid] = before; updated_books[bid] = book
        for chapter in book['chapters']:
            selected = [r for r in records if r['bookId'] == bid and r['chapterId'] == chapter['id']]
            edits, new_notes = {}, []
            for record in selected:
                url = record['sourceUrl']; key = hashlib.sha256(url.encode()).hexdigest()
                raw = (evidence / 'snapshots' / (key + '.bin')).read_bytes()
                assert hashlib.sha256(raw).hexdigest() == record['sourceRawSha256']
                meta = json.loads((evidence / 'snapshots' / (key + '.json')).read_text())
                soup = BeautifulSoup(decode_html(raw, meta.get('charset')), 'lxml')
                source_table = soup.find_all('table')[record['tableIndex']]
                assert hashlib.sha256(str(source_table).encode()).hexdigest() == record['tableHtmlSha256']
                assert hashlib.sha256((library.parent / record['assetPath']).read_bytes()).hexdigest() == record['imageSha256']
                ordinal = record['tableIndex'] + 1
                note_id, marker = f'source-table-{ordinal:03d}', f'〔原表{ordinal}〕'
                existing = next((n for n in chapter.get('footnotes', []) if n['id'] == note_id), None)
                if existing:
                    assert existing['imageAsset'] == record['assetPath'] and existing['imageSha256'] == record['imageSha256']
                    verify_boundaries(chapter, record)
                    continue
                (pi, position), boundaries = table_boundaries(chapter, record)
                edits.setdefault(pi, {})[position] = marker
                for (row_pi, left), separator in boundaries.items():
                    edits.setdefault(row_pi, {})[left + 1] = separator
                note = {'id': note_id, 'marker': marker,
                        'content': ['源页文字与原始括号图片合排的离线图式，保留分组关系。' if record.get('kind') == 'grouping_diagram' else '按源页 HTML 单元格排成的离线表格，保留合并行列；未改写数字。'],
                        'imageAsset': record['assetPath'], 'imageSha256': record['imageSha256'],
                        'sourceUrl': url, 'sourceTableIndex': record['tableIndex']}
                new_notes.append((note, pi, position)); added += 1
            apply_edits(chapter, edits, new_notes)
            for record in selected:
                verify_boundaries(chapter, record)
        if book != before:
            assert book['toc'] == before['toc']
            assert [(c['id'], len(c['content']), c.get('sections')) for c in book['chapters']] == [(c['id'], len(c['content']), c.get('sections')) for c in before['chapters']]
            changes[bid] = (path, payload)
        for chapter in book['chapters']:
            for note in chapter.get('footnotes', []):
                for ref in note['references']:
                    assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
    catalog_path = library / 'catalog.json'; catalog = json.loads(catalog_path.read_text())
    for i, metadata in enumerate(catalog['books']):
        if metadata['id'] not in changes: continue
        full = copy.deepcopy(changes[metadata['id']][1]['books'][0])
        for chapter in full['chapters']:
            counts = [len(p) for p in chapter.pop('content')]
            chapter.update(paragraphCount=len(counts), paragraphCharacterCounts=counts, characterCount=sum(counts))
            chapter.pop('footnotes', None)
            chapter.pop('sourceTextRepairs', None)
        full['characterCount'] = sum(c['characterCount'] for c in full['chapters'])
        catalog['books'][i] = full
    # Existing editorial footnote corrections are bound to exact paragraph hashes.
    # Refresh only when the entire original paragraph survives insertion removal.
    supplement_path = Path(__file__).with_name('library_footnote_supplements.json')
    supplements = json.loads(supplement_path.read_text()); refreshed = 0
    for kind in ('records', 'exclusions'):
        for record in supplements[kind]:
            bid = record['bookId']
            if bid not in updated_books: continue
            chapter = next(c for c in updated_books[bid]['chapters'] if c['id'] == record['chapterId'])
            text = chapter['content'][record['paragraphIndex']]
            current_hash = hashlib.sha256(text.encode()).hexdigest()
            if current_hash == record['paragraphSha256']: continue
            old_book = originals[bid]
            prior_path = evidence / 'before-table-repair' / f'{bid}.json'
            if prior_path.exists(): old_book = json.loads(prior_path.read_text())['books'][0]
            old_chapter = next(c for c in old_book['chapters'] if c['id'] == record['chapterId'])
            old_text = old_chapter['content'][record['paragraphIndex']]
            assert hashlib.sha256(old_text.encode()).hexdigest() == record['paragraphSha256']
            ignored = {i for m in re.finditer(r'〔原表\d+〕|\u2002\n?', text) for i in range(m.start(), m.end())}
            raw_positions = [i for i in range(len(text)) if i not in ignored]
            assert ''.join(text[i] for i in raw_positions) == old_text
            assert old_text[record['start']:record['start'] + len(record['marker'])] == record['marker']
            start = raw_positions[record['start']]
            assert text[start:start + len(record['marker'])] == record['marker']
            record.update(start=start, paragraphSha256=current_hash); refreshed += 1
    if apply and (changes or refreshed):
        backup = evidence / 'before-table-repair'; backup.mkdir(exist_ok=True)
        for path, payload in changes.values():
            if not (backup / path.name).exists(): (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        if not (backup / 'catalog.json').exists(): (backup / 'catalog.json').write_bytes(catalog_path.read_bytes())
        if changes: catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
        if refreshed:
            if not (backup / supplement_path.name).exists(): (backup / supplement_path.name).write_bytes(supplement_path.read_bytes())
            supplement_path.write_text(json.dumps(supplements, ensure_ascii=False, indent=2) + '\n')
    return {'changedBooks': list(changes), 'tablesRestored': added, 'footnoteSupplementsRefreshed': refreshed, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--evidence', type=Path, default=Path('.generated/source-audit-20261001'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.evidence, args.apply), ensure_ascii=False, indent=2))
