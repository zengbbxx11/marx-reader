#!/usr/bin/env python3
"""Render reviewed source HTML tables offline, preserving rowspan/colspan.

Requires playwright==1.55.0 and an installed Chromium; no browser download or
network requests. This creates a layout reproduction, not a source screenshot.
"""
import argparse
import base64
import hashlib
import json
import struct
from pathlib import Path
from bs4 import BeautifulSoup
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'tools/library_source_tables.json'


def render(browser_path):
    manifest = json.loads(MANIFEST.read_text())
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=browser_path, args=['--no-sandbox'])
        page = browser.new_page(viewport={'width': 2400, 'height': 2000}, device_scale_factor=2)
        page.route('**/*', lambda route: route.abort())
        for record in manifest['tables']:
            assert hashlib.sha256(record['html'].encode()).hexdigest() == record['tableHtmlSha256']
            existing = ROOT / 'app/src/main/assets' / record['assetPath']
            if existing.exists() and record.get('imageSha256') and record.get('kind') != 'grouping_diagram':
                assert hashlib.sha256(existing.read_bytes()).hexdigest() == record['imageSha256']
                continue
            soup = BeautifulSoup(record['html'], 'lxml')
            table = soup.table
            allowed = {'img', 'a', 'col', 'colgroup', 'caption', 'table', 'tbody', 'thead', 'tfoot', 'tr', 'td', 'th', 'br', 'b', 'strong', 'i', 'em', 'sup', 'sub', 'small', 'span', 'font', 'div', 'p', 'u'}
            for node in [table, *table.find_all()]:
                if node.name not in allowed:
                    raise ValueError(f'Unsupported source table element: {node.name}')
                if node.name == 'img':
                    image_record = next(r for r in record.get('embeddedImages',[]) if r['originalSrc'] == node.get('src'))
                    raw = (ROOT/'app/src/main/assets'/image_record['assetPath']).read_bytes()
                    assert hashlib.sha256(raw).hexdigest() == image_record['imageSha256']
                    mime = 'image/png' if image_record['assetPath'].endswith('.png') else 'image/jpeg'
                    align = node.get('align')
                    node.attrs = {'src':'data:'+mime+';base64,'+base64.b64encode(raw).decode(), 'width':round(image_record['width']*1.375), 'height':round(image_record['height']*1.375)}
                    if align: node['align'] = align
                    continue
                node.attrs = {k: v for k, v in node.attrs.items() if k in ('rowspan', 'colspan', 'align')}
            html = '''<!doctype html><meta charset="utf-8"><style>
            body { margin: 0; background: white; color: #171717; }
            table { border-collapse: collapse; width: max-content; min-width: 850px; }
            td, th { border: 1px solid #777; padding: 10px 14px; font-size: 22px; line-height: 1.6;
                     font-family: "Noto Serif CJK SC", serif; max-width: 420px; }
            font { font-size: inherit; color: inherit; }
            </style>''' + str(table)
            if record.get('kind') == 'grouping_diagram':
                html += '<style>table { min-width:0; } td { border:0; padding:0 6px; line-height:2; max-width:none; white-space:nowrap; }</style>'
            page.set_content(html)
            page.evaluate('document.fonts.ready')
            output = ROOT / 'app/src/main/assets' / record['assetPath']
            output.parent.mkdir(parents=True, exist_ok=True)
            page.locator('table').screenshot(path=str(output))
            record['imageSha256'] = hashlib.sha256(output.read_bytes()).hexdigest()
            record['imageWidth'], record['imageHeight'] = struct.unpack('>II', output.read_bytes()[16:24])
        browser.close()
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(f"Rendered {len(manifest['tables'])} offline source tables")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser', default='/usr/bin/chromium')
    render(parser.parse_args().browser)
