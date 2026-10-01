"""Promote six reviewed source headings discarded by legacy short-line filtering."""

REVIEWED = {
    'https://www.marxists.org/chinese/lenin/marxist.org-chinese-lenin-1915-5.htm': ('九',),
    'https://www.marxists.org/chinese/lenin/03.htm': ('一', '二', '三', '四', '五'),
}


def restore_numbered_headings(root, url: str) -> None:
    for title in REVIEWED.get(url, ()):
        candidates = []
        for node in root.find_all(['p', 'font']):
            if node.get_text(strip=True) != title:
                continue
            if node.name == 'p' and node.get('align', '').lower() == 'center':
                candidates.append(node)
            elif node.name == 'font' and node.find_parent('p', attrs={'align': 'center'}):
                # A mixed paragraph starts with 九 followed by the summary prose.
                candidates.append(node)
        # Avoid selecting both a paragraph and its nested font.
        candidates = [n for n in candidates if not any(p in candidates for p in n.parents)]
        if len(candidates) != 1:
            raise ValueError(f'Reviewed heading changed: {url} / {title}')
        node = candidates[0]
        node.name = 'h3' if title == '九' else 'h4'
