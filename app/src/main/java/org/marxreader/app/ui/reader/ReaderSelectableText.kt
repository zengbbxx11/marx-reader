package org.marxreader.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.marxreader.app.data.Footnote
import org.marxreader.app.data.FootnoteReference
import org.marxreader.app.data.ReaderFont
import org.marxreader.app.data.ReaderFontWeight

/**
 * The single native text layer used for rendering, selection handles and the copy menu.
 * Keeping the visible text and the selection layout in one view prevents the two
 * renderers from disagreeing about glyph positions, line breaks and line heights.
 */
@SuppressLint("WrongConstant")
internal class ReaderSelectableTextView(context: Context) : TextView(context) {
    private companion object {
        const val ANNOTATE_MENU_ID = 0x4D52
    }

    var onTextTap: ((offset: Int, x: Float, y: Float) -> Unit)? = null
    var onAnnotateSelection: ((start: Int, end: Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressTriggered = false
    private var downHadSelection = false
    private var annotationActionMode: ActionMode? = null

    init {
        setTextIsSelectable(true)
        setTextColor(android.graphics.Color.TRANSPARENT)
        highlightColor = android.graphics.Color.argb(72, 33, 150, 243)
        background = null
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setSelectAllOnFocus(false)
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                annotationActionMode = mode
                menu.add(Menu.NONE, ANNOTATE_MENU_ID, Menu.NONE, "高亮/批注")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != ANNOTATE_MENU_ID) return false
                val rawStart = selectionStart.coerceAtLeast(0)
                val rawEnd = selectionEnd.coerceAtLeast(0)
                val start = minOf(rawStart, rawEnd)
                val end = maxOf(rawStart, rawEnd)
                if (end > start) onAnnotateSelection?.invoke(start, end)
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (annotationActionMode === mode) annotationActionMode = null
            }
        })
    }

    fun applyReaderTextStyle(
        fontSizePx: Float,
        lineHeightPx: Float,
        fontFamily: ReaderFont,
        fontWeight: ReaderFontWeight,
        bold: Boolean,
        textColorArgb: Int
    ) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
        setLineSpacing((lineHeightPx - fontSizePx).coerceAtLeast(0f), 1f)
        setTextColor(textColorArgb)
        typeface = readerTypeface(fontFamily, fontWeight, bold)
    }

    override fun performLongClick(): Boolean {
        longPressTriggered = true
        return super.performLongClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                longPressTriggered = false
                downHadSelection = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
            }

            MotionEvent.ACTION_MOVE -> {
                if (!moved && (event.x - downX) * (event.x - downX) +
                    (event.y - downY) * (event.y - downY) > touchSlop * touchSlop
                ) {
                    moved = true
                    val selectionInProgress = downHadSelection || longPressTriggered ||
                        (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd)
                    if (!selectionInProgress) parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val selectionActive = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
            if (!moved && !longPressTriggered && !downHadSelection &&
                annotationActionMode == null && !selectionActive
            ) {
                textOffsetAt(event.x, event.y).takeIf { it >= 0 }?.let { offset ->
                    performClick()
                    onTextTap?.invoke(offset, event.x, event.y)
                }
            }
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            moved = false
            longPressTriggered = false
        }
        return handled
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun textOffsetAt(x: Float, y: Float): Int {
        val textLayout = layout ?: return -1
        if (textLayout.lineCount <= 0) return -1
        val verticalPosition = (y - totalPaddingTop).toInt()
            .coerceIn(0, (textLayout.height - 1).coerceAtLeast(0))
        val line = textLayout.getLineForVertical(verticalPosition)
        return textLayout.getOffsetForHorizontal(line, x - totalPaddingLeft)
    }
}

internal data class ReaderTextBackground(
    val start: Int,
    val end: Int,
    val color: Color
)

@Composable
internal fun ReaderSelectableText(
    text: CharSequence,
    fontSizePx: Float,
    lineHeightPx: Float,
    fontFamily: ReaderFont,
    fontWeight: ReaderFontWeight,
    contentKey: String,
    textColorArgb: Int,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onTextTap: (offset: Int, x: Float, y: Float) -> Unit,
    onAnnotateSelection: (start: Int, end: Int) -> Unit
) {
    val currentOnTextTap = rememberUpdatedState(onTextTap)
    val currentOnAnnotateSelection = rememberUpdatedState(onAnnotateSelection)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ReaderSelectableTextView(context).apply {
                this.onTextTap = { offset, x, y -> currentOnTextTap.value(offset, x, y) }
                this.onAnnotateSelection = { start, end ->
                    currentOnAnnotateSelection.value(start, end)
                }
            }
        },
        update = { view ->
            view.onTextTap = { offset, x, y -> currentOnTextTap.value(offset, x, y) }
            view.onAnnotateSelection = { start, end -> currentOnAnnotateSelection.value(start, end) }
            view.applyReaderTextStyle(fontSizePx, lineHeightPx, fontFamily, fontWeight, bold, textColorArgb)
            if (view.tag != contentKey) {
                view.setText(text, TextView.BufferType.SPANNABLE)
                view.tag = contentKey
            }
        }
    )
}

internal fun ReaderPage.selectionOverlayText(
    fontSizePx: Float,
    accentColor: Color,
    backgrounds: List<ReaderTextBackground> = emptyList()
): CharSequence {
    val styled = SpannableString(text)
    emphasis.forEach { emphasis ->
        val start = emphasis.start.coerceIn(0, text.length)
        val end = emphasis.end.coerceIn(start, text.length)
        if (end > start) {
            val extraPx = when (emphasis.level) {
                0 -> 6f
                2 -> 3f
                else -> 1.5f
            }
            styled.setSpan(
                AbsoluteSizeSpan((fontSizePx + extraPx).toInt(), false),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                StyleSpan(if (emphasis.level <= 2) Typeface.BOLD else Typeface.BOLD_ITALIC),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                ForegroundColorSpan(accentColor.toArgb()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    footnotes.forEach { footnote ->
        val start = footnote.start.coerceIn(0, text.length)
        val end = footnote.end.coerceIn(start, text.length)
        if (end > start) {
            styled.setSpan(
                RelativeSizeSpan(.78f),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                SuperscriptSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                ForegroundColorSpan(accentColor.toArgb()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    backgrounds.forEach { background ->
        val start = background.start.coerceIn(0, text.length)
        val end = background.end.coerceIn(start, text.length)
        if (end > start) styled.setSpan(
            BackgroundColorSpan(background.color.toArgb()),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return styled
}

internal fun selectionOverlayParagraphText(
    text: String,
    footnotes: List<Pair<FootnoteReference, Footnote>>,
    indentLength: Int,
    accentColor: Color,
    backgrounds: List<ReaderTextBackground> = emptyList()
): CharSequence {
    val styled = SpannableString(text)
    footnotes.forEach { (reference, _) ->
        val start = (reference.start + indentLength).coerceIn(0, text.length)
        val end = (reference.end + indentLength).coerceIn(start, text.length)
        if (end > start) {
            styled.setSpan(
                RelativeSizeSpan(.78f),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                SuperscriptSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                ForegroundColorSpan(accentColor.toArgb()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    backgrounds.forEach { background ->
        val start = background.start.coerceIn(0, text.length)
        val end = background.end.coerceIn(start, text.length)
        if (end > start) styled.setSpan(
            BackgroundColorSpan(background.color.toArgb()),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return styled
}
