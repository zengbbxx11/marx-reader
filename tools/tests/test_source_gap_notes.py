import copy
import json
import unittest
from pathlib import Path
from library_footnotes import recover_book
from repair_source_gap_notes import repair

ROOT = Path(__file__).resolve().parents[2]
LIBRARY = ROOT / 'app/src/main/assets/library'


class SourceGapNotesTest(unittest.TestCase):
    def test_verified_note_and_confirmed_source_gap_have_distinct_status(self):
        book = json.loads((LIBRARY / 'books/marx-work-bcd6bcd970b2.json').read_text())['books'][0]
        notes = {n['id']: n for n in book['chapters'][0]['footnotes']}
        fixed = notes['printed-f23b6a07921bb050']
        pending = notes['printed-cc271ec7fed6465f']
        self.assertNotIn('status', fixed)
        self.assertEqual('在《新莱茵报》上发表时不是“劳动力”，而是“劳动”。——编者注', fixed['content'][0])
        self.assertIn(fixed['content'][0], fixed['sourceEvidence']['sourceExcerpt'])
        self.assertEqual('SOURCE_MISSING', pending['status'])
        self.assertIn('原注缺失', ''.join(pending['content']))
        self.assertIn('不能作为本句的原注', ''.join(pending['content']))
        self.assertEqual('verified_source_gap', pending['sourceEvidence']['resolution']['status'])
        recovered = copy.deepcopy(book)
        recovered['chapters'][0]['footnotes'] = [n for n in recovered['chapters'][0]['footnotes']
                                               if n['id'] not in ('printed-f23b6a07921bb050', 'printed-cc271ec7fed6465f')]
        recover_book(recovered)
        for nid, previous in notes.items():
            if nid not in ('printed-f23b6a07921bb050', 'printed-cc271ec7fed6465f'): continue
            current = next(n for n in recovered['chapters'][0]['footnotes'] if n['id'] == nid)
            self.assertEqual(previous['content'], current['content'])
            self.assertEqual(previous.get('status'), current.get('status'))
            self.assertEqual(previous['references'], current['references'])

    def test_repair_is_repeatable_without_changing_assets(self):
        result = repair(LIBRARY)
        self.assertEqual([], result['changedBooks'])
        self.assertFalse(result['supplementsChanged'])
        self.assertEqual(0, result['versionConflictsPending'])
        self.assertEqual(1, result['verifiedSourceGaps'])
