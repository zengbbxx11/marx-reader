"""Source-signed note-container exits reviewed independently of the importer."""
import hashlib
import json
from functools import lru_cache
from pathlib import Path


@lru_cache(maxsize=1)
def _manifest():
    return json.loads(Path(__file__).with_suffix('.json').read_text())


def reviewed_note_end_nodes(root, url, html):
    record = _manifest().get(url)
    if record is None:
        return []
    if hashlib.sha256(html.encode()).hexdigest() != record['htmlSha256']:
        raise ValueError(f'Reviewed note-boundary source changed: {url}')
    nodes = []
    for boundary in record['ends']:
        anchors = root.find_all('a', attrs={'name': boundary['noteId']})
        if not anchors:
            anchors = root.find_all('a', id=boundary['noteId'])
        if len(anchors) != 1:
            raise ValueError(f'Reviewed definition anchor changed: {url}/{boundary["noteId"]}')
        node = anchors[0].find_parent(boundary['tag'])
        if node is None:
            raise ValueError(f'Reviewed definition container changed: {url}/{boundary["noteId"]}')
        if all(node is not previous for previous in nodes):
            nodes.append(node)
    return nodes
