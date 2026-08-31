package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAnchorsTest {
    @Test
    fun sentenceSelectionUsesChinesePunctuation() {
        val text = "第一句话。第二句话很重要！第三句话。"
        val selection = sentenceSelection(text, text.indexOf("重要"))

        assertEquals("第二句话很重要！", selection.text)
        assertEquals(text.indexOf("第二"), selection.start)
    }

    @Test
    fun exactAnchorKeepsOriginalParagraphAndRange() {
        val paragraph = "前文。需要记录的句子。后文。"
        val selection = sentenceSelection(paragraph, paragraph.indexOf("记录"))
        val note = noteForSelection("book", "chapter", 0, paragraph, selection, "想法")

        val resolved = resolveNoteAnchor(note, listOf(paragraph))

        assertEquals(NoteAnchorStatus.EXACT, resolved.status)
        assertEquals(selection.start, resolved.start)
        assertEquals(selection.end, resolved.end)
    }

    @Test
    fun anchorRelocatesAfterParagraphInsertion() {
        val paragraph = "前文。需要记录的句子。后文。"
        val selection = sentenceSelection(paragraph, paragraph.indexOf("记录"))
        val note = noteForSelection("book", "chapter", 0, paragraph, selection, "想法")

        val resolved = resolveNoteAnchor(note, listOf("新增段落。", paragraph))

        assertEquals(NoteAnchorStatus.RELOCATED, resolved.status)
        assertEquals(1, resolved.paragraphIndex)
    }

    @Test
    fun duplicateExcerptUsesMatchingContext() {
        val target = "甲。共同句子。正确后文。"
        val selection = sentenceSelection(target, target.indexOf("共同"))
        val note = noteForSelection("book", "chapter", 1, target, selection, "想法")
        val paragraphs = listOf("乙。共同句子。错误后文。", "插入。", target)

        val resolved = resolveNoteAnchor(note, paragraphs)

        assertEquals(2, resolved.paragraphIndex)
        assertTrue(resolved.status == NoteAnchorStatus.RELOCATED)
    }

    @Test
    fun blankAnnotationCreatesColoredHighlightWithNormalizedTags() {
        val paragraph = "劳动创造价值。"
        val selection = sentenceSelection(paragraph, 2)
        val note = noteForSelection(
            "book", "chapter", 0, paragraph, selection, "",
            color = HighlightColor.BLUE,
            tags = listOf(" 劳动，价值 ", "劳动"),
            pinned = true
        )

        assertEquals(NoteKind.HIGHLIGHT, note.kind)
        assertEquals(HighlightColor.BLUE, note.color)
        assertEquals(listOf("劳动", "价值"), note.tags)
        assertTrue(note.pinned)
    }
}
