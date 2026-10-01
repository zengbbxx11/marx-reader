"""Restore three source-superscript note links without removing note prose."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from text_encoding import decode_html
from build_full_chinese_library import extract_chapter
from library_source_notes import REVIEWED
from repair_source_short_lines import refresh_catalog

ROOT=Path(__file__).resolve().parents[1]


def repair(apply=False):
    library=ROOT/'app/src/main/assets/library';evidence=ROOT/'.generated/source-audit-20261001';path=library/'books/lenin-work-b2eeb64ece7d.json';payload=json.loads(path.read_text());book=payload['books'][0];before=copy.deepcopy(book);added=0
    for url,markers in REVIEWED.items():
        chapter=next(c for c in book['chapters'] if c['id'].endswith(hashlib.sha1(url.encode()).hexdigest()[:10]))
        key=hashlib.sha256(url.encode()).hexdigest();raw=(evidence/'snapshots'/(key+'.bin')).read_bytes();meta=json.loads((evidence/'snapshots'/(key+'.json')).read_text());assert hashlib.sha256(raw).hexdigest()==meta['rawSha256'];source=extract_chapter(url,chapter['title'],decode_html(raw,meta.get('charset')),1)
        for marker in markers:
            note_id='source-sup-'+marker
            if any(n['id']==note_id for n in chapter.get('footnotes',[])):continue
            source_note=next(n for n in source['footnotes'] if n['id']==note_id)
            # Independent source note text must already occur, ordered, in the local chapter.
            compact=lambda s:''.join(s.split())
            body=''.join(compact(p) for p in chapter['content']);text=''.join(compact(p) for p in source_note['content'])
            assert body.count(text)==1,(url,marker,'Source note content changed')
            matches=[]
            for ref in source_note['references']:
                source_paragraph=source['content'][ref['paragraphIndex']]
                prefix=compact(source_paragraph[max(0,ref['start']-32):ref['start']])
                for pi,p in enumerate(chapter['content']):
                    for start in range(len(p)):
                        if p[start:start+len(marker)]==marker and compact(p[:start]).endswith(prefix):matches.append({'paragraphIndex':pi,'start':start,'end':start+len(marker)})
            assert len(matches)==len(source_note['references'])==1,(url,marker,matches)
            note=copy.deepcopy(source_note);note['references']=matches;note['sourceUrl']=url;chapter.setdefault('footnotes',[]).append(note);added+=1
    if apply and book!=before:
        backup=evidence/'before-source-note-repair';backup.mkdir(exist_ok=True);(backup/path.name).write_bytes(path.read_bytes());path.write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':')))
        catalog_path,catalog=refresh_catalog(library,{book['id']:(path,payload)});(backup/'catalog.json').write_bytes(catalog_path.read_bytes());catalog_path.write_text(json.dumps(catalog,ensure_ascii=False,separators=(',',':')))
    return {'notesAdded':added,'applied':apply}


# Source-specific edition comparison. Kept separate from automatic note matching:
# the reviewed PDF supplement remains available, while the web edition is primary.
def repair_original_edition_note(apply=False):
    from bs4 import BeautifulSoup
    library=ROOT/'app/src/main/assets/library';evidence=ROOT/'.generated/source-audit-20261001'
    url='https://www.marxists.org/chinese/marx/mia-chinese-marx-184904.htm'
    key=hashlib.sha256(url.encode()).hexdigest();raw=(evidence/'snapshots'/(key+'.bin')).read_bytes();meta=json.loads((evidence/'snapshots'/(key+'.json')).read_text());assert hashlib.sha256(raw).hexdigest()==meta['rawSha256']
    soup=BeautifulSoup(decode_html(raw,meta.get('charset')),'lxml')
    p=next(p for p in soup.select('p.intr') if '有著文日期' in p.get_text());original=' '.join(p.get_text(' ',strip=True).split());assert original.startswith('①');original=original[1:].strip()
    path=library/'books/marx-work-bcd6bcd970b2.json';payload=json.loads(path.read_text());chapter=payload['books'][0]['chapters'][0]
    assert ''.join(original.split()) in ''.join(''.join(p.split()) for p in chapter['content'])
    note=next(n for n in chapter['footnotes'] if n['id']=='printed-9c9302d280d00ec0')
    if note['content'][0]==original:
        assert note['sourceRawSha256']==meta['rawSha256'] and note.get('supplementComparison')
        return False
    assert 'PDF' in ''.join(note['content'])
    old=copy.deepcopy(note['content']);note['supplementComparison']=old;note['content']=[original,'PDF 校补对照（另一版本）：'+old[0],*old[1:]];note['sourceUrl']=url;note['sourceRawSha256']=meta['rawSha256']
    if apply:
        backup=evidence/'before-original-source-note';backup.mkdir(exist_ok=True)
        if not (backup/path.name).exists():(backup/path.name).write_bytes(path.read_bytes())
        path.write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':')))
    return True

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    result=repair(args.apply);result['originalEditionNoteUpdated']=repair_original_edition_note(args.apply)
    print(json.dumps(result,ensure_ascii=False,indent=2))
