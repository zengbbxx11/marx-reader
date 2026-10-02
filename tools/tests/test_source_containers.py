import copy
import hashlib
import json
from pathlib import Path
import unittest
from build_full_chinese_library import extract_chapter
from repair_source_containers import REVIEW, restore


class SourceContainerRepairTest(unittest.TestCase):
    def test_source_prefix_does_not_remove_original_editorial_note(self):
        article = '正文完整句子。' * 80
        html = '<body><h1>测试</h1><div>来源：原刊。<br>译者：甲。<br>说明：原译文说明，不属于导航。</div>' + article + '</body>'
        chapter = extract_chapter('https://www.marxists.org/fixture', '测试', html, 1)
        text = ''.join(chapter['content'])
        self.assertIn('说明：原译文说明，不属于导航。', text)
        self.assertIn('译者：甲。', text)
        self.assertIn(article, text)

    def test_standalone_source_line_remains_filtered(self):
        html = '<body><h1>测试</h1><div>来源：原刊。</div>' + '正文完整句子。' * 80 + '</body>'
        chapter = extract_chapter('https://www.marxists.org/fixture', '测试', html, 1)
        self.assertNotIn('来源：原刊。', ''.join(chapter['content']))

    def test_real_insertion_preserves_notes_sections_and_old_annotations(self):
        r = json.loads(REVIEW.read_text())['repairs'][0]
        root = Path(__file__).resolve().parents[2]
        book = json.loads((root / 'app/src/main/assets/library/books' / (r['bookId'] + '.json')).read_text())['books'][0]
        c = copy.deepcopy(book['chapters'][0]); pi = r['paragraphIndex']
        self.assertFalse(restore(c, r))
        current = c['content'][pi]
        c['content'][pi] = current[:-(len('\n' + '\n'.join(r['sourceLines'])))]
        before = copy.deepcopy(c)
        self.assertTrue(restore(c, r))
        self.assertEqual(before['footnotes'], c['footnotes'])
        self.assertEqual(before['sections'], c['sections'])
        self.assertEqual(len(before['content']), len(c['content']))
        self.assertEqual(r['restorationMapping']['currentHash'], hashlib.sha256(current.encode()).hexdigest())
        a, b = r['restorationMapping']['ranges'][0]
        self.assertEqual(before['content'][pi], current[:a] + current[b+1:])

    def test_unreviewed_text_change_aborts_repair(self):
        r = json.loads(REVIEW.read_text())['repairs'][0]
        c = {'content': ['x'] * (r['paragraphIndex'] + 1)}
        with self.assertRaises(AssertionError):
            restore(c, r)
