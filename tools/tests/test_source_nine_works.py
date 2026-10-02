import copy
import hashlib
import json
import unittest
from library_footnotes import recover_book
from repair_source_nine_works import ROOT, REVIEW, restore


class NineWorksTest(unittest.TestCase):
    def setUp(self):
        self.review = json.loads(REVIEW.read_text())

    def test_guarded_repair_is_idempotent_and_preserves_old_positions(self):
        for record in self.review['repairs']:
            old = copy.deepcopy(record['previousChapter'])
            self.assertTrue(restore(old, record))
            self.assertEqual(record['currentChapter'], old)
            self.assertFalse(restore(old, record))
            changes = {c['paragraphIndex']: c for c in record.get('headingChanges', [])}
            for pi, (before, after) in enumerate(zip(record['previousChapter']['content'], old['content'])):
                self.assertEqual(before, after[len(changes[pi]['prefix']):] if pi in changes else after)
                if pi in changes:
                    mapping = changes[pi]['restorationMapping']
                    self.assertEqual(hashlib.sha256(after.encode()).hexdigest(), mapping['currentHash'])
                    self.assertEqual(hashlib.sha256(before.encode()).hexdigest(), mapping['previousHash'])
            for note in record['previousChapter']['footnotes']:
                self.assertIn(note, old['footnotes'])
            old['content'][0] += '漂移'
            with self.assertRaisesRegex(AssertionError, 'changed'):
                restore(old, record)

    def test_all_41_page_notes_have_exact_references_and_definition_bodies(self):
        record = self.review['repairs'][0]
        chapter = record['currentChapter']
        self.assertEqual(41, len(record['footnoteSupplements']))
        for supplement in record['footnoteSupplements']:
            pi, start, marker = supplement['paragraphIndex'], supplement['start'], supplement['marker']
            notes = [n for n in chapter['footnotes'] if {'paragraphIndex': pi, 'start': start, 'end': start+len(marker)} in n['references']]
            self.assertEqual(1, len(notes))
            self.assertEqual(supplement['content'], notes[0]['content'])
            self.assertEqual(marker, chapter['content'][pi][start:start+len(marker)])
            self.assertTrue(chapter['content'][supplement['source']['definitionParagraphIndex']].endswith(supplement['content'][0]))
        book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId']+'.json')).read_text())['books'][0]
        self.assertEqual(0, recover_book(copy.deepcopy(book))['addedReferences'])
        for section in chapter['sections']:
            self.assertIn(section['title'], chapter['content'][section['paragraphIndex']])
            self.assertTrue(any(n['id'].endswith('-'+section['id']) and n['paragraphIndex'] == section['paragraphIndex'] for n in book['toc']))

    def test_asterisk_provenance_and_manuscript_are_accessible_without_moving_body(self):
        record = self.review['repairs'][1]
        chapter = record['currentChapter']
        self.assertEqual(record['previousChapter']['content'], chapter['content'])
        note = next(n for n in chapter['footnotes'] if n['marker'] == '*')
        ref = note['references'][0]
        self.assertEqual('*', chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])
        self.assertEqual(chapter['content'][5].lstrip('* ').strip(), note['content'][0])
        raw = (ROOT / 'app/src/main/assets' / note['imageAsset']).read_bytes()
        self.assertEqual(note['imageSha256'], hashlib.sha256(raw).hexdigest())
