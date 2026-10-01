#!/usr/bin/env python3
"""Read-only DOM audit of isolated one-character source lines.

Unlike the normal importer, this retains short lines. A missing candidate needs
unique matching local context on both sides. It is a candidate until reviewed;
this does not establish full article completeness or validate source facts.
"""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path
from bs4 import BeautifulSoup, NavigableString
from text_encoding import decode_html

FORMAT_MARKERS = re.compile(r'〔(?:原表|图式|附件)\d+〕')


def compact(text):
    return re.sub(r'\s+', '', FORMAT_MARKERS.sub('', text))


def candidates(html, paragraphs):
    soup = BeautifulSoup(html, 'lxml')
    for node in soup.select('script, style, noscript'):
        node.decompose()
    root = soup.body or soup
    for index, table in enumerate(root.find_all('table')):
        table.insert_before(NavigableString(f'\n@@TABLE_START_{index}@@\n'))
        table.insert_after(NavigableString(f'\n@@TABLE_END_{index}@@\n'))
    for node in root.find_all('br'):
        node.replace_with(NavigableString('\n'))
    for node in root.find_all(['p', 'blockquote', 'li', 'div', 'table', 'tr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6']):
        node.insert_before(NavigableString('\n')); node.insert_after(NavigableString('\n'))
    lines, stack = [], []
    for line in root.get_text('', strip=False).splitlines():
        value = re.sub(r'\s+', ' ', line).strip()
        marker = re.fullmatch(r'@@TABLE_(START|END)_(\d+)@@', value)
        if marker:
            if marker[1] == 'START': stack.append(int(marker[2]))
            else:
                assert stack.pop() == int(marker[2])
        elif value:
            lines.append({'text': value, 'tableIndex': stack[-1] if stack else None})
    body = compact(''.join(paragraphs))
    result, i = [], 0
    while i < len(lines):
        if len(lines[i]['text']) != 1:
            i += 1; continue
        end = i + 1
        while end < len(lines) and len(lines[end]['text']) == 1 and lines[end]['tableIndex'] == lines[i]['tableIndex']:
            end += 1
        left = compact(''.join(x['text'] for x in lines[:i] if len(x['text']) > 1))[-32:]
        right = compact(''.join(x['text'] for x in lines[end:] if len(x['text']) > 1))[:32]
        missing = ''.join(x['text'] for x in lines[i:end])
        joined = left + right
        occurrences = len(list(re.finditer(re.escape(joined), body))) if len(left) >= 16 and len(right) >= 16 else 0
        full_left = compact(''.join(x['text'] for x in lines[:i]))[-32:]
        full_right = compact(''.join(x['text'] for x in lines[end:]))[:32]
        full_present = (full_left + missing + full_right in body) or (left + missing + right in body)
        status = 'PRESENT_IN_LOCAL_CONTEXT' if full_present else ('MISSING_CONTEXT_CANDIDATE' if occurrences == 1 else 'UNRESOLVED_CONTEXT')
        result.append({'sourceLineIndex': i, 'tableIndex': lines[i]['tableIndex'], 'text': missing,
                       'leftContext': left, 'rightContext': right, 'uniqueFilteredContextMatches': occurrences,
                       'status': status})
        i = end
    return result


def audit_pending_tables(library, evidence, ledger_path):
    ledger = json.loads(ledger_path.read_text())
    results = []
    for item in ledger['pending']:
        if not item['issueId'].startswith('table:'): continue
        key = hashlib.sha256(item['sourceUrl'].encode()).hexdigest()
        raw = (evidence / 'snapshots' / (key + '.bin')).read_bytes()
        metadata = json.loads((evidence / 'snapshots' / (key + '.json')).read_text())
        assert hashlib.sha256(raw).hexdigest() == metadata['rawSha256']
        soup = BeautifulSoup(decode_html(raw, metadata.get('charset')), 'lxml')
        table = soup.find_all('table')[item['tableIndex']]
        source_html = str(table)
        for node in table.find_all('br'): node.replace_with(NavigableString('\n'))
        for node in table.find_all(['p', 'blockquote', 'li', 'div', 'tr']):
            node.insert_before(NavigableString('\n')); node.insert_after(NavigableString('\n'))
        lines = [re.sub(r'\s+', ' ', line).strip() for line in table.get_text('').splitlines()]
        lines = [line for line in lines if line]
        dropped = [{'lineIndex': i, 'text': line} for i, line in enumerate(lines) if len(line) == 1]
        retained = compact(''.join(line for line in lines if len(line) > 1))
        book = json.loads((library / 'books' / (item['bookId'] + '.json')).read_text())['books'][0]
        chapter = next(c for c in book['chapters'] if c['id'] == item['chapterId'])
        matches = len(list(re.finditer(re.escape(retained), compact(''.join(chapter['content'])))))
        results.append({**item, 'sourceRawSha256': metadata['rawSha256'],
                        'tableHtmlSha256': hashlib.sha256(source_html.encode()).hexdigest(),
                        'sourceHtml': source_html, 'sourceLines': lines, 'singleCharacterLines': dropped,
                        'filteredTableLocatedCount': matches,
                        'reviewStatus': 'CONFIRMED_SHORT_LINE_OMISSION' if matches == 1 and dropped else 'REQUIRES_REVIEW'})
    return results


def audit(library, evidence, output):
    audit_report = json.loads((evidence / 'report.json').read_text())
    records, chapter_count = [], 0
    for book in audit_report['results']:
        payload = json.loads((library / 'books' / (book['id'] + '.json')).read_text())['books'][0]
        chapters = {c['id']: c for c in payload['chapters']}
        for entry in book['chapters']:
            if not entry.get('evidence'): continue
            raw = (evidence / 'snapshots' / (hashlib.sha256(entry['url'].encode()).hexdigest() + '.bin')).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == entry['evidence']['rawSha256']
            chapter_count += 1
            html = decode_html(raw, entry['evidence'].get('charset'))
            for candidate in candidates(html, chapters[entry['id']]['content']):
                records.append({'bookId': book['id'], 'title': payload['titleZh'], 'chapterId': entry['id'],
                                'chapterTitle': entry['title'], 'sourceUrl': entry['url'],
                                'sourceRawSha256': entry['evidence']['rawSha256'], **candidate})
    report = {'date': '2026-10-01', 'books': len(audit_report['results']), 'chapters': chapter_count,
              'statuses': dict(collections.Counter(r['status'] for r in records)), 'candidates': records,
              'note': 'Unique surrounding context is machine evidence, not manual confirmation. Present short lines do not establish complete article content.'}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return {k: v for k, v in report.items() if k != 'candidates'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--evidence', type=Path, default=Path('.generated/source-audit-20261001'))
    parser.add_argument('--output', type=Path, default=Path('.generated/source-short-line-review-20261001/all-short-lines.json'))
    parser.add_argument('--pending-tables', type=Path, help='Also review table entries in the progress ledger')
    args = parser.parse_args()
    summary = audit(args.library, args.evidence, args.output)
    if args.pending_tables:
        tables = audit_pending_tables(args.library, args.evidence, args.pending_tables)
        args.output.with_name('pending-tables.json').write_text(json.dumps(tables, ensure_ascii=False, indent=2) + '\n')
        summary['pendingTablesReviewed'] = len(tables)
        summary['confirmedTableOmissions'] = sum(t['reviewStatus'] == 'CONFIRMED_SHORT_LINE_OMISSION' for t in tables)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
