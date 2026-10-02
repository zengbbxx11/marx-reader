"""Generate exact-hash mappings for old notes spanning verified insertion repairs."""
import difflib
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def generate():
    mappings={}
    # Portable evidence retains this mapping even when local snapshots are absent.
    review = ROOT/'docs/SOURCE_LONG_WAR_REVIEW_2026-10-02.json'
    if review.exists():
        mapping = json.loads(review.read_text())['restorationMapping']
        mappings[(mapping['currentHash'], mapping['previousHash'])] = [tuple(r) for r in mapping['ranges']]
    filter_review = ROOT/'docs/SOURCE_FILTER_REVIEW_2026-10-02.json'
    if filter_review.exists():
        for record in json.loads(filter_review.read_text())['repairs']:
            mapping = record['restorationMapping']
            mappings[(mapping['currentHash'], mapping['previousHash'])] = [tuple(r) for r in mapping['ranges']]
    container_review = ROOT/'docs/SOURCE_CONTAINER_REVIEW_2026-10-02.json'
    if container_review.exists():
        for record in json.loads(container_review.read_text())['repairs']:
            mapping = record['restorationMapping']
            mappings[(mapping['currentHash'], mapping['previousHash'])] = [tuple(r) for r in mapping['ranges']]
    for folder in ['before-repair','before-layout-repair','before-table-repair','before-short-line-repair','before-source-note-repair']:
        paths = list((ROOT/'.generated/source-audit-20261001'/folder).glob('*.json'))
        if folder == 'before-repair':
            paths += list((ROOT/'.generated/source-attachments-20261001/before-attachment-repair').glob('*.json'))
            paths += list((ROOT/'.generated/source-unresolved-review-20261001/before-block-repair').glob('*.json'))
        for path in paths:
            if path.name=='catalog.json' or path.name=='library_footnote_supplements.json':continue
            payload=json.loads(path.read_text())
            if 'books' not in payload:continue
            old_book=payload['books'][0];new_path=ROOT/'app/src/main/assets/library/books'/path.name
            if not new_path.exists():continue
            new_book=json.loads(new_path.read_text())['books'][0];chapters={c['id']:c for c in new_book['chapters']}
            for old_chapter in old_book['chapters']:
                new_chapter=chapters[old_chapter['id']];assert len(old_chapter['content'])==len(new_chapter['content'])
                for before,after in zip(old_chapter['content'],new_chapter['content']):
                    if before==after:continue
                    changes=difflib.SequenceMatcher(None,before,after,autojunk=False).get_opcodes()
                    if any(tag not in ('equal','insert') for tag,*_ in changes):continue
                    ranges=[(j,k-1) for tag,a,b,j,k in changes if tag=='insert']
                    recovered=''.join(c for i,c in enumerate(after) if not any(a<=i<=b for a,b in ranges));assert recovered==before
                    key=(hashlib.sha256(after.encode()).hexdigest(),hashlib.sha256(before.encode()).hexdigest())
                    if key in mappings:assert mappings[key]==ranges
                    mappings[key]=ranges
    lines=['// Generated from verified insertion-only source repairs; do not edit by hand.',
           'package org.marxreader.app.data','',
           'internal fun sourceRestorationRanges(currentHash: String, previousHash: String): List<IntRange> =',
           '    sourceRestorationRangesByHash[currentHash to previousHash].orEmpty()','',
           'private val sourceRestorationRangesByHash: Map<Pair<String, String>, List<IntRange>> = mapOf(']
    for (after,before),ranges in sorted(mappings.items()):
        values=', '.join(f'{a}..{b}' for a,b in ranges)
        lines.append(f'    ("{after}" to "{before}") to listOf({values}),')
    lines+=[')','']
    (ROOT/'app/src/main/java/org/marxreader/app/data/SourceTextRestorations.kt').write_text('\n'.join(lines))
    print('Exact paragraph-hash mappings:',len(mappings))

if __name__=='__main__':generate()
