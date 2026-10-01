#!/usr/bin/env python3
"""Insert reviewed missing source characters, preserving paragraph and footnote IDs."""
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path
from package_library_v2 import make_toc

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'docs/SOURCE_SHORT_LINE_REVIEW_2026-10-01.json'
MANIFEST = ROOT / 'tools/library_source_short_lines.json'
MARKERS = re.compile(r'〔(?:原表|图式|附件)\d+〕')


def text_map(chapter):
    chars, positions = [], []
    for pi, text in enumerate(chapter['content']):
        ignored = {i for m in MARKERS.finditer(text) for i in range(m.start(), m.end())}
        for i, c in enumerate(text):
            if not c.isspace() and i not in ignored: chars.append(c); positions.append((pi, i))
    return ''.join(chars), positions


def insert(chapter, records):
    plain, positions = text_map(chapter); edits = {}; sections = []; inserted = 0
    for r in records:
        repair_id = 'short-line-' + hashlib.sha256((r['sourceUrl'] + ':' + str(r['sourceLineIndex'])).encode()).hexdigest()[:16]
        existing = next((x for x in chapter.get('sourceTextRepairs', []) if x['id'] == repair_id), None)
        if existing:
            assert re.sub(r'\s+', '', MARKERS.sub('', chapter['content'][existing['paragraphIndex']][existing['start']:existing['end']])) == re.sub(r'\s+', '', existing['text'])
            continue
        left, right, value = r['leftContext'], r['rightContext'], r['text']
        complete = left + value + right
        if plain.count(complete) == 1: continue
        missing = left + right
        if plain.count(missing) != 1: raise ValueError(f"Source insertion not uniquely aligned: {chapter['id']} / {value!r}")
        offset = plain.index(missing) + len(left)
        heading = r['reviewCategory'] in ('CONFIRMED_HEADING_OMISSION', 'CONFIRMED_INDEX_HEADING_OMISSION')
        if heading or r['reviewCategory'] in ('CONFIRMED_NOTE_NUMBER_OMISSION', 'CONFIRMED_NOTE_HEADING_OMISSION'):
            pi, at = positions[offset]; text = value + '\n'
        else:
            pi, previous = positions[offset - 1]; at = previous + 1; text = value
        edits.setdefault(pi, {}).setdefault(at, []).append((r['sourceLineIndex'], text, repair_id, r['sourceRawSha256']))
        if heading:
            sections.append({'id':'source-short-heading-' + hashlib.sha1((r['sourceUrl'] + str(r['sourceLineIndex']) + value).encode()).hexdigest()[:12],
                             'title':value, 'level':3 if r['bookId'] in ('lenin-work-5409317c986a','marx-work-6523f0586337') else 2,
                             'paragraphIndex':pi})
        inserted += 1
    shifts = {}
    for pi, changes in edits.items():
        ordered = [(at, ''.join(s for _, s, _, _ in sorted(group))) for at, group in sorted(changes.items())]
        shifts[pi] = ordered
        for note in chapter.get('footnotes', []):
            for ref in note['references']:
                if ref['paragraphIndex'] == pi:
                    ref['start'] += sum(len(s) for at,s in ordered if at <= ref['start'])
                    ref['end'] += sum(len(s) for at,s in ordered if at < ref['end'])
        for at, group in sorted(changes.items()):
            added_before = sum(len(s) for pos,s in ordered if pos < at)
            for _, text, repair_id, source_sha in sorted(group):
                start = at + added_before
                chapter.setdefault('sourceTextRepairs', []).append({'id':repair_id,'paragraphIndex':pi,'start':start,'end':start+len(text),'text':text,'sourceRawSha256':source_sha})
                added_before += len(text)
        original = chapter['content'][pi]; pieces=[]; cursor=0
        for at, value in ordered: pieces.extend([original[cursor:at],value]); cursor=at
        pieces.append(original[cursor:]); chapter['content'][pi]=''.join(pieces)
        recovered=chapter['content'][pi]; added=0
        ranges=[]
        for at,value in ordered: ranges.append((at+added,len(value))); added+=len(value)
        for at,length in reversed(ranges): recovered=recovered[:at]+recovered[at+length:]
        assert recovered == original
    chapter.setdefault('sections', []).extend(sections)
    chapter['sections'].sort(key=lambda s:s['paragraphIndex'])
    return inserted, shifts


