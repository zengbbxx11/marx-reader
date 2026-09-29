import json
import unittest
from pathlib import Path
from library_authorship import apply_authorship


class AuthorshipTest(unittest.TestCase):
    def test_capital_volumes_belong_only_to_marx_in_assets_and_directory(self):
        root = Path(__file__).resolve().parents[2] / 'app/src/main/assets/library'
        catalog = json.loads((root / 'catalog.json').read_text('utf-8'))
        authors = {a['id']: a for a in catalog['authors']}
        for volume in (1, 2, 3):
            book_id = f'capital-v{volume}-zh'
            book = next(b for b in catalog['books'] if b['id'] == book_id)
            body = json.loads((root / 'books' / f'{book_id}.json').read_text('utf-8'))['books'][0]
            self.assertEqual(['marx'], book['authorIds'])
            self.assertEqual(['marx'], body['authorIds'])
            self.assertIn(book_id, authors['marx']['sourceBookIds'])
            self.assertNotIn(book_id, authors['engels']['sourceBookIds'])

    def test_repackaging_old_metadata_preserves_editorial_credit(self):
        book = {'seriesId': 'capital-v3', 'authorIds': ['marx', 'engels'],
                'description': '由恩格斯整理出版。'}
        apply_authorship(book)
        self.assertEqual(['marx'], book['authorIds'])
        self.assertEqual('由恩格斯整理出版。', book['description'])
        joint = {'authorIds': ['marx', 'engels'], 'seriesId': None}
        apply_authorship(joint)
        self.assertEqual(['marx', 'engels'], joint['authorIds'])
