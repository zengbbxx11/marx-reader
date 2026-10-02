"""Restore reviewed source note prose while preserving paragraph indices and IDs."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from repair_source_short_lines import text_map, refresh_catalog

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_FILTER_REVIEW_2026-10-02.json'


def restore(chapter, record):
    pi = record['paragraphIndex']
    text = record['sourceParagraph']
    note = next(n for n in chapter['footnotes'] if n['id'] == record['previousNote']['id'])
    expected = record['sourceNoteContent'] + ['PDF 校补对照（另一版本）：' + p for p in record['previousNote']['content']]
    if text in chapter['content'][pi]:
        assert note['content'] == expected
        return False
    assert hashlib.sha256(chapter['content'][pi].encode()).hexdigest() == record['previousParagraphSha256']
    assert chapter['content'][pi + 1].startswith('[94]')
    assert note == record['previousNote']
    chapter['content'][pi] += '\n' + text
    note['content'] = expected
    note['supplementComparison'] = record['previousNote']['content']
    note['sourceUrl'] = record['sourceUrl']
    note['sourceRawSha256'] = record['freshEvidence']['rawSha256']
    assert hashlib.sha256(text_map(chapter)[0].encode()).hexdigest() == record['alignedTextSha256']
    return True


def repair(apply=False):
    records = json.loads(REVIEW.read_text())['repairs']; changed = {}
    library = ROOT / 'app/src/main/assets/library'
    for record in records:
        path = library / 'books' / (record['bookId'] + '.json')
        payload = json.loads(path.read_text()); book = payload['books'][0]
        chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
        before = copy.deepcopy(chapter)
        if not restore(chapter, record): continue
        assert len(chapter['content']) == len(before['content'])
        assert chapter['sections'] == before['sections']
        assert [(n['id'], n['references']) for n in chapter['footnotes']] == [(n['id'], n['references']) for n in before['footnotes']]
        changed[book['id']] = (path, payload)
    if apply and changed:
        catalog_path, catalog = refresh_catalog(library, changed)
        manifest_path = ROOT / 'tools/library_footnote_supplements.json'
        manifest = json.loads(manifest_path.read_text())
        for record in records:
            if record['bookId'] not in changed: continue
            supplement = next(s for s in manifest['records'] if s['bookId'] == record['bookId'] and s['marker'] == '[93]')
            supplement['supplementComparison'] = {'content': supplement['content'], 'source': supplement['source']}
            supplement['content'] = record['sourceNoteContent'] + ['PDF 校补对照（另一版本）：' + p for p in record['previousNote']['content']]
            supplement['source'] = {'title': '中文网页原注（含源站编注）', 'url': record['sourceUrl'], 'rawSha256': record['freshEvidence']['rawSha256'], 'marker': '[93]'}
        mapping_path = ROOT / 'app/src/main/java/org/marxreader/app/data/SourceTextRestorations.kt'
        code = mapping_path.read_text()
        for record in records:
            if record['bookId'] not in changed: continue
            m = record['restorationMapping']; ranges = ', '.join(f'{a}..{b}' for a, b in m['ranges'])
            entry = f'    ("{m["currentHash"]}" to "{m["previousHash"]}") to listOf({ranges}),'
            if entry not in code:
                at = code.rfind('\n)'); assert at >= 0
                code = code[:at] + '\n' + entry + code[at:]
        for path, payload in changed.values():
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        mapping_path.write_text(code)
    return {'changedBooks': list(changed), 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    print(json.dumps(repair(parser.parse_args().apply)))
