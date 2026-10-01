"""Apply reviewed, context-specific image corrections without altering body text."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from bs4 import BeautifulSoup
from library_source_images import MANIFEST
from text_encoding import decode_html


def repair(library, evidence, apply=False):
    changes, corrections = {}, []
    for image in json.loads(MANIFEST.read_text())['images']:
        for page, correction in image.get('referenceCorrections', {}).items():
            proof = correction['evidence']
            raw = (evidence / 'snapshots' / (hashlib.sha256(proof['sourcePageUrl'].encode()).hexdigest() + '.bin')).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == proof['sourceRawSha256']
            html = decode_html(raw, None)
            text = ' '.join(BeautifulSoup(html, 'lxml').get_text(' ', strip=True).split())
            assert proof['englishContext'] in text
            assert BeautifulSoup(html, 'lxml').find('img', src=Path(correction['sourceUrl']).name) is not None
            source_raw = (evidence / 'snapshots' / (hashlib.sha256(page.encode()).hexdigest() + '.bin')).read_bytes()
            chinese = ''.join(BeautifulSoup(decode_html(source_raw, None), 'lxml').get_text().split())
            assert ''.join(proof['chineseContext'].split()) in chinese
            assert hashlib.sha256((library.parent / correction['assetPath']).read_bytes()).hexdigest() == correction['sha256']
            refs = [r for r in image['references'] if r['sourceUrl'] == page]
            assert len(refs) == 1
            ref = refs[0]; path = library / 'books' / (ref['bookId'] + '.json')
            payload = json.loads(path.read_text()); before = copy.deepcopy(payload)
            chapter = next(c for c in payload['books'][0]['chapters'] if c['id'] == ref['chapterId'])
            notes = [n for n in chapter['footnotes'] if n.get('sourceUrl') in (image['sourceUrl'], correction['sourceUrl'])]
            assert len(notes) == 1
            note = notes[0]
            assert note['imageAsset'] in (image['assetPath'], correction['assetPath'])
            note.update(content=[correction['description']], imageAsset=correction['assetPath'],
                        imageSha256=correction['sha256'], sourceUrl=correction['sourceUrl'],
                        originalSourceUrl=image['sourceUrl'], correctionEvidence=proof)
            old_chapter = next(c for c in before['books'][0]['chapters'] if c['id'] == ref['chapterId'])
            assert chapter['content'] == old_chapter['content']
            assert payload['books'][0]['toc'] == before['books'][0]['toc']
            assert [(n['id'], n['references']) for n in chapter['footnotes']] == [(n['id'], n['references']) for n in old_chapter['footnotes']]
            if payload != before: changes[path] = payload
            corrections.append({'bookId': ref['bookId'], 'chapterId': ref['chapterId'], 'sourceUrl': page,
                                'sourceRawSha256': hashlib.sha256(source_raw).hexdigest(),
                                'originalImageUrl': image['sourceUrl'], 'correctedImageUrl': correction['sourceUrl'],
                                'imageSha256': correction['sha256'], 'assetPath': correction['assetPath'], 'evidence': proof})
    if apply:
        backup = evidence / 'before-brick-correction'; backup.mkdir(exist_ok=True)
        for path, payload in changes.items():
            if not (backup / path.name).exists(): (backup / path.name).write_bytes(path.read_bytes())
            path.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')))
    return {'changedBooks': [p.stem for p in changes], 'corrections': corrections, 'applied': apply}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('app/src/main/assets/library'))
    parser.add_argument('--evidence', type=Path, default=Path('.generated/source-final-gaps-20261001'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.library, args.evidence, args.apply), ensure_ascii=False, indent=2))
