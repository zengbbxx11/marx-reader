"""Read-only screening for adjacent repeated source lines, including table cells.

Uses validated raw cache and an independent HTML parser. All results are
review candidates, not automatic paragraph repairs.
"""
import argparse,json,pathlib,sys,hashlib,re
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
from bs4 import BeautifulSoup,NavigableString
from text_encoding import decode_html
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path('.generated/source-repeated-blocks/report.json'));args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];l=json.loads((root/'docs/SOURCE_REPAIR_PROGRESS.json').read_text());urls={}
for r in l['reviews']:
 if r.get('sourceUrl') and r.get('chapterId'):urls[(r['bookId'],r['chapterId'])]=r['sourceUrl']
cache={p.stem:p for p in (root/'.generated').glob('**/snapshots/*.bin')};out=[]
for (bid,cid),url in urls.items():
 p=cache[hashlib.sha256(url.encode()).hexdigest()];raw=p.read_bytes();meta=json.loads(p.with_suffix('.json').read_text());assert hashlib.sha256(raw).hexdigest()==meta['rawSha256'];s=BeautifulSoup(decode_html(raw,meta.get('charset')),'html.parser')
 for n in s.select('script,style'):n.decompose()
 for n in s.find_all('br'):n.replace_with(NavigableString('\n'))
 for n in list(s.find_all(['p','div','blockquote','li','table','tr','h1','h2','h3','h4','h5','h6'])):n.insert_before(NavigableString('\n'));n.insert_after(NavigableString('\n'))
 lines=[re.sub(r'\s+','',t) for t in s.get_text('',strip=False).splitlines() if t.strip()]
 for j,t in enumerate(lines):
  if j and t==lines[j-1] and len(t)>=2 and (j==1 or t!=lines[j-2]):
   count=2
   while j+count-1<len(lines) and lines[j+count-1]==t:count+=1
   out.append({'bookId':bid,'chapterId':cid,'sourceUrl':url,'sourceRawSha256':meta['rawSha256'],'text':t,'sourceCount':count,'before':lines[max(0,j-3):j-1],'after':lines[j+count-1:j+count+1]})
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');print('pages',len(urls),'candidates',len(out))
for x in out:print(x['bookId'],x['chapterId'],x['sourceCount'],x['text'][:170])
