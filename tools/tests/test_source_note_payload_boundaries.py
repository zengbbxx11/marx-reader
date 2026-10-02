import copy
import hashlib
import json
import unittest
from unittest.mock import patch
from build_full_chinese_library import extract_chapter
from repair_source_note_payload_boundaries import ROOT, REVIEW, restore


class NotePayloadBoundaryTest(unittest.TestCase):
    def test_reviewed_container_exit_keeps_appendix_in_body(self):
        url = 'https://www.marxists.org/note-boundary-fixture'
        html = '<body><h1>测试</h1><p>' + '正文完整句子。' * 50 + '<a name="_ftnref1" href="#_ftn1">[1]</a></p><span style="font-size:10.5pt"><a name="_ftn1" href="#_ftnref1">[1]</a>原注。<br>原注续段。</span><h3>附录</h3><p>附录正文保留。</p><p>上一篇 回目录 下一篇</p></body>'
        manifest = {url: {'htmlSha256': hashlib.sha256(html.encode()).hexdigest(),
                          'ends': [{'noteId': '_ftn1', 'tag': 'span'}]}}
        with patch('library_source_note_boundaries._manifest', return_value=manifest):
            chapter = extract_chapter(url, '测试', html, 1)
            self.assertEqual(['原注。', '原注续段。'], chapter['footnotes'][0]['content'])
            self.assertIn('附录正文保留。', chapter['content'])
            with self.assertRaisesRegex(ValueError, 'source changed'):
                extract_chapter(url, '测试', html + ' ', 1)

    def test_shared_labels_use_same_text_without_including_each_others_label(self):
        html = '<body><h1>测试</h1><p>' + '正文完整句子。' * 50 + '<a name="_ftnref43" href="#_ftn43">[43]</a><a name="_ftnref431" href="#_ftn431">[43a]</a></p><span><a name="_ftn43" href="#_ftnref43">[43]</a> <a name="_ftn431" href="#_ftnref431">[43a]</a> 共同原注。<br>共同续段。</span></body>'
        chapter = extract_chapter('https://www.marxists.org/shared-fixture', '测试', html, 1)
        self.assertEqual(2, len(chapter['footnotes']))
        for note in chapter['footnotes']:
            self.assertEqual(['共同原注。', '共同续段。'], note['content'])

    def test_real_repairs_preserve_all_other_fields_and_are_idempotent(self):
        records = json.loads(REVIEW.read_text())['repairs']
        self.assertEqual(109, len(records))
        books = {}
        for r in records:
            with self.subTest(book=r['bookId'], chapter=r['chapterId'], note=r['noteId']):
                if r['bookId'] not in books:
                    books[r['bookId']] = json.loads((ROOT / 'app/src/main/assets/library/books' / (r['bookId'] + '.json')).read_text())['books'][0]
                chapter = copy.deepcopy(next(c for c in books[r['bookId']]['chapters'] if c['id'] == r['chapterId']))
                note = next(n for n in chapter['footnotes'] if n['id'] == r['noteId'])
                note['content'] = r['previousNoteContent'][:]
                expected = copy.deepcopy(chapter)
                next(n for n in expected['footnotes'] if n['id'] == r['noteId'])['content'] = r['sourceNoteContent'][:]
                self.assertTrue(restore(chapter, r))
                self.assertFalse(restore(chapter, r))
                self.assertEqual(expected, chapter)
                for n in chapter['footnotes']:
                    for ref in n.get('references', []):
                        self.assertEqual(n['marker'], chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])
