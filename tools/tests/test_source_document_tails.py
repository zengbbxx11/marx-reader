import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from bs4 import BeautifulSoup
import library_source_reviewed_blocks as blocks
from repair_source_document_tails import ROOT, REVIEW, restore


class SourceDocumentTailTest(unittest.TestCase):
    def test_signed_boundary_repair_recovers_tail_and_rejects_changed_source(self):
        html = '<html><body><p>前文</p></body></html><h3>II</h3><p>后文</p></body></html>'
        repaired = html.replace('</body></html>', '', 1)
        record = {'sourceUrl': 'reviewed', 'decodedHtmlSha256': hashlib.sha256(html.encode()).hexdigest(),
                  'repairedHtmlSha256': hashlib.sha256(repaired.encode()).hexdigest(),
                  'closingTagCounts': {'body': 2, 'html': 2}}
        self.assertNotIn('后文', BeautifulSoup(html, 'lxml').get_text())
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            manifest.write_text(json.dumps({'documentBoundaryRepairs': [record]}))
            with patch.object(blocks, 'MANIFEST', manifest):
                result = blocks.repair_document_boundaries('reviewed', html)
                self.assertIn('后文', BeautifulSoup(result, 'lxml').get_text())
                self.assertEqual(blocks.repair_document_boundaries('other', html), html)
                with self.assertRaises(ValueError):
                    blocks.repair_document_boundaries('reviewed', html + 'changed')
                record['closingTagCounts']['body'] = 3
                manifest.write_text(json.dumps({'documentBoundaryRepairs': [record]}))
                with self.assertRaises(AssertionError):
                    blocks.repair_document_boundaries('reviewed', html)

    def test_restoration_preserves_old_paragraphs_notes_sections_and_is_idempotent(self):
        record = json.loads(REVIEW.read_text())['repairs'][0]
        book = json.loads((ROOT / 'app/src/main/assets/library/books' / (record['bookId'] + '.json')).read_text())['books'][0]
        current = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
        previous = copy.deepcopy(current)
        previous['content'] = previous['content'][:record['previousParagraphCount']]
        added_ids = {s['id'] for s in record['addedSections']}
        previous['sections'] = [s for s in previous['sections'] if s['id'] not in added_ids]
        before = copy.deepcopy(previous)
        self.assertTrue(restore(previous, record))
        self.assertEqual(previous, current)
        self.assertFalse(restore(previous, record))
        self.assertEqual(previous['content'][:66], before['content'])
        self.assertEqual(previous['footnotes'], before['footnotes'])
        self.assertEqual(previous['sections'][:len(before['sections'])], before['sections'])
        for section in record['addedSections']:
            self.assertGreaterEqual(section['paragraphIndex'], 66)
            self.assertTrue(any(n['title'] == section['title'] and n['paragraphIndex'] == section['paragraphIndex'] for n in book['toc']))
        previous['content'][0] += '改文'
        with self.assertRaises(AssertionError):
            restore(previous, record)
