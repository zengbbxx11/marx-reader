import collections
import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import library_source_reviewed_blocks as blocks
from build_full_chinese_library import extract_chapter
from repair_source_dated_footers import ROOT, REVIEW, restore
from repair_library_text import repair_book


class SourceDatedFooterTest(unittest.TestCase):
    def test_verified_dates_preserve_every_old_paragraph_note_and_section(self):
        records = json.loads(REVIEW.read_text())['repairs']
        self.assertEqual(len(records), 4)
        for record in records:
            book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
            chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
            previous = copy.deepcopy(chapter)
            previous['content'] = previous['content'][:-1]
            before = copy.deepcopy(previous)
            self.assertTrue(restore(previous, record))
            self.assertEqual(previous, chapter)
            self.assertFalse(restore(previous, record))
            self.assertEqual(previous['content'][:-1], before['content'])
            self.assertEqual(previous['footnotes'], before['footnotes'])
            self.assertEqual(previous['sections'], before['sections'])
            previous['content'][0] += '改文'
            with self.assertRaises(AssertionError):
                restore(previous, record)

    def test_signed_boundary_keeps_date_separate_from_footer_navigation(self):
        date = '1851年10月于伦敦'
        html = '<html><body><h1>测试</h1><p>' + '正文句子。' * 40 + '</p><p align="right">' + date + '</p><p>上一篇 回目录 下一篇</p></body></html>'
        record = {'bookId': 'test', 'sourceUrl': 'reviewed', 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(),
                  'selector': 'p[align="right"]', 'sourceText': date, 'kind': 'preserve_plain_container'}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            manifest.write_text(json.dumps({'blocks': []}))
            with patch.object(blocks, 'MANIFEST', manifest):
                before = extract_chapter('reviewed', '测试', html, 1)
                repair_book({'id': 'test', 'chapters': [before]}, collections.Counter(), {})
                self.assertNotIn(date, ''.join(before['content']))
                manifest.write_text(json.dumps({'blocks': [record]}))
                after = extract_chapter('reviewed', '测试', html, 1)
                repair_book({'id': 'test', 'chapters': [after]}, collections.Counter(), {})
                self.assertEqual(after['content'][-1], date)
                with self.assertRaises(ValueError):
                    extract_chapter('reviewed', '测试', html + '源文变化', 1)
