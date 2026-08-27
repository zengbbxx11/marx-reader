package org.marxreader.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme { PAPER, SEPIA, DARK, SYSTEM }
enum class ReadingMode { SCROLL, PAGE }

data class ReaderSettings(
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.75f,
    val horizontalPadding: Int = 22,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val mode: ReadingMode = ReadingMode.PAGE,
    val keepScreenOn: Boolean = false,
    val firstLineIndent: Boolean = true
)

class ReaderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(load())
    val settings = mutableSettings.asStateFlow()
    private val mutableSearchHistory = MutableStateFlow(loadSearchHistory())
    val searchHistory = mutableSearchHistory.asStateFlow()

    fun update(value: ReaderSettings) {
        mutableSettings.value = value
        preferences.edit()
            .putFloat("font_size", value.fontSize)
            .putFloat("line_height", value.lineHeight)
            .putInt("horizontal_padding", value.horizontalPadding)
            .putString("theme", value.theme.name)
            .putString("mode", value.mode.name)
            .putBoolean("mode_user_selected", true)
            .putBoolean("keep_screen_on", value.keepScreenOn)
            .putBoolean("first_line_indent", value.firstLineIndent)
            .apply()
    }

    fun addSearchHistory(query: String) {
        val value = query.trim()
        if (value.length < 2) return
        val updated = (listOf(value) + mutableSearchHistory.value.filterNot { it == value }).take(8)
        mutableSearchHistory.value = updated
        preferences.edit().putString("search_history", updated.joinToString("\u001F")).apply()
    }

    fun clearSearchHistory() {
        mutableSearchHistory.value = emptyList()
        preferences.edit().remove("search_history").apply()
    }

    private fun loadSearchHistory(): List<String> = preferences.getString("search_history", "")
        .orEmpty().split("\u001F").filter { it.isNotBlank() }.take(8)

    private fun load() = ReaderSettings(
        fontSize = preferences.getFloat("font_size", 20f),
        lineHeight = preferences.getFloat("line_height", 1.75f),
        horizontalPadding = preferences.getInt("horizontal_padding", 22),
        theme = runCatching { ReaderTheme.valueOf(preferences.getString("theme", "PAPER")!!) }
            .getOrDefault(ReaderTheme.PAPER),
        mode = if (!preferences.getBoolean("mode_user_selected", false)) ReadingMode.PAGE else
            runCatching { ReadingMode.valueOf(preferences.getString("mode", "PAGE")!!) }
                .getOrDefault(ReadingMode.PAGE),
        keepScreenOn = preferences.getBoolean("keep_screen_on", false),
        firstLineIndent = preferences.getBoolean("first_line_indent", true)
    )
}
