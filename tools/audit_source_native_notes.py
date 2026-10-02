"""Compare native HTML note definitions using html.parser, independently of the importer.

Uses SHA-validated cached snapshots and ledger chapter URLs; never edits book assets.
Bare printed notes and source definitions without anchors are outside this scan.
"""
import argparse,json,pathlib,re,hashlib,sys,collections
ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output",type=pathlib.Path,default=ROOT/".generated/native-note-verification.json")
args=parser.parse_args()
import os
os.chdir(ROOT)
sys.path.insert(0,'tools')
from bs4 import BeautifulSoup,NavigableString,Tag
from text_encoding import decode_html
from library_text_rules import PUA_MAP
l=json.load(open('docs/SOURCE_REPAIR_PROGRESS.json'));urls={(r['bookId'],r['chapterId']):r['sourceUrl'] for r in l['reviews'] if r.get('sourceUrl')}
cache={p.stem:p for p in pathlib.Path('.generated').glob('**/snapshots/*.bin')};books={};rows=[];candidates=[];noanchors=[];checked=0
norm=lambda s:re.sub(r'\s+','',s.translate(str.maketrans(PUA_MAP)))
reviewed=[]
for f in ['docs/SOURCE_PRINTED_NOTE_TAIL_REVIEW_2026-10-02.json','docs/SOURCE_SECOND_INTERNATIONAL_NOTE_REVIEW_2026-10-02.json']:
 for r in json.load(open(f))['repairs']:reviewed.append(r)
for (bid,cid),url in urls.items():
 p=cache[hashlib.sha256(url.encode()).hexdigest()];raw=p.read_bytes();m=json.load(open(p.with_suffix('.json')));assert hashlib.sha256(raw).hexdigest()==m['rawSha256'];h=decode_html(raw,m.get('charset'));s=BeautifulSoup(h,'html.parser');nodes=list(s.descendants);ix={id(n):i for i,n in enumerate(nodes)};anchors=[a for a in s.find_all('a') if a.get_text(strip=True) and re.fullmatch(r'_(?:ftn|edn)\d+',str(a.get('name',a.get('id','')))) and re.search(r'_(?:ftn|edn)ref',a.get('href',''))]
 if bid not in books:books[bid]=json.load(open('app/src/main/assets/library/books/'+bid+'.json'))['books'][0]
 c=next(c for c in books[bid]['chapters'] if c['id']==cid);native={n['id']:n for n in c.get('footnotes',[])};checked+=1
 if not anchors:noanchors.append({'bookId':bid,'chapterId':cid,'nativeNotesInLocal':len(native)})
 for a in anchors:
  aid=a.get('name',a.get('id'));container=a.find_parent('p')
  if container is None:
   container=a.parent
   while container.parent and norm(container.get_text())==norm(a.get_text()):container=container.parent
  if container.name=='p' and 'MsoFootnoteText' in container.get('class',[]):
   last=container
   for sibling in container.next_siblings:
    if isinstance(sibling,NavigableString) and not sibling.strip():continue
    if isinstance(sibling,Tag) and sibling.name=='p' and 'MsoFootnoteText' in sibling.get('class',[]) and not any(re.fullmatch(r'_(?:ftn|edn)\d+',str(n.get('name',n.get('id','')))) for n in sibling.find_all('a')):last=sibling
    else:break
   stop=ix[id(last._last_descendant())]+1
  else:stop=ix[id(container._last_descendant())]+1
  pieces=[];labels={id(a)};stopped_for_definition=False
  for node in nodes[ix[id(a)]+1:stop]:
   if isinstance(node,Tag) and node.name=='br' and not norm(''.join(pieces)):
    stopped_for_definition=True;break
   if isinstance(node,Tag) and node is not a and re.fullmatch(r'_(?:ftn|edn)\d+',str(node.get('name',node.get('id','')))) and re.search(r'_(?:ftn|edn)ref',node.get('href','')):
    if not norm(''.join(pieces)):labels.add(id(node));continue
    stopped_for_definition=True;break
   if isinstance(node,NavigableString) and not node.find_parent(['script','style']) and not any(id(parent) in labels for parent in node.parents):pieces.append(str(node))
  text=norm(''.join(pieces));marker=norm(a.get_text())
  sibling=container.next_sibling
  if not stopped_for_definition and isinstance(sibling,NavigableString) and re.fullmatch(r'[\s。．.,，;；:：!?！？]+',str(sibling)):text+=norm(str(sibling))
  note=native.get(aid);alignment='same_id'
  if note is None:
   choices=[n for n in native.values() if norm(n['marker'])==marker and norm(''.join(n['content'])).startswith(text)]
   if len(choices)==1:note=choices[0];alignment='unique_marker_and_content'
  if note is not None:
   local=norm(''.join(note['content']));result='exact_match' if text==local else 'requires_review'
   for r in reviewed:
    if (bid,cid,aid)==(r['bookId'],r['chapterId'],r['noteId']) and norm(''.join(r['previousNoteContent']))==text and norm(''.join(r['sourceNoteContent']))==local:result='reviewed_duplicate_tail_removed'
   if aid=='_ftn93' and bid=='marx-work-d1c3a7531cf2' and local.startswith(text) and note.get('supplementComparison'):result='reviewed_pdf_comparison_separately_labeled'
  else:local='';result='reviewed_source_empty_definition' if not text else 'requires_review'
  row={'bookId':bid,'chapterId':cid,'sourceUrl':url,'sourceRawSha256':m['rawSha256'],'sourceNoteId':aid,'sourceMarker':a.get_text(),'localNoteId':note['id'] if note else None,'alignment':alignment,'status':result,'sourceContentSha256':hashlib.sha256(text.encode()).hexdigest(),'localContentSha256':hashlib.sha256(local.encode()).hexdigest()};rows.append(row)
  if result=='requires_review':candidates.append({**row,'sourceContent':text,'localContent':local})
report={'scope':'独立html.parser按原生定义锚点与实际P／SPAN容器边界核对原注正文，不调用正文导入器；空白及已有PUA映射忽略，印刷裸注／无锚点注另需独立核查。','cacheOnly':True,'chaptersChecked':checked,'nativeDefinitionsChecked':len(rows),'statuses':dict(collections.Counter(r['status'] for r in rows)),'rows':rows,'chaptersWithoutNativeDefinitionAnchors':noanchors,'candidates':candidates};args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('chapters',checked,'notes',len(rows),'statuses',report['statuses'])
for r in candidates[:12]:print(r['bookId'],r['chapterId'],r['sourceNoteId'],len(r['sourceContent']),len(r['localContent']),r['sourceContent'][:70])
