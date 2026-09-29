"""Keep each author's MIA directory order, independently of dates and titles."""
import json
from pathlib import Path

MANIFEST = Path(__file__).with_name('library_source_order.json')


def apply_source_order(catalog: dict) -> None:
    indexes = json.loads(MANIFEST.read_text('utf-8'))
    for author in catalog['authors']:
        source = indexes.get(author['id'])
        if source is None:
            continue
        books = [b for b in catalog['books'] if author['id'] in b['authorIds']]
        by_url = {}
        for book in books:
            by_url.setdefault(book['sourceUrl'], []).append(book['id'])
        ordered = []
        for url in source['urls']:
            for book_id in by_url.get(url, []):
                if book_id not in ordered:
                    ordered.append(book_id)
        # Entries absent from the saved directory snapshot retain their
        # existing relative order after its listed works.
        ordered.extend(b['id'] for b in books if b['id'] not in ordered)
        author['sourceBookIds'] = ordered
        author['sourceOrderUrl'] = source['url']
