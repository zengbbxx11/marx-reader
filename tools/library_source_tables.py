"""Preserve reviewed table boundaries and add offline table reproduction links."""
import hashlib
import json
from pathlib import Path
from bs4 import NavigableString

MANIFEST = Path(__file__).with_name('library_source_tables.json')
BOUNDARY = '\u2002'


def table_tokens(root, url, reference_tokens):
    records = [r for r in json.loads(MANIFEST.read_text())['tables'] if r['sourceUrl'] == url]
    notes = {}
    nodes = list(root.find_all('table'))
    for record in records:
        matches = [n for n in nodes if n.__dict__.get('_original_source_sha256', hashlib.sha256(str(n).encode()).hexdigest()) == record['tableHtmlSha256']]
        if len(matches) != 1:
            raise ValueError(f"Reviewed source table changed: {url} / {record['tableIndex']}")
        table = matches[0]
        ordinal = record['tableIndex'] + 1
        note_id, marker = f'source-table-{ordinal:03d}', f'〔原表{ordinal}〕'
        token = f'@@MIA_FOOTNOTE_REF_{len(reference_tokens):05d}@@'
        reference_tokens[token] = (note_id, marker)
        notes[note_id] = {'id': note_id, 'marker': marker,
                         'content': ['源页文字与原始括号图片合排的离线图式，保留分组关系。' if record.get('kind') == 'grouping_diagram' else '按源页 HTML 单元格排成的离线表格，保留合并行列；未改写数字。'],
                         'imageAsset': record['assetPath'], 'imageSha256': record['imageSha256'],
                         'sourceUrl': url, 'sourceTableIndex': record['tableIndex']}
        table.find('tr').find(['td', 'th']).insert(0, NavigableString(token))
        for row in table.find_all('tr'):
            for cell in row.find_all(['td', 'th'], recursive=False)[:-1]:
                cell.insert_after(NavigableString(BOUNDARY))
    return notes
