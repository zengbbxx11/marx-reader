import copy
import hashlib
import json
from pathlib import Path
import unittest
from library_footnotes import recover_book
from repair_source_filter_notes import REVIEW, restore
from repair_source_short_lines import text_map


class FilterNoteRepairTest(unittest.TestCase):
    def setUp(self):
        self.record = json.loads(REVIEW.read_text())['repairs'][0]
        root = Path(__file__).resolve().parents[2]
        self.book = json.loads((root / 'app/src/main/assets/library/books' / (self.record['bookId'] + '.json')).read_text())['books'][0]

    def test_repair_preserves_all_positions_and_retains_both_editions(self):
        record = self.record
        chapter = copy.deepcopy(self.book['chapters'][0]); pi = record['paragraphIndex']
        current = chapter['content'][pi]
        chapter['content'][pi] = current[:-(len(record['sourceParagraph']) + 1)]
        note_index = next(i for i, n in enumerate(chapter['footnotes']) if n['id'] == record['previousNote']['id'])
        chapter['footnotes'][note_index] = copy.deepcopy(record['previousNote'])
        before = copy.deepcopy(chapter)
        self.assertTrue(restore(chapter, record))
        self.assertFalse(restore(chapter, record))
        self.assertEqual(len(before['content']), len(chapter['content']))
        self.assertEqual(before['sections'], chapter['sections'])
        self.assertEqual([(n['id'], n['references']) for n in before['footnotes']], [(n['id'], n['references']) for n in chapter['footnotes']])
        note = chapter['footnotes'][note_index]
        self.assertEqual(record['sourceNoteContent'][0], note['content'][0])
        self.assertIn('中文马克思主义文库', note['content'][0])
        self.assertTrue(note['content'][1].startswith('PDF 校补对照（另一版本）：'))
        self.assertEqual(record['alignedTextSha256'], hashlib.sha256(text_map(chapter)[0].encode()).hexdigest())
        a, b = record['restorationMapping']['ranges'][0]
        self.assertEqual(before['content'][pi], current[:a] + current[b+1:])

    def test_changed_context_aborts(self):
        chapter = copy.deepcopy(self.book['chapters'][0]); pi = self.record['paragraphIndex']
        chapter['content'][pi] = '不匹配的源段落'
        with self.assertRaises(AssertionError):
            restore(chapter, self.record)

    def test_note_recovery_from_manifest_keeps_source_note(self):
        book = copy.deepcopy(self.book); chapter = book['chapters'][0]
        chapter['footnotes'] = [n for n in chapter['footnotes'] if n['id'] != self.record['previousNote']['id']]
        result = recover_book(book)
        self.assertEqual(1, result['addedReferences'])
        note = next(n for n in chapter['footnotes'] if n['marker'] == '[93]')
        self.assertEqual(self.record['previousNote']['id'], note['id'])
        self.assertEqual(self.record['previousNote']['references'], note['references'])
        self.assertEqual(self.record['sourceNoteContent'][0], note['content'][0])
        self.assertTrue(note['content'][1].startswith('PDF 校补对照'))
