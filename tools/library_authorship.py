"""Author attribution is separate from editing and posthumous publication."""


def apply_authorship(book: dict) -> None:
    if book.get('seriesId') in {'capital-v1', 'capital-v2', 'capital-v3'}:
        book['authorIds'] = ['marx']
