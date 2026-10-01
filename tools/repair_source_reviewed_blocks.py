"""Apply insertion-only changes from reviewed blocks after complete source alignment."""
import argparse
import collections
import copy
import difflib
import hashlib
import json
from pathlib import Path

from build_full_chinese_library import extract_chapter
from repair_library_text import repair_book
from repair_source_short_lines import text_map, refresh_catalog
from package_library_v2 import make_toc
from text_encoding import decode_html


def repair(library, evidence, apply=False):
    reviews = json.loads((evidence / 'review.json').read_text())
    selected = [r for r in reviews if r['bookId'] in ('lenin-work-b2eeb64ece7d', 'lenin-work-5409317c986a', 'manifesto-zh-1920')]
    changed, results = {}, []
    for bid in sorted({r['bookId'] for r in selected}):
        path = library / 'books' / (bid + '.json'); payload = json.loads(path.read_text()); book = payload['books'][0]
        before = copy.deepcopy(book)
        for record in (r for r in selected if r['bookId'] == bid):
            chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
            url = record['sourceUrl']; meta = record['freshEvidence']
            raw = (evidence / 'snapshots' / (hashlib.sha256(url.encode()).hexdigest() + '.bin')).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == record['sourceRawSha256'] == meta['rawSha256']
            source = extract_chapter(url, chapter['title'], decode_html(raw, meta.get('charset')), 1)
            assert source is not None
            temporary = copy.deepcopy(book); temporary['chapters'] = [source]
            repair_book(temporary, collections.Counter(), {})
            old, positions = text_map(chapter); new, _ = text_map(source)
            edits = {}
            for op, a, b, j, k in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
                assert op in ('equal', 'insert'), (bid, chapter['id'], op, old[a:b], new[j:k])
                if op == 'insert':
                    pi, at = positions[a] if a < len(positions) else (len(chapter['content']) - 1, len(chapter['content'][-1]))
                    value = new[j:k]
                    if bid == 'lenin-work-b2eeb64ece7d':
                        value = next(s['title'] for s in source['sections'] if ''.join(s['title'].split()) == value) + '\n'
                    elif bid == 'manifesto-zh-1920':
                        value = value.replace('章', '章 ', 1)
                        if '章' in value: value = value.replace('（', '\n（') if '（' in value else value + '\n'
                    elif bid == 'lenin-work-5409317c986a':
                        value = '\u2002' + value
                    edits.setdefault(pi, []).append((at, value))
            for pi, changes in edits.items():
                # Preserve existing restoration spans as well as clickable references.
                for spans in ([ref for n in chapter.get('footnotes', []) for ref in n['references']], chapter.get('sourceTextRepairs', [])):
                    for span in spans:
                        if span['paragraphIndex'] != pi: continue
                        span['start'] += sum(len(s) for at, s in changes if at <= span['start'])
                        span['end'] += sum(len(s) for at, s in changes if at < span['end'])
                original = chapter['content'][pi]
                for at, value in reversed(changes):
                    chapter['content'][pi] = chapter['content'][pi][:at] + value + chapter['content'][pi][at:]
                recovered = chapter['content'][pi]
                shift = 0; ranges = []
                for at, value in changes:
                    ranges.append((at + shift, len(value))); shift += len(value)
                for at, size in reversed(ranges): recovered = recovered[:at] + recovered[at + size:]
                assert recovered == original
            assert text_map(chapter)[0] == new
            for section in source['sections']:
                if any(s['title'] == section['title'] for s in chapter['sections']): continue
                heading = ''.join(section['title'].split())
                candidates = [i for i, p in enumerate(chapter['content']) if ''.join(p.split()).startswith(heading)]
                assert len(candidates) == 1, section
                chapter['sections'].append({'id': 'source-block-' + hashlib.sha1((url + heading).encode()).hexdigest()[:12],
                                            'title': section['title'], 'level': section['level'], 'paragraphIndex': candidates[0]})
            chapter['sections'].sort(key=lambda s: s['paragraphIndex'])
            for note in chapter['footnotes']:
                for ref in note['references']:
                    assert chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']] == note['marker']
            results.append({'bookId': bid, 'chapterId': chapter['id'], 'chapterTitle': chapter['title'],
                            'sourceUrl': url, 'sourceRawSha256': meta['rawSha256'],
                            'insertedRanges': sum(len(v) for v in edits.values())})
        if book != before:
            assert [(c['id'], len(c['content'])) for c in book['chapters']] == [(c['id'], len(c['content'])) for c in before['chapters']]
            previous_ids = {n['id'] for n in book['toc']}
            book['toc'].extend(n for n in make_toc(book) if n['id'] not in previous_ids)
            changed[bid] = (path, payload)
    catalog_path, catalog = refresh_catalog(library, changed)
    if apply and changed:
        backup = evidence / 'before-block-repair'; backup.mkdir(exist_ok=True)
        for path, payload in changed.values():
            if not (backup / path.name).exists(): (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        if not (backup / 'catalog.json').exists(): (backup / 'catalog.json').write_bytes(catalog_path.read_bytes())
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': list(changed), 'reviews': results, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--evidence', type=Path, default=Path('.generated/source-unresolved-review-20261001'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.evidence, args.apply), ensure_ascii=False, indent=2))