def refresh_catalog(library, changed):
    path=library/'catalog.json'; catalog=json.loads(path.read_text())
    for i, metadata in enumerate(catalog['books']):
        if metadata['id'] not in changed: continue
        book=copy.deepcopy(changed[metadata['id']][1]['books'][0])
        for c in book['chapters']:
            sizes=[len(p) for p in c.pop('content')]; c.update(paragraphCount=len(sizes),paragraphCharacterCounts=sizes,characterCount=sum(sizes)); c.pop('footnotes',None); c.pop('sourceTextRepairs',None)
        book['characterCount']=sum(c['characterCount'] for c in book['chapters']); catalog['books'][i]=book
    return path,catalog


def repair(library,evidence,apply=False):
    review=json.loads(REVIEW.read_text()); records=[r for r in review['missingLineReviews'] if r['reviewCategory'].startswith('CONFIRMED')]
    changed={}; all_shifts={}; added=0
    for bid in sorted({r['bookId'] for r in records}):
        path=library/'books'/f'{bid}.json'; payload=json.loads(path.read_text()); book=payload['books'][0]; before=copy.deepcopy(book)
        for c in book['chapters']:
            selected=[r for r in records if r['bookId']==bid and r['chapterId']==c['id']]
            for r in selected:
                raw=(evidence/'snapshots'/(hashlib.sha256(r['sourceUrl'].encode()).hexdigest()+'.bin')).read_bytes()
                assert hashlib.sha256(raw).hexdigest()==r['sourceRawSha256']
            n, shifts=insert(c,selected); added+=n
            all_shifts[bid,c['id']]=shifts
            for note in c.get('footnotes',[]):
                for ref in note['references']:assert c['content'][ref['paragraphIndex']][ref['start']:ref['end']]==note['marker']
        if book==before:continue
        assert [(c['id'],len(c['content'])) for c in book['chapters']]==[(c['id'],len(c['content'])) for c in before['chapters']]
        old_ids={n['id'] for n in book['toc']};book['toc'].extend(n for n in make_toc(book) if n['id'] not in old_ids)
        changed[bid]=(path,payload)
    supplement_path=ROOT/'tools/library_footnote_supplements.json'; supplements=json.loads(supplement_path.read_text()); refreshed=0
    for kind in ('records','exclusions'):
        for record in supplements[kind]:
            shifts=all_shifts.get((record['bookId'],record['chapterId']),{}).get(record['paragraphIndex'],[])
            if not shifts:continue
            original=json.loads(changed[record['bookId']][0].read_text())['books'][0]
            old=next(c for c in original['chapters'] if c['id']==record['chapterId'])['content'][record['paragraphIndex']]
            assert hashlib.sha256(old.encode()).hexdigest()==record['paragraphSha256']
            record['start']+=sum(len(s) for at,s in shifts if at<=record['start'])
            chapter=next(c for c in changed[record['bookId']][1]['books'][0]['chapters'] if c['id']==record['chapterId'])
            text=chapter['content'][record['paragraphIndex']];assert text[record['start']:record['start']+len(record['marker'])]==record['marker']
            record['paragraphSha256']=hashlib.sha256(text.encode()).hexdigest();refreshed+=1
    catalog_path,catalog=refresh_catalog(library,changed)
    if apply and changed:
        backup=evidence/'before-short-line-repair';backup.mkdir(exist_ok=True)
        for path,payload in changed.values():
            assert not (backup/path.name).exists(),'Existing backup needs review'
            (backup/path.name).write_bytes(path.read_bytes());path.write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':')))
        if not (backup/'catalog.json').exists(): (backup/'catalog.json').write_bytes(catalog_path.read_bytes())
        catalog_path.write_text(json.dumps(catalog,ensure_ascii=False,separators=(',',':')))
        if not (backup/supplement_path.name).exists(): (backup/supplement_path.name).write_bytes(supplement_path.read_bytes())
        supplement_path.write_text(json.dumps(supplements,ensure_ascii=False,indent=2)+'\n')
    return {'changedBooks':list(changed),'shortLineGroupsInserted':added,'supplementsRefreshed':refreshed,'applied':apply}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--library',type=Path,default=ROOT/'app/src/main/assets/library');parser.add_argument('--evidence',type=Path,default=ROOT/'.generated/source-audit-20261001');parser.add_argument('--apply',action='store_true');args=parser.parse_args();print(json.dumps(repair(args.library,args.evidence,args.apply),ensure_ascii=False,indent=2))
