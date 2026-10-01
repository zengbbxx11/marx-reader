"""Preserve individually reviewed source headings and repeated statistical cells."""
import hashlib
import json
import re
from pathlib import Path
from bs4 import NavigableString

MANIFEST = Path(__file__).with_name('library_source_reviewed_blocks.json')


def restore_blocks(soup, url, html):
    for record in json.loads(MANIFEST.read_text())['blocks']:
        if record['sourceUrl'] != url: continue
        if hashlib.sha256(html.encode()).hexdigest() != record['decodedHtmlSha256']:
            raise ValueError(f'Reviewed block source changed: {url}')
        matches = [n for n in soup.select(record['selector'])
                   if re.sub(r'\s+', '', n.get_text()) == record['sourceText']]
        if len(matches) != 1:
            raise ValueError(f'Reviewed block not unique: {url} / {record["sourceText"]}')
        node = matches[0]
        if record['kind'] == 'heading':
            node.name = 'h3'
        else:
            node.clear(); node.append(NavigableString(record['retainedText']))


def preserved_titles(book_id):
    return {record['retainedTitle'] for record in json.loads(MANIFEST.read_text())['blocks']
            if record['bookId'] == book_id and 'retainedTitle' in record}
