package org.marxreader.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme { PAPER, SEPIA, DARK, SYSTEM }
enum class ReadingMode { SCROLL, PAGE }
enum class ReaderFont { SERIF, SANS_SERIF }
enum class ReaderFontWeight { REGULAR, MEDIUM }
enum class ReaderBrightnessMode { SYSTEM, CUSTOM }
enum class ReaderLayoutPreset { DEFAULT, COMPACT, RELAXED, LARGE, CUSTOM }

data class ReaderSettings(
    val fontFamily: ReaderFont = ReaderFont.SERIF,
    val fontWeight: ReaderFontWeight = ReaderFontWeight.REGULAR,
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.75f,
    val paragraphSpacing: Float = .72f,
    val horizontalPadding: Int = 22,
    val verticalPadding: Int = 22,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val mode: ReadingMode = ReadingMode.PAGE,
    val brightnessMode: ReaderBrightnessMode = ReaderBrightnessMode.SYSTEM,
    val brightness: Float = .5f,
    val keepScreenOn: Boolean = false,
    val firstLineIndent: Boolean = true
) {
    val layoutPreset: ReaderLayoutPreset get() = when {
        matchesLayout(20f, 1.75f, .72f, 22, 22) -> ReaderLayoutPreset.DEFAULT
        matchesLayout(18f, 1.5f, .42f, 16, 16) -> ReaderLayoutPreset.COMPACT
        matchesLayout(21f, 1.95f, 1f, 28, 28) -> ReaderLayoutPreset.RELAXED
        matchesLayout(27f, 1.85f, .9f, 24, 26) -> ReaderLayoutPreset.LARGE
        else -> ReaderLayoutPreset.CUSTOM
    }

    fun applyPreset(preset: ReaderLayoutPreset): ReaderSettings = when (preset) {
        ReaderLayoutPreset.DEFAULT -> copy(
            fontSize = 20f, lineHeight = 1.75f, paragraphSpacing = .72f,
            horizontalPadding = 22, verticalPadding = 22
        )
        ReaderLayoutPreset.COMPACT -> copy(
            fontSize = 18f, lineHeight = 1.5f, paragraphSpacing = .42f,
            horizontalPadding = 16, verticalPadding = 16
        )
        ReaderLayoutPreset.RELAXED -> copy(
            fontSize = 21f, lineHeight = 1.95f, paragraphSpacing = 1f,
            horizontalPadding = 28, verticalPadding = 28
        )
        ReaderLayoutPreset.LARGE -> copy(
            fontSize = 27f, lineHeight = 1.85f, paragraphSpacing = .9f,
            horizontalPadding = 24, verticalPadding = 26
        )
        ReaderLayoutPreset.CUSTOM -> this
    }

    private fun matchesLayout(
        size: Float,
        height: Float,
        spacing: Float,
        horizontal: Int,
        vertical: Int
    ): Boolean =
        kotlin.math.abs(fontSize - size) < .01f &&
            kotlin.math.abs(lineHeight - height) < .01f &&
            kotlin.math.abs(paragraphSpacing - spacing) < .01f &&
            horizontalPadding == horizontal && verticalPadding == vertical
}

/** Preserve every supported setting; corrupted values fall back to the existing defaults. */
internal fun ReaderSettings.validated(): ReaderSettings {
    val defaults = ReaderSettings()
    fun Float.valid(range: ClosedFloatingPointRange<Float>, fallback: Float) =
        takeIf { it.isFinite() && it in range } ?: fallback
    return copy(
        fontSize = fontSize.valid(15f..32f, defaults.fontSize),
        lineHeight = lineHeight.valid(1.3f..2.2f, defaults.lineHeight),
        paragraphSpacing = paragraphSpacing.valid(.35f..1.25f, defaults.paragraphSpacing),
        horizontalPadding = horizontalPadding.takeIf { it in 12..42 } ?: defaults.horizontalPadding,
        verticalPadding = verticalPadding.takeIf { it in 12..48 } ?: defaults.verticalPadding,
        brightness = brightness.valid(.05f..1f, defaults.brightness)
    )
}

class ReaderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val storedValues = preferences.all
    private val mutableSettings = MutableStateFlow(load())
    val settings = mutableSettings.asStateFlow()
    private val mutableSearchHistory = MutableStateFlow(loadSearchHistory())
    val searchHistory = mutableSearchHistory.asStateFlow()

    fun update(settings: ReaderSettings) {
        val value = settings.validated()
        mutableSettings.value = value
        preferences.edit()
            .putString("font_family", value.fontFamily.name)
            .putString("font_weight", value.fontWeight.name)
            .putFloat("font_size", value.fontSize)
            .putFloat("line_height", value.lineHeight)
            .putFloat("paragraph_spacing", value.paragraphSpacing)
            .putInt("horizontal_padding", value.horizontalPadding)
            .putInt("vertical_padding", value.verticalPadding)
            .putString("theme", value.theme.name)
            .putString("mode", value.mode.name)
            .putBoolean("mode_user_selected", true)
            .putString("brightness_mode", value.brightnessMode.name)
            .putFloat("brightness", value.brightness.coerceIn(.05f, 1f))
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

    private fun loadSearchHistory(): List<String> = (storedValues["search_history"] as? String)
        .orEmpty().split('\u001F').filter { it.isNotBlank() }.take(8)

    private fun load() = ReaderSettings(
        fontFamily = enumPreference("font_family", ReaderFont.SERIF),
        fontWeight = enumPreference("font_weight", ReaderFontWeight.REGULAR),
        fontSize = storedValues["font_size"] as? Float ?: 20f,
        lineHeight = storedValues["line_height"] as? Float ?: 1.75f,
        paragraphSpacing = storedValues["paragraph_spacing"] as? Float ?: .72f,
        horizontalPadding = storedValues["horizontal_padding"] as? Int ?: 22,
        verticalPadding = storedValues["vertical_padding"] as? Int ?: 22,
        theme = enumPreference("theme", ReaderTheme.PAPER),
        mode = if (storedValues["mode_user_selected"] != true) ReadingMode.PAGE else
            enumPreference("mode", ReadingMode.PAGE),
        brightnessMode = enumPreference("brightness_mode", ReaderBrightnessMode.SYSTEM),
        brightness = storedValues["brightness"] as? Float ?: .5f,
        keepScreenOn = storedValues["keep_screen_on"] as? Boolean ?: false,
        firstLineIndent = storedValues["first_line_indent"] as? Boolean ?: true
    ).validated()

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        (storedValues[key] as? String)?.let { value ->
            enumValues<T>().firstOrNull { it.name == value }
        } ?: fallback
}
