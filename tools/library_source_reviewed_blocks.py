"""Preserve individually reviewed source headings and repeated statistical cells."""
import hashlib
import json
import re
from pathlib import Path
from bs4 import NavigableString

MANIFEST = Path(__file__).with_name('library_source_reviewed_blocks.json')


def repair_document_boundaries(url, html):
    records = [r for r in json.loads(MANIFEST.read_text()).get('documentBoundaryRepairs', [])
               if r['sourceUrl'] == url]
    for record in records:
        if hashlib.sha256(html.encode()).hexdigest() != record['decodedHtmlSha256']:
            raise ValueError(f'Reviewed document boundary source changed: {url}')
        for tag in ('body', 'html'):
            matches = list(re.finditer(r'</' + tag + r'\s*>', html, re.I))
            assert len(matches) == record['closingTagCounts'][tag]
            for match in reversed(matches[:-1]):
                html = html[:match.start()] + html[match.end():]
        assert hashlib.sha256(html.encode()).hexdigest() == record['repairedHtmlSha256']
    return html


def restore_blocks(soup, url, html):
    for record in json.loads(MANIFEST.read_text())['blocks']:
        if record['sourceUrl'] != url: continue
        if hashlib.sha256(html.encode()).hexdigest() != record['decodedHtmlSha256']:
            raise ValueError(f'Reviewed block source changed: {url}')
        matches = [n for n in soup.select(record['selector'])
                   if re.sub(r'\s+', '', n.get_text()) == record['sourceText']]
        if len(matches) != record.get('expectedMatches', 1):
            raise ValueError(f'Reviewed block not unique: {url} / {record["sourceText"]}')
        node = matches[record.get('matchIndex', 0)]
        if record['kind'] == 'heading':
            node.name = 'h3'
        elif record['kind'] == 'statistical_row':
            node.clear(); node.append(NavigableString(record['retainedText']))
        elif record['kind'] == 'preserve_numbered_heading':
            node['data-source-reviewed-heading'] = '1'
        elif record['kind'] == 'preserve_same_title_heading':
            node.name = 'h3'
            node['data-source-reviewed-heading'] = '1'
        elif record['kind'] == 'preserve_plain_container':
            node['data-source-reviewed-boundary'] = '1'
        else:
            assert record['kind'] == 'preserve_heading'
    for record in json.loads(MANIFEST.read_text()).get('rawLineHeadings', []):
        if record['sourceUrl'] != url:
            continue
        if hashlib.sha256(html.encode()).hexdigest() != record['decodedHtmlSha256']:
            raise ValueError(f'Reviewed raw heading source changed: {url}')
        root = soup.select_one(record['selector'])
        matches = [(node, line) for node in root.contents if type(node) is NavigableString
                   for line in str(node).splitlines(keepends=True) if line.strip() == record['retainedTitle']]
        if len(matches) != 1:
            raise ValueError(f'Reviewed raw heading not unique: {url}')
        node, line = matches[0]
        before, after = str(node).split(line, 1)
        heading = soup.new_tag(record['tag'])
        heading['data-source-reviewed-heading'] = '1'
        heading.string = record['retainedTitle']
        node.replace_with(NavigableString(before), heading, NavigableString(after))


def preserved_titles(book_id):
    manifest = json.loads(MANIFEST.read_text())
    return ({record['retainedTitle'] for record in manifest['blocks']
             if record['bookId'] == book_id and 'retainedTitle' in record}
            | {r['text'] for r in manifest.get('plainLines', []) if r['bookId'] == book_id}
            | {r['retainedTitle'] for r in manifest.get('rawLineHeadings', []) if r['bookId'] == book_id})


def preserved_repeated_paragraphs(url, html):
    records = [r for r in json.loads(MANIFEST.read_text()).get('repeatedLines', [])
               if r['sourceUrl'] == url]
    for record in records:
        if hashlib.sha256(html.encode()).hexdigest() != record['decodedHtmlSha256']:
            raise ValueError(f'Reviewed repeated-line source changed: {url}')
    return {r['text'] for r in records}


def reviewed_plain_lines(url, html):
    records = [r for r in json.loads(MANIFEST.read_text()).get('plainLines', [])
               if r['sourceUrl'] == url]
    for record in records:
        if hashlib.sha256(html.encode()).hexdigest() != record['decodedHtmlSha256']:
            raise ValueError(f'Reviewed plain-line source changed: {url}')
    return {r['text'] for r in records}
