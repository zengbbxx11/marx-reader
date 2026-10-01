package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAnchorsTest {
    @Test
    fun oldAnnotationSpanningRestoredBankTableKeepsItsOriginalWords() {
        val old = "在存款总额中所占的百分比柏林9家大银行其余48家资本在1000万马克以上的银行115家资本在100─1000万马克的银行资本不到100万马克的小银行1907─8年度47"
        val restored = "在存款总额中所占的百分比〔原表2〕柏林9家大银行 其余48家资本在1000万马克以上的银行 115家资本在100─1000万马克的银行 资本不到100万马克的小银行 \n1907─8年度 47"
        val note = noteForSelection("lenin-work-5409317c986a", "chapter", 0, old,
            TextSelection(0, old.length, old), "批注")
        val resolved = resolveNoteAnchor(note, listOf(restored))
        assertEquals(NoteAnchorStatus.RELOCATED, resolved.status)
        val inserted = sourceRestorationRanges(paragraphHash(restored), paragraphHash(old))
        assertTrue(inserted.isNotEmpty())
        val words = (resolved.start until resolved.end).filter { position -> inserted.none { position in it } }
            .joinToString("") { restored[it].toString() }
        assertEquals(old, words)
        val reopened = resolveNoteAnchor(note.copy(selectionStart = resolved.start, selectionEnd = resolved.end), listOf(restored))
        assertEquals(resolved.start, reopened.start)
        assertEquals(resolved.end, reopened.end)
    }

    @Test
    fun oldTableQuoteRelocatesAcrossNewCellAndRowBoundaries() {
        val old = "表头。第一次79010313％第二次67534351％。尾文。"
        val start = old.indexOf("第一次")
        val end = old.indexOf("。尾文")
        val note = noteForSelection("lenin-work-9b5e398ffc38", "chapter", 0, old,
            TextSelection(start, end, old.substring(start, end)), "数据")
        val restored = old.replace("第一次79010313％第二次67534351％",
            "〔原表1〕第一次\u2002790\u2002103\u200213％\u2002\n第二次\u2002675\u2002343\u200251％")
        val resolved = resolveNoteAnchor(note, listOf(restored))
        assertEquals(NoteAnchorStatus.RELOCATED, resolved.status)
        val actual = restored.substring(resolved.start, resolved.end)
            .replace(Regex("〔原表\\d+〕|\u2002\n?"), "")
        assertEquals(note.excerpt, actual)
        val persisted = note.copy(selectionStart = resolved.start, selectionEnd = resolved.end)
        val reopened = resolveNoteAnchor(persisted, listOf(restored))
        assertEquals(resolved.start, reopened.start)
        assertEquals(resolved.end, reopened.end)
    }

    @Test
    fun oldSelectionSpanningRestoredHousingDiagramKeepsItsWords() {
        val old = "我现在描下一小块。这张图可以充分表明。"
        val note = noteForSelection("engels-work-12d43c9362e2", "chapter", 0, old,
            TextSelection(0, old.length, old), "")
        val restored = old.replace("。这张图", "。〔图式1〕这张图")
        val resolved = resolveNoteAnchor(note, listOf(restored))
        assertEquals(NoteAnchorStatus.RELOCATED, resolved.status)
        assertEquals(old, restored.substring(resolved.start, resolved.end).replace("〔图式1〕", ""))
    }

    @Test
    fun oldSelectionSpanningRestoredFormulaKeepsItsWords() {
        val old = "前文。就是。因此，从内容来看。后文。"
        val start = old.indexOf("就是")
        val end = old.indexOf("后文")
        val note = noteForSelection("capital-v2-zh", "chapter", 0, old,
            TextSelection(start, end, old.substring(start, end)), "想法")
        val restored = old.replace("就是。", "就是〔图式1〕。")
        val resolved = resolveNoteAnchor(note, listOf(restored))
        assertEquals(NoteAnchorStatus.RELOCATED, resolved.status)
        assertEquals(note.excerpt, restored.substring(resolved.start, resolved.end).replace("〔图式1〕", ""))
        // Repository persists the shifted offsets but retains the original quote.
        val persisted = note.copy(selectionStart = resolved.start, selectionEnd = resolved.end)
        val reopened = resolveNoteAnchor(persisted, listOf(restored))
        assertEquals(resolved.start, reopened.start)
        assertEquals(resolved.end, reopened.end)
    }

    @Test
    fun restoredFormulaDoesNotMoveSelectionToAnotherIdenticalQuote() {
        val old = "正确前文。就是。因此。正确后文。"
        val start = old.indexOf("就是")
        val note = noteForSelection("capital-v2-zh", "chapter", 1, old,
            TextSelection(start, start + 6, old.substring(start, start + 6)), "想法")
        val resolved = resolveNoteAnchor(note, listOf("错误前文。就是。因此。错误后文。", old.replace("就是。", "就是〔图式1〕。")))
        assertEquals(1, resolved.paragraphIndex)
    }
    @Test
    fun freeRangeSelectionPreservesExactCharacters() {
        val text = "第一句话。第二句话很重要！第三句话。"
        val start = text.indexOf("句话很")
        val end = start + "句话很".length
        val selection = TextSelection(start, end, text.substring(start, end))

        assertEquals("句话很", selection.text)
        assertEquals(start, selection.start)
        assertEquals(end, selection.end)
    }

    @Test
    fun exactAnchorKeepsOriginalParagraphAndRange() {
        val paragraph = "前文。需要记录的句子。后文。"
        val start = paragraph.indexOf("记录")
        val end = start + "记录的".length
        val selection = TextSelection(start, end, paragraph.substring(start, end))
        val note = noteForSelection("book", "chapter", 0, paragraph, selection, "想法")

        val resolved = resolveNoteAnchor(note, listOf(paragraph))

        assertEquals(NoteAnchorStatus.EXACT, resolved.status)
        assertEquals(selection.start, resolved.start)
        assertEquals(selection.end, resolved.end)
    }

    @Test
    fun anchorRelocatesAfterParagraphInsertion() {
        val paragraph = "前文。需要记录的句子。后文。"
        val start = paragraph.indexOf("记录")
        val end = start + "记录的".length
        val selection = TextSelection(start, end, paragraph.substring(start, end))
        val note = noteForSelection("book", "chapter", 0, paragraph, selection, "想法")

        val resolved = resolveNoteAnchor(note, listOf("新增段落。", paragraph))

        assertEquals(NoteAnchorStatus.RELOCATED, resolved.status)
        assertEquals(1, resolved.paragraphIndex)
    }

    @Test
    fun duplicateExcerptUsesMatchingContext() {
        val target = "甲。共同句子。正确后文。"
        val start = target.indexOf("共同")
        val end = start + "共同句子".length
        val selection = TextSelection(start, end, target.substring(start, end))
        val note = noteForSelection("book", "chapter", 1, target, selection, "想法")
        val paragraphs = listOf("乙。共同句子。错误后文。", "插入。", target)

        val resolved = resolveNoteAnchor(note, paragraphs)

        assertEquals(2, resolved.paragraphIndex)
        assertTrue(resolved.status == NoteAnchorStatus.RELOCATED)
    }

    @Test
    fun blankAnnotationCreatesColoredHighlightWithNormalizedTags() {
        val paragraph = "劳动创造价值。"
        val selection = TextSelection(2, 4, paragraph.substring(2, 4))
        val note = noteForSelection(
            "book", "chapter", 0, paragraph, selection, "",
            color = HighlightColor.BLUE,
            tags = listOf(" 劳动，价值 ", "劳动"),
            pinned = true
        )

        assertEquals(NoteKind.HIGHLIGHT, note.kind)
        assertEquals("创造", note.excerpt)
        assertEquals(HighlightColor.BLUE, note.color)
        assertEquals(listOf("劳动", "价值"), note.tags)
        assertTrue(note.pinned)
    }

    @Test
    fun exactHashKeepsFullRangeForPreviouslyTruncatedExcerpt() {
        val paragraph = "甲".repeat(600)
        val selection = TextSelection(50, 580, paragraph.substring(50, 580))
        val note = noteForSelection("book", "chapter", 0, paragraph, selection, "想法")
            .copy(excerpt = selection.text.take(500))

        val resolved = resolveNoteAnchor(note, listOf(paragraph))

        assertEquals(NoteAnchorStatus.EXACT, resolved.status)
        assertEquals(50, resolved.start)
        assertEquals(580, resolved.end)
    }
}
