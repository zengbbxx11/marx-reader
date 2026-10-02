import unittest
from build_full_chinese_library import extract_chapter, keep_text


class BreadcrumbFilterTest(unittest.TestCase):
    def test_archive_breadcrumb_arrow_variants_are_filtered(self):
        for arrow in ['->', '→', '>>', '»']:
            with self.subTest(arrow=arrow):
                self.assertFalse(keep_text('中文马克思主义文库 ' + arrow + ' 列宁 ' + arrow + ' 列宁选集', '测试'))
        self.assertTrue(keep_text('正文〔注：——中文马克思主义文库〕仍需保留。', '测试'))

    def test_breadcrumbs_do_not_merge_into_prose_or_note(self):
        breadcrumb = '<p><a href="/chinese/">中文马克思主义文库</a> &gt;&gt; <a href="index.htm">列宁</a> &gt;&gt; 列宁选集</p>'
        prose = '这一段正文需要完整保留。' * 30
        html = '<body>' + breadcrumb + '<h1>测试</h1><p>' + prose + '<a name="_ftnref1" href="#_ftn1">[1]</a></p><p><a name="_ftn1" href="#_ftnref1">[1]</a>原注。</p>' + breadcrumb + '</body>'
        chapter = extract_chapter('https://www.marxists.org/fixture', '测试', html, 1)
        self.assertIn(prose, ''.join(chapter['content']))
        self.assertNotIn('中文马克思主义文库', ''.join(chapter['content']))
        note = chapter['footnotes'][0]
        self.assertEqual(['原注。'], note['content'])
        ref = note['references'][0]
        self.assertEqual('[1]', chapter['content'][ref['paragraphIndex']][ref['start']:ref['end']])
