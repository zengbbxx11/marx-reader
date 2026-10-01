import json
import unittest
from pathlib import Path
from bs4 import BeautifulSoup
from library_source_headings import restore_numbered_headings

ROOT = Path(__file__).resolve().parents[2]


class SourceLayoutTest(unittest.TestCase):
    def test_mixed_summary_heading_preserves_prose(self):
        soup = BeautifulSoup('<body><p align="center"><font>九</font><br/>现在我们来作一个总结。</p></body>', 'lxml')
        restore_numbered_headings(soup, 'https://www.marxists.org/chinese/lenin/marxist.org-chinese-lenin-1915-5.htm')
        self.assertEqual('九', soup.h3.text)
        self.assertIn('现在我们来作一个总结。', soup.p.text)

    def test_changed_reviewed_heading_is_rejected(self):
        soup = BeautifulSoup('<body><p>九</p></body>', 'lxml')
        with self.assertRaisesRegex(ValueError, 'Reviewed heading changed'):
            restore_numbered_headings(soup, 'https://www.marxists.org/chinese/lenin/marxist.org-chinese-lenin-1915-5.htm')

    def test_numbered_headings_are_in_body_and_toc_with_parent_hierarchy(self):
        review = json.loads((ROOT / 'docs/SOURCE_LAYOUT_REVIEW_2026-10-01.json').read_text())
        for record in review['missingHeadings']:
            book = json.loads((ROOT / f"app/src/main/assets/library/books/{record['bookId']}.json").read_text())['books'][0]
            chapter = next(c for c in book['chapters'] if c['id'] == record['chapterId'])
            section = next(s for s in chapter['sections'] if s['title'] == record['heading'])
            self.assertTrue(chapter['content'][section['paragraphIndex']].startswith(record['heading'] + '\n'))
            node = next(n for n in book['toc'] if n.get('chapterId') == chapter['id'] and n['title'] == record['heading'])
            self.assertEqual(section['paragraphIndex'], node['paragraphIndex'])
            if record['heading'] != '九':
                parent = next(n for n in book['toc'] if n['id'] == node['parentId'])
                self.assertTrue(parent['title'].startswith('2 关于'))

    def test_all_ten_reviewed_body_images_are_bundled_and_referenced(self):
        review = json.loads((ROOT / 'docs/SOURCE_LAYOUT_REVIEW_2026-10-01.json').read_text())
        records = [r for r in review['images'] if r['status'] in ('BODY_TABLE_IMAGE_MISSING', 'BODY_DIAGRAM_MISSING')]
        self.assertEqual(10, len(records))
        for record in records:
            ref = record['references'][0]
            book = json.loads((ROOT / f"app/src/main/assets/library/books/{ref['bookId']}.json").read_text())['books'][0]
            chapter = next(c for c in book['chapters'] if c['id'] == ref['chapterId'])
            note = next(n for n in chapter['footnotes'] if n.get('sourceUrl') == record['imageUrl'])
            self.assertEqual(record['sha256'], note['imageSha256'])
            self.assertTrue((ROOT / 'app/src/main/assets' / note['imageAsset']).is_file())
