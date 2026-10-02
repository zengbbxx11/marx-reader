import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import verify_source_repairs


class ReviewedBodyLedgerTest(unittest.TestCase):
    def check(self, content, chapter_id='chapter'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            books = root / 'app/src/main/assets/library/books'; books.mkdir(parents=True)
            (books / 'book.json').write_text(json.dumps({'books': [{'chapters': [{'id': chapter_id, 'content': content}]}]}))
            ledger = root / 'ledger.json'
            ledger.write_text(json.dumps({'verifiedAssets': [], 'repairs': [], 'reviews': [{'bookId': 'book', 'chapterId': 'chapter', 'localBodySha256': hashlib.sha256('源文'.encode()).hexdigest()}]}))
            with patch.object(verify_source_repairs, 'ROOT', root), patch.object(verify_source_repairs, 'LEDGER', ledger), contextlib.redirect_stdout(io.StringIO()):
                verify_source_repairs.verify()

    def test_layout_and_known_asset_markers_do_not_change_body_hash(self):
        self.check(['源 \n文〔附件1〕'])

    def test_report_cannot_hide_changed_shipped_text(self):
        with self.assertRaisesRegex(SystemExit, 'Reviewed body changed'):
            self.check(['改文'])

    def test_deleted_reviewed_chapter_is_reported(self):
        with self.assertRaisesRegex(SystemExit, 'Reviewed chapter missing'):
            self.check(['源文'], chapter_id='different')
