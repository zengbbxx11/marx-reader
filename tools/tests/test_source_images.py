import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from build_full_chinese_library import extract_chapter
from library_source_images import MANIFEST, image_tokens
from repair_source_content import insert_formulas
from audit_footnotes import audit


class SourceImagesTest(unittest.TestCase):
    def test_wall_correction_is_scoped_to_body_page_and_cover_is_retained(self):
        from bs4 import BeautifulSoup
        base = 'https://www.marxists.org/chinese/engels/1844-1845/'
        wall = image_tokens(BeautifulSoup('<body><img src="11.jpg"></body>', 'lxml'), base + '05.htm', {})['source-attachment-001']
        cover = image_tokens(BeautifulSoup('<body><img src="11.jpg"></body>', 'lxml'), base + '15.htm', {})['source-attachment-001']
        self.assertTrue(wall['imageAsset'].endswith('bricks2.gif'))
        self.assertEqual(base + '11.jpg', wall['originalSourceUrl'])
        self.assertTrue(wall['sourceUrl'].endswith('bricks2.gif'))
        self.assertEqual(base + '11.jpg', cover['sourceUrl'])
        self.assertNotEqual(wall['imageAsset'], cover['imageAsset'])
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(wall['imageSha256'], hashlib.sha256((root / 'app/src/main/assets' / wall['imageAsset']).read_bytes()).hexdigest())

    def test_extractor_retains_image_between_words_as_clickable_source_note(self):
        html = '<body><p>' + '正文' * 120 + '就是<img src="003-2.jpg">。因此。</p></body>'
        chapter = extract_chapter('https://www.marxists.org/chinese/marx-engels/24/003.htm', '第一章', html, 1)
        self.assertIn('就是〔图式1〕。因此', ''.join(chapter['content']))
        note = chapter['footnotes'][0]
        self.assertTrue(note['imageAsset'].endswith('003-2.jpg'))
        ref = note['references'][0]
        self.assertEqual(note['marker'], chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])

    def test_unreviewed_capital_image_cannot_silently_disappear(self):
        with self.assertRaisesRegex(ValueError, 'Unreviewed Capital II image'):
            extract_chapter('https://www.marxists.org/chinese/marx-engels/24/003.htm', '第一章',
                            '<body><p>正文<img src="new-formula.jpg"></p></body>', 1)

    def test_insertions_keep_paragraphs_and_remap_old_note_on_same_paragraph(self):
        old = {'id': 'c', 'content': ['甲乙[1]丙', '丁'],
               'footnotes': [{'id': 'old', 'marker': '[1]', 'content': ['注'],
                             'references': [{'paragraphIndex': 0, 'start': 2, 'end': 5}]}]}
        source = {'content': ['甲〔图式1〕乙[1]丙', '丁'],
                  'footnotes': [{'id': 'source-image-001', 'marker': '〔图式1〕', 'content': ['图式'],
                                'imageAsset': 'library/illustrations/example.jpg'}]}
        self.assertEqual(1, insert_formulas(old, source))
        self.assertEqual(2, len(old['content']))
        ref = old['footnotes'][0]['references'][0]
        self.assertEqual('[1]', old['content'][0][ref['start']:ref['end']])
        self.assertEqual(0, insert_formulas(old, source))

    def test_source_changes_are_rejected_before_mutating_text(self):
        old = {'id': 'c', 'content': ['甲乙'], 'footnotes': []}
        original = copy.deepcopy(old)
        with self.assertRaisesRegex(AssertionError, 'Source text changed'):
            insert_formulas(old, {'content': ['甲〔图式1〕错误'], 'footnotes': []})
        self.assertEqual(original, old)

    def test_shipped_images_and_all_49_references_match_source_hashes(self):
        root = Path(__file__).resolve().parents[2]
        records = [r for r in json.loads(MANIFEST.read_text())['images']
                   if r.get('kind') != 'source_attachment']
        self.assertEqual(23, len(records))
        for record in records:
            raw = (root / 'app/src/main/assets' / record['assetPath']).read_bytes()
            self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
        book = json.loads((root / 'app/src/main/assets/library/books/capital-v2-zh.json').read_text())['books'][0]
        notes = [n for c in book['chapters'] for n in c['footnotes'] if n.get('imageAsset') and 'sourceTableIndex' not in n]
        self.assertEqual(49, len(notes))
        self.assertEqual({r['assetPath'] for r in records if '/capital-v2/' in r['assetPath']}, {n['imageAsset'] for n in notes})

    def test_attachments_preserve_existing_image_ids_and_remap_old_notes(self):
        import re
        pattern = re.compile(r'〔附件\d+〕')
        old = {'id': 'c', 'content': ['甲〔图式1〕乙[1]丙'], 'footnotes': [
            {'id': 'old', 'marker': '[1]', 'references': [{'paragraphIndex': 0, 'start': 7, 'end': 10}]}]}
        source = {'content': ['甲〔附件1〕〔图式1〕乙[1]丙'], 'footnotes': [
            {'id': 'source-image-001', 'marker': '〔图式1〕', 'imageAsset': 'old.jpg'},
            {'id': 'source-attachment-001', 'marker': '〔附件1〕', 'imageAsset': 'new.jpg'}]}
        self.assertEqual(1, insert_formulas(old, source, pattern))
        self.assertEqual(['old', 'source-attachment-001'], [n['id'] for n in old['footnotes']])
        ref = old['footnotes'][0]['references'][0]
        self.assertEqual('[1]', old['content'][0][ref['start']:ref['end']])
        self.assertEqual(0, insert_formulas(old, source, pattern))

    def test_shipped_attachments_have_all_reviewed_references_and_hashes(self):
        root = Path(__file__).resolve().parents[2]
        records = [r for r in json.loads(MANIFEST.read_text())['images'] if r.get('kind') == 'source_attachment']
        self.assertEqual(29, len(records))
        for image in records:
            self.assertEqual(image['sha256'], hashlib.sha256((root / 'app/src/main/assets' / image['assetPath']).read_bytes()).hexdigest())
            for ref in image['references']:
                resolved = {**image, **image.get('referenceCorrections', {}).get(ref['sourceUrl'], {})}
                book = json.loads((root / 'app/src/main/assets/library/books' / (ref['bookId'] + '.json')).read_text())['books'][0]
                chapter = next(c for c in book['chapters'] if c['id'] == ref['chapterId'])
                notes = [n for n in chapter['footnotes'] if n.get('sourceUrl') == resolved['sourceUrl']]
                expected = sum(r['bookId'] == ref['bookId'] and r['chapterId'] == ref['chapterId'] for r in image['references'])
                self.assertEqual(expected, len(notes))
                for note in notes:
                    self.assertEqual(resolved['assetPath'], note['imageAsset'])
                    self.assertEqual(resolved['sha256'], note['imageSha256'])

    def test_audit_rejects_missing_image_even_when_text_offsets_are_valid(self):
        with tempfile.TemporaryDirectory() as temporary:
            library = Path(temporary) / 'library'
            (library / 'books').mkdir(parents=True)
            note = {'id': 'img', 'marker': '〔图式1〕', 'content': ['原图'],
                    'imageAsset': 'library/illustrations/missing.jpg', 'imageSha256': 'bad',
                    'references': [{'paragraphIndex': 0, 'start': 0, 'end': 5}]}
            book = {'id': 'sample', 'titleZh': '测试', 'chapters': [{'id': 'c', 'content': ['〔图式1〕'], 'footnotes': [note]}]}
            (library / 'books/sample.json').write_text(json.dumps({'books': [book]}))
            with self.assertRaisesRegex(SystemExit, 'missing offline image'):
                audit(library)
