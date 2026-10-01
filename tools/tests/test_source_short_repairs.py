import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from bs4 import BeautifulSoup
from build_full_chinese_library import extract_chapter
from library_source_short_lines import preserve_short_lines
from library_source_notes import note_tokens
from repair_source_short_lines import insert


class ShortLineRepairsTest(unittest.TestCase):
    def test_insertions_preserve_paragraph_indices_and_existing_footnote(self):
        before = '前面的文字足够长，缺失数字原来就在这个位置。后面的文字足够长，用于核对位置。[1]'
        left,right=before.split('。',1);left+='。'
        chapter={'id':'c','content':[before],'footnotes':[{'marker':'[1]','references':[{'paragraphIndex':0,'start':before.index('[1]'),'end':len(before)}]}]}
        record={'leftContext':left,'rightContext':right[:32],'text':'4','reviewCategory':'CONFIRMED_PLAIN_TEXT_TABLE_OMISSION','sourceUrl':'https://www.marxists.org/test','sourceLineIndex':1,'sourceRawSha256':'fixture'}
        count,_=insert(chapter,[record]);self.assertEqual(1,count)
        self.assertEqual(1,len(chapter['content']))
        ref=chapter['footnotes'][0]['references'][0]
        self.assertEqual('[1]',chapter['content'][0][ref['start']:ref['end']])
        self.assertEqual(0,insert(chapter,[record])[0])

    def test_registered_numeric_heading_survives_reimport(self):
        url='https://www.marxists.org/fixture';html='<body><p>'+('前文足够完整。'*40)+'</p><h3>2</h3><p>'+('后文足够完整。'*40)+'</p></body>'
        record={'sourceUrl':url,'decodedHtmlSha256':hashlib.sha256(html.encode()).hexdigest(),'records':[{'sourceLineIndex':1,'text':'2','reviewCategory':'CONFIRMED_HEADING_OMISSION','headingLevel':2}]}
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'manifest.json';path.write_text(json.dumps({'pages':[record]}))
            with patch('library_source_short_lines.MANIFEST',path):
                chapter=extract_chapter(url,'测试文稿',html,1)
                self.assertTrue(any(s['title']=='2' for s in chapter['sections']))
                self.assertIn('2',''.join(chapter['content']))
                with self.assertRaisesRegex(ValueError,'source changed'):
                    preserve_short_lines(BeautifulSoup(html,'lxml'),url,html.replace('后文','改变'))

    def test_superscript_notes_have_distinct_reference_and_definition_tokens(self):
        soup=BeautifulSoup('<body><p>正文<sup>1</sup></p><span style="font-size: 10.5pt"><sup>1</sup>原注内容</span></body>','lxml')
        refs,defs={},{}
        note_tokens(soup,'https://www.marxists.org/chinese/lenin/192004/07.htm',refs,defs,{})
        self.assertEqual([('source-sup-1','1')],list(refs.values()))
        self.assertEqual([('source-sup-1','1')],list(defs.values()))

    def test_all_119_registered_restorations_are_present_in_final_assets(self):
        root=Path(__file__).resolve().parents[2]
        books=[json.loads(p.read_text())['books'][0] for p in (root/'app/src/main/assets/library/books').glob('*.json')]
        entries=[(c,r) for b in books for c in b['chapters'] for r in c.get('sourceTextRepairs',[])]
        self.assertEqual(121,len(entries))
        for chapter,entry in entries:
            actual=chapter['content'][entry['paragraphIndex']][entry['start']:entry['end']]
            self.assertEqual(''.join(entry['text'].split()),''.join(actual.split()))
