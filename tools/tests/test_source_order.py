import copy
import json
import unittest
from pathlib import Path
from library_source_order import apply_source_order, MANIFEST


class SourceOrderTest(unittest.TestCase):
    def test_bundled_catalog_matches_each_directory_and_keeps_all_books(self):
        path = Path(__file__).resolve().parents[2] / 'app/src/main/assets/library/catalog.json'
        catalog = json.loads(path.read_text('utf-8'))
        original = copy.deepcopy(catalog)
        apply_source_order(catalog)
        self.assertEqual(original, catalog)
        indexes = json.loads(MANIFEST.read_text('utf-8'))
        for author in catalog['authors']:
            books = {b['id']: b for b in catalog['books'] if author['id'] in b['authorIds']}
            ordered = author['sourceBookIds']
            self.assertEqual(set(books), set(ordered))
            self.assertEqual(len(books), len(ordered))
            ranks = {u: i for i, u in enumerate(indexes[author['id']]['urls'])}
            actual = [ranks[books[i]['sourceUrl']] for i in ordered if books[i]['sourceUrl'] in ranks]
            self.assertEqual(sorted(actual), actual)


if __name__ == '__main__':
    unittest.main()
