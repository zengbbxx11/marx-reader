import copy
import json
import unittest
from repair_source_heading_locations import ROOT, REVIEW, restore


class SourceHeadingLocationTest(unittest.TestCase):
    def test_reviewed_toc_repairs_preserve_body_notes_and_old_sections(self):
        records = json.loads(REVIEW.read_text())['repairs']
        self.assertEqual(sum(len(r['sections']) for r in records), 3)
        for record in records:
            book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
            chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
            previous = copy.deepcopy(chapter)
            added_ids = {s['id'] for s in record['sections']}
            previous['sections'] = [s for s in previous['sections'] if s['id'] not in added_ids]
            before = copy.deepcopy(previous)
            self.assertTrue(restore(previous, record))
            self.assertEqual(previous, chapter)
            self.assertFalse(restore(previous, record))
            self.assertEqual(previous['content'], before['content'])
            self.assertEqual(previous['footnotes'], before['footnotes'])
            for s in record['sections']:
                self.assertTrue(any(n['title'] == s['title'] and n['paragraphIndex'] == s['paragraphIndex'] for n in book['toc']))

    def test_changed_body_is_rejected(self):
        record = json.loads(REVIEW.read_text())['repairs'][0]
        book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
        chapter = copy.deepcopy(book['chapters'][0]); chapter['content'][0] += '改文'
        with self.assertRaises(AssertionError): restore(chapter, record)
