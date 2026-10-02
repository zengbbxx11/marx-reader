import copy
import json
import unittest
from repair_source_printed_note_tails import ROOT, REVIEW, restore


class PrintedNoteTailTest(unittest.TestCase):
    def fixtures(self):
        book = json.loads((ROOT / 'app/src/main/assets/library/books/capital-v1-zh.json').read_text())['books'][0]
        records = json.loads(REVIEW.read_text())['repairs']
        self.assertEqual(9, len(records))
        for record in records:
            chapter = copy.deepcopy(next(c for c in book['chapters'] if c['id'] == record['chapterId']))
            next(n for n in chapter['footnotes'] if n['id'] == record['noteId'])['content'] = record['previousNoteContent'][:]
            yield chapter, record

    def test_all_repairs_change_only_reviewed_payload_and_are_idempotent(self):
        for chapter, record in self.fixtures():
            with self.subTest(note=record['noteId'], chapter=record['chapterId']):
                expected = copy.deepcopy(chapter)
                next(n for n in expected['footnotes'] if n['id'] == record['noteId'])['content'] = record['sourceNoteContent'][:]
                self.assertTrue(restore(chapter, record))
                self.assertFalse(restore(chapter, record))
                self.assertEqual(expected, chapter)
                for n in chapter['footnotes']:
                    for ref in n.get('references', []):
                        self.assertEqual(n['marker'], chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])

    def test_independent_supplement_must_remain_complete(self):
        for chapter, record in self.fixtures():
            target = record['independentPrintedNotes'][0]['id']
            next(n for n in chapter['footnotes'] if n['id'] == target)['content'].append('未经核验的变化')
            before = copy.deepcopy(chapter)
            with self.assertRaises(AssertionError):
                restore(chapter, record)
            self.assertEqual(before, chapter)
