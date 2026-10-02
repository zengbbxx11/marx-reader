import copy
import hashlib
import json
from pathlib import Path
import unittest
from build_full_chinese_library import extract_chapter, keep_text
from repair_source_long_war import restore, REVIEW
from repair_source_short_lines import text_map


class LongWarRepairTest(unittest.TestCase):
    def test_inline_archive_note_survives_but_breadcrumb_does_not(self):
        self.assertFalse(keep_text('中文马克思主义文库 -> 毛泽东', '测试'))
        self.assertFalse(keep_text('中文马克思主义文库', '测试'))
        self.assertTrue(keep_text('（八八）也有朕兆〔注：现通常写为“征兆”。——中文马克思主义文库〕可寻。', '测试'))

    def test_import_keeps_paragraph_and_clickable_footnote(self):
        prose = '（八八）现在来说计划性。〔注：——中文马克思主义文库〕' + '正文。' * 100
        html = '<body><h1>测试</h1>' + prose + '<a name="_ftnref38" href="#_ftn38">[38]</a><br><a name="_ftn38" href="#_ftnref38">[38]</a>见《礼记•中庸》。</body>'
        chapter = extract_chapter('https://www.marxists.org/fixture', '测试', html, 1)
        self.assertIn(prose, ''.join(chapter['content']))
        note = next(n for n in chapter['footnotes'] if n['marker'] == '[38]')
        self.assertEqual(['见《礼记•中庸》。'], note['content'])
        self.assertEqual(1, len(note['references']))

    def test_real_repair_preserves_positions_and_old_annotation_mapping(self):
        review = json.loads(REVIEW.read_text())
        root = Path(__file__).resolve().parents[2]
        book = json.loads((root / 'app/src/main/assets/library/books' / (review['bookId'] + '.json')).read_text())['books'][0]
        chapter = copy.deepcopy(book['chapters'][0])
        self.assertEqual(review['alignedTextSha256'], hashlib.sha256(text_map(chapter)[0].encode()).hexdigest())
        self.assertFalse(restore(chapter, review))
        pi = review['paragraphIndex']; current = chapter['content'][pi]
        chapter['content'][pi] = current[:-(len(review['sourceParagraph']) + 1)]
        chapter['footnotes'] = [n for n in chapter['footnotes'] if n['id'] != review['note']['id']]
        before = copy.deepcopy(chapter)
        self.assertTrue(restore(chapter, review))
        self.assertEqual(len(before['content']), len(chapter['content']))
        self.assertEqual(before['sections'], chapter['sections'])
        self.assertTrue(all(n in chapter['footnotes'] for n in before['footnotes']))
        mapping = review['restorationMapping']
        self.assertEqual(mapping['previousHash'], hashlib.sha256(before['content'][pi].encode()).hexdigest())
        self.assertEqual(mapping['currentHash'], hashlib.sha256(chapter['content'][pi].encode()).hexdigest())
        a, b = mapping['ranges'][0]
        self.assertEqual(before['content'][pi], current[:a] + current[b+1:])
