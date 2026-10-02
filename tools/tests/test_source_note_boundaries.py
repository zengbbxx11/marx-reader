import copy
import hashlib
import json
import unittest
from build_full_chinese_library import extract_chapter, keep_text
from repair_source_note_boundaries import restore


class NoteBoundaryTest(unittest.TestCase):
    def test_final_note_stops_at_separate_index_but_keeps_continuation(self):
        html = '<body><h1>测试</h1><p>' + '正文完整句子。' * 70 + '<a name="_ftnref1" href="#_ftn1">[1]</a></p><p><a name="_ftn1" href="#_ftnref1">[1]</a>原注第一段。</p><p>原注第二段。</p><h3>人名索引</h3><p>甲：索引词条。</p></body>'
        chapter = extract_chapter('https://www.marxists.org/fixture', '测试', html, 1)
        note = chapter['footnotes'][0]
        self.assertEqual(['原注第一段。', '原注第二段。'], note['content'])
        self.assertIn('甲：索引词条。', chapter['content'])
        self.assertTrue(any(s['title'] == '人名索引' for s in chapter['sections']))
        ref = note['references'][0]
        self.assertEqual('[1]', chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])

    def test_repair_keeps_body_index_ids_and_reference_positions(self):
        section = {'id': 'index', 'title': '人名索引', 'paragraphIndex': 1}
        old_content = ['原注。', '人名索引', '甲：索引词条。']
        ref = {'paragraphIndex': 0, 'start': 2, 'end': 6}
        chapter = {'content': ['正文[48]', '人名索引', '甲：索引词条。'], 'sections': [section], 'footnotes': [{'id': '_ftn48', 'marker': '[48]', 'content': old_content, 'references': [ref]}]}
        record = {'noteId': '_ftn48', 'references': [ref], 'indexSection': section, 'sourceNoteContent': ['原注。'], 'previousNoteContentSha256': hashlib.sha256(json.dumps(old_content, ensure_ascii=False).encode()).hexdigest()}
        before = copy.deepcopy(chapter)
        self.assertTrue(restore(chapter, record))
        self.assertFalse(restore(chapter, record))
        self.assertEqual(before['content'], chapter['content'])
        self.assertEqual(before['sections'], chapter['sections'])
        self.assertEqual(before['footnotes'][0]['references'], chapter['footnotes'][0]['references'])
        self.assertEqual('_ftn48', chapter['footnotes'][0]['id'])
        self.assertEqual(['原注。'], chapter['footnotes'][0]['content'])

    def test_site_update_line_is_filtered_without_removing_editorial_note(self):
        self.assertFalse(keep_text('中文马克思主义文库2022年11月更新。', '测试'))
        self.assertTrue(keep_text('正文〔注：——中文马克思主义文库〕。', '测试'))
