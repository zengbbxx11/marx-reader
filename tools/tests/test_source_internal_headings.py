import collections
import copy
import hashlib
import json
from pathlib import Path
import unittest
from repair_source_internal_headings import REVIEW, ROOT, restore
from repair_library_text import repair_book
from repair_source_short_lines import text_map


class InternalHeadingRepairTest(unittest.TestCase):
    def test_reviewed_repairs_are_exact_insertions_with_stable_indices(self):
        records = json.loads(REVIEW.read_text())['repairs']
        self.assertEqual(sum(r['headingCount'] for r in records), 99)
        for record in records:
            book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
            chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
            self.assertFalse(restore(copy.deepcopy(chapter), record))
            previous = copy.deepcopy(chapter)
            added_ids = {n['id'] for n in record.get('addedFootnotes', [])}
            previous['footnotes'] = [n for n in previous['footnotes'] if n['id'] not in added_ids]
            titles = {h['title'] for x in record['changes'] for h in x['sections']}
            previous['sections'] = [s for s in previous['sections'] if s['title'] not in titles]
            for change in record['changes']:
                pi = change['paragraphIndex']; shift = len(change['prefix'])
                at = change.get('insertionOffset', 0)
                previous['content'][pi] = change['previousParagraph']
                for spans in ([r for n in previous['footnotes'] for r in n['references']], previous.get('sourceTextRepairs', [])):
                    for span in spans:
                        if span['paragraphIndex'] == pi:
                            if span['start'] >= at + shift: span['start'] -= shift
                            if span['end'] >= at + shift: span['end'] -= shift
                m = change['restorationMapping']
                self.assertEqual(hashlib.sha256(change['currentParagraph'].encode()).hexdigest(), m['currentHash'])
                self.assertEqual(change['currentParagraph'][:m['ranges'][0][0]] + change['currentParagraph'][m['ranges'][0][1]+1:], change['previousParagraph'])
            self.assertTrue(restore(previous, record))
            self.assertEqual(previous, chapter)
            self.assertFalse(restore(previous, record))
            # A subsequent cleanup must keep the restored headings and positions.
            cleaned = copy.deepcopy(book)
            repair_book(cleaned, collections.Counter(), {})
            self.assertEqual(cleaned['chapters'], book['chapters'])
            for section in chapter['sections']:
                self.assertIn(''.join(section['title'].split()), ''.join(chapter['content'][section['paragraphIndex']].split()))
                self.assertTrue(any(n['title'] == section['title'] and n['paragraphIndex'] == section['paragraphIndex'] for n in book['toc']))

    def test_changed_old_text_is_rejected(self):
        record = json.loads(REVIEW.read_text())['repairs'][0]
        book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
        chapter = copy.deepcopy(book['chapters'][0]); chapter['content'][0] += '改文'
        with self.assertRaises(AssertionError): restore(chapter, record)

    def test_only_individually_reviewed_numeric_headings_bypass_filter(self):
        import tempfile
        from unittest.mock import patch
        from build_full_chinese_library import extract_chapter
        import library_source_reviewed_blocks
        url = 'https://www.marxists.org/chinese/test-numeric-headings.htm'
        html = '<html><body><h4>（1）</h4><p>' + '原文内容。' * 60 + '</p><h4>（1）</h4><p>' + '第二段内容。' * 60 + '</p><h4>（9）</h4></body></html>'
        blocks = [{'bookId': 'test', 'sourceUrl': url, 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(), 'selector': 'h4', 'sourceText': '（1）', 'kind': 'preserve_numbered_heading', 'expectedMatches': 2, 'matchIndex': i, 'retainedTitle': '（1）'} for i in range(2)]
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps({'blocks': blocks}))
            with patch.object(library_source_reviewed_blocks, 'MANIFEST', manifest):
                chapter = extract_chapter(url, '测试文章', html, 1)
                self.assertEqual([s['title'] for s in chapter['sections']], ['（1）', '（1）'])
                self.assertNotIn('（9）', ''.join(chapter['content']))
                with self.assertRaisesRegex(ValueError, 'source changed'):
                    extract_chapter(url, '测试文章', html.replace('原文', '变文'), 1)

    def test_reviewed_repeated_emphasis_is_retained(self):
        import tempfile
        from unittest.mock import patch
        from build_full_chinese_library import extract_chapter
        import library_source_reviewed_blocks
        url = 'https://www.marxists.org/chinese/test-repeated.htm'
        html = '<html><body><p>' + '正文内容。' * 60 + '</p>要不得！<br>要不得！<br>其他文字！<br>其他文字！<br></body></html>'
        record = {'sourceUrl': url, 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(), 'text': '要不得！'}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps({'blocks': [], 'repeatedLines': [record]}))
            with patch.object(library_source_reviewed_blocks, 'MANIFEST', manifest):
                chapter = extract_chapter(url, '测试文章', html, 1)
                self.assertEqual(chapter['content'].count('要不得！'), 2)
                self.assertEqual(chapter['content'].count('其他文字！'), 1)
                with self.assertRaisesRegex(ValueError, 'source changed'):
                    extract_chapter(url, '测试文章', html + '变化', 1)

    def test_reviewed_edition_line_stays_text_without_toc_heading(self):
        import tempfile
        from unittest.mock import patch
        from build_full_chinese_library import extract_chapter
        import library_source_reviewed_blocks
        url = 'https://www.marxists.org/chinese/test-edition-line.htm'
        line = '第一卷 230-247页 输入)'
        html = '<html><body><p>' + '正文内容。' * 60 + '</p><div>（根据《选集》<br>' + line + '</div></body></html>'
        record = {'bookId': 'test-edition', 'sourceUrl': url, 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(), 'text': line}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps({'blocks': [], 'plainLines': [record]}))
            with patch.object(library_source_reviewed_blocks, 'MANIFEST', manifest):
                chapter = extract_chapter(url, '测试文章', html, 1)
                self.assertIn(line, chapter['content'])
                self.assertFalse(chapter['sections'])
                book = {'id': 'test-edition', 'titleZh': '测试文章', 'chapters': [chapter]}
                before = copy.deepcopy(chapter)
                repair_book(book, collections.Counter(), {})
                self.assertEqual(book['chapters'][0], before)
                with self.assertRaisesRegex(ValueError, 'source changed'):
                    extract_chapter(url, '测试文章', html + '变化', 1)

    def test_reviewed_same_title_internal_headings_keep_only_body_occurrences(self):
        import tempfile
        from unittest.mock import patch
        from build_full_chinese_library import extract_chapter
        import library_source_reviewed_blocks
        url = 'https://www.marxists.org/chinese/test-same-title.htm'
        title = '同名内部标题'
        html = '<html><body><p class="title1">' + title + '</p><p>' + '序言内容。' * 60 + '</p><p style="text-align:center">' + title + '</p><p>' + '正文内容。' * 60 + '</p>\n　　' + title + '\n<br><p>' + '末节内容。' * 60 + '</p></body></html>'
        base = {'bookId': 'same-title', 'sourceUrl': url, 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(), 'retainedTitle': title}
        manifest_data = {'blocks': [{**base, 'selector': 'p[style]', 'sourceText': title, 'kind': 'preserve_same_title_heading'}], 'rawLineHeadings': [{**base, 'selector': 'body', 'tag': 'h4'}]}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps(manifest_data))
            with patch.object(library_source_reviewed_blocks, 'MANIFEST', manifest):
                chapter = extract_chapter(url, title, html, 1)
                self.assertEqual(chapter['content'].count(title), 2)
                self.assertEqual([s['title'] for s in chapter['sections']], [title, title])
                self.assertEqual(len({s['id'] for s in chapter['sections']}), 2)
                with self.assertRaisesRegex(ValueError, 'source changed'):
                    extract_chapter(url, title, html + '变化', 1)

    def test_reviewed_signature_is_not_merged_into_footer_navigation(self):
        import tempfile
        from unittest.mock import patch
        from build_full_chinese_library import extract_chapter
        import library_source_reviewed_blocks
        url = 'https://www.marxists.org/chinese/test-signature.htm'
        html = '<html><body><p>' + '正文内容。' * 60 + '</p><p align="right"><b>戴・怀恩科普</b><br>1920年6月30日于莫斯科</p><p>上一篇 回目录</p></body></html>'
        record = {'bookId': 'signature', 'sourceUrl': url, 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(), 'selector': 'p[align="right"]', 'sourceText': '戴・怀恩科普1920年6月30日于莫斯科', 'kind': 'preserve_plain_container'}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps({'blocks': [record]}))
            with patch.object(library_source_reviewed_blocks, 'MANIFEST', manifest):
                chapter = extract_chapter(url, '测试文章', html, 1)
                book = {'id': 'signature', 'titleZh': '测试文章', 'chapters': [chapter]}
                repair_book(book, collections.Counter(), {})
                self.assertIn('戴・怀恩科普1920年6月30日于莫斯科', ''.join(chapter['content']))
                self.assertNotIn('回目录', ''.join(chapter['content']))
                self.assertFalse(chapter['sections'])
