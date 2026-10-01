"""Restore reviewed superscript author notes from the 1920 Lenin text."""
import re
from bs4 import NavigableString
from library_source_short_lines import materialize_short_lines

REVIEWED = {
    'https://www.marxists.org/chinese/lenin/192004/05.htm': ('1','2'),
    'https://www.marxists.org/chinese/lenin/192004/07.htm': ('1',),
}


def note_tokens(root,url,references,definitions,short_tokens):
    if url in REVIEWED:
        for paragraph in list(root.find_all('p')):
            if ''.join(paragraph.get_text().split()) == '上一篇回目录下一篇': paragraph.decompose()
    for marker in REVIEWED.get(url,()):
        nodes=[n for n in root.find_all('sup') if materialize_short_lines(n.get_text(strip=True),short_tokens)==marker]
        if len(nodes)!=2:raise ValueError(f'Reviewed superscript note changed: {url} / {marker}')
        targets={}
        for node in nodes:
            is_definition=node.find_parent('span',style=re.compile(r'font-size:\s*10\.5pt')) is not None
            kind='DEF' if is_definition else 'REF'
            assert kind not in targets
            targets[kind]=node
        assert set(targets)=={'REF','DEF'}
        note_id='source-sup-'+marker
        for kind,mapping in [('REF',references),('DEF',definitions)]:
            token=f'@@MIA_FOOTNOTE_{kind}_{len(mapping):05d}@@'
            mapping[token]=(note_id,marker)
            targets[kind].replace_with(NavigableString(token))
