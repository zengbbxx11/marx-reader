import copy
import json
from pathlib import Path
import unittest
from build_full_chinese_library import extract_chapter
from repair_source_footer_notes import REVIEW, restore


class FooterNoteBoundaryTest(unittest.TestCase):
    def extract(self, notes, footer):
        html = '<body><h1>测试</h1><p>' + '正文完整句子。' * 70 + '<a name="_ftnref1" href="#_ftn1">[1]</a></p><span style="font-size:10.5pt"><a name="_ftn1" href="#_ftnref1">[1]</a>' + notes + '</span>' + footer + '</body>'
        return extract_chapter('https://www.marxists.org/fixture', '测试', html, 1)

    def test_credit_stays_in_body_and_out_of_note(self):
        c = self.extract('原注第一段。<br>原注第二段。', '<p align="center">感谢 甲 录入及校对</p>')
        self.assertEqual(['原注第一段。', '原注第二段。'], c['footnotes'][0]['content'])
        self.assertIn('感谢 甲 录入及校对', ''.join(c['content']))
        self.assertNotIn('MIA_NOTE_END', ''.join(c['content']))

    def test_source_citation_inside_note_container_is_retained(self):
        c = self.extract('原注。<p>来源：《原注引用的书》。</p>', '<p align="right"><b>来源</b>：《整篇文章来源》。</p>')
        self.assertEqual(['原注。', '来源：《原注引用的书》。'], c['footnotes'][0]['content'])
        self.assertIn('来源：《整篇文章来源》。', ''.join(c['content']))

    def test_real_repairs_preserve_body_sections_and_references(self):
        root = Path(__file__).resolve().parents[2]
        records = json.loads(REVIEW.read_text())['repairs']
        self.assertEqual(19, len(records))
        for r in records:
            with self.subTest(book=r['bookId']):
                b = json.loads((root / 'app/src/main/assets/library/books' / (r['bookId'] + '.json')).read_text())['books'][0]
                c = copy.deepcopy(next(c for c in b['chapters'] if c['id'] == r['chapterId']))
                self.assertFalse(restore(c, r))
                n = next(n for n in c['footnotes'] if n['id'] == r['noteId']); n['content'] = r['previousNoteContent'][:]
                before = copy.deepcopy(c)
                self.assertTrue(restore(c, r)); self.assertFalse(restore(c, r))
                self.assertEqual(before['content'], c['content'])
                self.assertEqual(before['sections'], c['sections'])
                self.assertEqual(before['footnotes'][0]['references'], c['footnotes'][0]['references'])
                self.assertEqual(r['sourceNoteContent'], n['content'])
