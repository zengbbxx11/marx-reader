import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from bs4 import BeautifulSoup
from library_source_tables import BOUNDARY, table_tokens, MANIFEST
from repair_source_tables import apply_edits, table_boundaries, verify_boundaries

ROOT = Path(__file__).resolve().parents[2]


class SourceTablesTest(unittest.TestCase):
    def test_complete_source_sequence_required_before_splitting_numbers(self):
        chapter = {'id': 'c', 'content': ['其他数字790103，并不是完整表格。']}
        with self.assertRaisesRegex(ValueError, 'not uniquely aligned'):
            table_boundaries(chapter, {'chapterId': 'c', 'tableIndex': 0, 'rows': [['人数', '790', '103']]})

    def test_duplicate_table_sequence_requires_review(self):
        with self.assertRaisesRegex(ValueError, 'not uniquely aligned'):
            table_boundaries({'content': ['人数790103。人数790103']},
                             {'chapterId': 'c', 'tableIndex': 0, 'rows': [['人数', '790', '103']]})

    def test_boundaries_inserted_without_changing_original_prose_or_old_footnote(self):
        old = {'content': ['表头人数比例79010313％[117]尾文'],
               'footnotes': [{'id': 'old', 'marker': '[117]', 'references': [{'paragraphIndex': 0, 'start': 15, 'end': 20}]}]}
        # Locate the old reference rather than depending on a hand-counted offset.
        ref = old['footnotes'][0]['references'][0]
        ref['start'] = old['content'][0].index('[117]'); ref['end'] = ref['start'] + 5
        before = copy.deepcopy(old)
        record = {'chapterId': 'c', 'tableIndex': 0, 'rows': [['表头', '人数', '比例'], ['790', '103', '13％']]}
        first, boundaries = table_boundaries(old, record)
        edits = {0: {0: '〔原表1〕', **{pos + 1: s for (pi, pos), s in boundaries.items()}}}
        apply_edits(old, edits, [({'id': 'source-table-001', 'marker': '〔原表1〕'}, 0, 0)])
        verify_boundaries(old, record)
        self.assertEqual(len(before['content']), len(old['content']))
        self.assertIn('790' + BOUNDARY + '103' + BOUNDARY + '13％', old['content'][0])
        ref = old['footnotes'][0]['references'][0]
        self.assertEqual('[117]', old['content'][0][ref['start']:ref['end']])

    def test_existing_paragraph_boundary_is_preserved(self):
        chapter = {'content': ['标题人数', '790103']}
        _, boundaries = table_boundaries(chapter, {'chapterId': 'c', 'tableIndex': 0, 'rows': [['标题', '人数'], ['790', '103']]})
        self.assertNotIn((0, len(chapter['content'][0]) - 1), boundaries)
        self.assertIn((1, 2), boundaries)

    def test_extractor_fails_if_reviewed_table_changes(self):
        html = '<table><tr><td>人数</td><td>790</td></tr></table>'
        record = {'sourceUrl': 'https://www.marxists.org/reviewed.htm', 'tableIndex': 0,
                  'tableHtmlSha256': hashlib.sha256(str(BeautifulSoup(html, 'lxml').table).encode()).hexdigest(),
                  'assetPath': 'library/illustrations/table.png', 'imageSha256': 'fixture'}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'tables.json'; manifest.write_text(json.dumps({'tables': [record]}))
            with patch('library_source_tables.MANIFEST', manifest):
                soup = BeautifulSoup(html.replace('790', '791'), 'lxml')
                with self.assertRaisesRegex(ValueError, 'Reviewed source table changed'):
                    table_tokens(soup, record['sourceUrl'], {})
                soup = BeautifulSoup(html, 'lxml'); tokens = {}
                notes = table_tokens(soup, record['sourceUrl'], tokens)
                self.assertIn(BOUNDARY, soup.text)
                self.assertEqual('〔原表1〕', notes['source-table-001']['marker'])

    def test_all_reviewed_offline_tables_have_source_hashes_boundaries_and_references(self):
        records = json.loads(MANIFEST.read_text())['tables']
        self.assertEqual(68, len(records))
        for record in records:
            self.assertEqual(record['tableHtmlSha256'], hashlib.sha256(record['html'].encode()).hexdigest())
            self.assertEqual(record['imageSha256'], hashlib.sha256((ROOT / 'app/src/main/assets' / record['assetPath']).read_bytes()).hexdigest())
            book = json.loads((ROOT / f"app/src/main/assets/library/books/{record['bookId']}.json").read_text())['books'][0]
            chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
            verify_boundaries(chapter, record)
            note = next(n for n in chapter['footnotes'] if n['id'] == f"source-table-{record['tableIndex'] + 1:03d}")
            self.assertEqual(record['assetPath'], note['imageAsset'])
            for ref in note['references']:
                self.assertEqual(note['marker'], chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])
