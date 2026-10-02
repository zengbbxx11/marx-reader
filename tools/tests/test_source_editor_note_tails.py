import copy
import json
import unittest
from repair_source_editor_note_tails import ROOT, REVIEW, restore


class EditorNoteTailTest(unittest.TestCase):
    def fixture(self):
        record = json.loads(REVIEW.read_text())['repairs'][0]
        book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
        chapter = copy.deepcopy(next(c for c in book['chapters'] if c['id'] == record['chapterId']))
        note = next(n for n in chapter['footnotes'] if n['id'] == record['noteId'])
        note['content'] = record['previousNoteContent'][:]
        return chapter, record

    def test_only_duplicate_tail_changes_and_repair_is_idempotent(self):
        chapter, record = self.fixture()
        before = copy.deepcopy(chapter)
        self.assertTrue(restore(chapter, record))
        self.assertFalse(restore(chapter, record))
        expected = copy.deepcopy(before)
        next(n for n in expected['footnotes'] if n['id'] == record['noteId'])['content'] = record['sourceNoteContent'][:]
        self.assertEqual(expected, chapter)
        self.assertEqual(15, len(record['independentEditorNotes']))
        for n in chapter['footnotes']:
            for ref in n.get('references', []):
                self.assertEqual(n['marker'], chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])

    def test_refuses_to_trim_if_independent_editor_note_would_be_lost(self):
        chapter, record = self.fixture()
        target = record['independentEditorNotes'][0]['id']
        chapter['footnotes'] = [n for n in chapter['footnotes'] if n['id'] != target]
        before = copy.deepcopy(chapter)
        with self.assertRaises((AssertionError, KeyError)):
            restore(chapter, record)
        self.assertEqual(before, chapter)
