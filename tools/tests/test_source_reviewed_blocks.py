import collections
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from bs4 import BeautifulSoup
from build_full_chinese_library import extract_chapter
from library_source_reviewed_blocks import restore_blocks
from repair_library_text import repair_book


class ReviewedBlocksTest(unittest.TestCase):
    def test_repeated_statistical_values_and_standalone_dash_survive_import(self):
        url = 'https://www.marxists.org/fixture'
        html = '<body><p>' + '正文。' * 100 + '</p><p>在澳洲\n100.0%\n100.0%\n-</p></body>'
        record = {'bookId': 'fixture', 'sourceUrl': url, 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(),
                  'selector': 'p', 'sourceText': '在澳洲100.0%100.0%-', 'kind': 'statistical_row',
                  'retainedText': '在澳洲\u2002100.0%\u2002100.0%\u2002-'}
        with tempfile.TemporaryDirectory() as d:
            manifest = Path(d) / 'manifest.json'; manifest.write_text(json.dumps({'blocks': [record]}))
            with patch('library_source_reviewed_blocks.MANIFEST', manifest):
                chapter = extract_chapter(url, '统计', html, 1)
                self.assertIn('在澳洲100.0%100.0%-', ''.join(''.join(chapter['content']).split()))
                with self.assertRaisesRegex(ValueError, 'source changed'):
                    restore_blocks(BeautifulSoup(html, 'lxml'), url, html + '改变')

    def test_reviewed_internal_chapter_heading_survives_cleanup(self):
        book = {'id': 'manifesto-zh-1920', 'titleZh': '共产党宣言', 'chapters': [
            {'id': 'c', 'title': '共产党宣言', 'content': ['第一章 有产者及无产者', '正文'],
             'sections': [{'title': '第一章 有产者及无产者', 'paragraphIndex': 0}]}]}
        repair_book(book, collections.Counter(), {})
        self.assertEqual('第一章 有产者及无产者', book['chapters'][0]['content'][0])
        self.assertEqual(0, book['chapters'][0]['sections'][0]['paragraphIndex'])

    def test_final_reviewed_source_blocks_are_present(self):
        root = Path(__file__).resolve().parents[2]
        def book(bid): return json.loads((root / 'app/src/main/assets/library/books' / (bid + '.json')).read_text())['books'][0]
        imperial = ''.join(''.join(book('lenin-work-5409317c986a')['chapters'][0]['content']).split())
        self.assertIn('在澳洲100.0%100.0%-在美洲', imperial)
        manifesto = book('manifesto-zh-1920')['chapters'][0]
        self.assertIn('第一章有产者及无产者（有产者就是', ''.join(''.join(manifesto['content']).split()))
        self.assertEqual(4, sum(s['title'].startswith(('第一章', '第二章', '第三章', '第四章')) for s in manifesto['sections']))
        left = book('lenin-work-b2eeb64ece7d')
        for c, number in zip(left['chapters'][10:15], '一二三四五'):
            self.assertTrue(any(s['title'].startswith(number) for s in c['sections']))
