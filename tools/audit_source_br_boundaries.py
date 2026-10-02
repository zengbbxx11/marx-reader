"""Read-only independent source BR/block audit using validated cached raw HTML.

Does not reuse the importer DOM parser. Candidates require live source review;
containment is a screening heuristic, not proof of full source completeness.
"""
import argparse,json,pathlib,re,hashlib,sys,collections
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
from bs4 import BeautifulSoup,NavigableString
from text_encoding import decode_html
from library_text_rules import PUA_MAP
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path('.generated/source-br-boundaries/report.json'));args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];l=json.loads((root/'docs/SOURCE_REPAIR_PROGRESS.json').read_text());urls={}
for r in l['reviews']:
 if r.get('sourceUrl') and r.get('chapterId'):urls[(r['bookId'],r['chapterId'])]=r['sourceUrl']
cache={p.stem:p for p in (root/'.generated').glob('**/snapshots/*.bin')};books={};out=[];checked=0;linecount=0
# Do not normalize punctuation or silently map unproven damaged characters.
def norm(t):
 for a,b in PUA_MAP.items():t=t.replace(a,b)
 return re.sub(r'\s+|[\[［]原注\d+[\]］]|[\[［]\d+[\]］]|〔(?:原表|图式|附件)\d+〕','',t)
for (bid,cid),url in urls.items():
 p=cache.get(hashlib.sha256(url.encode()).hexdigest())
 if not p:raise ValueError(f'Missing cached source: {url}')
 raw=p.read_bytes();meta=json.loads(p.with_suffix('.json').read_text());assert hashlib.sha256(raw).hexdigest()==meta['rawSha256'];h=decode_html(raw,meta.get('charset'));s=BeautifulSoup(h,'html.parser');body=s.body or s
 # html.parser retains text after premature document-ending tags; original raw page untouched.
 for n in s.select('script,style'):n.decompose()
 for n in s.find_all('br'):n.replace_with(NavigableString('\n'))
 for n in list(s.find_all(['p','div','blockquote','li','table','tr','h1','h2','h3','h4','h5','h6'])):n.insert_before(NavigableString('\n'));n.insert_after(NavigableString('\n'))
 if bid not in books:books[bid]=json.loads((root/f'app/src/main/assets/library/books/{bid}.json').read_text())['books'][0]
 c=next(c for c in books[bid]['chapters'] if c['id']==cid);local=norm(''.join(c['content'])+''.join(''.join(n['content']) for n in c.get('footnotes',[])));checked+=1
 # Entire parsed document, rather than first BODY, deliberately checks stray tail content.
 for pos,t in enumerate(s.get_text('',strip=False).splitlines()):
  text=norm(t)
  if len(text)<75:continue
  linecount+=1
  if text not in local:out.append({'bookId':bid,'chapterId':cid,'sourceUrl':url,'sourceRawSha256':meta['rawSha256'],'lineIndex':pos,'text':t.strip(),'compactLength':len(text)})
report={'scope':'884章缓存，独立html.parser和原始BR/块边界，不复用导入正文解析器；长行至少75非空白字符。仅应用已证实PUA映射和去除原注/图片标记。','cacheOnly':True,'chaptersChecked':checked,'longLinesChecked':linecount,'candidates':out}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('chapters',checked,'lines',linecount,'candidates',len(out))
for x in out:print(x['bookId'],x['chapterId'],x['compactLength'],x['text'][:210])
