"""Retain only reviewed short source lines, bound to the original decoded HTML."""
import hashlib
import json
import re
from pathlib import Path
from bs4 import NavigableString

MANIFEST = Path(__file__).with_name('library_source_short_lines.json')


def preserve_short_lines(soup, url, html):
    pages = json.loads(MANIFEST.read_text())['pages']
    page = next((p for p in pages if p['sourceUrl'] == url), None)
    if page is None: return {}
    if hashlib.sha256(html.encode()).hexdigest() != page['decodedHtmlSha256']:
        raise ValueError(f'Reviewed short-line source changed: {url}')
    for node in soup.select('script, style, noscript'): node.decompose()
    root = soup.body or soup
    for node in root.find_all('br'): node.replace_with(NavigableString('\n'))
    for node in root.find_all(['p','blockquote','li','div','table','tr','h1','h2','h3','h4','h5','h6']):
        node.insert_before(NavigableString('\n')); node.insert_after(NavigableString('\n'))
    nodes = [n for n in root.descendants if type(n) is NavigableString]
    joined = ''.join(str(n) for n in nodes); mapping = [(n,i) for n in nodes for i in range(len(str(n)))]
    lines=[];cursor=0
    for raw in joined.splitlines(keepends=True):
        value=re.sub(r'\s+',' ',raw).strip()
        if value:
            chars=[cursor+i for i,c in enumerate(raw) if not c.isspace()]
            lines.append((value,chars))
        cursor+=len(raw)
    edits={};tokens={};ordinal=0
    for record in page['records']:
        for offset, char in enumerate(record['text']):
            value, positions=lines[record['sourceLineIndex']+offset]
            assert value==char and len(positions)==1,(url,record['sourceLineIndex'],value,char)
            node,at=mapping[positions[0]];token=f'@@MIA_SOURCE_CHAR_{ordinal:05d}@@';ordinal+=1
            heading=record['reviewCategory'] in ('CONFIRMED_HEADING_OMISSION','CONFIRMED_INDEX_HEADING_OMISSION')
            if heading:
                parent=node.find_parent(['h2','h3','h4','h5','h6'])
                if parent is not None: parent.name='p'
            tokens[token]={'text':char,'heading':heading,'level':record.get('headingLevel',2)}
            edits.setdefault(id(node),(node,{}))[1][at]=token
    for node, changes in edits.values():
        text=str(node)
        for at,token in sorted(changes.items(),reverse=True):text=text[:at]+token+text[at+1:]
        node.replace_with(NavigableString(text))
    return tokens


def materialize_short_lines(value,tokens):
    for token,record in tokens.items(): value=value.replace(token,record['text'])
    return value
